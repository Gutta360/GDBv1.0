CREATE SCHEMA IF NOT EXISTS gdb_core;

CREATE TABLE gdb_core.applications (
    app_id UUID PRIMARY KEY,
    app_code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    version_metadata VARCHAR(120),
    schema_name VARCHAR(63) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    permitted_datasets JSONB NOT NULL DEFAULT '[]'::jsonb,
    snapshot_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_gdb_applications_code UNIQUE (app_code),
    CONSTRAINT uk_gdb_applications_schema UNIQUE (schema_name),
    CONSTRAINT chk_gdb_applications_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_gdb_applications_code CHECK (app_code ~ '^[A-Z][A-Z0-9_]{1,39}$')
);

CREATE TABLE gdb_core.tenants (
    tenant_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES gdb_core.applications(app_id),
    tenant_code VARCHAR(50) NOT NULL,
    tenant_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_gdb_tenants_app_code UNIQUE (app_id, tenant_code),
    CONSTRAINT chk_gdb_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
CREATE INDEX ix_gdb_tenants_app_status ON gdb_core.tenants(app_id, status);

CREATE TABLE gdb_core.enrollment_codes (
    enrollment_code_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES gdb_core.applications(app_id),
    tenant_id UUID NOT NULL REFERENCES gdb_core.tenants(tenant_id),
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_gdb_enrollment_codes_tenant ON gdb_core.enrollment_codes(tenant_id, expires_at);

CREATE TABLE gdb_core.devices (
    device_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES gdb_core.applications(app_id),
    tenant_id UUID NOT NULL REFERENCES gdb_core.tenants(tenant_id),
    device_name VARCHAR(120) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    registration_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    application_version VARCHAR(80),
    platform VARCHAR(40),
    registered_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_gdb_devices_status CHECK (registration_status IN ('ACTIVE', 'REVOKED'))
);
CREATE INDEX ix_gdb_devices_tenant_status ON gdb_core.devices(tenant_id, registration_status);

CREATE TABLE gdb_core.sync_batches (
    batch_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES gdb_core.applications(app_id),
    tenant_id UUID NOT NULL REFERENCES gdb_core.tenants(tenant_id),
    device_id UUID NOT NULL REFERENCES gdb_core.devices(device_id),
    contract_version INTEGER NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    conflict_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_gdb_sync_batches_contract CHECK (contract_version > 0)
);
CREATE INDEX ix_gdb_sync_batches_tenant_received ON gdb_core.sync_batches(tenant_id, received_at DESC);

CREATE TABLE gdb_core.sync_event_results (
    result_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES gdb_core.sync_batches(batch_id),
    event_id UUID NOT NULL,
    event_index INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    result_code VARCHAR(80),
    result_message VARCHAR(500),
    server_sequence BIGINT,
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT uk_gdb_sync_results_batch_event UNIQUE (batch_id, event_id),
    CONSTRAINT chk_gdb_sync_results_status CHECK (status IN ('ACCEPTED', 'DUPLICATE', 'REJECTED', 'CONFLICT', 'RETRYABLE_FAILURE'))
);
CREATE INDEX ix_gdb_sync_results_batch_order ON gdb_core.sync_event_results(batch_id, event_index);

CREATE TABLE gdb_core.conflicts (
    conflict_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES gdb_core.applications(app_id),
    tenant_id UUID NOT NULL REFERENCES gdb_core.tenants(tenant_id),
    device_id UUID NOT NULL REFERENCES gdb_core.devices(device_id),
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID NOT NULL,
    base_version BIGINT NOT NULL,
    current_version BIGINT NOT NULL,
    event_id UUID NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution_note VARCHAR(500),
    CONSTRAINT chk_gdb_conflict_status CHECK (status IN ('OPEN', 'RESOLVED'))
);
CREATE INDEX ix_gdb_conflicts_tenant_status ON gdb_core.conflicts(tenant_id, status, created_at DESC);

CREATE TABLE gdb_core.snapshots (
    snapshot_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES gdb_core.applications(app_id),
    tenant_id UUID NOT NULL REFERENCES gdb_core.tenants(tenant_id),
    device_id UUID NOT NULL REFERENCES gdb_core.devices(device_id),
    format_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    content_length BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    validation_status VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    record_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    retention_until TIMESTAMPTZ,
    CONSTRAINT chk_gdb_snapshots_validation CHECK (validation_status IN ('VALID', 'INVALID')),
    CONSTRAINT chk_gdb_snapshots_status CHECK (status IN ('AVAILABLE', 'DELETED')),
    CONSTRAINT chk_gdb_snapshots_length CHECK (content_length > 0)
);
CREATE INDEX ix_gdb_snapshots_tenant_uploaded ON gdb_core.snapshots(tenant_id, uploaded_at DESC);

CREATE TABLE gdb_core.sync_sessions (
    session_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES gdb_core.applications(app_id),
    tenant_id UUID NOT NULL REFERENCES gdb_core.tenants(tenant_id),
    device_id UUID NOT NULL REFERENCES gdb_core.devices(device_id),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    uploaded_count INTEGER NOT NULL DEFAULT 0,
    downloaded_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    conflict_count INTEGER NOT NULL DEFAULT 0,
    outcome VARCHAR(30) NOT NULL DEFAULT 'STARTED',
    error_category VARCHAR(50)
);
CREATE INDEX ix_gdb_sync_sessions_tenant_started ON gdb_core.sync_sessions(tenant_id, started_at DESC);

CREATE TABLE gdb_core.audit_logs (
    audit_id UUID PRIMARY KEY,
    actor_type VARCHAR(30) NOT NULL,
    actor_id VARCHAR(120),
    app_id UUID REFERENCES gdb_core.applications(app_id),
    tenant_id UUID REFERENCES gdb_core.tenants(tenant_id),
    device_id UUID REFERENCES gdb_core.devices(device_id),
    action VARCHAR(80) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    details VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_gdb_audit_logs_time ON gdb_core.audit_logs(occurred_at DESC);
