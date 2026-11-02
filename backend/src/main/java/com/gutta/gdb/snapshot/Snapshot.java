package com.gutta.gdb.snapshot;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "snapshots", schema = "gdb_core")
public class Snapshot {
    @Id @Column(name = "snapshot_id", nullable = false) private UUID id;
    @Column(name = "app_id", nullable = false) private UUID appId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "device_id", nullable = false) private UUID deviceId;
    @Column(name = "format_version", nullable = false) private int formatVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "uploaded_at", nullable = false) private Instant uploadedAt;
    @Column(name = "content_length", nullable = false) private long contentLength;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(name = "storage_key", nullable = false, length = 255, unique = true) private String storageKey;
    @Column(name = "validation_status", nullable = false, length = 20) private String validationStatus;
    @Column(nullable = false, length = 20) private String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "record_counts", nullable = false, columnDefinition = "jsonb") private String recordCounts;
    @Column(name = "retention_until") private Instant retentionUntil;
    protected Snapshot() {}
    public Snapshot(UUID id, UUID appId, UUID tenantId, UUID deviceId, int formatVersion, Instant createdAt,
                    long contentLength, String sha256, String storageKey, String recordCounts, Instant retentionUntil) {
        this.id = id; this.appId = appId; this.tenantId = tenantId; this.deviceId = deviceId; this.formatVersion = formatVersion;
        this.createdAt = createdAt; this.uploadedAt = Instant.now(); this.contentLength = contentLength; this.sha256 = sha256;
        this.storageKey = storageKey; validationStatus = "VALID"; status = "AVAILABLE"; this.recordCounts = recordCounts; this.retentionUntil = retentionUntil;
    }
    public UUID getId() { return id; }
    public UUID getAppId() { return appId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public int getFormatVersion() { return formatVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUploadedAt() { return uploadedAt; }
    public long getContentLength() { return contentLength; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public String getValidationStatus() { return validationStatus; }
    public String getStatus() { return status; }
    public String getRecordCounts() { return recordCounts; }
    public Instant getRetentionUntil() { return retentionUntil; }
    public void markDeleted() { status = "DELETED"; }
}
