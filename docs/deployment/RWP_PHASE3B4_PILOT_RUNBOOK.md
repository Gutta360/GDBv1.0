# GDB v1.0 — Phase 3B.4 Controlled Pilot Preparation Runbook

Date: 2026-11-01 (Asia/Kolkata)  
Status: **Planning only — no staging deployment authorized or performed**  
Scope: Synthetic-data pilot using one owner-controlled Android tablet

## 1. Purpose and evidence boundary

This runbook prepares a temporary isolated staging pilot on rwp-server. It does not authorize SSH, installation, database creation, service restart, Nginx change, Tailscale change, backup transfer, tablet enrollment, or Phase 3C.

Reviewed documents:

- [Phase 3B.1 final architecture](RWP_PHASE3B1_FINAL_ARCHITECTURE.md)
- [Phase 3B.2 GO/NO-GO review](RWP_PHASE3B2_GO_NO_GO.md)
- [Phase 3B.3 remediation](RWP_PHASE3B3_REMEDIATION.md)
- [Current test results](../testing/TEST_RESULTS.md)

Evidence labels:

- **VERIFIED LOCAL:** executed against disposable local resources or verified in the repository.
- **SERVER OBSERVED:** point-in-time read-only observation from the earlier authorized preflight.
- **PROPOSED:** design or command not executed on rwp-server.
- **OWNER APPROVAL:** must be explicitly approved before execution.
- **PILOT REQUIRED:** can only be verified with staging infrastructure and the physical tablet.

Current decision: **READY FOR OWNER DECISIONS AND A NARROWLY SCOPED STAGING AUTHORIZATION; NOT READY FOR EXECUTION.**

Execution update (2026-11-02): the owner subsequently authorized Gate 1 and
reported Batches 1–3 complete on `rwp-server`. Those batches are treated as
owner-supplied execution state; this document does not claim independent
server verification for them. Batches 4–7 remain pending until their output
is returned and recorded.

## 2. Verified baseline

### 2.1 Local evidence

- Backend package: **9 tests passed, 0 failed, 0 errors, 0 skipped**.
- Flyway: clean versions 1 through 3 on disposable PostgreSQL 18.6.
- Role isolation: migration role succeeds; runtime role performs required operations but cannot create or alter core schema objects.
- Angular: **2 tests passed and production build passed**.
- TypeScript client: **5 tests passed and declaration build passed**.
- Same-host backup harness: encrypted dump/snapshot, checksum, restore, and 2-row integrity verification passed.
- Repository validation and git diff check: passed.
- Local administrator sessions, RBAC, audit, device enrollment/rotation/revocation, tenant isolation, synchronization idempotency, conflicts, tombstones, and snapshot checks have automated evidence.

### 2.2 Not verified

No staging process, database, filesystem, route, certificate, Android enrollment, external mobile synchronization, off-server backup, production key custody, or workload capacity has been verified. Real GApp SQLite/SQLCipher behavior is also unverified.

The earlier read-only server observation found Ubuntu 26.04 x86_64, 12 logical CPUs, 7.2 GiB RAM, 4 GiB swap, 83 GiB free root space, existing GDC/GDCLab/GT2.0 ports 8080/8090/8091, GDC Nginx /api/ on port 80, and a GT2.0 Tailscale root. Recheck all of this read-only before any future execution.

## 3. Proposed isolated staging topology

| Resource | Proposed staging value | Isolation rule |
|---|---|---|
| Backend unit/account | gdb-staging-backend.service / gdb-staging:gdb-staging | Dedicated unit and non-login account |
| Backend | 127.0.0.1:8192 | Not existing ports or production candidate 8092 |
| Admin gateway | 127.0.0.1:8193 | Angular and admin API only |
| Sync gateway | 127.0.0.1:8194 | Registration, sync, snapshot routes only |
| PostgreSQL | gdb-staging-postgres on gdb-staging-private only | Never reuse gapps-postgres or its volume; no host port |
| Database proxy | gdb-staging-postgres-proxy on gdb-staging-private and gdb-staging-proxy-edge | Only proxy publishes the backend loopback port |
| Docker network/volume | gdb-staging-private / gdb-staging-postgres-data | Separate project resources; proxy edge bridge is separate and staging-only |
| Image | postgres:18.6-alpine, then approved digest | Disposable synthetic-data database |
| Database | gdb_staging | Dedicated Flyway history |
| Roles | gdb_staging_migrator / gdb_staging_app | Migration-only versus runtime |
| Filesystem | /opt/gdb-staging | Separate release root; selected after preflight because `/home/gutta` parent permissions are not suitable for the service account |
| Environment | /etc/gdb-staging/backend.env | Root-owned, mode 0600 |
| Snapshots/logs | /var/lib/gdb-staging/snapshots and /var/log/gdb-staging | Separate mutable data and log identity |
| Candidate private routes | svc:gdb-staging-admin / svc:gdb-staging-sync | Administrators versus named tablet only |

~~~text
Admin browser
  -> approved private HTTPS svc:gdb-staging-admin
  -> Nginx 127.0.0.1:8193
  -> GDB backend 127.0.0.1:8192

Owner Android tablet on external mobile internet
  -> approved Tailscale profile and svc:gdb-staging-sync
  -> Nginx 127.0.0.1:8194
  -> device registration/sync/snapshot routes only
  -> GDB backend 127.0.0.1:8192

GDB backend
  -> 127.0.0.1:55433/gdb_staging
  -> gdb-staging-postgres-proxy
  -> gdb-staging-private
  -> gdb-staging-postgres:5432
  -> gdb_staging_app at runtime

Existing GDC, GDCLab, GT2.0, gapps-postgres, shared Nginx /api/,
and the existing GT2.0 Tailscale root remain outside the topology.
~~~

Tailscale Services are a candidate private transport, not public ingress. The tablet must be deliberately enrolled and granted access. An unmanaged device without the approved profile or another separately approved private transport cannot reach this staging endpoint.

Capability references reviewed: [Tailscale Services](https://tailscale.com/docs/features/tailscale-services), [Serve CLI](https://tailscale.com/docs/reference/tailscale-cli/serve), [Android installation](https://tailscale.com/docs/install/android), [auth keys](https://tailscale.com/docs/features/access-control/auth-keys), [key expiry](https://tailscale.com/docs/features/access-control/key-expiry), and [grants/ACLs](https://tailscale.com/docs/features/access-control/acls).

## 4. Owner approvals required before execution

The owner must explicitly approve:

1. A narrowly scoped staging deployment on rwp-server, with named operator and change window.
2. Synthetic data only and teardown after the pilot.
3. Final read-only availability of ports 8192, 8193, 8194, and 55433.
4. The staging account, paths, Docker network, container, volume, database, roles, and image digest.
5. The migration-only Flyway step and runtime Flyway-disabled configuration.
6. Exact private HTTPS service names, certificate owner, renewal method, and administrator/device grants.
7. Tailscale installation and management on the owner tablet; no unmanaged customer device is in scope.
8. Backup destination, encryption, key custodian, offline recovery, retention, restore owner, and RPO/RTO.
9. Acceptance of the V1 administrator model or a requirement for MFA/SSO.
10. Pilot workload limits, monitoring owner, rollback owner, and incident contact.

No approval is implied by this document.

## 5. Future authorized staging commands

Every command below is a future template unless explicitly marked otherwise.
The reviewed fail-fast owner-run continuation is also available at
`deployment/gdb-staging-gate1.sh`; it runs one batch per invocation and does
not continue after a failed or unexpected check. It requires the owner's
already authenticated interactive SSH terminal and never handles the sudo
password.

### 5.1 Read-only preflight

~~~bash
hostnamectl
uname -a
nproc
free -h
df -h /
ss -ltnp
systemctl --no-pager --type=service --state=running
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'
docker network ls
docker volume ls
sudo nginx -t
sudo tailscale status
sudo tailscale serve status
~~~

Abort if any existing service, container, volume, route, or port would be reused. Do not inspect production rows or secret values.

### 5.2 Account and filesystem

~~~bash
sudo useradd --system --home-dir /opt/gdb-staging \
  --shell /usr/sbin/nologin gdb-staging
sudo install -d -o gdb-staging -g gdb-staging -m 0750 \
  /opt/gdb-staging/backend/releases \
  /opt/gdb-staging/frontend/releases \
  /opt/gdb-staging/operations \
  /var/lib/gdb-staging/snapshots \
  /var/log/gdb-staging
sudo install -d -o root -g gdb-staging -m 0750 /etc/gdb-staging
~~~

If an account or path already exists, stop and review ownership; do not use recursive changes on a broad home directory. Copy only approved immutable release artifacts and verify:

~~~bash
sha256sum -c /opt/gdb-staging/operations/RELEASE.SHA256SUMS
readlink -f /opt/gdb-staging/backend/current
readlink -f /opt/gdb-staging/frontend/current
~~~

### 5.3 Runtime environment

Create through the approved secret-delivery process, replace every placeholder, and set mode 0600:

~~~text
GDB_DB_URL=jdbc:postgresql://127.0.0.1:55433/gdb_staging
GDB_DB_USERNAME=gdb_staging_app
GDB_DB_PASSWORD=<runtime-role-secret>
GDB_DB_POOL_MAX=5
GDB_DB_POOL_MIN=1
GDB_DB_CONNECTION_TIMEOUT_MS=5000
GDB_ADMIN_BOOTSTRAP_TOKEN=<one-time-staging-bootstrap-secret>
GDB_ADMIN_SESSION_TTL=PT2H
GDB_ADMIN_SESSION_COOKIE_SECURE=true
GDB_SNAPSHOT_MASTER_KEY=<32-byte-base64-staging-key>
GDB_SNAPSHOT_STORAGE=/var/lib/gdb-staging/snapshots
GDB_SERVER_ADDRESS=127.0.0.1
GDB_SERVER_PORT=8192
GDB_ALLOWED_ORIGINS=https://<approved-staging-admin-name>
GDB_ENROLLMENT_TTL=PT15M
GDB_MAX_BATCH_SIZE=100
GDB_MAX_PULL_SIZE=100
GDB_MAX_SNAPSHOT_BYTES=52428800
GDB_SNAPSHOT_RETENTION_COUNT=3
SPRING_FLYWAY_ENABLED=false
~~~

The runtime file must not contain the migration-role secret. Rotate or disable the bootstrap secret after the first staging administrator is created.

### 5.4 Disposable PostgreSQL

~~~bash
docker network create --internal gdb-staging-private
docker volume create gdb-staging-postgres-data
docker run -d --name gdb-staging-postgres \
  --network gdb-staging-private --restart unless-stopped \
  --cpus=1 --memory=768m --pids-limit=128 \
  --read-only --tmpfs /run --tmpfs /tmp \
  --env-file /etc/gdb-staging/postgres.env \
  -v gdb-staging-postgres-data:/var/lib/postgresql \
  postgres:18.6-alpine
until docker exec gdb-staging-postgres pg_isready -U postgres -d postgres; do sleep 2; done
~~~

The owner-approved postgres.env is disposable, mode 0600, and unrelated to existing GApps configuration. PostgreSQL has no host publication; the owner-run continuation’s `batch4-network-fix` command creates the separate HAProxy TCP proxy, publishes only `127.0.0.1:55433`, and verifies the proxy’s internal backend state before Batch 5. Feed a reviewed SQL file to the exact container:

~~~sql
CREATE ROLE gdb_staging_migrator LOGIN PASSWORD '<migration-role-secret>';
CREATE ROLE gdb_staging_app LOGIN PASSWORD '<runtime-role-secret>';
CREATE DATABASE gdb_staging OWNER gdb_staging_migrator;
REVOKE ALL ON DATABASE gdb_staging FROM PUBLIC;
GRANT CONNECT ON DATABASE gdb_staging TO gdb_staging_migrator, gdb_staging_app;
ALTER ROLE gdb_staging_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE gdb_staging_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
~~~

~~~bash
docker exec -i gdb-staging-postgres psql -U postgres -d postgres \
  -v ON_ERROR_STOP=1 < /etc/gdb-staging/db-bootstrap.sql
~~~

Securely retire the bootstrap SQL after verifying the roles. Never put passwords in shell history or logs.

### 5.5 Migration and runtime

The repository role test proves the boundary, but this target-host migration procedure is not verified. Use a one-shot environment containing only the migration role:

~~~text
GDB_DB_URL=jdbc:postgresql://127.0.0.1:55433/gdb_staging
GDB_DB_USERNAME=gdb_staging_migrator
GDB_DB_PASSWORD=<migration-role-secret>
GDB_SNAPSHOT_MASTER_KEY=<staging-key>
GDB_SNAPSHOT_STORAGE=/var/lib/gdb-staging/snapshots
GDB_SERVER_ADDRESS=127.0.0.1
GDB_SERVER_PORT=0
GDB_ALLOWED_ORIGINS=https://<approved-staging-admin-name>
~~~

~~~bash
sudo -u gdb-staging sh -c \
  'set -a; . /etc/gdb-staging/migrator.env; set +a; \
   timeout --foreground 120s java -Xms64m -Xmx256m \
   -XX:MaxMetaspaceSize=128m -jar \
   /opt/gdb-staging/backend/current/gdb-backend-1.0.0-SNAPSHOT.jar \
   --spring.main.web-application-type=none --spring.flyway.enabled=true'
~~~

Treat a nonzero exit, timeout, checksum issue, or wrong database target as failure. Verify Flyway version 3 and confirm no process retains the migration secret. If the one-shot process does not exit cleanly, stop and revise the procedure; never leave the backend running under the migration role.

Proposed staging unit:

~~~ini
[Unit]
Description=GDB staging backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=gdb-staging
Group=gdb-staging
WorkingDirectory=/opt/gdb-staging/backend/current
EnvironmentFile=/etc/gdb-staging/backend.env
ExecStart=/usr/bin/java -Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -jar /opt/gdb-staging/backend/current/gdb-backend-1.0.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
MemoryHigh=640M
MemoryMax=768M
CPUQuota=200%
ReadWritePaths=/var/lib/gdb-staging
SyslogIdentifier=gdb-staging-backend

[Install]
WantedBy=multi-user.target
~~~

The unit must use gdb_staging_app and SPRING_FLYWAY_ENABLED=false:

~~~bash
sudo systemctl daemon-reload
sudo systemctl enable --now gdb-staging-backend.service
systemctl --no-pager --full status gdb-staging-backend.service
curl --fail --silent --show-error http://127.0.0.1:8192/actuator/health
~~~

### 5.6 Nginx staging gateways

Create a new reviewed fragment only after route authorization. Do not edit the existing GDC /api/ block:

~~~nginx
server {
    listen 127.0.0.1:8193;
    server_name gdb-staging-admin-internal;
    root /opt/gdb-staging/frontend/current/browser;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
    location /api/ {
        proxy_pass http://127.0.0.1:8192;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        client_max_body_size 60m;
    }
    location = /actuator/health {
        proxy_pass http://127.0.0.1:8192;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
    }
}
server {
    listen 127.0.0.1:8194;
    server_name gdb-staging-sync-internal;
    location = /api/v1/devices/register { proxy_pass http://127.0.0.1:8192; client_max_body_size 1m; }
    location /api/v1/sync/ { proxy_pass http://127.0.0.1:8192; client_max_body_size 5m; }
    location = /api/v1/snapshots { proxy_pass http://127.0.0.1:8192; client_max_body_size 60m; }
    location /api/v1/snapshots/ { proxy_pass http://127.0.0.1:8192; client_max_body_size 60m; }
    location / { return 404; }
}
~~~

Before any reload, verify:

~~~bash
sudo nginx -t
sudo ss -ltnp | grep -E ':(8193|8194)\b'
~~~

Do not reload if the test reports an existing-route change, unexpected listener, or syntax error.

### 5.7 Candidate Tailscale Services

~~~text
svc:gdb-staging-admin -> 127.0.0.1:8193
svc:gdb-staging-sync  -> 127.0.0.1:8194
~~~

Illustrative commands, subject to installed-version and route-owner approval:

~~~text
tailscale serve --service=svc:gdb-staging-admin --https=443 127.0.0.1:8193
tailscale serve --service=svc:gdb-staging-sync --https=443 127.0.0.1:8194
tailscale serve status
~~~

Administrator identities may reach only the admin Service. The named tablet identity/tag may reach only the sync Service. The tablet must not reach the admin Service, GDC, GDCLab, GT2.0, PostgreSQL, Docker, or another tenant. Do not use a global Tailscale Serve reset; it could affect GT2.0. If service-specific removal syntax is not confirmed, obtain the route-owner rollback procedure before enabling a route.

## 6. Physical Android tablet test

This section is PILOT REQUIRED and was not executed.

1. Use only the owner tablet and synthetic identifiers such as pilot-patient-001. Do not enter real patient or clinical data.
2. Install the approved Tailscale Android profile using the owner account or MDM. Use a one-off/pre-approved provisioning key if required; record expiry and revocation owner.
3. Tag the tablet as the pilot sync device, not as an administrator.
4. Use cellular data or independent external Wi-Fi with no route to the RWP LAN.
5. Confirm negative reachability: no admin Service, GDC, GDCLab, GT2.0, PostgreSQL, Docker, or other tenant.
6. From the admin Service, create one synthetic application, tenant, and short-lived enrollment code through POST /api/v1/admin/tenants/{tenantId}/enrollment-codes.
7. Register through POST /api/v1/devices/register on the sync Service. Confirm one-use code, intended tenant/application scope, device ID, and credential.
8. Confirm device GET /api/v1/sync/status succeeds while admin paths on the sync gateway return 404 or unauthorized.
9. Disable network, create and update a synthetic patient locally, close/reopen the GApp, and verify queued events/cursor survive.
10. Reconnect over mobile internet, push, pull, acknowledge, and verify stable cursor advancement.
11. Retry the same batch/event and verify duplicate acknowledgement with no second business event.
12. Create stale concurrent synthetic updates and verify conflict preservation; create a delete and verify tombstone propagation.
13. Upload/download a synthetic encrypted snapshot and verify checksum and tenant scope.
14. Create a second synthetic tenant and verify cross-tenant push, pull, and snapshot access fails.
15. Rotate the device credential and verify the old credential returns 401.
16. Revoke the device and verify status, push, pull, and snapshot requests return 401. Re-enrollment must require a new explicit enrollment code.

TLS acceptance requires the exact approved hostname, valid Android clock, trusted certificate with matching name, no warning or validation bypass, no HTTP fallback, and a recorded renewal owner. From an approved workstation:

~~~bash
openssl s_client -connect <staging-sync-hostname>:443 \
  -servername <staging-sync-hostname> -verify_return_error </dev/null
curl --fail --show-error https://<staging-sync-hostname>/api/v1/sync/status
~~~

The unauthenticated status request is expected to be rejected. Record status codes, cursors, batch IDs, device IDs, and synthetic entity IDs only; never record credentials or real data.

## 7. Off-server backup and restore rehearsal

### 7.1 Required owner decisions

Select a separate host/provider/account, production-approved AEAD/envelope encryption, named key owner, offline recovery-key custody, retention, restore owner, and RPO/RTO. Proposed staging targets are RPO 24 hours and RTO 2 hours, to be measured. The existing same-host harness is not off-server disaster recovery.

### 7.2 Future executable procedure

The following is a conditional example using age and rsync. Do not install or use either until the owner approves the tools, destination, and key custody:

~~~bash
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 0700 -p /var/backups/gdb-staging
docker exec gdb-staging-postgres pg_dump --format=custom --no-owner --no-privileges \
  -U gdb_staging_migrator -d gdb_staging \
  > "/var/backups/gdb-staging/gdb_staging-$STAMP.dump"
age -r "<approved-age-recipient>" \
  -o "/var/backups/gdb-staging/gdb_staging-$STAMP.dump.age" \
  "/var/backups/gdb-staging/gdb_staging-$STAMP.dump"
tar --create --file - --directory /var/lib/gdb-staging snapshots \
  | age -r "<approved-age-recipient>" \
  > "/var/backups/gdb-staging/snapshots-$STAMP.tar.age"
sha256sum /var/backups/gdb-staging/*.age \
  > "/var/backups/gdb-staging/SHA256SUMS-$STAMP"
rsync --archive --checksum --partial /var/backups/gdb-staging/ \
  <approved-backup-account>@<approved-backup-host>:/<approved-destination>/gdb-staging/
sha256sum --check "/var/backups/gdb-staging/SHA256SUMS-$STAMP"
shred -u "/var/backups/gdb-staging/gdb_staging-$STAMP.dump"
~~~

The cleartext dump must not be transferred and may be removed only after encrypted artifacts and checksums are verified. If the approved tool differs, replace and re-review the complete procedure.

Restore only to an exact new disposable container/volume:

~~~bash
docker run -d --name gdb-staging-restore-<timestamp> \
  --cpus=1 --memory=768m --env-file /etc/gdb-staging/restore-postgres.env \
  -v gdb-staging-restore-<timestamp>:/var/lib/postgresql postgres:18.6-alpine
age -d -i /offline/recovery/<approved-recovery-key> \
  < gdb_staging-<timestamp>.dump.age > /tmp/gdb-staging-restore.dump
docker exec -i gdb-staging-restore-<timestamp> pg_restore --exit-on-error \
  --no-owner --no-privileges -U gdb_restore -d gdb_staging_restore \
  < /tmp/gdb-staging-restore.dump
sha256sum --check SHA256SUMS-<timestamp>
~~~

Verify synthetic patient/event counts, Flyway version 3, tenant isolation, snapshot decrypt/checksum, revocation state, and application startup. Record backup, transfer, decrypt, restore, and verification times. Remove only the exact restore container/volume after owner acceptance.

## 8. Security and capacity

| Actor | Allowed | Not allowed |
|---|---|---|
| Administrator | Admin Service, portal, synthetic app/tenant/device management | PostgreSQL, Docker, GDC/GDCLab/GT2.0 administration |
| Pilot tablet | Sync Service registration, sync, snapshots for enrolled scope | Admin Service, other tenants, PostgreSQL, Docker, unrelated RWP services |
| gdb_staging_app | Required runtime tables and controlled app-schema function | Superuser, CREATEDB, CREATEROLE, core-schema DDL, other databases |
| gdb_staging_migrator | Flyway V1–V3 and approved provisioning DDL during migration | Long-running application use or shared runtime credentials |

Local administrator controls are verified for login, hashed expiring sessions, logout, revoke-all, ADMIN/AUDITOR boundaries, bootstrap-once behavior, and audit. Remaining limitations are no MFA/SSO, no demonstrated lockout/rate limiting, and no production secret manager. Bootstrap is initial/emergency access only.

Proposed staging alerts:

- health/database failure: immediate;
- more than 3 backend restarts in 10 minutes: stop;
- memory at 640 MiB warning or 768 MiB hard limit: stop new load;
- CPU sustained above 200% quota or Hikari acquisition timeout: investigate;
- staging allocation at 70% warning and 85% critical;
- backup age over 24 hours, checksum/transfer/restore failure: block continuation;
- any non-loopback backend/database/gateway listener: immediate rollback;
- any credential, plaintext snapshot, or unexpected sensitive log: stop and rotate.

Initial pilot envelope: one administrator, one tablet, one synthetic application/tenant, at most 100 events/day, 10 batches/hour, snapshots no larger than 50 MiB, and no production traffic. Limits are Hikari 5/1, JVM 128–512 MiB, systemd 640/768 MiB, PostgreSQL 1 CPU/768 MiB, and loopback-only database access. These are planning limits, not measured capacity.

## 9. Acceptance criteria

Accept staging only when all applicable evidence is recorded:

1. Existing GDC, GDCLab, GT2.0, gapps-postgres, shared Nginx /api/, and GT2.0 Tailscale root remain unchanged and healthy.
2. Staging listeners are only 127.0.0.1:8192, 8193, 8194, and 55433.
3. Flyway reaches only gdb_staging and records version 3.
4. Runtime role works and unauthorized schema DDL fails.
5. Admin HTTPS certificate and session login are valid; bootstrap is not normal access.
6. Tablet reaches only sync over external mobile internet with valid TLS and no bypass.
7. Enrollment is short-lived/one-use; rotation and revocation reject old credentials.
8. Offline events survive app restart and synchronize after reconnection.
9. Acknowledgements, cursors, duplicates, conflicts, tombstones, snapshots, and tenant isolation pass.
10. Encrypted backup exists at the approved off-server destination and full synthetic restore is timed and accepted.
11. Resource/log thresholds remain within the approved envelope.
12. Owner signs the staging evidence and separately decides whether any later phase is authorized.

Failure of one mandatory item is a pilot NO-GO.

## 10. Failure and rollback

Stop and withdraw only GDB staging traffic if a route reaches an unrelated service/tenant, a listener leaves the approved interface, Flyway targets the wrong resource, isolation or revocation fails, TLS requires a bypass, backup/recovery fails, or resource thresholds are breached. Preserve evidence before teardown.

Future owner-authorized GDB-only rollback:

~~~bash
sudo systemctl disable --now gdb-staging-backend.service
sudo sh -c 'journalctl -u gdb-staging-backend.service --since "2 hours ago" > /var/log/gdb-staging/rollback-backend-journal.txt'
sudo docker logs gdb-staging-postgres-proxy 2>&1 | sudo tee /var/log/gdb-staging/rollback-proxy.log >/dev/null
sudo docker stop gdb-staging-postgres-proxy
sudo docker logs gdb-staging-postgres 2>&1 | sudo tee /var/log/gdb-staging/rollback-postgres.log >/dev/null
sudo docker stop gdb-staging-postgres
~~~

Do not run a global Tailscale Serve reset. Remove only owner-approved staging Service entries using the installed-version, service-specific procedure. If that syntax is not confirmed, leave policy disabled and escalate to the route owner.

After synthetic evidence is archived and teardown is approved, remove only exact staging resources:

~~~bash
docker rm -f gdb-staging-postgres-proxy
docker network rm gdb-staging-proxy-edge
docker rm -f gdb-staging-postgres
docker volume rm gdb-staging-postgres-data
docker network rm gdb-staging-private
sudo rm -rf /opt/gdb-staging /var/lib/gdb-staging /var/log/gdb-staging /etc/gdb-staging
sudo userdel gdb-staging
~~~

Do not run teardown while evidence, backup, or incident review depends on these resources. Never substitute existing GApps names or broad paths.

If a credential is exposed, disable the admin session or device, rotate the affected secret, revoke both Tailscale identity and GDB device credential when relevant, preserve audit/journal evidence, and record the incident.

## 11. Remaining production blockers

- Customer devices outside the managed pilot tailnet still have no approved production transport.
- Production endpoint names, Tailscale grants, certificate ownership, and Nginx routing are not live-verified.
- Production database secrets, image digest, storage, and migration procedure remain owner-controlled.
- Off-server destination, key custody, retention, RPO/RTO, and restore rehearsal remain unverified.
- V1 admin has no MFA/SSO or demonstrated lockout/rate limiting.
- Workload sizing, aggregate resource contention, storage growth, and monitoring are untested.
- Real GApp SQLite/SQLCipher behavior and physical two-device acceptance remain unverified.
- Separate authorization for Phase 3C or production deployment has not been granted.

## 12. Final status and authorization request

**Phase 3B.4 runbook status: COMPLETE FOR REVIEW.**

The topology, future commands, owner decisions, physical tablet procedure, recovery procedure, access policy, capacity envelope, acceptance criteria, and rollback conditions are documented. No command requiring SSH, deployment, server mutation, live routing, production database access, or tablet enrollment was executed.

Obtain explicit owner authorization naming the operator, window, exact staging services/routes, backup destination, key owner, and rollback authority before any staging action. This runbook does not transition GDB to Phase 3C.
