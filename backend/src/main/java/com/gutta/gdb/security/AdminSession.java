package com.gutta.gdb.security;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_sessions", schema = "gdb_core")
public class AdminSession {
    @Id @Column(name = "admin_session_id", nullable = false) private UUID id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "admin_user_id", nullable = false)
    private AdminUser user;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected AdminSession() {}

    public AdminSession(UUID id, AdminUser user, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.user = user;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean usable(Instant now) { return revokedAt == null && expiresAt.isAfter(now) && user.isActive(); }
    public void touch(Instant at) { this.lastSeenAt = at; }
    public void revoke(Instant at) { this.revokedAt = at; }
    public UUID getId() { return id; }
    public AdminUser getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
