package com.gutta.gdb.device;

import com.gutta.gdb.application.GdbApplicationEntity;
import com.gutta.gdb.tenant.Tenant;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices", schema = "gdb_core")
public class Device {
    @Id @Column(name = "device_id", nullable = false) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "app_id", nullable = false) private GdbApplicationEntity application;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id", nullable = false) private Tenant tenant;
    @Column(name = "device_name", nullable = false, length = 120) private String name;
    @Column(name = "token_hash", nullable = false, length = 64, unique = true) private String tokenHash;
    @Column(name = "registration_status", nullable = false, length = 20) private String status;
    @Column(name = "application_version", length = 80) private String applicationVersion;
    @Column(length = 40) private String platform;
    @Column(name = "registered_at", nullable = false) private Instant registeredAt;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "last_sync_at") private Instant lastSyncAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected Device() {}
    public Device(UUID id, GdbApplicationEntity application, Tenant tenant, String name, String tokenHash,
                   String applicationVersion, String platform, Instant now) {
        this.id = id; this.application = application; this.tenant = tenant; this.name = name; this.tokenHash = tokenHash;
        this.applicationVersion = applicationVersion; this.platform = platform; this.status = "ACTIVE";
        this.registeredAt = now; this.lastSeenAt = now;
    }
    public UUID getId() { return id; }
    public GdbApplicationEntity getApplication() { return application; }
    public Tenant getTenant() { return tenant; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getApplicationVersion() { return applicationVersion; }
    public String getPlatform() { return platform; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public boolean isActive() { return "ACTIVE".equals(status) && tenant.isActive(); }
    public void touch(Instant now) { if (lastSeenAt == null || Duration.between(lastSeenAt, now).toMinutes() >= 5) lastSeenAt = now; }
    public void markSync(Instant now) { lastSyncAt = now; touch(now); }
    public void revoke(Instant now) { status = "REVOKED"; revokedAt = now; }
    public void rotateToken(String newHash, Instant now) { tokenHash = newHash; status = "ACTIVE"; revokedAt = null; lastSeenAt = now; }
}
