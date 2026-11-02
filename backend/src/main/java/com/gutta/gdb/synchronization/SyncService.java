package com.gutta.gdb.synchronization;

import com.gutta.gdb.application.ApplicationService;
import com.gutta.gdb.application.GdbApplicationEntity;
import com.gutta.gdb.config.ApiException;
import com.gutta.gdb.device.Device;
import com.gutta.gdb.device.DeviceRepository;
import com.gutta.gdb.security.GdbPrincipal;
import com.gutta.gdb.conflict.ConflictService;
import com.gutta.gdb.shared.DatabaseSchemaProvisioner;
import com.gutta.gdb.shared.SecretHashing;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.gutta.gdb.synchronization.SyncDtos.*;

@Service
public class SyncService {
    private final ApplicationService applications;
    private final DeviceRepository devices;
    private final SyncBatchStore batches;
    private final SyncEventProcessor processor;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretHashing hashing;
    private final ConflictService conflicts;
    private final com.gutta.gdb.config.GdbProperties properties;

    public SyncService(ApplicationService applications, DeviceRepository devices, SyncBatchStore batches,
                       SyncEventProcessor processor, JdbcTemplate jdbc, ObjectMapper mapper, SecretHashing hashing,
                       ConflictService conflicts, com.gutta.gdb.config.GdbProperties properties) {
        this.applications = applications; this.devices = devices; this.batches = batches; this.processor = processor;
        this.jdbc = jdbc; this.mapper = mapper; this.hashing = hashing; this.conflicts = conflicts; this.properties = properties;
    }

    public PushResponse push(GdbPrincipal principal, PushRequest request) {
        if (!principal.appCode().equalsIgnoreCase(request.appCode()) || !principal.tenantId().equals(request.tenantId()) || !principal.deviceId().equals(request.deviceId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "SYNC_SCOPE_MISMATCH", "Authenticated application, tenant, and device do not match the request.");
        if (request.contractVersion() != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CONTRACT", "Only synchronization contract version 1 is supported.");
        if (request.events().size() > properties.maxBatchSize()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "BATCH_TOO_LARGE", "Batch exceeds the configured event limit.");
        GdbApplicationEntity app = applications.requireActive(principal.appCode());
        String schema = app.getSchemaName();
        String requestHash = hash(request);
        SyncBatchStore.BatchState opened = batches.open(principal.appId(), principal.tenantId(), principal.deviceId(), request, requestHash);
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO gdb_core.sync_sessions(session_id,app_id,tenant_id,device_id,started_at,outcome) VALUES (?,?,?,?,?,?)",
                sessionId, principal.appId(), principal.tenantId(), principal.deviceId(), Timestamp.from(Instant.now()), "STARTED");
        List<EventResult> results = new ArrayList<>(batches.results(request.batchId()));
        if (opened.completedAt() == null) {
            for (int index = results.size(); index < request.events().size(); index++) {
                EventResult result;
                try {
                    result = processor.process(principal, schema, request.events().get(index));
                } catch (RuntimeException error) {
                    result = new EventResult(request.events().get(index).eventId(), "RETRYABLE_FAILURE", "DATABASE_ERROR", "Event was not committed; retry is safe.", null, Instant.now());
                }
                batches.result(request.batchId(), index, result);
                results.add(result);
            }
            batches.complete(request.batchId(), results, Instant.now());
        }
        Device device = devices.findByIdAndTenant_Id(principal.deviceId(), principal.tenantId()).orElseThrow();
        device.markSync(Instant.now());
        jdbc.update("UPDATE gdb_core.sync_sessions SET completed_at=?,uploaded_count=?,rejected_count=?,conflict_count=?,outcome=? WHERE session_id=?",
                Timestamp.from(Instant.now()), results.size(), (int) results.stream().filter(r -> "REJECTED".equals(r.status())).count(),
                (int) results.stream().filter(r -> "CONFLICT".equals(r.status())).count(),
                results.stream().anyMatch(r -> "RETRYABLE_FAILURE".equals(r.status())) ? "RETRYABLE_FAILURE" : "COMPLETED", sessionId);
        return response(opened, results);
    }

    @Transactional(readOnly = true)
    public PullResponse pull(GdbPrincipal principal, long after, int requestedLimit) {
        GdbApplicationEntity app = applications.requireActive(principal.appCode());
        int limit = Math.min(Math.max(requestedLimit, 1), properties.maxPullSize());
        String table = DatabaseSchemaProvisioner.quote(app.getSchemaName());
        List<PulledEvent> events = jdbc.query("SELECT server_sequence,event_id,device_id,entity_type,entity_id,operation,base_version,entity_version,payload,created_at,accepted_at FROM " + table + ".sync_event_log WHERE tenant_id=? AND server_sequence>? ORDER BY server_sequence LIMIT ?",
                (rs, row) -> new PulledEvent(rs.getLong(1), rs.getObject(2, java.util.UUID.class), rs.getObject(3, java.util.UUID.class), rs.getString(4), rs.getObject(5, java.util.UUID.class), rs.getString(6), rs.getLong(7), rs.getLong(8), 1,
                        mapper.readTree(rs.getString(9)), instant(rs, 10), instant(rs, 11)), principal.tenantId(), after, limit + 1);
        boolean more = events.size() > limit;
        if (more) events = new ArrayList<>(events.subList(0, limit));
        long next = events.isEmpty() ? after : events.get(events.size() - 1).serverSequence();
        return new PullResponse(principal.appCode(), principal.tenantId(), after, next, more, events);
    }

    @Transactional(readOnly = true)
    public StatusResponse status(GdbPrincipal principal) {
        GdbApplicationEntity app = applications.requireActive(principal.appCode());
        String table = DatabaseSchemaProvisioner.quote(app.getSchemaName());
        Long latest = jdbc.queryForObject("SELECT COALESCE(MAX(server_sequence),0) FROM " + table + ".sync_event_log WHERE tenant_id=?", Long.class, principal.tenantId());
        Device device = devices.findByIdAndTenant_Id(principal.deviceId(), principal.tenantId()).orElseThrow();
        return new StatusResponse(principal.appId(), principal.appCode(), principal.tenantId(), principal.deviceId(), latest == null ? 0 : latest,
                conflicts.openCount(principal.appId(), principal.tenantId()), device.getLastSyncAt());
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private PushResponse response(SyncBatchStore.BatchState state, List<EventResult> results) {
        return new PushResponse(state.batchId(), state.receivedAt(), state.completedAt() == null ? Instant.now() : state.completedAt(),
                (int) results.stream().filter(r -> "ACCEPTED".equals(r.status())).count(),
                (int) results.stream().filter(r -> "DUPLICATE".equals(r.status())).count(),
                (int) results.stream().filter(r -> "REJECTED".equals(r.status())).count(),
                (int) results.stream().filter(r -> "CONFLICT".equals(r.status())).count(), List.copyOf(results));
    }

    private String hash(Object value) {
        try { return hashing.sha256(mapper.writeValueAsString(value)); }
        catch (JacksonException error) { throw new IllegalArgumentException("Request cannot be hashed", error); }
    }
}
