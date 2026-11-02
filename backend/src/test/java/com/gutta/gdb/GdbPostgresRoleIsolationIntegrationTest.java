package com.gutta.gdb;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class GdbPostgresRoleIsolationIntegrationTest {
    private static final String ADMIN = "role-isolation-admin-token-32-characters";
    private static final String MIGRATOR = "gdb_staging_migrator";
    private static final String MIGRATOR_PASSWORD = "migration-password-for-test-only";
    private static final String APP = "gdb_staging_app";
    private static final String APP_PASSWORD = "runtime-password-for-test-only";

    @Container
    static final RoleConfiguredPostgres POSTGRES = new RoleConfiguredPostgres(
            System.getenv().getOrDefault("GDB_TEST_POSTGRES_IMAGE", "postgres:18-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // Flyway is deliberately run before the Spring context using gdb_migrator.
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("gdb.admin-token", () -> ADMIN);
        registry.add("gdb.snapshot-master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("gdb.snapshot-storage", () -> "target/role-test-snapshots");
        registry.add("gdb.allowed-origins", () -> "http://localhost:4200");
    }

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void migrationRoleRunsFlywayAndRuntimeRoleCanOperateButCannotAlterCoreSchema() throws Exception {
        assertThat(POSTGRES.migrationVersion()).isEqualTo("3");
        String appCode = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        HttpResponse<String> created = request("POST", "/api/v1/admin/applications", "{\"appCode\":\"" + appCode
                + "\",\"displayName\":\"Role Test\",\"versionMetadata\":\"1\",\"permittedDatasets\":\"[]\",\"snapshotPolicy\":\"{}\"}", ADMIN);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(request("GET", "/api/v1/admin/applications", null, ADMIN).statusCode()).isEqualTo(200);

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP, APP_PASSWORD);
             Statement statement = app.createStatement()) {
            assertThat(statement.executeQuery("SELECT count(*) FROM gdb_core.applications").next()).isTrue();
            SQLException schemaDdl = null;
            try { statement.execute("CREATE TABLE gdb_core.runtime_must_not_create (id UUID)"); }
            catch (SQLException error) { schemaDdl = error; }
            assertThat((Object) schemaDdl).as("runtime role must not create core tables").isNotNull();

            SQLException tableDdl = null;
            try { statement.execute("ALTER TABLE gdb_core.applications ADD COLUMN runtime_must_not_alter TEXT"); }
            catch (SQLException error) { tableDdl = error; }
            assertThat((Object) tableDdl).as("runtime role must not alter core tables").isNotNull();
        }
    }

    private HttpResponse<String> request(String method, String path, String body, String admin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (admin != null) builder.header("X-GDB-Admin-Token", admin);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static final class RoleConfiguredPostgres extends PostgreSQLContainer<RoleConfiguredPostgres> {
        private String migrationVersion;

        RoleConfiguredPostgres(String image) { super(image); }

        @Override
        public void start() {
            super.start();
            try (Connection admin = DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
                statement.execute("CREATE ROLE " + APP + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
                statement.execute("GRANT CREATE ON DATABASE \"" + getDatabaseName() + "\" TO " + MIGRATOR);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare disposable PostgreSQL roles", error);
            }
            Flyway.configure()
                    .dataSource(getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
                    .schemas("gdb_core").defaultSchema("gdb_core")
                    .locations("classpath:db/migration")
                    .load().migrate();
            try (Connection migrator = DriverManager.getConnection(getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
                 Statement statement = migrator.createStatement()) {
                statement.execute("REVOKE ALL ON DATABASE \"" + getDatabaseName() + "\" FROM PUBLIC");
                statement.execute("GRANT CONNECT ON DATABASE \"" + getDatabaseName() + "\" TO " + APP);
                statement.execute("REVOKE CREATE ON DATABASE \"" + getDatabaseName() + "\" FROM " + APP);
                migrationVersion = Flyway.configure().dataSource(getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
                        .schemas("gdb_core").defaultSchema("gdb_core").load().info().current().getVersion().getVersion();
            } catch (SQLException error) {
                throw new IllegalStateException("Could not grant disposable runtime privileges", error);
            }
        }

        String migrationVersion() { return migrationVersion; }
    }
}
