package com.gutta.gdb.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class DeviceDtos {
    private DeviceDtos() {}
    public record CreateEnrollmentCodeRequest(@NotNull UUID tenantId) {}
    public record EnrollmentCodeResponse(UUID appId, UUID tenantId, String enrollmentCode, Instant expiresAt) {}
    public record RegisterDeviceRequest(
            @NotBlank @Size(min = 20, max = 200) String enrollmentCode,
            @NotBlank @Size(min = 2, max = 120) String deviceName,
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{1,119}$") String applicationId,
            @NotBlank @Size(max = 80) String applicationVersion,
            @NotBlank @Size(max = 40) String platform) {}
    public record RegisterDeviceResponse(UUID appId, UUID tenantId, UUID deviceId, String tokenType,
                                         String deviceToken, String appCode, String tenantCode,
                                         String tenantName, Instant registeredAt) {}
    public record DeviceResponse(UUID appId, UUID tenantId, UUID deviceId, String appCode, String tenantCode,
                                 String deviceName, String status, String applicationVersion, String platform,
                                 Instant registeredAt, Instant lastSeenAt, Instant lastSyncAt, Instant revokedAt) {
        static DeviceResponse from(Device d) { return new DeviceResponse(d.getApplication().getId(), d.getTenant().getId(), d.getId(),
                d.getApplication().getCode(), d.getTenant().getCode(), d.getName(), d.getStatus(), d.getApplicationVersion(),
                d.getPlatform(), d.getRegisteredAt(), d.getLastSeenAt(), d.getLastSyncAt(), d.getRevokedAt()); }
    }
    public record RevokeDeviceResponse(UUID deviceId, String status, Instant revokedAt) {}
    public record RotateCredentialResponse(UUID deviceId, String tokenType, String deviceToken, Instant rotatedAt) {}
}
