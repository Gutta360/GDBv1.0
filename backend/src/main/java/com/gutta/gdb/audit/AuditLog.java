package com.gutta.gdb.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs", schema = "gdb_core")
public class AuditLog {
    @Id @Column(name = "audit_id", nullable = false) private UUID id;
    @Column(name = "actor_type", nullable = false, length = 30) private String actorType;
    @Column(name = "actor_id", length = 120) private String actorId;
    @Column(name = "app_id") private UUID appId;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(name = "device_id") private UUID deviceId;
    @Column(nullable = false, length = 80) private String action;
    @Column(nullable = false, length = 20) private String outcome;
    @Column(length = 500) private String details;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    protected AuditLog() {}
    public AuditLog(String actorType, String actorId, UUID appId, UUID tenantId, UUID deviceId,
                    String action, String outcome, String details) {
        id = UUID.randomUUID(); this.actorType = actorType; this.actorId = actorId; this.appId = appId; this.tenantId = tenantId;
        this.deviceId = deviceId; this.action = action; this.outcome = outcome; this.details = details; occurredAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getActorType() { return actorType; }
    public String getActorId() { return actorId; }
    public UUID getAppId() { return appId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public String getAction() { return action; }
    public String getOutcome() { return outcome; }
    public String getDetails() { return details; }
    public Instant getOccurredAt() { return occurredAt; }
}
