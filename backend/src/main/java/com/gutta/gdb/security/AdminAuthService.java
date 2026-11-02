package com.gutta.gdb.security;

import com.gutta.gdb.audit.AuditService;
import com.gutta.gdb.config.ApiException;
import com.gutta.gdb.config.GdbProperties;
import com.gutta.gdb.shared.SecretHashing;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static com.gutta.gdb.security.AdminDtos.*;

@Service
public class AdminAuthService {
    private final AdminUserRepository users;
    private final AdminSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final SecretHashing hashing;
    private final GdbProperties properties;
    private final AuditService audit;

    public AdminAuthService(AdminUserRepository users, AdminSessionRepository sessions, PasswordEncoder passwords,
                            SecretHashing hashing, GdbProperties properties, AuditService audit) {
        this.users = users; this.sessions = sessions; this.passwords = passwords; this.hashing = hashing;
        this.properties = properties; this.audit = audit;
    }

    @Transactional
    public SessionIssue bootstrap(BootstrapRequest request) {
        if (!hashing.constantTimeEquals(request.bootstrapToken(), properties.adminToken()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_BOOTSTRAP_CREDENTIAL", "The bootstrap credential is invalid.");
        if (users.countByStatus("ACTIVE") > 0)
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ALREADY_PROVISIONED", "An administrator is already provisioned.");
        AdminUser user = users.save(new AdminUser(java.util.UUID.randomUUID(), normalize(request.username()),
                passwords.encode(request.password()), "ADMIN", Instant.now()));
        audit.record("ADMIN", user.getUsername(), null, null, null, "ADMIN_BOOTSTRAP", "SUCCESS", "Initial administrator provisioned.");
        return issue(user);
    }

    @Transactional
    public SessionIssue login(LoginRequest request) {
        String username = normalize(request.username());
        Optional<AdminUser> candidate = users.findByUsernameIgnoreCase(username);
        if (candidate.isEmpty() || !candidate.get().isActive() || !passwords.matches(request.password(), candidate.get().getPasswordHash())) {
            audit.record("ADMIN", username, null, null, null, "ADMIN_LOGIN", "FAILURE", "Invalid administrator credentials.");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_CREDENTIALS", "Administrator credentials are invalid.");
        }
        AdminUser user = candidate.get();
        user.loggedIn(Instant.now());
        audit.record("ADMIN", user.getUsername(), null, null, null, "ADMIN_LOGIN", "SUCCESS", "Administrator session created.");
        return issue(user);
    }

    @Transactional
    public Optional<AdminPrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return sessions.findByTokenHash(hashing.sha256(rawToken))
                .filter(session -> session.usable(Instant.now()))
                .map(session -> {
                    session.touch(Instant.now());
                    AdminUser user = session.getUser();
                    return new AdminPrincipal(user.getId(), user.getUsername(), user.getRole());
                });
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        sessions.findByTokenHash(hashing.sha256(rawToken)).ifPresent(session -> {
            session.revoke(Instant.now());
            audit.record("ADMIN", session.getUser().getUsername(), null, null, null, "ADMIN_LOGOUT", "SUCCESS", "Administrator session revoked.");
        });
    }

    @Transactional
    public void revokeAll(AdminPrincipal principal) {
        sessions.revokeAllForUser(principal.userId(), Instant.now());
        audit.record("ADMIN", principal.username(), null, null, null, "ADMIN_SESSIONS_REVOKED", "SUCCESS", "All administrator sessions revoked.");
    }

    private SessionIssue issue(AdminUser user) {
        Instant now = Instant.now();
        Instant expires = now.plus(properties.adminSessionTtl());
        String raw = hashing.randomToken("gdbs_");
        sessions.save(new AdminSession(java.util.UUID.randomUUID(), user, hashing.sha256(raw), now, expires));
        return new SessionIssue(raw, new SessionResponse(user.getUsername(), user.getRole(), expires));
    }

    private String normalize(String username) {
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z0-9][a-z0-9._-]{1,79}$"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_USERNAME", "Administrator username is invalid.");
        return normalized;
    }

    public record SessionIssue(String rawToken, SessionResponse response) {}
}
