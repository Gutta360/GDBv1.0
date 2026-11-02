package com.gutta.gdb.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ApplicationDtos {
    private ApplicationDtos() {}

    public record CreateApplicationRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,39}$") String appCode,
            @NotBlank @Size(min = 2, max = 120) String displayName,
            @Size(max = 120) String versionMetadata,
            String permittedDatasets,
            String snapshotPolicy) {}

    public record ApplicationResponse(UUID appId, String appCode, String displayName, String versionMetadata,
                                       String schemaName, String status, String permittedDatasets,
                                       String snapshotPolicy, Instant createdAt, Instant updatedAt) {
        static ApplicationResponse from(GdbApplicationEntity app) {
            return new ApplicationResponse(app.getId(), app.getCode(), app.getDisplayName(), app.getVersionMetadata(),
                    app.getSchemaName(), app.getStatus(), app.getPermittedDatasets(), app.getSnapshotPolicy(),
                    app.getCreatedAt(), app.getUpdatedAt());
        }
    }
}
