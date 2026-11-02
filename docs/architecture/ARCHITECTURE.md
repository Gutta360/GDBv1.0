# GDB architecture

## Boundary

GDB is a modular monolith and infrastructure service. GApps own business rules and their offline operational SQLite/SQLCipher databases. GDB accepts generic versioned entity events, establishes authoritative server ordering for accepted events, retains event history and current entity state, and preserves incompatible concurrent edits as conflicts.

```text
GApp Angular/Capacitor + SQLite/SQLCipher
       |  HTTPS, device bearer credential
       v
GDB Spring Boot modular monolith
       |-- application / tenant / device registry
       |-- generic push, pull, cursor, conflict protocol
       |-- snapshot validation and encrypted file storage
       |-- admin APIs and Angular portal
       v
PostgreSQL: gdb_core + one controlled gdb_app_<code> schema per application
```

## Backend modules

`application` registers approved app codes and provisions safe schema names. `tenant` scopes customers to applications. `device` implements enrollment and revocation. `synchronization` implements idempotent push, stable pull cursors, entity versions, tombstones, and server ordering. `conflict` records stale-base events. `snapshot` validates checksums and encrypts payloads at rest. `monitoring` and `audit` expose operational state without payload logging. `security` derives scope from the authenticated device token.

## Consistency model

The server does not use device clocks as authority and does not use last-write-wins by default. Each event carries a base entity version and next client entity version. A matching base version commits a new server sequence and entity state; a stale base is saved as an open conflict and is not applied. The client advances its pull cursor only after its local transaction applies the entire received page.

## Trust model

The request body is never the source of tenant authorization. The device token resolves to application, tenant, and device identity. Body identifiers must match that principal. Admin APIs use an environment-supplied bootstrap token for V1.0 and remain private by default.

## Intentional V1 boundary

There is no GApp patient model, business validation, unrestricted admin data editor, replacement-device restore wizard, or production deployment automation. Those belong to the GApp or the V2 roadmap.
