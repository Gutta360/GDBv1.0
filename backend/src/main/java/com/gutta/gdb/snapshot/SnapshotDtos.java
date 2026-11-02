package com.gutta.gdb.snapshot;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class SnapshotDtos {
    private SnapshotDtos() {}
    public record UploadRequest(
            @Min(1) int formatVersion,
            @NotNull Instant createdAt,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
            @Positive long contentLength,
            @NotBlank String payload,
            @Size(max = 2000) String recordCounts) {}
    public record SnapshotResponse(UUID snapshotId, UUID appId, UUID tenantId, UUID deviceId, int formatVersion,
                                   Instant createdAt, Instant uploadedAt, long contentLength, String sha256,
                                   String validationStatus, String status, String recordCounts) {
        static SnapshotResponse from(Snapshot s) { return new SnapshotResponse(s.getId(), s.getAppId(), s.getTenantId(), s.getDeviceId(),
                s.getFormatVersion(), s.getCreatedAt(), s.getUploadedAt(), s.getContentLength(), s.getSha256(), s.getValidationStatus(), s.getStatus(), s.getRecordCounts()); }
    }
}
