package com.gutta.gdb.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_users", schema = "gdb_core")
public class AdminUser {
    @Id @Column(name = "admin_user_id", nullable = false) private UUID id;
    @Column(nullable = false, unique = true, length = 80) private String username;
    @Column(name = "password_hash", nullable = false, length = 255) private String passwordHash;
    @Column(nullable = false, length = 30) private String role;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected AdminUser() {}

    public AdminUser(UUID id, String username, String passwordHash, String role, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = "ACTIVE";
        this.createdAt = createdAt;
    }

    public boolean isActive() { return "ACTIVE".equals(status) && revokedAt == null; }
    public void loggedIn(Instant at) { this.lastLoginAt = at; }
    public void revoke(Instant at) { this.status = "REVOKED"; this.revokedAt = at; }
    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
