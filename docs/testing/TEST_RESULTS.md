# GDB V1.0 local integration validation and build results

Date: 2026-11-01 (Asia/Kolkata)

## Final status

**CONDITIONALLY READY.** Phase 3A PostgreSQL integration verification now passes completely. The backend, Angular administration portal, reusable TypeScript client, deployment templates, and documentation are locally verified. RWP infrastructure inspection is complete, but production deployment, physical devices, and GApp-specific SQLite adapters remain outside this phase.

No GT2.0 source, existing GDC/GDCLab/GT2.0 service, or production database was modified or contacted. `rwp-server` was inspected read-only in Phase 3B; no server mutation was performed.

## Phase 3A result — 2026-11-01

**PASS — all mandatory PostgreSQL integration tests executed successfully.**

The existing Testcontainers configuration was inspected and executed without modification:

- Testcontainers version: **2.0.5**.
- PostgreSQL image configured: `postgres:18-alpine`, overrideable with `GDB_TEST_POSTGRES_IMAGE`.
- Disposable container lifecycle: `@Container PostgreSQLContainer`, with `@Testcontainers(disabledWithoutDocker = true)`.
- Runtime installed for this task: Colima **0.10.3** with Lima **2.2.1**; Docker CLI **29.9.2**; Docker daemon **29.5.2**.
- Colima was started as a disposable Docker VM with 2 CPUs and 3 GiB RAM. Colima enforced its 20 GiB minimum disk size despite the requested 8 GiB. No shell profile was changed and the VM was stopped after testing.
- Testcontainers used `DOCKER_HOST=unix:///Users/gutta/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` as command-scoped variables.
- Ephemeral probe: `postgres:18-alpine` started and accepted `pg_isready`; the probe container was removed by its exact test name.
- Final `mvn -Dmaven.repo.local=/private/tmp/gt2-m2 test`: **7 tests discovered, 7 passed, 0 failed, 0 skipped**.
- `GdbPostgresIntegrationTest`: **4 tests passed, 0 failed, 0 skipped**.
- Flyway: **PASS**; PostgreSQL **18.6** reported by the disposable container, and migration `gdb_core` version 1 was applied successfully.
- Testcontainers cleanup removed the PostgreSQL container; the generated Ryuk container was removed by its exact generated name after verification, leaving no test containers.

The integration test guard was not bypassed or disabled. No production database or unrelated container was used.

## A. Environment preflight

Commands executed:

```text
java -version                         -> Java 21.0.12.1
mvn -version                          -> Maven 3.9.16
node --version                        -> v26.3.0
npm --version                         -> 11.16.0
docker version / docker info          -> Docker CLI/Colima installed for this task; daemon stopped after verification
podman --version                      -> unavailable; not required
psql --version                        -> unavailable on host; PostgreSQL exercised in disposable container
ng version / tsc --version            -> not globally installed; project-local tools used
```

Colima and Docker CLI were installed through Homebrew with explicit authorization. No Docker Desktop service, persistent Homebrew service, shell profile, or unrelated project was changed. Project-local npm dependencies were installed without lifecycle scripts and lockfiles are now present.

## B. Backend and Flyway

Commands executed:

```bash
cd backend
DOCKER_HOST=unix:///Users/gutta/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mvn -Dmaven.repo.local=/private/tmp/gt2-m2 test
```

Results:

- Maven test suite: **7 tests discovered, 7 passed, 0 skipped, 0 failed**.
- Unit/protocol tests: 3 passed.
- `GdbPostgresIntegrationTest`: 4 scenarios executed against disposable PostgreSQL 18.6 and passed.
- The PostgreSQL integration class covers Flyway version 1, registration/enrollment, authentication and credential lifecycle, push/pull, cursors, duplicate idempotency, stale-base conflicts, tombstones, application/tenant isolation, encrypted snapshots, checksum rejection, and tenant ownership.
- Flyway migration: **PASS**, `gdb_core` version 1 applied to a clean disposable database.
- An intermediate `clean package` attempt while Colima was unavailable reported 4 skipped integration tests; it was not used as acceptance evidence. The authoritative `mvn test` run above completed with zero skips.
- Minimal GDB fixes made: explicit `Timestamp` binding for direct `JdbcTemplate` timestamp writes, `ResultSet.getTimestamp(...).toInstant()` conversion for direct JDBC timestamp reads, and removal of one extra placeholder in the `sync_event_log` INSERT. These fixes are limited to synchronization JDBC compatibility.

## C. Angular administration portal

Commands executed:

```bash
cd frontend
npm install --ignore-scripts
npm install --save-dev vitest@^5.0.0 jsdom@^30.0.0 --ignore-scripts
npm ci --ignore-scripts
npm test
npm run build
```

Results:

- Dependency install: **PASS**, 0 reported vulnerabilities; `frontend/package-lock.json` generated.
- Tests: **1 test file, 2 tests passed, 0 failed**.
- Production build: **PASS** with Angular 22.2.5; initial bundle 245.68 kB raw / 66.89 kB estimated transfer.
- Output: `frontend/dist/gdb-admin-portal/browser`.
- Verified strict TypeScript compilation, API URL/token configuration, dashboard and administration screen compilation, routing source, and production output.
- Angular 22's LMDB cache aborts under this workstation's Node 26 runtime; project scripts force `NG_BUILD_CACHE_STORE=sqlite`, after which build and tests pass. No framework upgrade was made.
- The portal now defaults to same-origin `/api` requests; `frontend/proxy.conf.json` supports local development, and the private Nginx template serves Angular static files while proxying only API/health paths.

## D. Reusable TypeScript synchronization client

Commands executed:

```bash
cd client/typescript
npm install --ignore-scripts
npm ci --ignore-scripts
npm test
npm run build
```

Results:

- Dependency install: **PASS**, 0 reported vulnerabilities; `client/typescript/package-lock.json` generated.
- Tests: **2 test files, 5 tests passed, 0 failed**.
- TypeScript declaration build: **PASS**.
- Public API covers enrollment, authenticated push/pull, cursor persistence contract, acknowledgement/duplicate handling, conflict preservation, and snapshot upload.
- The simulated multi-device test uses two independent local stores/cursors against a deterministic server protocol model: convergent update path passes, and stale concurrent update remains available for domain resolution.
- Automatic domain-specific SQLite/SQLCipher operations remain the integrating GApp's responsibility; no business-specific adapter was invented in GDB.

## E. Multi-device and failure evidence

Executed automated evidence:

- Independent device A/B stores and cursors: **PASS in reusable-client simulation**.
- Server-ordered pull and convergence: **PASS in reusable-client simulation**.
- Stale-base concurrent update preserved: **PASS in reusable-client simulation and PostgreSQL integration**.
- Duplicate event acknowledgement/idempotency: **PASS in client protocol test and PostgreSQL integration**.
- Tombstone propagation: **PASS in PostgreSQL integration**.
- Snapshot checksum/encryption/tenant ownership: **PASS in PostgreSQL integration**.
- Tenant/application isolation and forged cross-tenant request rejection: **PASS in PostgreSQL integration**.
- Database persistence/recovery: batch/event idempotency and retry-safe failure response paths are covered; an explicit process-restart/restore drill remains unimplemented.
- Physical two-device GApp acceptance, offline continuity, restart recovery, and real SQLite/SQLCipher transaction behavior: **not executed**.

The client intentionally leaves a non-acknowledged event in the integrating store's failure path. A caller can retry the same event/batch safely because the backend contract is idempotent; automatic scheduling/backoff belongs to the GApp adapter.

## F. Repository and safety validation

Commands executed:

```bash
./scripts/validate-repository.sh
git diff --check
```

Both passed. No secrets were added. Deployment artifacts are examples only; no systemd unit, Nginx configuration, firewall, Tailscale policy, database, or service was changed.

## G. RWP server readiness

Read-only `rwp-server` preflight was completed separately and documented in [RWP_PHASE3B_PREFLIGHT.md](../deployment/RWP_PHASE3B_PREFLIGHT.md). No deployment or production mutation was performed.

## H. Files added or changed for this validation phase

- `frontend/src/app/api.service.ts`, `frontend/src/app/api.service.spec.ts`, `frontend/angular.json`, `frontend/proxy.conf.json`
- `client/typescript/src/multi-device.integration.test.ts`
- `deployment/nginx/gdb.private.conf.example`
- `deployment/systemd/gdb-healthcheck.example.sh`, `deployment/systemd/gdb.logrotate.example`
- `docs/architecture/GDB_ADMIN_HOSTING.md`, `docs/architecture/MULTI_DEVICE_SYNC.md`
- `docs/security/ACCESS_MODEL.md`
- `docs/deployment/RWP_SERVER_PREFLIGHT.md`, `docs/deployment/DEPLOYMENT_PLAN.md`, `docs/deployment/ROLLBACK_PLAN.md`
- `docs/operations/DEPLOYMENT_GUIDE.md`, `docs/testing/TEST_PLAN.md`, `docs/testing/TEST_RESULTS.md`, `README.md`
- `backend/src/main/java/com/gutta/gdb/synchronization/SyncBatchStore.java`, `SyncService.java`, `SyncEventProcessor.java`, `SyncAdminController.java`

The earlier implementation also includes the backend, Flyway migration, integration suite, admin portal screens, client package, and existing architecture/security documentation listed in the repository layout.

## Phase 3B.3 local remediation addendum — 2026-11-01

The following results supersede the earlier 7-test backend count for the current source tree. Two additional security/isolation tests were added during blocker remediation:

- Backend command: "DOCKER_HOST=unix:///Users/gutta/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -Dmaven.repo.local=/private/tmp/gt2-m2 package"
- Backend result: **9 tests, 9 passed, 0 failed, 0 errors, 0 skipped; BUILD SUCCESS**.
- Flyway result: **PASS**, clean versions 1 through 3 on disposable PostgreSQL 18.6.
- Added administrator session test: bootstrap, login, logout, revoke-all, invalid credentials, and second-bootstrap rejection.
- Added PostgreSQL role-isolation test: Flyway as gdb_migrator, runtime application as gdb_app, required application operation succeeds, unauthorized core-schema DDL fails.
- Angular result: **2 tests passed; production build passed**.
- TypeScript client result: **5 tests passed; declaration build passed**.
- Backup/restore harness: **PASS**, disposable postgres:18-alpine, encrypted dump and snapshot, SHA-256 verification, restore into a second database, and 2 business rows verified. The destination was another path on the same Mac and is not evidence of off-server disaster recovery.
- Repository validation and git diff --check: **PASS**.

The physical Android/tablet test, live Tailscale/Nginx route test, off-server backup copy, production key custody, and stress/capacity rehearsal remain unexecuted.

## I. Future approved deployment sequence

Use [DEPLOYMENT_PLAN.md](../deployment/DEPLOYMENT_PLAN.md) and [ROLLBACK_PLAN.md](../deployment/ROLLBACK_PLAN.md). The abbreviated sequence is: read-only inventory → artifact review → disposable PostgreSQL rehearsal and backup/restore test → private HTTPS/ACL approval → versioned install outside the live path → start only GDB → health/API smoke tests → approved two-device acceptance → handoff. Stop before any mutation when resource, isolation, backup, or access assumptions are unverified.

## J. Gate 1 continuation preparation — 2026-11-02

A deployment defect was found during local review before any `rwp-server`
mutation: V3 previously granted managed-schema privileges only to `gdb_app`,
while the authorized staging deployment uses `gdb_staging_app`. V3 now grants
the required core and managed-schema privileges and provisioning-function
execution to the approved generic and staging runtime role names. The role
isolation test now uses `gdb_staging_migrator` and `gdb_staging_app` and no
longer supplies the core-schema grants outside Flyway.

Commands executed after this scoped change:

```bash
cd backend
mvn -Dmaven.repo.local=/private/tmp/gt2-m2 test
mvn -Dmaven.repo.local=/private/tmp/gt2-m2 -DskipTests package
cd ../frontend
npm test
npm run build
cd ../client/typescript
npm test
npm run build
cd ..
git diff --check
```

Results:

- Backend test run: **9 tests, 0 failures, 0 errors, 6 skipped**. The six
  PostgreSQL/Testcontainers tests were skipped because this Mac session had no
  Docker-compatible runtime (`/var/run/docker.sock` absent). They are not
  counted as passed.
- Backend package: **BUILD SUCCESS** with tests skipped by the explicit
  `-DskipTests` command; this is artifact compilation evidence, not integration
  evidence.
- Angular: **2 tests passed; production build passed**.
- TypeScript client: **5 tests passed; declaration build passed**.
- `git diff --check`: **PASS**.

The corrected Flyway migration and staging deployment script have not yet been
executed against the authorized server staging database. The owner-run
continuation is [gdb-staging-gate1.sh](../../deployment/gdb-staging-gate1.sh);
server Batch 4–7 output is required before recording Gate 1 as passed.
