package com.gutta.gdb.synchronization;

import com.gutta.gdb.conflict.ConflictService;
import com.gutta.gdb.security.GdbPrincipal;
import com.gutta.gdb.shared.DatabaseSchemaProvisioner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class SyncEventProcessor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConflictService conflicts;

    public SyncEventProcessor(JdbcTemplate jdbc, ObjectMapper mapper, ConflictService conflicts) {
        this.jdbc = jdbc; this.mapper = mapper; this.conflicts = conflicts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncDtos.EventResult process(GdbPrincipal principal, String schema, SyncDtos.SyncEvent event) {
        String table = DatabaseSchemaProvisioner.quote(schema);
        String eventHash = hash(event);
        ExistingEvent existing = findEvent(table, event.eventId());
        Instant now = Instant.now();
        if (existing != null) {
            if (existing.requestHash().equals(eventHash)) return new SyncDtos.EventResult(event.eventId(), "DUPLICATE", "IDEMPOTENT_REPLAY", "Event was already accepted.", existing.sequence(), now);
            return new SyncDtos.EventResult(event.eventId(), "REJECTED", "EVENT_ID_REUSED", "Event ID was already used for different content.", null, now);
        }
        if (event.baseVersion() < 0 || event.entityVersion() != event.baseVersion() + 1 || event.payloadVersion() != 1)
            return new SyncDtos.EventResult(event.eventId(), "REJECTED", "INVALID_VERSION", "Event version metadata is invalid.", null, now);
        if (event.payload().isNull() || !event.payload().isObject())
            return new SyncDtos.EventResult(event.eventId(), "REJECTED", "INVALID_PAYLOAD", "Event payload must be a JSON object.", null, now);

        CurrentState state = findState(table, principal.tenantId(), event);
        if ("CREATE".equals(event.operation()) && state != null)
            return new SyncDtos.EventResult(event.eventId(), "REJECTED", "ENTITY_EXISTS", "Create targets an existing entity.", null, now);
        if (!"CREATE".equals(event.operation()) && state == null)
            return new SyncDtos.EventResult(event.eventId(), "REJECTED", "ENTITY_NOT_FOUND", "Update or delete targets an unknown entity.", null, now);
        long current = state == null ? 0 : state.version();
        if (event.baseVersion() != current) {
            String payload = write(event.payload());
            conflicts.record(principal, event.entityType(), event.entityId(), event.baseVersion(), current, event.eventId(), payload);
            return new SyncDtos.EventResult(event.eventId(), "CONFLICT", "STALE_BASE_VERSION", "Concurrent change preserved as an open conflict.", null, now);
        }
        Long sequence = jdbc.queryForObject("INSERT INTO " + table + ".sync_event_log(server_sequence,event_id,request_hash,tenant_id,device_id,entity_type,entity_id,operation,base_version,entity_version,payload,created_at,accepted_at) VALUES (DEFAULT,?,?,?,?,?,?,?,?,?,?::jsonb,?,?) RETURNING server_sequence",
                Long.class, event.eventId(), eventHash, principal.tenantId(), principal.deviceId(), event.entityType(), event.entityId(), event.operation(),
                event.baseVersion(), event.entityVersion(), write(event.payload()), Timestamp.from(event.createdAt()), Timestamp.from(now));
        jdbc.update("INSERT INTO " + table + ".entity_state(tenant_id,entity_type,entity_id,current_version,last_event_id,tombstone,payload,updated_at) VALUES (?,?,?,?,?,?,?::jsonb,?) " +
                        "ON CONFLICT (tenant_id,entity_type,entity_id) DO UPDATE SET current_version=EXCLUDED.current_version,last_event_id=EXCLUDED.last_event_id,tombstone=EXCLUDED.tombstone,payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at",
                principal.tenantId(), event.entityType(), event.entityId(), event.entityVersion(), event.eventId(), "DELETE".equals(event.operation()), write(event.payload()), Timestamp.from(now));
        return new SyncDtos.EventResult(event.eventId(), "ACCEPTED", "APPLIED", "Event committed.", sequence, now);
    }

    private ExistingEvent findEvent(String table, UUID eventId) {
        try {
            return jdbc.queryForObject("SELECT server_sequence,request_hash FROM " + table + ".sync_event_log WHERE event_id=?",
                    (rs, row) -> new ExistingEvent(rs.getLong(1), rs.getString(2)), eventId);
        } catch (org.springframework.dao.EmptyResultDataAccessException error) { return null; }
    }

    private CurrentState findState(String table, UUID tenantId, SyncDtos.SyncEvent event) {
        try {
            return jdbc.queryForObject("SELECT current_version FROM " + table + ".entity_state WHERE tenant_id=? AND entity_type=? AND entity_id=? FOR UPDATE",
                    (rs, row) -> new CurrentState(rs.getLong(1)), tenantId, event.entityType(), event.entityId());
        } catch (org.springframework.dao.EmptyResultDataAccessException error) { return null; }
    }

    private String hash(SyncDtos.SyncEvent event) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(event).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (JacksonException | java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("Event cannot be hashed", error); }
    }

    private String write(JsonNode node) {
        try { return mapper.writeValueAsString(node); } catch (JacksonException error) { throw new IllegalArgumentException("Payload cannot be serialized", error); }
    }

    private record ExistingEvent(long sequence, String requestHash) {}
    private record CurrentState(long version) {}
}
