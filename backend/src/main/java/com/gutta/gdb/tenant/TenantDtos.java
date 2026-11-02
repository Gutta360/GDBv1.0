package com.gutta.gdb.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class TenantDtos {
    private TenantDtos() {}
    public record CreateTenantRequest(
            @NotNull UUID appId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,50}$") String tenantCode,
            @NotBlank @Size(min = 2, max = 120) String tenantName) {}

    public record TenantResponse(UUID tenantId, UUID appId, String appCode, String tenantCode,
                                 String tenantName, String status, Instant createdAt, Instant updatedAt) {
        static TenantResponse from(Tenant t) { return new TenantResponse(t.getId(), t.getApplication().getId(),
                t.getApplication().getCode(), t.getCode(), t.getName(), t.getStatus(), t.getCreatedAt(), t.getUpdatedAt()); }
    }

}
