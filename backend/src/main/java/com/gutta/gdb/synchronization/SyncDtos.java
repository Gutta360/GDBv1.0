package com.gutta.gdb.synchronization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SyncDtos {
    private SyncDtos() {}

    public record PushRequest(
            @Min(1) int contractVersion,
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{1,119}$") String appCode,
            @NotNull UUID tenantId,
            @NotNull UUID deviceId,
            @NotNull UUID batchId,
            @NotEmpty @Size(max = 500) List<@Valid SyncEvent> events) {}

    public record SyncEvent(
            @NotNull UUID eventId,
            @NotBlank @Size(max = 80) String entityType,
            @NotNull UUID entityId,
            @NotBlank @Pattern(regexp = "CREATE|UPDATE|DELETE") String operation,
            @Min(0) long baseVersion,
            @Min(1) long entityVersion,
            @Min(1) int payloadVersion,
            @NotNull JsonNode payload,
            @NotNull Instant createdAt) {}

    public record EventResult(UUID eventId, String status, String code, String message,
                              Long serverSequence, Instant acknowledgedAt) {}
    public record PushResponse(UUID batchId, Instant receivedAt, Instant completedAt,
                               int acceptedCount, int duplicateCount, int rejectedCount, int conflictCount,
                               List<EventResult> results) {}
    public record PullResponse(String appCode, UUID tenantId, long previousCursor, long nextCursor,
                               boolean hasMore, List<PulledEvent> events) {}
    public record PulledEvent(long serverSequence, UUID eventId, UUID originDeviceId, String entityType,
                              UUID entityId, String operation, long baseVersion, long entityVersion,
                              int payloadVersion, JsonNode payload, Instant createdAt, Instant acceptedAt) {}
    public record StatusResponse(UUID appId, String appCode, UUID tenantId, UUID deviceId,
                                 long latestServerSequence, long openConflictCount,
                                 Instant lastSuccessfulSyncAt) {}
}
