# GDB v1.0 — RWP Server Phase 3B.1 Final Production Deployment Architecture

Architecture date: 2026-11-01 (Asia/Kolkata)  
Status: **CONDITIONAL PASS — design complete; deployment not authorized**

## 1. Scope and decision summary

This document defines the proposed production architecture for GDB v1.0 on `rwp-server`. It is a design and planning artifact only. No server connection, configuration write, package installation, database provisioning, service restart, route change, deployment, or migration was performed for Phase 3B.1.

The recommended design is:

1. Run the GDB Spring Boot backend as a dedicated systemd service, under a dedicated `gdb` account, bound only to `127.0.0.1:8092`.
2. Run PostgreSQL in a dedicated Docker container and named volume, with no public listener. Do not reuse `gapps-postgres` or `postgres_postgres_data`.
3. Serve the Angular portal and the synchronization API through a dedicated private HTTPS entry. The preferred route is a separately approved Tailscale HTTPS endpoint/hostname forwarding to a dedicated local Nginx GDB gateway on `127.0.0.1:8093`. The existing Tailscale root route to GT2.0 remains unchanged.
4. Keep releases, configuration, snapshots, logs, database data, and off-server backups in separate ownership and lifecycle boundaries.
5. Require an approved backup destination, restore rehearsal, private route/ACL, credentials, resource limits, and deployment authorization before Phase 3C.

The design is **CONDITIONALLY PASSABLE for controlled deployment planning**, but it is not a deployment approval. The private hostname/route, database resource policy, backup destination, operator authentication controls, and deployment authorization remain decisions for a later phase.

## 2. Evidence classification

| Label | Meaning |
|---|---|
| **VERIFIED** | Observed during the authorized Phase 3B read-only preflight or executed locally and recorded in the linked test results. |
| **PROPOSED** | The architecture recommended by this document; it has not been installed or exercised on `rwp-server`. |
| **DECISION REQUIRED** | An owner must approve or supply the value before deployment. |
| **OUT OF SCOPE** | Explicitly not performed in this phase. |

The authoritative infrastructure evidence is [RWP_PHASE3B_PREFLIGHT.md](RWP_PHASE3B_PREFLIGHT.md). Local build and PostgreSQL evidence is [TEST_RESULTS.md](../testing/TEST_RESULTS.md). The current deployment and rollback procedures remain [DEPLOYMENT_PLAN.md](DEPLOYMENT_PLAN.md) and [ROLLBACK_PLAN.md](ROLLBACK_PLAN.md).

## 3. Verified baseline

### 3.1 Host capacity and existing services

The Phase 3B inspection verified:

| Resource or service | Verified finding | GDB consequence |
|---|---|---|
| Operating system | Ubuntu 26.04 LTS, x86_64; kernel `7.0.0-38-generic` | Compatible in principle; no package installation was performed. |
| CPU | 12 logical CPUs | A modest additional service is plausible; no load test was performed. |
| Memory | 7.2 GiB total, 5.4 GiB available at inspection | GDB and its database need explicit limits and monitoring. |
| Swap | 4 GiB, unused at inspection | Safety margin only; not a capacity guarantee. |
| Root filesystem | 98 GiB total, 83 GiB free at inspection | Initial artifacts fit in principle; snapshots and backups need retention limits. |
| Existing GDC | systemd service on port 8080; Nginx `/api/` route | Must not be shared or modified by GDB. |
| Existing GDCLab | systemd service on port 8090 | Must remain independent. |
| Existing GT2.0 | systemd service on `127.0.0.1:8091` | Must remain independent; current Tailscale root targets it. |
| Docker | Active Docker 29.7.0/containerd service | Available as a deployment mechanism. |
| Existing PostgreSQL | `gapps-postgres`, image `postgres:18`, loopback `127.0.0.1:5432`, persistent `postgres_postgres_data` volume | Must not be reused casually. |
| Tailscale | Active, version 1.98.10; current private HTTPS root targets GT2.0 | A separate GDB endpoint must be verified and approved. |
| Nginx | Active, version 1.28.3; HTTP 80 is shared; no active Nginx 443 listener was observed | Do not add GDB to the existing global `/api/` route. |

The host Java version, exact existing PostgreSQL minor build, database contents, credentials, and business data were not inspected. No production data or secrets were accessed.

### 3.2 Local GDB readiness evidence

Phase 3A.1 is complete and is not a remaining blocker:

- Backend: **7 tests discovered, 7 passed, 0 failed, 0 skipped**.
- PostgreSQL integration: four Testcontainers scenarios executed against disposable PostgreSQL 18.6 and passed.
- Flyway: clean `gdb_core` schema migration to version 1 passed.
- Angular portal: 2 tests passed and production build passed.
- TypeScript client: 5 tests passed and declaration build passed.
- Repository validation and `git diff --check`: passed.

The local tests do not constitute production deployment or physical two-device/GApp SQLite acceptance. They do provide evidence for the server, database, synchronization, isolation, conflict, tombstone, snapshot, and checksum behaviors covered by the executed suite.

## 4. Target topology

```text
                         PRIVATE TAILNET / APPROVED PRIVATE NETWORK

  Admin browser and approved GApp devices
                 |
                 |  HTTPS only; no public Internet endpoint
                 v
  gdb-admin.<approved-private-name>          [PROPOSED; hostname/ACL required]
                 |
                 |  dedicated Tailscale HTTPS endpoint
                 |  existing GT2.0 Tailscale root is not changed
                 v
  Nginx GDB gateway: 127.0.0.1:8093           [PROPOSED local listener]
       |                              |
       | Angular static files           | /api/ and health proxy
       | /home/gutta/gdb/frontend/      v
       | current/browser/        GDB backend: 127.0.0.1:8092
       |                              |
       |                              | private loopback database connection
       |                              v
       |                 gdb-postgres container: 5432
       |                 dedicated gdb-postgres-data volume
       |                 optional loopback publication: 127.0.0.1:5433
       |
       +--> /var/lib/gdb/snapshots/     encrypted GDB snapshot storage

  Off-server encrypted backup target    [PROPOSED; destination and credentials required]
       ^              ^
       |              |
       |              +-- PostgreSQL backup / WAL or equivalent recovery stream
       +----------------- snapshots and backup manifests/checksums
```

The diagram shows a target design, not current server state. The only currently verified GDB-related listener is none: port 8092 was unbound during inspection. Ports 8093 and 5433 are proposed and are not reserved.

## 5. Service and filesystem layout

### 5.1 Services and lifecycle

| Component | Proposed name | Account | Bind/endpoint | Lifecycle boundary |
|---|---|---|---|---|
| GDB backend | `gdb-backend.service` | `gdb:gdb` | `127.0.0.1:8092` | Start/stop/restart only this unit during GDB operations. |
| GDB PostgreSQL | container `gdb-postgres`; optional wrapper `gdb-postgres.service` | container user/database role | Docker-private network; optional host bind `127.0.0.1:5433` | Independent project, volume, health check, and backup schedule. |
| GDB web gateway | dedicated Nginx GDB server block | Nginx service account | `127.0.0.1:8093` from the private Tailscale endpoint | Separate configuration fragment; no change to GDC or GDCLab routes. |
| Health check | GDB health-check timer or external monitor | restricted monitor | loopback `/actuator/health` | Alert only; must not expose secrets or mutate data. |

The repository example uses port 8095 for local Docker development. Production should use 8092 as the approved loopback backend port to avoid the existing 8080/8090/8091 services. The port choice is a proposal and must be confirmed with a final listener check immediately before installation.

### 5.2 Proposed paths

GDB should remain outside `/home/gutta/gapps`, which is already associated with GApps and its PostgreSQL Compose project.

```text
/home/gutta/gdb/
├── backend/releases/<version>/gdb-backend-<version>.jar
├── frontend/releases/<version>/browser/
├── backend/current -> approved backend release
├── frontend/current -> approved frontend release
└── operations/                         release metadata and manifests

/etc/gdb/backend.env                    root-owned, mode 0600 secrets/config
/var/lib/gdb/snapshots/                 mutable encrypted snapshot data
/var/log/gdb/                           optional file log staging, if required
/var/backups/gdb/                       local staging only; not the DR copy
```

The preferred target root is `/home/gutta/gdb` because the existing examples use that convention. `/srv/gdb` is an acceptable alternative if operations policy requires service-owned data under `/srv`; the choice must be made before installation and kept consistent across systemd, Nginx, backup, and rollback procedures.

The `gdb` account must own only the release/current paths and mutable snapshot data it needs. The environment file must not be committed to Git, placed in a web root, or printed through service diagnostics.

## 6. Ports, routes, and API exposure

| Port or route | Current/proposed state | Ownership and rule |
|---|---|---|
| TCP 80 | **VERIFIED current** shared Nginx HTTP | Leave existing GDC and GDCLab routing unchanged; do not add GDB's `/api/` here. |
| Tailscale HTTPS root / 443 | **VERIFIED current** root routes to GT2.0 `127.0.0.1:8091` | Do not reuse, replace, or add a conflicting root route. |
| TCP 5432 | **VERIFIED current** `gapps-postgres` loopback mapping | Do not reuse the existing GApps container or volume. |
| TCP 8080 | **VERIFIED current** GDC backend | No GDB traffic. |
| TCP 8090 | **VERIFIED current** GDCLab backend | No GDB traffic. |
| TCP 8091 | **VERIFIED current** GT2.0 backend | No GDB traffic. |
| TCP 8092 | **PROPOSED** GDB backend loopback listener | Confirm availability at deployment time; bind loopback only. |
| TCP 8093 | **PROPOSED** local Nginx GDB gateway | Confirm availability; bind loopback only. |
| TCP 5433 | **PROPOSED** GDB PostgreSQL loopback publication, only if needed by systemd | Confirm availability; do not expose outside the host. A Docker-private network is preferred if the backend is containerized instead. |
| `/` | **PROPOSED** Angular portal under the dedicated private hostname | Serve only the versioned GDB browser output. |
| `/api/` | **PROPOSED** same-origin GDB API | Proxy only to `127.0.0.1:8092`; never point it at the GDC backend. |
| `/actuator/health` | **PROPOSED** restricted health route | Limit to the private operator route or loopback monitor; do not expose database details. |

The Angular portal and API should remain same-origin to avoid browser CORS complexity. Device synchronization must use the same approved private HTTPS service or another separately approved private route. No APK or public Internet endpoint is part of this architecture. If physical GApps cannot reach the approved private network, that is a Phase 3C network decision, not a reason to expose GDB publicly.

## 7. Private HTTPS decision

### 7.1 Alternatives

| Option | Benefits | Risks or prerequisites | Decision |
|---|---|---|---|
| Dedicated Tailscale HTTPS endpoint/hostname | Private tailnet reachability, existing private transport, no public DNS requirement, preserves current public exposure boundary | A second usable hostname/route is not verified; Tailscale Serve ownership and ACLs must be confirmed; current root belongs to GT2.0 | **Recommended, subject to verification** |
| Dedicated Nginx private HTTPS virtual host | Familiar static/API routing, explicit host-based separation, can use an approved private certificate | Current Nginx has no verified 443 listener; current Tailscale root already owns private HTTPS; certificate, bind address, and route ownership must be designed without collision | Viable fallback, requires separate route design |
| Add GDB under existing GDC `/api/` or GT2.0 Tailscale root | Minimal apparent configuration | Namespace and ownership collision; would risk existing applications and violate isolation | **Rejected** |

### 7.2 Selected design

Select a **dedicated Tailscale HTTPS endpoint/hostname terminating at a dedicated local Nginx GDB gateway**. The Nginx gateway listens on a proposed loopback port 8093, serves the Angular files, and proxies `/api/` to the backend on 8092. This keeps the existing Nginx HTTP site and GT2.0 Tailscale root untouched.

This selection is conditional. The following must be verified and approved before deployment:

- The exact private hostname or endpoint and its ownership.
- Tailscale Serve capability for a separate endpoint/path without replacing the GT2.0 root.
- Tailnet ACLs for administrators and approved GApp devices.
- TLS certificate and renewal ownership.
- Whether the endpoint can reach only the local Nginx gateway and not the database.
- Nginx host/path behavior and request-size limits for encrypted snapshots.

If a separate Tailscale endpoint cannot be provided without changing the existing root, stop. Use a separately approved Nginx private virtual host and private address only after a route/certificate review. Do not silently convert the current root or shared `/api/` route.

Tailscale provides private network transport; it does not replace application authentication, authorization, device credential revocation, audit logging, or secret rotation.

## 8. PostgreSQL and Flyway architecture

### 8.1 Isolation design

The recommended production database boundary is:

- Dedicated Docker container: `gdb-postgres`.
- Dedicated named volume: `gdb-postgres-data`.
- Dedicated Docker network: `gdb-private`.
- Dedicated database: `gdb`.
- Dedicated runtime role: `gdb_app`.
- Separate migration role such as `gdb_migrator`, if the application/deployment process supports it.
- No connection from Nginx, Tailscale, browsers, or devices directly to PostgreSQL.
- No reuse of `gapps-postgres`, `postgres_postgres_data`, or any existing GApps database.

The repository's Docker Compose file is a development template and publishes PostgreSQL on `127.0.0.1:55432`; it is not production configuration. For a systemd-hosted backend, a dedicated loopback publication at proposed port 5433 may be used. If the backend is later containerized, prefer the Docker-private network and omit the host publication.

The image must be pinned to an approved PostgreSQL 18 digest or exact patch version before deployment. The Phase 3B inspection verified the existing GApps image tag `postgres:18`, but did not inspect its minor build and did not authorize reuse.

### 8.2 Migration procedure

Flyway must run only against the empty, dedicated GDB database during the first installation. Before the service is made available:

1. Confirm the target connection resolves to the dedicated database and volume.
2. Confirm the database role cannot access existing GApps databases.
3. Run Flyway and record the schema history and migration checksum.
4. Verify the expected `gdb_core` schema and version 1 migration.
5. Run health and smoke tests with a non-production test tenant.
6. Preserve the migration result with the release manifest.

The local disposable PostgreSQL evidence confirms the migration itself, including clean initialization. It does not prove the production connection, permissions, or backup/restore behavior.

### 8.3 Resource limits and monitoring

The existing PostgreSQL container had no reported explicit CPU or memory limit. GDB must not be added without a combined workload review. At minimum, deployment planning must set and monitor:

- PostgreSQL memory and CPU limits compatible with the measured 7.2 GiB host.
- Backend JVM heap and systemd memory limit.
- Snapshot upload size and per-tenant quota.
- Database and snapshot filesystem usage.
- Connection pool maximum and timeout.
- Backup age, backup size, checksum, and restore-test result.

Exact limits are **DECISION REQUIRED** after a representative workload estimate. The architecture must not infer safe production limits from the point-in-time idle load alone.

## 9. Data, snapshot, backup, and disaster recovery

These are separate recovery domains:

1. **Synchronization database:** tenants, devices, credentials, event log, cursors, conflicts, tombstones, audit data, and Flyway state.
2. **Encrypted snapshots:** uploaded GApp snapshot files and their checksums/metadata.
3. **Operational configuration and secrets:** release manifests, environment configuration, and credential-rotation records.
4. **Disaster recovery copy:** encrypted, off-server copies of the above required for loss of `rwp-server`.

### 9.1 Proposed policy

The following is a proposed starting policy, not a verified existing capability:

| Data | Frequency | Retention | Integrity/recovery evidence |
|---|---|---|---|
| PostgreSQL base backup or encrypted logical dump | Daily, with a pre-release backup | 35 daily copies and 12 monthly copies, subject to storage review | SHA-256 manifest, successful completion alert, periodic restore to a disposable database |
| PostgreSQL WAL/continuous recovery stream | Preferably every 15 minutes or better if operationally supported | At least 7 days, subject to off-server capacity | Recovery-point timestamp and replay/restore test |
| Encrypted GDB snapshots | On upload plus scheduled off-server copy | At least 14 daily and 8 weekly copies, subject to tenant policy | Per-object SHA-256 and periodic decrypt/checksum verification |
| Release artifacts and manifests | Every approved release | All currently supported rollback releases; prune only by policy | Artifact checksum and provenance record |
| Configuration/secret recovery metadata | On approved change, without copying plaintext secrets into logs | Versioned encrypted secret backup under owner-controlled access | Access audit and recovery exercise |

The policy targets an initial **RPO of 15 minutes for database changes when continuous WAL recovery is implemented**, otherwise the effective RPO is the last successful backup. The initial **RTO target is four hours** after a tested restore path exists. These targets are proposed and must be accepted by the service owner.

Same-server copies under `/var/backups/gdb` are staging only and do not satisfy disaster recovery. The off-server destination, encryption key ownership, network path, retention cost, and access controls are **DECISION REQUIRED**. No existing GDC backup directory is assumed to be suitable for GDB.

### 9.2 Recovery sequence

For a host or database loss, the future controlled procedure is:

1. Declare the incident and stop GDB traffic, leaving GDC, GDCLab, GT2.0, and their databases untouched.
2. Provision or select a clean, isolated recovery host/database target.
3. Verify backup manifest, checksum, key availability, and backup age.
4. Restore PostgreSQL and apply WAL to the selected recovery point.
5. Restore encrypted snapshots and verify their checksums without weakening encryption or tenant ownership checks.
6. Install the approved GDB artifact and configuration through the recovery runbook.
7. Verify Flyway state, health, authentication, tenant isolation, synchronization cursors, tombstones, conflicts, and snapshot access.
8. Re-open the private route only after acceptance evidence is recorded.

No restore drill has been executed against `rwp-server`; no production recovery claim is made.

## 10. Security and access model

### 10.1 Administrative access

The V1 portal currently uses a bootstrap administrator token and an `ADMIN` role as documented by the local implementation. The token is an application credential, not a full human identity, MFA, or SSO solution.

For the controlled V1 deployment:

- Keep the portal private behind the approved Tailscale/private HTTPS route.
- Store the bootstrap token outside Git in the protected environment file or approved secret manager.
- Rotate the bootstrap token before production use and document ownership.
- Enforce server-side `ADMIN` authorization on administration endpoints.
- Record administrative actions and credential revocations in the audit path.
- Do not treat Tailscale membership as a replacement for application authorization.

Human login/SSO/MFA and finer-grained operator roles are **DECISION REQUIRED** if the portal will be used by more than a small, explicitly trusted operator group. The current V1 token model is a deployment risk for broader exposure and must remain private.

### 10.2 Device and tenant access

- Device enrollment codes are single-use and short-lived according to the backend contract.
- Device credentials are stored and rotated independently of the administrator token.
- Revoked devices must fail authentication and must not receive incremental data.
- Tenant and application scope must be checked server-side for every registration, push, pull, event, and snapshot operation.
- Database roles and API authorization must prevent cross-tenant access even if a client forges identifiers.
- Synchronization traffic must use HTTPS through the approved private route.
- PostgreSQL and snapshot storage must not be reachable directly by devices.

### 10.3 Secrets and transport

The production secret set includes the database password, administrator credential, snapshot master key, TLS material or Tailscale ownership, and backup encryption keys. Secrets must be generated, stored, rotated, and recovered under named ownership. They must not appear in Git, command history, process listings where avoidable, logs, screenshots, or test output.

## 11. Monitoring and operational controls

The minimum operational surface for approval is:

- Backend health endpoint on loopback, checked by a restricted monitor.
- Systemd restart and failure alerts for `gdb-backend.service`.
- PostgreSQL container health, restart, connection, storage, and backup alerts.
- Disk usage alerts for database, snapshots, logs, and local backup staging.
- Backup age, checksum, and restore-test alerts.
- Authentication failures, revoked-device attempts, cross-tenant authorization failures, and administrative actions in the audit stream.
- Nginx/Tailscale route availability without exposing backend stack traces or database metadata.
- Log rotation and retention independent of GDC, GDCLab, and GT2.0.

The repository contains example health-check and logrotate files. They are templates only and were not installed or activated on `rwp-server`.

## 12. Future controlled deployment sequence

This sequence describes effects and stop points. It is not authorization to execute them.

### Gate A — approval and evidence

Obtain explicit approval for the database isolation model, private hostname/route, port assignments, service account, secret ownership, resource limits, backup/RPO/RTO policy, and maintenance window. Confirm a separate deployment authorization exists.

### Gate B — non-production rehearsal

Build the exact backend and frontend artifacts, record checksums, create a disposable GDB PostgreSQL target, run Flyway, execute the backend integration suite, rehearse snapshot upload/checksum validation, and test backup/restore. Stop on any skipped or failed mandatory test.

### Gate C — isolated installation

Create only GDB-specific directories, account, database container/volume, environment file, systemd unit, and private route. Do not edit or reload existing GDC, GDCLab, or GT2.0 services unless a separate change explicitly requires it and is approved. Verify the final listener table before starting GDB.

### Gate D — smoke and acceptance

Start only `gdb-postgres` and `gdb-backend`, validate Flyway state and health, then enable the private route. Test admin authentication, application/tenant creation, enrollment, device authentication, push/pull, cursors, duplicate handling, conflict preservation, tombstones, snapshot checksum validation, and tenant isolation. Record all results.

### Gate E — controlled integration

Run the approved two-device GApp test using an integrating SQLite/SQLCipher adapter. Confirm offline behavior, restart recovery, cursor persistence, retry behavior, conflict resolution, and snapshot workflows. These are not demonstrated by the reusable client alone.

## 13. Rollback and failure handling

Rollback must be GDB-only:

1. Withdraw only the GDB private route.
2. Preserve logs, audit records, release checksums, Flyway history, and backup identifiers.
3. Stop only `gdb-backend.service` and, if required, the GDB PostgreSQL component.
4. Restore the previous approved GDB backend/frontend artifacts.
5. Prefer a tested forward fix for schema changes; never run an unreviewed destructive migration rollback.
6. Restore the GDB database or snapshots only from a verified backup when data recovery is required.
7. Start GDB, verify health and smoke tests, then restore private traffic.
8. Record the incident, artifact, database state, recovery point, and follow-up action.

No rollback action may stop, restart, alter, or restore GDC, GDCLab, GT2.0, `gapps-postgres`, or unrelated containers/volumes.

## 14. Decisions, risks, and remaining prerequisites

| Item | Status | Required resolution |
|---|---|---|
| Phase 3A backend/PostgreSQL verification | **VERIFIED PASS** | No remaining local-test blocker; retain evidence in `TEST_RESULTS.md`. |
| GDB backend port 8092 | **PROPOSED** | Confirm unbound immediately before installation and approve. |
| Local Nginx gateway port 8093 | **PROPOSED** | Confirm unbound and approve the dedicated loopback server block. |
| Private hostname/Tailscale endpoint | **DECISION REQUIRED** | Verify separate endpoint/ACL support without changing GT2.0 root. |
| Nginx TLS fallback | **DECISION REQUIRED** | Define certificate, bind address, and ownership if Tailscale endpoint is unavailable. |
| Dedicated PostgreSQL container/volume | **PROPOSED** | Approve image digest, container project, volume, network, roles, and resource limits. |
| Backup/off-server destination | **DECISION REQUIRED** | Name destination, encryption key owner, retention, alert owner, and restore procedure. |
| RPO/RTO | **DECISION REQUIRED** | Accept initial 15-minute/4-hour targets or set alternatives. |
| V1 administrator token model | **RISK** | Rotate and protect for V1; decide whether SSO/MFA/finer roles are required before wider use. |
| Physical GApp network reachability | **DECISION REQUIRED** | Confirm devices can reach the private endpoint; do not make GDB public by default. |
| Two-device SQLite/SQLCipher acceptance | **NOT EXECUTED** | Complete with an integrating GApp in the separately authorized next phase. |
| Production deployment authorization | **NOT GRANTED** | Obtain explicit authorization after all gates and evidence are complete. |

## 15. Phase 3C readiness checklist

Phase 3C is **not authorized by this document**. Before it can be considered, all items below must be checked:

- [x] Phase 3A.1: 7 backend tests executed, 7 passed, 0 skipped.
- [x] Clean disposable PostgreSQL migration verified locally.
- [x] Local Angular and TypeScript builds verified.
- [x] Existing RWP infrastructure and route conflicts documented from read-only evidence.
- [ ] Exact private hostname/endpoint, certificate model, and Tailscale ACL approved.
- [ ] Existing GT2.0 Tailscale root and GDC `/api/` route protected from change.
- [ ] GDB service account, paths, listener ports, and systemd lifecycle approved.
- [ ] Dedicated PostgreSQL image, database, roles, volume, network, and resource limits approved.
- [ ] Backup/off-server destination, encryption, retention, RPO/RTO, and restore rehearsal approved.
- [ ] Secret generation, rotation, storage, and operator ownership approved.
- [ ] Production artifact checksums and deployment/rollback runbook reviewed.
- [ ] Explicit separate authorization to perform deployment granted.
- [ ] Two-device/GApp adapter test plan approved for the next phase.

## 16. Final Phase 3B.1 assessment

**CONDITIONAL PASS.** A safe, isolated GDB architecture has been established from the verified Phase 3B baseline and the completed local Phase 3A evidence. The recommendation preserves the existing GDC, GDCLab, GT2.0, Tailscale root, shared Nginx routes, and GApps PostgreSQL deployment.

The architecture is not yet deployment-ready because the private endpoint, database/resource policy, off-server backup target, secret/operator controls, and separate deployment authorization remain unresolved. Architecture approval, if granted, must not be interpreted as deployment authorization.

Phase 3B.1 stops here. No Phase 3C inspection, deployment, database provisioning, service change, or route change was performed.
