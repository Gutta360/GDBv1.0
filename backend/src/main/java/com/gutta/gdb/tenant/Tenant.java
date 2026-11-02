package com.gutta.gdb.tenant;

import com.gutta.gdb.application.GdbApplicationEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants", schema = "gdb_core")
public class Tenant {
    @Id @Column(name = "tenant_id", nullable = false) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "app_id", nullable = false) private GdbApplicationEntity application;
    @Column(name = "tenant_code", nullable = false, length = 50) private String code;
    @Column(name = "tenant_name", nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Tenant() {}
    public Tenant(UUID id, GdbApplicationEntity application, String code, String name, Instant now) {
        this.id = id; this.application = application; this.code = code; this.name = name; this.status = "ACTIVE";
        this.createdAt = now; this.updatedAt = now;
    }
    public UUID getId() { return id; }
    public GdbApplicationEntity getApplication() { return application; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isActive() { return "ACTIVE".equals(status) && application.isActive(); }
    public void suspend(Instant now) { status = "SUSPENDED"; updatedAt = now; }
    public void activate(Instant now) { status = "ACTIVE"; updatedAt = now; }
}
