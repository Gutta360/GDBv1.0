# Database design

## PostgreSQL schemas

`gdb_core` is created by Flyway and contains registry, authorization metadata, batch results, conflict metadata, snapshot metadata, monitoring sessions, and audit logs. Registering code `GDC` provisions `gdb_app_gdc` through a validated identifier derived from the registry. GDB never concatenates an arbitrary client-provided schema name.

Each application schema has:

- `sync_event_log`: accepted generic events with a monotonically assigned `server_sequence`, event identity, origin device, entity/version metadata, JSONB payload, and timestamps.
- `entity_state`: current accepted entity payload/revision/tombstone per `(tenant_id, entity_type, entity_id)`.

The app code and tenant ID remain explicit scope columns even inside the app schema. This is defense in depth, not a replacement for API authorization.

## Core relationships

```text
applications 1--N tenants 1--N devices
      |             |           |
      +-- enrollment codes      +-- sync batches
      +-- snapshots              +-- audit logs
      +-- conflicts              +-- pull origin identity
```

Core primary IDs are UUIDs. Tenant-scoped unique keys include application/tenant scope. JSONB is used for opaque application metadata and event payloads; GDB does not create tables named after business concepts.

## Migration and upgrade rules

Flyway owns `gdb_core` migrations. Application schema provisioning is idempotent and only accepts the registry-derived `gdb_app_<lowercase-code>` pattern. Existing GT2.0/GApps schemas are not referenced or altered. Before production, add a forward Flyway migration for any change to core tables and rehearse it against a fresh and representative database.

## Backup boundary

Back up the PostgreSQL database, Flyway history, `gdb_core` metadata, app schemas, and the external snapshot directory together. A same-server copy is not an independent backup.
