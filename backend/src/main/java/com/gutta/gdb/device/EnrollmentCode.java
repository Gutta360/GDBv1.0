package com.gutta.gdb.device;

import com.gutta.gdb.application.GdbApplicationEntity;
import com.gutta.gdb.tenant.Tenant;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "enrollment_codes", schema = "gdb_core")
public class EnrollmentCode {
    @Id @Column(name = "enrollment_code_id", nullable = false) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "app_id", nullable = false) private GdbApplicationEntity application;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id", nullable = false) private Tenant tenant;
    @Column(name = "code_hash", nullable = false, length = 64, unique = true) private String codeHash;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "used_at") private Instant usedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected EnrollmentCode() {}
    public EnrollmentCode(UUID id, GdbApplicationEntity application, Tenant tenant, String codeHash, Instant expiresAt, Instant createdAt) {
        this.id = id; this.application = application; this.tenant = tenant; this.codeHash = codeHash; this.expiresAt = expiresAt; this.createdAt = createdAt;
    }
    public UUID getId() { return id; }
    public GdbApplicationEntity getApplication() { return application; }
    public Tenant getTenant() { return tenant; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean canUseAt(Instant now) { return usedAt == null && expiresAt.isAfter(now) && tenant.isActive() && application.isActive(); }
    public void consume(Instant now) { usedAt = now; }
}
