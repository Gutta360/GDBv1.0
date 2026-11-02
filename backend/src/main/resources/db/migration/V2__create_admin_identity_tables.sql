CREATE TABLE gdb_core.admin_users (
    admin_user_id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uk_gdb_admin_users_username UNIQUE (username),
    CONSTRAINT chk_gdb_admin_users_role CHECK (role IN ('ADMIN', 'AUDITOR')),
    CONSTRAINT chk_gdb_admin_users_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE gdb_core.admin_sessions (
    admin_session_id UUID PRIMARY KEY,
    admin_user_id UUID NOT NULL REFERENCES gdb_core.admin_users(admin_user_id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX ix_gdb_admin_sessions_user ON gdb_core.admin_sessions(admin_user_id, expires_at);
CREATE INDEX ix_gdb_admin_sessions_expiry ON gdb_core.admin_sessions(expires_at);
