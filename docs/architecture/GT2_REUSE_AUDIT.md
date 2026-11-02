# GT2.0 reuse audit

Audit source: `/Users/gutta/Desktop/GT2.0.zip`, extracted read-only under `/private/tmp/gdb-gt2-audit`. The ZIP is the source of truth; the ZIP’s own Phase 2 readiness document incorrectly says the backend is documentation-only even though the archive contains a compiled Spring Boot backend and source.

## Inventory

- Backend: Java 21, Spring Boot 4.1.2, Spring Security, JPA, Flyway, PostgreSQL, Actuator, Testcontainers.
- Core backend packages: `com.gt.device`, `tenant`, `security`, `sync`, `patient`, and `snapshot`.
- Migration: one `V1__create_gt2_backup_schema.sql` creating `tenants`, `enrollment_codes`, `devices`, PATIENT tables, upload batches/results, processed event markers, snapshots, and recovery audit.
- Frontend: Angular 22.2, TypeScript 6, Capacitor 8, encrypted `@capacitor-community/sqlite`, local users, patients, outbox, and a one-way upload/snapshot client.
- Tests/docs: frontend unit tests, backend hashing/sync tests, PostgreSQL/Testcontainers integration tests, API/schema/operations documentation.

## Classification

### A — directly reusable patterns

- UUID event and entity identity.
- One-use expiring enrollment code with hashed storage and pessimistic consumption lock.
- Random per-device opaque bearer token with server-side SHA-256 hash and revocation.
- Constant-time secret comparison and environment-only admin secret.
- Local patient/outbox transaction and outbox state machine (`PENDING`, `IN_FLIGHT`, `FAILED`, `ACKNOWLEDGED`).
- Bounded batch selection, explicit per-event acknowledgements, idempotent event replay, tombstones, and stable outbox ordering.
- AES-256-GCM snapshot file envelope, normalized storage path, checksum verification, and retention pattern.
- Java 21/Spring Boot/Flyway/Testcontainers toolchain.

### B — reusable after refactoring

- GT2 DTO validation and exception response style, generalized to generic entity types.
- Device/tenant provisioning flow, expanded with application scope and lifecycle audit.
- Snapshot logical-document approach, generalized to an opaque application-owned payload and metadata rather than PATIENT rows.
- Angular/Capacitor sync repository ideas, generalized in `client/typescript` behind a local-store abstraction.

### C — new GDB implementation

- Application registry and controlled per-app schema provisioning.
- Tenant/application/device authorization model.
- Bidirectional pull stream and device cursor contract.
- Generic event/state tables and server sequence ordering.
- Default conflict preservation for stale base versions.
- Admin dashboard and monitoring APIs.
- Cross-application and cross-tenant isolation tests and operational docs.

### D — remains in GT2.0/GApps

- Patient entities, numbering, patient validation, patient screens, local user login, dental workflows, and domain-aware conflict resolution.

GT2.0 itself remains unchanged and is not a GDB module.
