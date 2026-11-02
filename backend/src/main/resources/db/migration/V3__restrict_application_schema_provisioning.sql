CREATE OR REPLACE FUNCTION gdb_core.provision_application_schema(requested_schema text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, gdb_core
AS $$
DECLARE
    runtime_role name;
BEGIN
    IF requested_schema !~ '^gdb_app_[a-z][a-z0-9_]{1,39}$' THEN
        RAISE EXCEPTION 'Invalid managed schema name';
    END IF;

    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', requested_schema);
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I.sync_event_log (
        server_sequence BIGSERIAL PRIMARY KEY,
        event_id UUID NOT NULL UNIQUE,
        request_hash VARCHAR(64) NOT NULL,
        tenant_id UUID NOT NULL,
        device_id UUID NOT NULL,
        entity_type VARCHAR(80) NOT NULL,
        entity_id UUID NOT NULL,
        operation VARCHAR(20) NOT NULL,
        base_version BIGINT NOT NULL,
        entity_version BIGINT NOT NULL,
        payload JSONB NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        accepted_at TIMESTAMPTZ NOT NULL)', requested_schema);
    EXECUTE format('ALTER TABLE %I.sync_event_log ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64)', requested_schema);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I.sync_event_log(tenant_id, server_sequence)',
        requested_schema || '_event_tenant_seq', requested_schema);
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I.entity_state (
        tenant_id UUID NOT NULL,
        entity_type VARCHAR(80) NOT NULL,
        entity_id UUID NOT NULL,
        current_version BIGINT NOT NULL,
        last_event_id UUID NOT NULL,
        tombstone BOOLEAN NOT NULL,
        payload JSONB NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        PRIMARY KEY (tenant_id, entity_type, entity_id))', requested_schema);

    -- The runtime roles may use the managed application schema, but cannot
    -- create or alter arbitrary schemas/tables in the GDB database. The
    -- generic role is retained for the local role-isolation test while the
    -- explicitly isolated staging role is supported by the same migration.
    FOR runtime_role IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('gdb_app', 'gdb_staging_app')
    LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', requested_schema, runtime_role);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', requested_schema, runtime_role);
        EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA %I TO %I', requested_schema, runtime_role);
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION gdb_core.provision_application_schema(text) FROM PUBLIC;
DO $$
DECLARE
    runtime_role name;
BEGIN
    FOR runtime_role IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('gdb_app', 'gdb_staging_app')
    LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA gdb_core TO %I', runtime_role);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA gdb_core TO %I', runtime_role);
        EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA gdb_core TO %I', runtime_role);
        EXECUTE format('REVOKE CREATE ON SCHEMA gdb_core FROM %I', runtime_role);
        EXECUTE format('GRANT EXECUTE ON FUNCTION gdb_core.provision_application_schema(text) TO %I', runtime_role);
    END LOOP;
END
$$;
