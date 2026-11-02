package com.gutta.gdb.synchronization;

import com.gutta.gdb.config.ApiException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SyncBatchStore {
    private final JdbcTemplate jdbc;
    public SyncBatchStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public BatchState open(UUID appId, UUID tenantId, UUID deviceId, SyncDtos.PushRequest request, String requestHash) {
        jdbc.update("INSERT INTO gdb_core.sync_batches(batch_id,app_id,tenant_id,device_id,contract_version,request_hash,received_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT (batch_id) DO NOTHING",
                request.batchId(), appId, tenantId, deviceId, request.contractVersion(), requestHash, Timestamp.from(Instant.now()));
        BatchState state = find(request.batchId());
        if (!state.appId().equals(appId) || !state.tenantId().equals(tenantId) || !state.deviceId().equals(deviceId))
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "BATCH_ID_REUSED", "Batch ID belongs to a different synchronization context.");
        if (!state.requestHash().equals(requestHash))
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "BATCH_ID_REUSED", "Batch ID was already used for different content.");
        return state;
    }

    @Transactional(readOnly = true)
    public BatchState find(UUID id) {
        try {
            return jdbc.queryForObject("SELECT batch_id,app_id,tenant_id,device_id,request_hash,received_at,completed_at,accepted_count,duplicate_count,rejected_count,conflict_count FROM gdb_core.sync_batches WHERE batch_id=?",
                    (rs, row) -> new BatchState(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                            rs.getString(5), instant(rs, 6), instant(rs, 7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11)), id);
        } catch (EmptyResultDataAccessException error) { throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", "Synchronization batch was not found."); }
    }

    @Transactional(readOnly = true)
    public List<SyncDtos.EventResult> results(UUID batchId) {
        return jdbc.query("SELECT event_id,status,result_code,result_message,server_sequence,acknowledged_at FROM gdb_core.sync_event_results WHERE batch_id=? ORDER BY event_index",
                (rs, row) -> new SyncDtos.EventResult(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Long) rs.getObject(5), instant(rs, 6)), batchId);
    }

    @Transactional
    public void result(UUID batchId, int index, SyncDtos.EventResult result) {
        jdbc.update("INSERT INTO gdb_core.sync_event_results(result_id,batch_id,event_id,event_index,status,result_code,result_message,server_sequence,acknowledged_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (batch_id,event_id) DO NOTHING",
                UUID.randomUUID(), batchId, result.eventId(), index, result.status(), result.code(), result.message(), result.serverSequence(), Timestamp.from(result.acknowledgedAt()));
    }

    @Transactional
    public void complete(UUID batchId, List<SyncDtos.EventResult> results, Instant completedAt) {
        int accepted = (int) results.stream().filter(r -> "ACCEPTED".equals(r.status())).count();
        int duplicate = (int) results.stream().filter(r -> "DUPLICATE".equals(r.status())).count();
        int rejected = (int) results.stream().filter(r -> "REJECTED".equals(r.status())).count();
        int conflict = (int) results.stream().filter(r -> "CONFLICT".equals(r.status())).count();
        jdbc.update("UPDATE gdb_core.sync_batches SET completed_at=?,accepted_count=?,duplicate_count=?,rejected_count=?,conflict_count=? WHERE batch_id=?",
                Timestamp.from(completedAt), accepted, duplicate, rejected, conflict, batchId);
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record BatchState(UUID batchId, UUID appId, UUID tenantId, UUID deviceId, String requestHash,
                             Instant receivedAt, Instant completedAt, int acceptedCount, int duplicateCount,
                             int rejectedCount, int conflictCount) {}
}
