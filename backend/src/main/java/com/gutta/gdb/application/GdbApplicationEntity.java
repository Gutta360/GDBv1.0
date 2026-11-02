package com.gutta.gdb.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "applications", schema = "gdb_core")
public class GdbApplicationEntity {
    @Id @Column(name = "app_id", nullable = false) private UUID id;
    @Column(name = "app_code", nullable = false, length = 40, unique = true) private String code;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(name = "version_metadata", length = 120) private String versionMetadata;
    @Column(name = "schema_name", nullable = false, length = 63, unique = true) private String schemaName;
    @Column(nullable = false, length = 20) private String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "permitted_datasets", nullable = false, columnDefinition = "jsonb") private String permittedDatasets;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "snapshot_policy", nullable = false, columnDefinition = "jsonb") private String snapshotPolicy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected GdbApplicationEntity() {}

    public GdbApplicationEntity(UUID id, String code, String displayName, String versionMetadata,
                                 String schemaName, String permittedDatasets, String snapshotPolicy, Instant now) {
        this.id = id; this.code = code; this.displayName = displayName; this.versionMetadata = versionMetadata;
        this.schemaName = schemaName; this.status = "ACTIVE"; this.permittedDatasets = permittedDatasets;
        this.snapshotPolicy = snapshotPolicy; this.createdAt = now; this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getVersionMetadata() { return versionMetadata; }
    public String getSchemaName() { return schemaName; }
    public String getStatus() { return status; }
    public String getPermittedDatasets() { return permittedDatasets; }
    public String getSnapshotPolicy() { return snapshotPolicy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isActive() { return "ACTIVE".equals(status); }
    public void deactivate(Instant now) { status = "INACTIVE"; updatedAt = now; }
    public void activate(Instant now) { status = "ACTIVE"; updatedAt = now; }
}
