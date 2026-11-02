# Test plan and acceptance status

## Automated gates

- Java compile/package with Maven and Java 21.
- Unit tests for secret hashing, schema-name validation, DTO/version rules, and service orchestration.
- PostgreSQL/Testcontainers integration: Flyway startup, app/tenant/device registration, revocation, idempotent push, two-device pull convergence, stale-base conflict preservation, tombstones, isolation, and encrypted snapshot round trip.
- Angular strict production build and client TypeScript build.
- Reusable-client two-device simulation with separate local stores and cursors.

## Mandatory scenarios

1. Single-device create/upload.
2. Two-device merge of independent records.
3. Independent updates.
4. Same-base concurrent edit creates an open conflict without overwrite.
5. Repeated event upload returns `DUPLICATE`.
6. Interrupted push/pull resumes from durable outbox/cursor.
7. Delete propagates as tombstone.
8. Tenant and application isolation.
9. Revoked device is rejected.
10. Snapshot checksum, encryption, metadata, and retention.
11. Local GApp operations continue while backend is unavailable.
12. Unsupported protocol/payload versions fail safely.
13. PostgreSQL/backend restart preserves durable state.

## Status at handoff

The backend, Angular portal, and reusable client compile locally. The reusable client simulation passes convergence and conflict-preservation checks with independent stores. The PostgreSQL/Testcontainers suite now executes all four scenarios against disposable PostgreSQL 18.6 with zero skips and zero failures. Physical Android acceptance, process-restart/restore drills, and production deployment are not claimed.

See [TEST_RESULTS.md](TEST_RESULTS.md) for exact commands and outcomes.
