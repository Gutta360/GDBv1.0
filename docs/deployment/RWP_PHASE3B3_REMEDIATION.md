# GDB v1.0 — Phase 3B.3 Production Blocker Remediation

Review date: 2026-11-01 (Asia/Kolkata)  
Scope: GDB repository and disposable local validation resources only  
Final assessment: **CONDITIONAL PASS for remediation; NO-GO for Phase 3C or production deployment**

## 1. Decision summary

The Phase 3B.2 blockers were converted into GDB-local controls, disposable tests, and owner decision records. The current implementation has evidence for:

- PostgreSQL 18.6 migrations and application behavior using disposable Testcontainers.
- Separate migration/runtime PostgreSQL roles, including a negative DDL privilege test.
- Administrator bootstrap hardening, password login, expiring sessions, logout/revocation, RBAC, and audit events.
- A nonproduction encrypted backup/restore rehearsal with checksum verification.
- A candidate private routing design that separates administration from synchronization and preserves the existing GT2.0 Tailscale root by using separate Tailscale Services.
- Backend, Angular, TypeScript, repository, and packaging validation.

This does not establish production readiness. No RWP server, live Nginx/Tailscale policy, production database, external backup destination, or physical tablet was accessed or changed. Customer-tablet transport, production route ownership, off-server key custody, measured capacity, and owner approvals remain open. Phase 3C is not authorized by this report.

## 2. Original blocker remediation matrix

Status labels are evidence-based: VERIFIED means locally executed or observed in this phase; PROPOSED means a design not installed; OWNER DECISION means deployment cannot proceed until the value is supplied and approved.

| Original blocker | Recommended solution | Implementation status | Actual validation evidence | Remaining risk | Required owner decision |
|---|---|---|---|---|---|
| Customer tablets outside the RWP tailnet had no approved transport. | Use a dedicated sync-only private endpoint for managed pilot tablets. Keep offline-first queues and GDB device credentials independent from network membership. | PROPOSED and documented; no physical tablet test. | deployment/tailscale/gdb-services.example.md defines svc:gdb-sync to loopback 8094; backend/client tests prove device auth, retry-safe push/pull, cursors, conflicts, tombstones, and snapshot access. | No Android provisioning, external-Wi-Fi reachability, real GApp queue persistence, or two-device test. | Approve managed-device scope, transport, ACL/grant policy, support owner, and test window. |
| Private admin route was not verified and could conflict with GDC /api/ or the GT2.0 Tailscale root. | Use separate Tailscale Services: svc:gdb-admin to Nginx 8093 and svc:gdb-sync to Nginx 8094. Leave existing routes unchanged. | Candidate design implemented as review-only templates; not live. | Two-service topology is documented and official Tailscale Services/Serve capabilities were reviewed. No live route or policy changed. | Exact service names, ACLs, certificates, Tailscale plan/approval, and Nginx behavior remain unverified. | Approve service names, endpoint ownership, ACLs, TLS handling, and route rehearsal. |
| Production runtime and migration roles were not separated. | Use database gdb, runtime role gdb_app, migration role gdb_migrator, dedicated schema/volume/network, and migration-only Flyway. Provision application schemas through a migration-owned SECURITY DEFINER function. | Implemented and locally verified. | V2 and V3 migrate successfully. The role-isolation test runs Flyway as gdb_migrator, starts the application as gdb_app, proves application creation works, and proves runtime CREATE TABLE and ALTER TABLE against gdb_core fail. | Production grants, ownership, secret delivery, and deployment procedure are not rehearsed on the target host. | Approve role secrets, database names, image digest, migration procedure, and grant review. |
| Backups had no selected off-server destination or restore evidence. | Encrypt database and snapshot artifacts, checksum every artifact, copy to a physically separate destination, keep keys outside the host, and perform isolated restore verification. | Nonproduction rehearsal verified; production DR remains open. | The backup harness ran against disposable postgres:18-alpine, encrypted a dump and snapshot, verified SHA-256 checksums, restored into a second database, and verified 2 business rows. Destination was /private/tmp on the same Mac. | AES-256-CBC is only a portable local harness; no off-server copy, key recovery, retention run, or measured RTO. | Select destination, production encryption/key custody, retention, RPO/RTO, and restore owner. |
| Administration used a bootstrap-token-only model. | Retain bootstrap only for one-time provisioning/emergency recovery. Add administrator identities, BCrypt passwords, hashed expiring sessions, logout/revoke-all, ADMIN/AUDITOR roles, audit records, and disable legacy token access after an active admin exists. | Implemented and locally verified. | Integration tests exercise bootstrap, session cookie, login, logout, session revocation, invalid credentials, and second-bootstrap rejection. Angular uses credential login and withCredentials; it does not persist the legacy token. | MFA/SSO, account recovery, rate limiting/lockout, production secret-manager custody, and browser acceptance over HTTPS are not verified. | Accept the V1 admin model or require MFA/SSO before customer-data use. Approve bootstrap custody, rotation, recovery, and roles. |
| Resource limits and capacity were not measured. | Start with bounded JVM/systemd resources, explicit Hikari limits, snapshot/log quotas, disk thresholds, and monitoring. Rehearse before customer traffic. | Initial limits implemented as templates; capacity not proven. | JVM/systemd bounds and Hikari max 10/min 2 with a 5-second acquisition timeout are configured. Phase 3B host baseline was 12 logical CPUs, 7.2 GiB RAM, and 83 GiB free; no stress test. | Aggregate contention, peak tablet concurrency, snapshot growth, and RTO under load are unknown. | Approve CPU/RAM/pool/storage limits and thresholds after a disposable workload rehearsal. |
| Deployment safety and operational acceptance were not demonstrated. | Keep GDB in its own service/account/ports/volume/network/routes, stage immutable releases, test health and rollback gates, and stop at a GDB-only change boundary. | Templates and runbook updated; target-host rehearsal not performed. | Backend package, repository validation, and local functional tests pass. No live service, Nginx, Tailscale, PostgreSQL, or rollback action changed. | Operator selection errors could still target an existing service or volume; no second-person production review. | Approve change window, second-person review, exact paths/ports, rollback authority, and Phase 3C authorization. |

## 3. Customer connectivity design and physical-tablet test plan

### 3.1 V1 pilot topology

The recommended pilot has two independent trust boundaries:

~~~text
Administrator browser
    -> svc:gdb-admin (private HTTPS, administrator grant only)
    -> Nginx 127.0.0.1:8093
    -> GDB admin API / Angular portal

Managed Android tablet
    -> svc:gdb-sync (private HTTPS, pilot-device grant only)
    -> Nginx 127.0.0.1:8094
    -> device register/sync/snapshot routes only
~~~

The synchronization client remains offline-first. It writes events and cursor state to the integrating GApp local store, attempts synchronization only when the approved private connection is available, and leaves unacknowledged work queued for retry. Tailscale membership is only network authorization; it does not replace GDB device credentials, application scope, tenant scope, revocation, or audit logging.

The candidate design uses separate Services rather than the current GT2.0 node root. Official [Tailscale Services documentation](https://tailscale.com/docs/features/tailscale-services) describes distinct service identities/endpoints, and the [Serve CLI documentation](https://tailscale.com/docs/reference/tailscale-cli/serve) documents local-service proxying. These illustrative commands remain review-only:

~~~text
tailscale serve --service=svc:gdb-admin --https=443 127.0.0.1:8093
tailscale serve --service=svc:gdb-sync --https=443 127.0.0.1:8094
~~~

No command above was executed against the live tailnet.

### 3.2 Android lifecycle and policy requirements

Before the pilot owner approves this transport:

1. Enroll only named test tablets using managed Android provisioning or a one-off, pre-approved, tagged auth-key workflow. See [Tailscale Android installation](https://tailscale.com/docs/install/android).
2. Use a device tag such as tag:gdb-sync-device; do not grant customer devices administrator or operator tags.
3. Use a one-off/short-lived provisioning key where possible, store it only in the MDM/provisioning system, and revoke it after enrollment. Review [auth-key behavior](https://tailscale.com/docs/features/access-control/auth-keys).
4. Set an explicit expiry and renewal owner. Review [key expiry](https://tailscale.com/docs/features/access-control/key-expiry).
5. Grant the device tag only to svc:gdb-sync:443. Deny svc:gdb-admin, GDC, GDCLab, GT2.0, PostgreSQL, Docker, and unrelated tailnet destinations. Use policy tests before enrollment; review [grants and ACLs](https://tailscale.com/docs/features/access-control/acls).
6. Test revocation twice: revoke the Tailscale device identity and independently revoke the GDB device credential.
7. Record tablet serial, assigned tenant, Tailscale identity, GDB device ID, owner, expiry, last-seen time, and revocation status in a pilot register. Do not place reusable credentials in the GApp database or support ticket.

Operational limitations are explicit: a tablet needs the Tailscale app/profile and a supported network; captive portals, MDM restrictions, battery/background execution, identity expiry, and vendor network policy can interrupt connectivity; and private transport does not test public-Internet ingress. A tablet that cannot establish the approved private connection must continue offline and must not fall back to an unapproved public endpoint.

### 3.3 Physical test outside the RWP LAN

This is a future owner-authorized acceptance test, not completed in Phase 3B.3:

1. Prepare two managed Android test tablets and two independent GApp local stores assigned to the same approved pilot tenant, plus a separate administrator browser identity.
2. Provision through the approved Android/MDM process. Confirm device tags and GDB enrollment code scope.
3. Put both tablets on a cellular hotspot or independently managed external Wi-Fi with no route to the RWP LAN. Confirm the only intended private path is the approved Tailscale service.
4. Verify negative reachability: tablets cannot reach the admin Service, GDC, GDCLab, GT2.0, PostgreSQL, Docker, or another tenant's sync scope.
5. Enroll both devices. Rotate one GDB credential; verify the old credential fails. Revoke one device; verify only that device loses synchronization.
6. Disable network access. Create events on both tablets, close/reopen the GApp, and verify events remain queued with cursor state intact. Re-enable the approved connection and verify retry, acknowledgement, cursor advancement, and duplicate safety.
7. Create a stale concurrent update from both devices. Verify a conflict is returned and both conflict/winning-event records remain available. Verify a delete produces a tombstone visible to the other device.
8. Upload/download an encrypted snapshot and verify checksum and tenant scope.
9. Expire/revoke the Tailscale identity and separately revoke the GDB credential. Repeat sync and record failure/recovery.
10. Capture enrollment, first-sync, outage, recovery, and restore timestamps without logging payloads or secrets. Stop if any route reaches an unrelated service or tenant.

Pass criteria: offline data is not lost; no device reaches the admin or unrelated services; no cross-tenant data is returned; retries do not duplicate business events; conflicts/tombstones remain recoverable; and recovery works after network and credential restoration.

## 4. PostgreSQL role isolation

The local implementation uses database gdb, migration role gdb_migrator, runtime role gdb_app, core schema gdb_core, and application schemas gdb_app_<app-code>.

V3 creates a migration-owned, SECURITY DEFINER provisioning function with a constrained schema-name pattern. Runtime application registration calls this function instead of issuing unrestricted schema/table DDL. The role-isolation test starts disposable postgres:18-alpine, creates the two roles, applies Flyway V1–V3 as gdb_migrator, starts Spring Boot with Flyway disabled as gdb_app, and verifies migration version 3, successful controlled application creation, successful runtime operations, and failed runtime CREATE TABLE and ALTER TABLE against gdb_core.

This is local evidence only. Production must repeat the grant assertions against a fresh dedicated database with owner-approved secret delivery and an exact image digest. Migration must be a separate controlled step; the long-running application must not receive migration-role credentials.

## 5. Backup and recovery

### 5.1 Validated nonproduction approach

The new scripts/validate-backup-restore.sh starts one disposable PostgreSQL 18 Alpine container, creates two business rows, creates a custom-format dump, encrypts the dump and a snapshot artifact, verifies SHA-256 manifests, decrypts both artifacts, restores into a second database, and verifies both rows.

~~~bash
DOCKER_HOST=unix:///Users/gutta/.colima/default/docker.sock \
  ./scripts/validate-backup-restore.sh /private/tmp/gdb-phase3b3-backup-destination

Backup validation PASS
PostgreSQL image: postgres:18-alpine
Restore rows verified: 2
Destination bytes: 12288
~~~

This proves the local script round trip, not disaster recovery. The destination is another path on the same Mac, not a physically separate/off-server destination. The script uses AES-256-CBC with PBKDF2 only as a portable local harness; production must use owner-approved AEAD/envelope/KMS or equivalent with the key outside the application host. The temporary test key is removed during cleanup.

### 5.2 Proposed production policy requiring approval

- RPO target: 15 minutes if WAL/continuous archiving is selected; otherwise explicitly accept the larger interval.
- RTO target: 4 hours, measured by a complete isolated restore.
- Database: encrypted daily full/base backup, pre-release backup, and selected WAL/archive policy.
- Snapshots: preserve encrypted application snapshots and manifests with nightly reconciliation.
- Retention starting point: 35 daily and 12 monthly database copies; 14 daily and 8 weekly snapshot copies, subject to measured storage.
- Destination: a physically separate host/provider/account, with transport encryption and no shared production filesystem.
- Key custody: separate key owner, documented recovery key, access audit, rotation, and recovery exercise.
- Verification: checksum every artifact; monthly full restore to an isolated target; tenant/application/device scope checks after restore.
- Capacity: warning at 70% of the dedicated allocation and critical at 85%; stop new snapshot ingestion before filesystem exhaustion.

No production backup target, key custody, WAL stream, retention schedule, or measured RPO/RTO is verified by this phase.

## 6. Administrator security remediation

Implemented GDB-local controls:

- admin_users and admin_sessions tables in Flyway V2.
- BCrypt password hashing with cost 12.
- Random session tokens stored only as SHA-256 hashes.
- HttpOnly, SameSite=Strict cookies; Secure defaults to true; default session TTL is 8 hours.
- One-time bootstrap endpoint guarded by the bootstrap token and active-admin count.
- Legacy X-GDB-Admin-Token access is accepted only before the first active admin exists.
- ADMIN and AUDITOR roles with method/path authorization boundaries.
- Login failure, bootstrap, logout, revoke-all, and authorization events recorded through the existing audit log.
- Angular login/restore/logout flow with credential cookies; the legacy token is not persisted in local storage.

The integration test proves the primary lifecycle, including invalid credentials and second-bootstrap rejection. This is a material improvement over bootstrap-token-only administration, but not an MFA/SSO implementation. Before customer-data use, the owner must decide whether the private pilot is acceptable with this V1 model or whether MFA/SSO, rate limiting, account lockout, and external identity management are required. Bootstrap credentials must remain outside Git and frontend artifacts, be rotated after provisioning, and be held by a named operator.

## 7. Initial resource plan

These are starting limits, not capacity guarantees:

| Resource | Initial setting | Evidence/status |
|---|---:|---|
| JVM heap | Xms128m / Xmx512m | Template implemented; no production load test |
| JVM metaspace | 192m maximum | Template implemented; no production load test |
| systemd memory | MemoryHigh=640M, MemoryMax=768M | Template implemented; host workload rehearsal required |
| CPU | CPUQuota=200% | Template implemented; no stress test |
| Hikari pool | max 10, min 2, 5-second acquisition timeout | Implemented; concurrency sizing not measured |
| Backend/admin/sync gateways | loopback 8092/8093/8094 | Proposed, not live |
| Snapshot request limit | 60 MiB admin/sync template | Must match product limits and storage policy |
| Disk alerts | 70% warning / 85% critical | Proposed; monitoring not installed |

Phase 3B host measurements were 12 logical CPUs, 7.2 GiB RAM, 4 GiB swap, and 83 GiB free root space. These are point-in-time observations, not a GDB capacity reservation. A disposable workload rehearsal must measure simultaneous sync, snapshot upload, PostgreSQL connections, backup throughput, log growth, and restore time without using rwp-server.

## 8. Validation evidence

All commands below were executed locally. Testcontainers used Colima's Docker socket and disposable PostgreSQL containers; no production database was used.

| Area | Command/result | Evidence |
|---|---|---|
| Backend tests/package | DOCKER_HOST=... TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -Dmaven.repo.local=/private/tmp/gt2-m2 package | **9 tests, 9 passed, 0 failed, 0 errors, 0 skipped; BUILD SUCCESS.** Five integration tests, one role-isolation test, and three unit/protocol tests. |
| Flyway/PostgreSQL | Same Maven run; postgres:18-alpine reported PostgreSQL 18.6 | **PASS.** Clean V1–V3 migration; role test verifies migration/runtime separation. |
| Angular | cd frontend && npm test && npm run build | **PASS.** One test file, 2 tests; production Angular build completed. |
| TypeScript client | cd client/typescript && npm test && npm run build | **PASS.** Two test files, 5 tests; TypeScript declaration build completed. |
| Backup/restore | DOCKER_HOST=... ./scripts/validate-backup-restore.sh /private/tmp/gdb-phase3b3-backup-destination | **PASS.** Encrypted dump/snapshot, SHA-256 verification, restore, and 2-row integrity check. Same-host limitation remains. |
| Repository validation | ./scripts/validate-repository.sh | **PASS.** Backup harness was adjusted to avoid a false-positive literal secret-pattern match without weakening the scanner. |
| Diff whitespace | git diff --check | **PASS.** |

No tests are currently skipped in the authoritative backend package run. Physical tablet, live Tailscale/Nginx route, off-server backup, process-restart restore, real GApp SQLite/SQLCipher adapter, and stress/capacity tests remain unexecuted rather than being represented as passes.

## 9. Remaining blockers and owner decisions

1. Approve and test exact private endpoints and grants. Candidate: svc:gdb-admin and svc:gdb-sync; no live route changed.
2. Select and test customer-tablet transport outside the RWP LAN. Managed Tailscale devices are the recommended bounded pilot option; public sync ingress remains out of scope.
3. Approve production database role secrets, image digest, storage layout, and migration-only procedure.
4. Select a physically separate backup destination, production encryption/key custody, retention, and restore owner.
5. Approve the V1 administrator model or require MFA/SSO and account controls before customer data.
6. Run a disposable workload and restore rehearsal to measure RPO/RTO, connection limits, storage growth, and thresholds.
7. Complete an integrating GApp/SQLite/SQLCipher two-device test over the selected external network.
8. Obtain explicit Phase 3C deployment authorization and a second-person review of GDB-only paths, ports, containers, volumes, and routes.

## 10. Controlled sequence after separate authorization

This sequence is planning guidance only and was not executed:

1. Record owner decisions and freeze names, ports, image digests, secrets, route grants, backup destination, and RPO/RTO.
2. Repeat role/migration and backup/restore rehearsals in an isolated nonproduction environment.
3. Run the external physical-tablet test with no RWP LAN access and verify negative reachability.
4. Under a separately authorized change window, recheck target-host listeners, resources, existing service health, Tailscale ownership, and Nginx routing.
5. Stage immutable GDB artifacts in dedicated paths; create only GDB-owned resources; run the migration role once.
6. Start only GDB backend/database/gateways; verify health, route identity, admin login, device enrollment, tenant isolation, and backup completion.
7. Perform the approved two-device acceptance test and record evidence.
8. Keep the rollback checkpoint until the acceptance report and first verified backup are complete.

Immediate rollback triggers are any route reaching GDC/GDCLab/GT2.0, any non-loopback backend/database listener, wrong Flyway target, failed tenant/device isolation, failed credential revocation, backup/key/restore unavailability, resource threshold breach, or unexplained impact to an existing service. Rollback must withdraw only GDB routes and stop only GDB-owned components; no destructive schema rollback is authorized by this document.

## 11. Final assessment

**CONDITIONAL PASS — remediation implementation and local validation complete; production deployment remains NO-GO.**

The GDB codebase is better prepared for a controlled pilot: local backend, PostgreSQL role separation, admin security, Angular, TypeScript, backup rehearsal, repository validation, and package verification are passing. Unresolved items are operational and physical: private route/ACL ownership, external tablet transport, production secret/key custody, off-server backup and measured restore, workload sizing, owner acceptance of the V1 admin model, and separate deployment authorization.

This report does not authorize SSH, live Nginx/Tailscale changes, production database work, installation, deployment, or Phase 3C. Work stops after this local report.
