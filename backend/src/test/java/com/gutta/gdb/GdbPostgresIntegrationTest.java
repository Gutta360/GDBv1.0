package com.gutta.gdb;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GdbPostgresIntegrationTest {
    private static final String ADMIN = "integration-admin-token-32-characters-long";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("GDB_TEST_POSTGRES_IMAGE", "postgres:18-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("gdb.admin-token", () -> ADMIN);
        registry.add("gdb.snapshot-master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("gdb.snapshot-storage", () -> Path.of("target/test-snapshots").toAbsolutePath().toString());
        registry.add("gdb.allowed-origins", () -> "http://localhost:4200");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired Flyway flyway;
    private final HttpClient client = HttpClient.newHttpClient();
    private static String normalAdminCookie;

    @Test
    @Order(1)
    void administratorBootstrapLoginSessionAndRevocationAreEnforced() throws Exception {
        String password = "LocalIntegrationPassword-2026!";
        HttpResponse<String> bootstrap = send("POST", "/api/v1/auth/admin/bootstrap", json(Map.of(
                "bootstrapToken", ADMIN, "username", "operator", "password", password)), null, null);
        assertThat(bootstrap.statusCode()).isEqualTo(201);
        String firstCookie = sessionCookie(bootstrap);
        assertThat(firstCookie).startsWith("GDB_ADMIN_SESSION=");
        assertThat(send("GET", "/api/v1/auth/admin/me", null, null, null, firstCookie).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/admin/monitoring/dashboard", null, null, null, firstCookie).statusCode()).isEqualTo(200);

        HttpResponse<String> login = send("POST", "/api/v1/auth/admin/login", json(Map.of(
                "username", "operator", "password", password)), null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        String secondCookie = sessionCookie(login);
        assertThat(secondCookie).isNotEqualTo(firstCookie);
        assertThat(send("POST", "/api/v1/auth/admin/logout", null, null, null, secondCookie).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/v1/auth/admin/me", null, null, null, secondCookie).statusCode()).isEqualTo(401);

        HttpResponse<String> relogin = send("POST", "/api/v1/auth/admin/login", json(Map.of(
                "username", "operator", "password", password)), null, null);
        String thirdCookie = sessionCookie(relogin);
        assertThat(send("POST", "/api/v1/auth/admin/sessions/revoke-all", null, null, null, thirdCookie).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/v1/auth/admin/me", null, null, null, thirdCookie).statusCode()).isEqualTo(401);
        HttpResponse<String> restored = send("POST", "/api/v1/auth/admin/login", json(Map.of(
                "username", "operator", "password", password)), null, null);
        normalAdminCookie = sessionCookie(restored);
        assertThat(send("POST", "/api/v1/auth/admin/login", json(Map.of(
                "username", "operator", "password", "wrong-password")), null, null).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/v1/auth/admin/bootstrap", json(Map.of(
                "bootstrapToken", ADMIN, "username", "second", "password", password)), null, null).statusCode()).isEqualTo(409);
    }

    @Test
    @Order(2)
    void flywayApplicationTenantEnrollmentAndDeviceLifecycleAreIsolated() throws Exception {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        Scope scope = provision(2);
        assertThat(send("GET", "/api/v1/sync/status", null, null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/sync/status", null, scope.devices.getFirst().token(), null).statusCode()).isEqualTo(200);

        DeviceRef rotated = rotate(scope.devices.getFirst());
        assertThat(send("GET", "/api/v1/sync/status", null, scope.devices.getFirst().token(), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/sync/status", null, rotated.token(), null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/admin/devices/" + rotated.deviceId() + "/revoke", null, null, ADMIN).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/sync/status", null, rotated.token(), null).statusCode()).isEqualTo(401);

        HttpResponse<String> reused = send("POST", "/api/v1/devices/register", json(Map.of(
                "enrollmentCode", scope.firstEnrollmentCode(), "deviceName", "Reuse", "applicationId", "com.example." + scope.appCode().toLowerCase(Locale.ROOT),
                "applicationVersion", "1.0.0", "platform", "ANDROID")), null, null);
        assertThat(reused.statusCode()).isEqualTo(401);
    }

    @Test
    void pushPullCursorsDuplicatesConflictsAndTombstonesWorkAcrossDevices() throws Exception {
        Scope scope = provision(2);
        DeviceRef first = scope.devices.get(0), second = scope.devices.get(1);
        UUID entityId = UUID.randomUUID();
        JsonNode create = event(entityId, "CREATE", 0, 1, Map.of("name", "Record A", "active", true));
        JsonNode pushed = push(first, create);
        assertThat(pushed.path("results").get(0).path("status").asText()).isEqualTo("ACCEPTED");

        JsonNode firstPage = pull(second, 0, 1);
        assertThat(firstPage.path("events").size()).isEqualTo(1);
        long cursor = firstPage.path("nextCursor").asLong();
        assertThat(firstPage.path("hasMore").asBoolean()).isFalse();
        assertThat(firstPage.path("events").get(0).path("entityId").asText()).isEqualTo(entityId.toString());

        JsonNode replay = push(first, create);
        assertThat(replay.path("results").get(0).path("status").asText()).isEqualTo("DUPLICATE");

        JsonNode firstUpdate = event(entityId, "UPDATE", 1, 2, Map.of("name", "Record A1", "active", true));
        assertThat(push(first, firstUpdate).path("results").get(0).path("status").asText()).isEqualTo("ACCEPTED");
        JsonNode staleUpdate = event(entityId, "UPDATE", 1, 2, Map.of("name", "Record A2", "active", true));
        assertThat(push(second, staleUpdate).path("results").get(0).path("status").asText()).isEqualTo("CONFLICT");

        JsonNode delete = event(entityId, "DELETE", 2, 3, Map.of("name", "Record A1", "active", false));
        assertThat(push(first, delete).path("results").get(0).path("status").asText()).isEqualTo("ACCEPTED");
        JsonNode later = pull(second, cursor, 100);
        List<String> operations = new ArrayList<>();
        later.path("events").forEach(item -> operations.add(item.path("operation").asText()));
        assertThat(operations).contains("UPDATE", "DELETE");
        assertThat(later.path("events").get(later.path("events").size() - 1).path("operation").asText()).isEqualTo("DELETE");
    }

    @Test
    void applicationAndTenantScopeRejectCrossAccess() throws Exception {
        Scope left = provision(1);
        Scope right = provision(1);
        UUID entityId = UUID.randomUUID();
        assertThat(push(left.devices.getFirst(), event(entityId, "CREATE", 0, 1, Map.of("name", "Private", "active", true)))
                .path("results").get(0).path("status").asText()).isEqualTo("ACCEPTED");
        JsonNode rightPull = pull(right.devices.getFirst(), 0, 100);
        assertThat(rightPull.path("events")).isEmpty();

        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("contractVersion", 1); forged.put("appCode", left.appCode()); forged.put("tenantId", right.tenantId());
        forged.put("deviceId", left.devices.getFirst().deviceId()); forged.put("batchId", UUID.randomUUID());
        forged.put("events", List.of(event(entityId, "CREATE", 0, 1, Map.of("name", "Forged", "active", true))));
        assertThat(send("POST", "/api/v1/sync/push", json(forged), left.devices.getFirst().token(), null).statusCode()).isEqualTo(403);
    }

    @Test
    void encryptedSnapshotsValidateChecksumsAndRemainTenantScoped() throws Exception {
        Scope owner = provision(1);
        Scope stranger = provision(1);
        byte[] bytes = "{\"formatVersion\":1,\"records\":[]}".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> upload = new LinkedHashMap<>(); upload.put("formatVersion", 1); upload.put("createdAt", Instant.now());
        upload.put("sha256", sha256(bytes)); upload.put("contentLength", bytes.length); upload.put("payload", Base64.getEncoder().encodeToString(bytes)); upload.put("recordCounts", "{}");
        HttpResponse<String> saved = send("POST", "/api/v1/snapshots", json(upload), owner.devices.getFirst().token(), null);
        assertThat(saved.statusCode()).isEqualTo(201);
        String id = mapper.readTree(saved.body()).path("snapshotId").asText();
        assertThat(send("GET", "/api/v1/snapshots/" + id, null, stranger.devices.getFirst().token(), null).statusCode()).isEqualTo(404);
        HttpResponse<String> download = send("GET", "/api/v1/snapshots/" + id, null, owner.devices.getFirst().token(), null);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body().getBytes(StandardCharsets.ISO_8859_1)).isEqualTo(bytes);
        assertThat(download.headers().firstValue("X-Snapshot-Sha256")).contains(sha256(bytes));

        upload.put("sha256", "0".repeat(64));
        assertThat(send("POST", "/api/v1/snapshots", json(upload), owner.devices.getFirst().token(), null).statusCode()).isEqualTo(400);
        assertThat(Files.walk(Path.of("target/test-snapshots")).anyMatch(path -> path.toString().endsWith(".gdbsnapshot"))).isTrue();
    }

    private Scope provision(int deviceCount) throws Exception {
        String appCode = "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        JsonNode app = body(send("POST", "/api/v1/admin/applications", json(Map.of("appCode", appCode, "displayName", "Integration " + appCode,
                "versionMetadata", "1.0.0", "permittedDatasets", "[]", "snapshotPolicy", "{}")), null, ADMIN));
        String tenantId = body(send("POST", "/api/v1/admin/tenants", json(Map.of("appId", app.path("appId").asText(), "tenantCode", "DEMO", "tenantName", "Integration Tenant")), null, ADMIN)).path("tenantId").asText();
        List<DeviceRef> devices = new ArrayList<>(); String firstCode = null;
        for (int index = 0; index < deviceCount; index++) {
            JsonNode code = body(send("POST", "/api/v1/admin/tenants/" + tenantId + "/enrollment-codes", null, null, ADMIN));
            if (firstCode == null) firstCode = code.path("enrollmentCode").asText();
            JsonNode registered = body(send("POST", "/api/v1/devices/register", json(Map.of("enrollmentCode", code.path("enrollmentCode").asText(), "deviceName", "Tablet " + index,
                    "applicationId", "com.example." + appCode.toLowerCase(Locale.ROOT), "applicationVersion", "1.0.0", "platform", "ANDROID")), null, null));
            devices.add(new DeviceRef(registered.path("deviceId").asText(), registered.path("deviceToken").asText(), appCode, tenantId));
        }
        return new Scope(appCode, tenantId, devices, firstCode);
    }

    private DeviceRef rotate(DeviceRef device) throws Exception {
        JsonNode rotated = body(send("POST", "/api/v1/admin/devices/" + device.deviceId() + "/rotate", null, null, ADMIN));
        return new DeviceRef(device.deviceId(), rotated.path("deviceToken").asText(), device.appCode(), device.tenantId());
    }

    private JsonNode push(DeviceRef device, JsonNode event) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(); request.put("contractVersion", 1); request.put("appCode", device.appCode());
        request.put("tenantId", device.tenantId()); request.put("deviceId", device.deviceId()); request.put("batchId", UUID.randomUUID()); request.put("events", List.of(event));
        return body(send("POST", "/api/v1/sync/push", json(request), device.token(), null));
    }

    private JsonNode pull(DeviceRef device, long cursor, int limit) throws Exception {
        return body(send("GET", "/api/v1/sync/pull?after=" + cursor + "&limit=" + limit, null, device.token(), null));
    }

    private JsonNode event(UUID entityId, String operation, long baseVersion, long entityVersion, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>(); event.put("eventId", UUID.randomUUID()); event.put("entityType", "ORDER"); event.put("entityId", entityId);
        event.put("operation", operation); event.put("baseVersion", baseVersion); event.put("entityVersion", entityVersion); event.put("payloadVersion", 1); event.put("payload", payload); event.put("createdAt", Instant.now());
        return mapper.valueToTree(event);
    }

    private JsonNode body(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).withFailMessage(response.body()).isLessThan(300); return mapper.readTree(response.body()); }
    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
    private HttpResponse<String> send(String method, String path, String requestBody, String token, String admin) throws Exception {
        return send(method, path, requestBody, token, admin, null);
    }
    private HttpResponse<String> send(String method, String path, String requestBody, String token, String admin, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (cookie != null) builder.header("Cookie", cookie);
        else if (admin != null && normalAdminCookie != null) builder.header("Cookie", normalAdminCookie);
        else if (admin != null) builder.header("X-GDB-Admin-Token", admin);
        if (requestBody != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, requestBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(requestBody)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
    private String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private record DeviceRef(String deviceId, String token, String appCode, String tenantId) {}
    private record Scope(String appCode, String tenantId, List<DeviceRef> devices, String firstEnrollmentCode) {}
}
