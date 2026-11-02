package com.gutta.gdb.conflict;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conflicts", schema = "gdb_core")
public class Conflict {
    @Id @Column(name = "conflict_id", nullable = false) private UUID id;
    @Column(name = "app_id", nullable = false) private UUID appId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "device_id", nullable = false) private UUID deviceId;
    @Column(name = "entity_type", nullable = false, length = 80) private String entityType;
    @Column(name = "entity_id", nullable = false) private UUID entityId;
    @Column(name = "base_version", nullable = false) private long baseVersion;
    @Column(name = "current_version", nullable = false) private long currentVersion;
    @Column(name = "event_id", nullable = false) private UUID eventId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolution_note", length = 500) private String resolutionNote;
    protected Conflict() {}
    public Conflict(UUID appId, UUID tenantId, UUID deviceId, String entityType, UUID entityId,
                    long baseVersion, long currentVersion, UUID eventId, String payload) {
        id = UUID.randomUUID(); this.appId = appId; this.tenantId = tenantId; this.deviceId = deviceId;
        this.entityType = entityType; this.entityId = entityId; this.baseVersion = baseVersion; this.currentVersion = currentVersion;
        this.eventId = eventId; this.payload = payload; status = "OPEN"; createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getAppId() { return appId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public long getBaseVersion() { return baseVersion; }
    public long getCurrentVersion() { return currentVersion; }
    public UUID getEventId() { return eventId; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
}
