# GDB V1.0 — GApps Data Backup

GDB is a standalone central synchronization and backup platform for independent GApps. It owns application registration, tenant/device scope, authenticated bidirectional event exchange, conflict preservation, snapshot metadata/storage, monitoring, and audit. It does not contain dental, fertilizer, laboratory, or other business workflows.

This repository is independent of GT2.0. GT2.0 was inspected read-only from `/Users/gutta/Desktop/GT2.0.zip`; no GT2.0 source, service, database, or deployment location is modified by this project.

## Current status

The local implementation contains the V1.0 backend foundation, generic push/pull protocol, per-application schema provisioning, enrollment/revocation, encrypted snapshot storage, admin portal source, TypeScript sync client, deployment templates, and required architecture documentation. Angular, client, and disposable PostgreSQL integration verification now pass locally.

Status: **CONDITIONALLY READY**. Backend, Angular, client, and PostgreSQL/Testcontainers builds/tests pass locally. Physical-device acceptance and production deployment have not been run. No production service was changed or deployed.

## Repository layout

```text
backend/       Spring Boot modular monolith, Flyway schema, APIs
frontend/      Angular standalone administration portal
client/        Reusable platform-neutral TypeScript sync client
deployment/    Docker/systemd/private reverse-proxy examples
docs/          Architecture, protocol, security, operations, testing
scripts/       Local validation and database backup helpers
```

## Local backend

Requirements: Java 21, Maven 3.9+, PostgreSQL 15+ (18 preferred for the target environment). Set secrets in the environment; do not commit them.

```bash
cd backend
export GDB_DB_URL='jdbc:postgresql://127.0.0.1:5432/gdb'
export GDB_DB_USERNAME='gdb_app'
export GDB_DB_PASSWORD='replace-me'
export GDB_ADMIN_BOOTSTRAP_TOKEN="$(openssl rand -hex 32)"
export GDB_SNAPSHOT_MASTER_KEY="$(openssl rand -base64 32)"
export GDB_SNAPSHOT_STORAGE="$PWD/data/snapshots"
mvn test
mvn spring-boot:run
```

The default local address is `127.0.0.1:8095` for the development run. Production planning uses `127.0.0.1:8092`, with Flyway-owned `gdb_core` and a restricted schema-provisioning function for validated `gdb_app_<code>` schemas. Existing databases and services are outside this repository’s scope.

## Admin portal

```bash
cd frontend
npm ci
npm start
```

Open `http://localhost:4200`; local development proxies `/api` to `127.0.0.1:8095`. For a centrally hosted deployment, the portal uses same-origin `/api` requests behind the reviewed private Nginx/Tailscale Service design. Use the bootstrap credential only to create the first administrator, then sign in with the expiring session-based portal. The portal is private administration tooling; expose it only through an approved private network path.

## Protocol and client

See [docs/api/API_CONTRACT.md](docs/api/API_CONTRACT.md), [docs/api/SYNC_PROTOCOL.md](docs/api/SYNC_PROTOCOL.md), and [client/typescript/src/index.ts](client/typescript/src/index.ts). A GApp remains responsible for its local SQLite/SQLCipher transaction, durable outbox, domain validation, and application of pulled events.

## Important limitations

- A device-local change not transferred to GDB cannot be recovered from GDB.
- V1.0 preserves conflicts and exposes them to the owning GApp; it does not perform generic business merges.
- Snapshot upload and validation are implemented; interactive restore and disaster-recovery orchestration are V2.0 work.
- Do not deploy to `rwp-server` until PostgreSQL integration, private HTTPS, backup/restore rehearsal, and physical-device acceptance are explicitly approved.

Read the [deployment guide](docs/operations/DEPLOYMENT_GUIDE.md), [hosting model](docs/architecture/GDB_ADMIN_HOSTING.md), [deployment plan](docs/deployment/DEPLOYMENT_PLAN.md), and [test plan](docs/testing/TEST_PLAN.md) before any server work.
