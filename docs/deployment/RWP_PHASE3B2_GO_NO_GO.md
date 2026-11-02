# GDB v1.0 — Phase 3B.2 Final Deployment Readiness and GO/NO-GO Review

Review date: 2026-11-01 (Asia/Kolkata)  
Review scope: planning and documentation only  
Final recommendation: **NO-GO for Phase 3C at this time**

## 1. Executive decision

GDB v1.0 is locally verified and has a viable isolated deployment architecture, but the evidence is not sufficient to authorize Phase 3C or production installation.

The current decision is:

- **Local implementation readiness:** GO for continued planning.
- **Controlled server deployment:** NO-GO until the listed owner approvals and rehearsals are complete.
- **Phase 3C:** NO-GO; do not begin automatically.

This is an operational NO-GO, not a finding that the tested GDB code has failed. The principal blockers are the unresolved private endpoint for administrators, a secure connectivity design for customer tablets that may not be on Tailscale, production database privilege separation, an off-server backup target and restore rehearsal, resource limits, and the V1 bootstrap-token administration model.

No SSH, infrastructure change, installation, database provisioning, migration, service restart, route change, or deployment was performed for this review.

## 2. Evidence reviewed and classification

| Source | Evidence status |
|---|---|
| [Phase 3B.1 architecture](RWP_PHASE3B1_FINAL_ARCHITECTURE.md) | Reviewed as the proposed target design; proposals were not treated as installed facts. |
| [Phase 3B read-only infrastructure assessment](RWP_PHASE3B_PREFLIGHT.md) | Verified point-in-time host, service, port, Docker, PostgreSQL, Nginx, and Tailscale observations. |
| [Phase 3A.1 test results](../testing/TEST_RESULTS.md) | Verified local test/build evidence. |
| Source configuration and security code | Reviewed for binding, Flyway, authentication, authorization, and secret behavior. |

The following labels are used below:

- **VERIFIED:** executed or observed evidence exists.
- **PROPOSED:** recommended design, not installed or exercised on `rwp-server`.
- **OWNER APPROVAL:** a named owner must approve the value or decision.
- **BLOCKER:** Phase 3C must not start while unresolved.

## 3. Verified prerequisites

### 3.1 Local implementation and integration evidence

The authoritative Phase 3A.1 run recorded:

- Backend: **7 tests discovered, 7 passed, 0 failed, 0 skipped**.
- PostgreSQL integration: four Testcontainers scenarios executed against disposable PostgreSQL 18.6 with zero skips.
- Flyway: clean `gdb_core` schema migration to version 1 passed.
- Angular portal: 2 tests passed and production build passed.
- TypeScript client: 5 tests passed and declaration build passed.
- Repository validation and `git diff --check`: passed.

The integration suite provides evidence for registration/enrollment, device authentication and credential lifecycle, tenant/application isolation, push/pull, cursors, duplicate idempotency, stale conflicts, tombstones, encrypted snapshots, and checksum validation. It does not prove physical customer-tablet connectivity, real GApp SQLite/SQLCipher behavior, process-restart recovery, or production backup restoration.

### 3.2 Verified server baseline

The authorized Phase 3B inspection recorded:

- Ubuntu 26.04 LTS, x86_64, 12 logical CPUs.
- 7.2 GiB RAM with 5.4 GiB available at inspection and 4 GiB unused swap.
- 83 GiB free on the 98 GiB root filesystem at inspection.
- Docker 29.7.0 active; existing `gapps-postgres` uses `postgres:18`, loopback port 5432, and persistent volume `postgres_postgres_data`.
- Existing GDC, GDCLab, and GT2.0 services on 8080, 8090, and 8091 respectively.
- Nginx HTTP port 80 with GDC `/api/` routing.
- Tailscale private HTTPS root currently routes to GT2.0 on 8091.
- Port 8092 had no listener at inspection time; it is a candidate only, not reserved.

These measurements are point-in-time observations. No load test, database query, backup test, or stress test was performed.

## 4. Final recommended topology

```text
Admin browser
    |
    | approved private HTTPS, administrator ACL
    v
https://gdb-admin.<approved-private-name>/       [OWNER APPROVAL]
    |
    | separate Tailscale HTTPS endpoint/hostname
    | current GT2.0 Tailscale root remains unchanged
    v
Nginx GDB gateway, 127.0.0.1:8093                [PROPOSED]
    |-- Angular static files: /home/gutta/gdb/frontend/current/browser
    `-- /api/ and restricted health proxy
            |
            v
GDB backend systemd service, 127.0.0.1:8092     [PROPOSED]
    |  user/group: gdb:gdb
    |  release root: /home/gutta/gdb
    v
gdb-postgres container, private Docker network  [PROPOSED]
    |-- database: gdb
    |-- runtime role: gdb_app
    |-- migration role: gdb_migrator
    `-- named volume: gdb-postgres-data

Encrypted snapshots: /var/lib/gdb/snapshots
Encrypted database/snapshot backups: approved off-server target

Customer tablets outside the tailnet:
    NO APPROVED PRODUCTION PATH YET — HARD BLOCKER
```

The administrator path and device synchronization path are separate access decisions. The administrator portal must not be used as evidence that arbitrary customer tablets can safely reach the synchronization API.

## 5. Backend deployment decision

### 5.1 Recommended configuration

| Setting | Recommendation | Status |
|---|---|---|
| Service | `gdb-backend.service` | PROPOSED |
| Account | Dedicated `gdb:gdb` account | OWNER APPROVAL; not created |
| Backend bind | `127.0.0.1` only | PROPOSED; must be enforced |
| Backend port | `8092` | Candidate observed free; final availability must be rechecked |
| Release path | `/home/gutta/gdb/backend/releases/<version>/` | PROPOSED |
| Current release | Versioned symlink, not an in-place overwrite | PROPOSED |
| Snapshot path | `/var/lib/gdb/snapshots` | PROPOSED; must be separately backed up |
| JVM heap | Start with `-Xms128m -Xmx512m` and bounded metaspace | OWNER APPROVAL; workload rehearsal required |
| systemd memory | Start with `MemoryHigh=640M`, `MemoryMax=768M` | OWNER APPROVAL; not tested on host |
| CPU | Start with a maximum of 2 CPU cores worth of quota | OWNER APPROVAL; no production load test |
| Restart | `Restart=on-failure`, bounded restart delay, alert on repeated failure | PROPOSED |
| Filesystem protections | `NoNewPrivileges`, `PrivateTmp`, restricted write paths | PROPOSED; unit review required |

The JVM limits are intentionally aligned with the modest host capacity and existing GT2.0 sizing, but they are not verified capacity limits. The final choice must account for the existing unbounded `gapps-postgres` container and the aggregate host workload.

### 5.2 Backend acceptance gates

Before enabling any private route, the deployment rehearsal must prove:

1. The process listens only on `127.0.0.1:8092`.
2. The service account cannot write outside approved release and snapshot paths.
3. `/actuator/health` reports healthy without exposing database details.
4. Repeated restart and out-of-memory/failure behavior produces an alert and does not restart unrelated services.
5. Snapshot upload limits, request timeouts, and connection pool limits are explicit.
6. A route check proves `/api/` reaches GDB and not GDC.

## 6. Database isolation and migration decision

### 6.1 Final naming and storage recommendation

Use the following dedicated names:

- Container: `gdb-postgres`.
- Docker network: `gdb-private`.
- Named volume: `gdb-postgres-data`.
- Database: `gdb`.
- Runtime role: `gdb_app`.
- Migration role: `gdb_migrator`.
- Optional backup role: a separately controlled, non-application role if required by the selected backup method.

Do not reuse `gapps-postgres`, `postgres_postgres_data`, an existing GApps database, or any existing application role. If the systemd backend needs a host connection, publish only `127.0.0.1:5433` after a final port check. A Docker-private connection is preferred when both components run in Docker.

### 6.2 Privilege boundaries

The intended privilege boundary is:

| Role | Required authority | Must not have |
|---|---|---|
| `gdb_app` | `CONNECT` to `gdb`, required schema usage, and CRUD on GDB runtime objects | Superuser, `CREATEDB`, `CREATEROLE`, replication, access to GApps databases, schema DDL |
| `gdb_migrator` | Controlled DDL and Flyway history updates in `gdb_core` during an approved migration | Superuser, host access, access to unrelated databases, long-running application use |
| Backup role, if needed | Only the backup capabilities required by the selected method | Application login and write access to GDB data |

**Important implementation finding:** the current Spring configuration exposes one datasource username/password and enables Flyway through the same application configuration. Separate runtime and migration roles are therefore not verified by the current deployment template.

The recommended V1 operating procedure is a one-time, controlled migration step using `gdb_migrator`, followed by the long-running service using `gdb_app` with Flyway disabled or restricted after the migration has been validated. This requires a disposable deployment rehearsal and an explicit configuration review; it must not be improvised on production. An alternative is a reviewed application configuration change supporting separate Flyway credentials.

This is a **BLOCKER** until the chosen approach is tested and the resulting grants are verified against the dedicated database.

### 6.3 Migration acceptance gates

Before production traffic:

1. Prove the connection points to the new `gdb` database and named volume.
2. Run Flyway against a clean database and record `gdb_core` version 1 and checksum.
3. Verify the runtime role cannot create or alter schema objects.
4. Start the service with the runtime role and confirm JPA validation and all smoke tests pass.
5. Verify no query or credential can reach the existing GApps database.
6. Back up the migrated database before enabling any device or admin traffic.

## 7. Admin portal routing decision

### 7.1 Recommended exact strategy

Use a dedicated private hostname in the form:

```text
https://gdb-admin.<approved-tailnet-or-private-domain>/
```

The exact hostname is intentionally not invented here. Its owner must approve and verify it. The endpoint must terminate or forward private HTTPS to a dedicated local Nginx GDB gateway on `127.0.0.1:8093`. Nginx serves the Angular browser output and proxies only GDB `/api/` and the restricted health path to `127.0.0.1:8092`.

This strategy explicitly does not use:

- the existing GDC global `/api/` route on Nginx port 80;
- the existing Tailscale root `https://rwp-server.tail203f6d.ts.net`, which currently targets GT2.0;
- a public DNS name or public API listener;
- direct PostgreSQL access.

### 7.2 Route decision status

The dedicated Tailscale hostname/endpoint was not verified during Phase 3B and cannot be assumed to exist. The current Nginx configuration also did not show an active 443 listener. Therefore the exact hostname, Tailscale Serve arrangement, certificate/renewal ownership, ACL, and Nginx gateway configuration are **OWNER APPROVAL and BLOCKER** items.

If Tailscale cannot provide a separate endpoint without changing the GT2.0 root, stop. A dedicated Nginx private HTTPS virtual host on an independently approved address is a fallback design, not an automatic change to the current Nginx or Tailscale configuration.

## 8. Remote tablet connectivity

### 8.1 Administrator access versus device access

Administrator access is intended for a small operator group and can remain behind the approved private Tailscale/private HTTPS route. Customer tablets are different clients:

- They may be outside the RWP local network.
- They may not be enrolled in the GDB operator's tailnet.
- They require device authentication, tenant/application scope, revocation, retry handling, and synchronization availability.
- They must never receive PostgreSQL, Docker, Nginx administration, or Tailscale operator access.

Tailscale membership must not be assumed for every customer tablet.

### 8.2 Connectivity options

| Option | Security posture | Suitability |
|---|---|---|
| Managed Tailscale on approved tablets | Strong private transport; ACLs can separate sync devices from administrators | **Recommended for a controlled pilot only**, if customer devices can be managed and supported |
| Customer/partner VPN or private APN to a dedicated sync route | Keeps traffic private without granting operator access | Viable, but network ownership and support are unresolved |
| Dedicated public sync ingress with TLS, rate limiting, device credentials, revocation, monitoring, and abuse controls | Reaches arbitrary tablets | **Not ready for V1**; the current evidence does not establish a production-hardened public ingress |
| Reuse the administrator Tailscale route as universal device access | Simple appearance | Rejected; mixes operator and device trust boundaries and assumes tailnet membership |

### 8.3 Required decision

For the next controlled two-device test, use only tablets with an explicitly approved private connectivity method, preferably managed Tailscale identities or a private VPN. For real customer tablets outside the tailnet, the owner must select and approve a separate device transport before deployment.

Until that decision is made and tested, customer-tablet synchronization is a **hard Phase 3C blocker**. GDB must not be made publicly reachable merely to close this gap.

## 9. Backup and recovery decision

### 9.1 Proposed baseline policy for approval

| Item | Proposed baseline | Status |
|---|---|---|
| PostgreSQL backup | Encrypted daily full/base backup, plus pre-release backup | OWNER APPROVAL |
| WAL/continuous recovery | Archive at approximately 15-minute recovery points when supported | OWNER APPROVAL; not configured or tested |
| Snapshot backup | Copy encrypted snapshots on upload or by a short scheduled interval, plus nightly reconciliation | OWNER APPROVAL |
| Database retention | 35 daily and 12 monthly copies | OWNER APPROVAL; storage sizing required |
| Snapshot retention | 14 daily and 8 weekly copies, subject to tenant policy | OWNER APPROVAL; storage sizing required |
| Off-server location | Separate provider/account or approved backup host, not `rwp-server` | **BLOCKER; destination not selected** |
| Encryption | Encrypt database dumps and backup transport; keep keys outside the application host | **BLOCKER; key owner not selected** |
| Checksums | SHA-256 manifests for dumps, snapshots, and release artifacts | PROPOSED; verification job not installed |
| Restore verification | Monthly full restore rehearsal to a disposable isolated target; checksum checks on every backup | **BLOCKER; no rehearsal executed** |
| Initial target | RPO 15 minutes with WAL; RTO 4 hours after rehearsal | OWNER APPROVAL; not demonstrated |

GDB snapshot payloads are encrypted by the application with AES-256-GCM and checksum-validated. That does not by itself provide database backup, off-server retention, key recovery, or disaster recovery.

### 9.2 Storage thresholds

The following are proposed starting thresholds and require owner approval after workload sizing:

- Dedicated GDB data/backup allocation warning at 70% and critical at 85%.
- Host root free space warning below 30 GiB and critical below 20 GiB.
- Alert on any snapshot/database backup that exceeds its per-tenant or operational quota.
- Stop new snapshot ingestion before filesystem exhaustion; preserve existing data and alert operators.

The current 83 GiB host free-space observation is not a GDB allocation and must not be treated as available backup capacity.

### 9.3 Recovery gates

No deployment GO is possible until an isolated restore proves:

1. The backup can be decrypted with an independently controlled key.
2. PostgreSQL restores to the expected Flyway version and selected recovery point.
3. Snapshot files pass checksum and decryption verification.
4. Tenant/application/device scope remains enforced after restore.
5. The service can start without touching GDC, GDCLab, GT2.0, or the existing GApps database.
6. The measured recovery time is consistent with the accepted RTO.

## 10. Security readiness

### 10.1 Verified application controls

Source and test evidence verifies that:

- Administrator API routes require the configured bootstrap token and receive the `ADMIN` role.
- Device registration is separate from administrator routes.
- Device bearer credentials are hashed at rest and are rotated/revoked through the device lifecycle.
- Device requests derive application, tenant, and device scope from the authenticated credential.
- Cross-tenant and forged-scope access is rejected by the integration suite.
- Snapshot payloads are checksum-validated and encrypted at rest with tenant/snapshot associated data.
- Secrets are environment-provided and are not embedded in the frontend bundle.

### 10.2 Unresolved security limitations

- V1 administration uses one environment-provided bootstrap token. It does not provide operator accounts, MFA, SSO, or finer-grained human roles.
- Tailscale/private network membership is not a substitute for application authorization.
- Database runtime/migration privilege separation has not been applied or verified.
- Secret-manager availability, rotation ownership, and recovery-key ownership on `rwp-server` were not inspected.
- Public or non-tailnet tablet ingress has not been security-reviewed.

The bootstrap-token model is acceptable only for a tightly controlled private V1 operator group after rotation and protected storage. Broader administration requires an owner-approved operator-identity phase.

## 11. Deployment safety and independence

The deployment can be isolated from existing applications only if all of the following are enforced:

- GDB uses its own account, paths, systemd unit, Docker project, network, volume, database, and roles.
- GDB uses 8092/8093/5433 only after final conflict checks.
- Existing Nginx GDC `/api/` routing is not edited to host GDB.
- Existing Tailscale GT2.0 root is not changed or replaced.
- `gapps-postgres` and its volume are not reused, migrated, restarted, or queried.
- The installation and migration runbook has explicit GDB-only commands and a second-person review.
- Rollback stops or withdraws only GDB components.

The architecture makes this independence possible, but it has not been executed. A successful local test cannot verify that a production operator will select the correct container, volume, route, or service.

## 12. Operational readiness definition

### 12.1 Health and monitoring

Before accepting traffic, implement and verify:

- Loopback health check for backend and database readiness.
- Alerts for service failure, repeated restart, database health, connection exhaustion, and disk thresholds.
- Alerts for backup age, checksum failure, off-server copy failure, and restore-test failure.
- Nginx/Tailscale endpoint reachability checks that distinguish GDB from GT2.0.
- Audit visibility for administrator actions, enrollment, rotation, revocation, authorization failures, and synchronization conflicts.
- Journald or dedicated log rotation with no credentials, event payloads, or snapshot bytes in logs.

### 12.2 Acceptance tests

The controlled deployment acceptance run must include:

1. Route identity: admin URL serves GDB Angular assets and `/api/` reaches GDB only.
2. Health: backend and database are healthy under the approved service account.
3. Admin: token rotation, authentication, authorization, and audit entry.
4. Tenant/device: application and tenant creation, one-use enrollment, authentication, rotation, revocation.
5. Synchronization: push, pull, stable cursor, duplicate retry, stale conflict, tombstone, and recovery retry.
6. Snapshot: encrypted upload, checksum rejection, tenant isolation, download verification, retention.
7. Security: unauthorized cross-tenant and cross-application requests fail.
8. Resource: service limits, disk thresholds, and restart alert behave as designed.
9. Backup: backup completion, checksum, off-server copy, and isolated restore.
10. Device transport: two approved tablets complete a push/pull cycle using the selected private connectivity method.

The final item cannot be satisfied by the current reusable TypeScript client simulation alone; it requires an integrating GApp SQLite/SQLCipher adapter and an approved tablet network path.

## 13. Proposed implementation sequence after GO

This is a future sequence only and must not be executed under this report.

### Gate 0 — owner decisions

Approve the private hostname/route, tablet transport, ports, service account, PostgreSQL image and roles, resource limits, secrets, backup target, RPO/RTO, operator access model, and maintenance window.

### Gate 1 — isolated rehearsal

Build exact release artifacts and checksums. In a disposable environment, apply the chosen Flyway/runtime-role procedure, exercise the Nginx/Tailscale route, test the selected tablet transport, and perform backup/restore.

### Gate 2 — server preflight

Under separately authorized read-only or change-controlled access, recheck listeners, disk, memory, Docker state, Tailscale ownership, Nginx configuration, and existing service health. Abort if any GDC/GDCLab/GT2.0 resource would be reused or affected.

### Gate 3 — GDB-only installation

Create only GDB paths/account/container/volume/roles, stage immutable artifacts, run the approved migration step, install the restricted systemd unit, and configure only the dedicated private route.

### Gate 4 — smoke and acceptance

Start only GDB components, verify all acceptance tests, perform backup and restore evidence, and confirm existing application health remains unchanged.

### Gate 5 — controlled two-device test

Use two approved tablets and the selected private transport. Verify offline behavior, retry, cursor persistence, conflict handling, restart recovery, and snapshot workflow. Stop before customer rollout if the device adapter or network path is incomplete.

## 14. Rollback and recovery checkpoints

### Immediate rollback triggers

Withdraw GDB traffic and stop the GDB-only rollout if any of these occurs:

- The private route reaches GDC, GT2.0, or an unexpected backend.
- Any GDB listener binds outside the approved private/loopback interface.
- Flyway targets the wrong database, schema, volume, or role.
- A migration fails, checksum changes unexpectedly, or runtime validation fails.
- Tenant/application isolation or device revocation fails.
- Backup, encryption-key, or restore verification is unavailable.
- Memory, disk, restart, or database health thresholds are exceeded.
- Any existing GDC, GDCLab, GT2.0, or GApps PostgreSQL service shows an unexplained impact.

### Recovery sequence

1. Remove only GDB traffic from the private route.
2. Preserve GDB logs, audit records, release checksums, and Flyway state.
3. Stop only `gdb-backend.service` and, if required, the `gdb-postgres` component.
4. Restore the previous GDB artifact or isolated GDB backup according to the reviewed runbook.
5. Prefer a forward fix for schema changes; never execute an unreviewed destructive SQL rollback.
6. Verify GDB health and isolation before reopening the route.
7. Confirm the existing services and databases were not modified or restarted.

No rollback or restore action has been executed in this phase.

## 15. Explicit owner approvals and blockers

| Decision or evidence | Status | Effect on Phase 3C |
|---|---|---|
| Local backend/PostgreSQL test suite | **VERIFIED PASS** | Satisfied locally. |
| Angular and TypeScript builds | **VERIFIED PASS** | Satisfied locally. |
| Candidate backend port 8092 | Observed free once, not reserved | Recheck required; blocker until confirmed. |
| Dedicated private admin hostname/route | Not verified | **BLOCKER** |
| Customer-tablet transport outside tailnet | Not selected or tested | **HARD BLOCKER** |
| Dedicated PostgreSQL container/volume | Proposed only | **BLOCKER** |
| Separate migration/runtime roles | Not supported by the current single-datasource template without an approved procedure/configuration | **BLOCKER** |
| Resource limits and workload sizing | Not tested | **BLOCKER** |
| Off-server backups and encryption-key ownership | Not selected | **HARD BLOCKER** |
| Restore rehearsal and measured RTO | Not executed | **HARD BLOCKER** |
| V1 bootstrap-token operator model | Verified implementation, limited security posture | Owner acceptance required; blocker for broader admin exposure. |
| Physical two-device GApp acceptance | Not executed | Blocker for customer rollout; not necessarily for isolated infrastructure rehearsal. |
| Separate deployment authorization | Not granted | **HARD BLOCKER** |

## 16. Final recommendation

**NO-GO for Phase 3C and production deployment at this time.**

The local GDB implementation has passed its documented verification scope, and the proposed isolated topology is technically coherent. However, an evidence-based GO cannot be issued while customer-tablet connectivity, private endpoint ownership, database privilege separation, off-server backup/recovery, production resource limits, and explicit deployment authorization remain unresolved.

The next authorized step should be a decision gate that records the owner-approved values and then performs a disposable deployment rehearsal. Only after those decisions, the restore rehearsal, and the controlled two-device test are successful should Phase 3C be reconsidered.

This report stops here. It does not authorize SSH, server changes, deployment, provisioning, migrations, service restarts, or Phase 3C.

## 17. Owner direction update — Gate 3 deferred

After review of the Gate 3 preparation work, the owner discontinued the
MacBook-based off-server backup and independent restore workflow. Gate 3 is
therefore **DEFERRED**, not passed. No Gate 3 backup, restore, encryption-key
generation, package installation, or server operation was performed.

Gate 1 staging and Gate 2 synthetic functional acceptance remain preserved.
The next development/testing priority is physical Android tablet
synchronization through an approved private connectivity path. Production
deployment remains unauthorized, and `rwp-server` remains a single point of
failure until an independent recovery design is separately approved and
executed.
