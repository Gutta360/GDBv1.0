# GDB controlled deployment plan

This is a future execution plan, not an authorization to deploy. Every mutation requires separate approval and a named checkpoint owner.

## Checkpoints

1. **Read-only inventory:** confirm host resources, services, ports, private DNS/HTTPS, storage, backup destination, and a GDB-only PostgreSQL target. Stop if any existing service or database would be reused.
2. **Artifact review:** build the backend, run Maven tests, build Angular, build/test the TypeScript client, review checksums, and stage a versioned release outside the live path.
3. **Database rehearsal:** create a disposable or separately approved empty GDB database, run Flyway, execute PostgreSQL integration tests, and rehearse backup/restore.
4. **Private network preparation:** define and test separate Tailscale Services/ACLs for GDB Admin and GDB Sync, and validate that PostgreSQL and ports 8092–8094 remain private.
5. **Install:** create the dedicated GDB service account/path, install the reviewed JAR and Angular `browser` output, create root-readable environment secrets, and configure the reviewed systemd/Nginx templates.
6. **Start and smoke test:** start only the new GDB service, verify `/actuator/health`, Flyway state, admin authentication, application/tenant registration, one enrollment, and a push/pull smoke test in a test tenant.
7. **Acceptance:** run the approved two-device GApp adapter test, snapshot checksum test, backup test, and monitoring/audit review.
8. **Handoff:** record versions, configuration ownership, backup schedule, alert ownership, and rollback evidence.

## Explicit stop conditions

Stop before installation if PostgreSQL isolation, private HTTPS, backup/restore, admin access controls, or service inventory is uncertain. Do not remove or migrate any existing GApp service as part of GDB V1.0.
