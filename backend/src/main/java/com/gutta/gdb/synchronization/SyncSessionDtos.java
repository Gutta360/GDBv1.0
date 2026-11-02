package com.gutta.gdb.synchronization;

import java.time.Instant;
import java.util.UUID;

public final class SyncSessionDtos {
    private SyncSessionDtos() {}
    public record Session(UUID sessionId, UUID appId, UUID tenantId, UUID deviceId, Instant startedAt, Instant completedAt,
                          int uploadedCount, int downloadedCount, int rejectedCount, int conflictCount,
                          String outcome, String errorCategory) {}
}
