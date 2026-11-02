# GDB v1.0 — Gate 1 Read-Only SSH Authorization

Inspection date: 2026-11-01 (Asia/Kolkata)  
Target: `rwp-server`  
Scope: one-time, strictly read-only SSH inspection

## Decision

**Gate 1 read-only inspection: GO — completed.**  
**Phase 3C deployment: NO-GO — not authorized and not executed.**

The inspection used the authorized SSH session only for non-mutating host, service, listener, Docker metadata, and health checks. No `sudo`, database access or SQL, configuration reads containing secrets, filesystem writes, Docker resource changes, service restarts, reloads, migrations, or deployments were performed.

## 1. Verified host capacity

| Check | Verified result |
|---|---|
| Host / OS | `rwp-server`, Ubuntu 26.04 LTS |
| Kernel / architecture | Linux 7.0.0-38-generic, x86_64 |
| CPU | 12 logical CPUs |
| Memory | 7.2 GiB total; 5.3 GiB available at inspection |
| Swap | 4.0 GiB total; 0 B used |
| Load | 0.00 / 0.00 / 0.00 at inspection |
| Root filesystem | 98G total, 11G used, 83G available, 12% used |
| Java | OpenJDK 21.0.12.1 |
| Docker | Server 29.7.0; overlayfs; 12 CPUs; about 7.2 GiB visible memory |
| Nginx | 1.28.3 (Ubuntu), active |
| Tailscale | 1.98.10; status checked without changing it |

This is a modest but currently lightly loaded host. No load, stress, backup/restore, or sustained capacity test was authorized or performed; existing workloads take priority.

## 2. Port availability and conflicts

The proposed GDB staging ports were unbound at inspection time:

| Port | Result |
|---|---|
| 8192 | UNBOUND |
| 8193 | UNBOUND |
| 8194 | UNBOUND |
| 55433 | UNBOUND |

Existing relevant listeners were observed on ports 22, 80, 443, 5432, 8080, 8090, and 8091. Port 5432 is bound to loopback for the existing Docker PostgreSQL service. Nginx is shared infrastructure, so its existing routes must not be changed without separate authorization.

## 3. Existing services and health observations

| Component | Verified result |
|---|---|
| GDC backend | `gdc-backend.service` active/running; listener on 8080; standard tested health paths returned 404, so app-level health is NOT independently verified |
| GDCLab backend | `gdclab-backend.service` active/running; listener on 8090; standard tested health paths returned 404, so app-level health is NOT independently verified |
| GT2.0 backend | `gt2-backend.service` active/running; `/actuator/health` returned HTTP 200 on 8091 |
| Nginx | active/running; `/` and `/gdclab/` returned HTTP 200; existing `/api/` probes returned 404 |
| Docker / containerd | both active/running |
| Tailscaled | active/running; current tailnet-only root proxies `http://127.0.0.1:8091` for GT2.0 |
| Native PostgreSQL unit | `postgresql.service` not found; PostgreSQL is provided by Docker |

GDC and GDCLab process/listener status and Nginx frontend responses were observed, but their application health remains **NOT VERIFIED** because the standard paths tested were not exposed. GT2.0 actuator health was verified.

## 4. Docker metadata and conflicts

`docker info`, `docker ps -a`, `docker inspect`, network metadata, volume metadata, and `docker system df` were inspected without entering containers or changing resources.

- `gapps-postgres`, image `postgres:18`, is running and healthy, with loopback mapping `127.0.0.1:5432->5432/tcp`.
- `gapps-postgres` uses the existing `postgres_postgres_data` volume mounted at `/var/lib/postgresql`; this must not be reused for GDB.
- `hello-home`, image `nginxdemos/hello`, is an unrelated exited container with no ports.
- No GDB-named container, network, or volume was found.
- Existing Docker networks include `gapps-network` and `postgres_default`; no new resources were created.
- Docker reported 2 containers (1 running), 2 images, 2 local volumes, and no build cache. The active local volume data reported by Docker was about 77 MB.

## 5. Capacity assessment and staging conflicts

There was no immediate port conflict or obvious host-capacity blocker for a future isolated GDB staging design. However, the available memory and disk are not a deployment guarantee. Resource limits/quotas, workload sizing, backup capacity, restore procedures, and sustained performance were not tested.

The following conflicts or prerequisites remain:

1. Phase 3C deployment authorization is not granted; Gate 1 read-only authorization does not authorize deployment.
2. Existing Nginx is shared, and the current Tailscale root belongs to the existing GT2.0 route. A dedicated private admin hostname/route and ownership are not verified or approved.
3. Tablet sync transport outside the current tailnet is not selected or tested.
4. Dedicated PostgreSQL isolation, runtime/migration roles, backup/key custody, and restore verification are not established. Do not use `gapps-postgres` or `postgres_postgres_data` for GDB.
5. GDC and GDCLab application-level health endpoints were not independently verified.

## 6. Final recommendation

**GO for the completed Gate 1 read-only inspection.** The evidence is sufficient to record that the requested host inventory was performed within the stated read-only boundary.

**NO-GO for Phase 3C deployment.** Do not deploy, migrate, alter Nginx/Tailscale, create Docker resources, modify services, or access databases until a separate deployment authorization is provided and the outstanding isolation, routing, sync, backup/restore, and health-verification prerequisites are resolved.

Stop here and await separate deployment authorization.

## 7. 2026-11-02 final read-only readiness recheck

The previously authorized read-only scope was rechecked on 2026-11-02 (Asia/Kolkata). No `sudo` was used, and no database, SQL, secret, filesystem, Docker resource, service, Nginx, or Tailscale mutation was performed.

| Check | Current result |
|---|---|
| Host baseline | `rwp-server`; 12 CPUs; load 0.00/0.00/0.00 |
| Memory / swap | 7.2 GiB total, 5.3 GiB available; 4.0 GiB swap with 0 B used |
| Root disk | 98G total, 11G used, 83G available, 12% used |
| Target ports | 8192, 8193, 8194, and 55433 all UNBOUND |
| Existing services | GDC, GDCLab, GT2.0, Nginx, Docker, containerd, and Tailscaled active/running; native PostgreSQL unit not found |
| Existing database container | `gapps-postgres` healthy on loopback `127.0.0.1:5432`; existing volume remains `postgres_postgres_data` |
| GDB staging resources | `gdb-staging-postgres`, `gdb-staging-private`, and `gdb-staging-postgres-data` absent |
| Nginx | `/` and `/gdclab/` returned HTTP 200; unprivileged `nginx -t` reported syntax OK but could not open `/run/nginx.pid` (permission denied) |
| Tailscale | 1.98.10; tailnet-only root still proxies `127.0.0.1:8091` for GT2.0 |
| GT2.0 health | `/actuator/health` returned HTTP 200 |
| GDC/GDCLab health | standard `/actuator/health`, `/health`, `/healthz`, and `/` probes returned 404; application health remains NOT VERIFIED |

The GDC/GDCLab service units and listeners are active, but no correct documented health endpoint was available in the reviewed material and no endpoint responded successfully. Per the Gate 1 instructions, this is an owner-review limitation and is a deployment stop condition; it must not be interpreted as proof that either application is unhealthy.

## 8. Gate 1 execution checkpoint — owner authorized; continuation pending

The owner has authorized the isolated Gate 1 staging change set. Batches 1–3
were reported complete by the owner on 2026-11-02. This section does not claim
independent verification of those batch outputs; Batches 4–7 remain pending.

### Resources and exact identifiers

- Account/service: `gdb-staging` / `gdb-staging-backend.service`
- Backend: `127.0.0.1:8192`
- Admin gateway candidate: `127.0.0.1:8193`
- Sync gateway candidate: `127.0.0.1:8194`
- PostgreSQL: container `gdb-staging-postgres`, loopback `127.0.0.1:55433`
- Database/roles: `gdb_staging`, `gdb_staging_migrator`, `gdb_staging_app`
- Docker network/volume: `gdb-staging-private` / `gdb-staging-postgres-data`
- Paths: `/opt/gdb-staging`, `/var/lib/gdb-staging`, `/var/log/gdb-staging`, `/etc/gdb-staging`
- Image: `postgres:18.6-alpine`, subject to availability verification and the owner-approved image digest

### Proposed bounded resources

- Backend JVM: Xms128m, Xmx512m, MaxMetaspaceSize=192m
- Backend systemd: `MemoryHigh=640M`, `MemoryMax=768M`, `CPUQuota=200%`
- PostgreSQL: 1 CPU, 768 MiB, swap disabled for the container, PID limit 128
- Hikari pool: maximum 5, minimum 1, five-second acquisition timeout
- Pilot envelope: one administrator, one named tablet, one synthetic application/tenant, at most 100 events/day, 10 batches/hour, snapshots no larger than 50 MiB
- All backend, gateway, and database listeners loopback-only; no public HTTPS route

### Planned command set

The reviewed runbook’s exact command templates have been consolidated into the
fail-fast owner-run continuation script
[`deployment/gdb-staging-gate1.sh`](../../deployment/gdb-staging-gate1.sh).
It performs a fresh read-only preflight at Batch 4, validates the existing
network and volume instead of recreating them, creates only the approved
PostgreSQL container, and requires separate invocations for Batches 5–7.
It does not include Nginx, Tailscale, tablet, backup, or production actions.

```bash
sudo useradd --system --home-dir /home/gutta/gdb-staging \
  --shell /usr/sbin/nologin gdb-staging
sudo install -d -o gdb-staging -g gdb-staging -m 0750 \
  /home/gutta/gdb-staging/backend/releases \
  /home/gutta/gdb-staging/frontend/releases \
  /home/gutta/gdb-staging/operations \
  /var/lib/gdb-staging/snapshots \
  /var/log/gdb-staging
sudo install -d -o root -g gdb-staging -m 0750 /etc/gdb-staging

docker network create gdb-staging-private
docker volume create gdb-staging-postgres-data
docker run -d --name gdb-staging-postgres \
  --network gdb-staging-private --restart unless-stopped \
  --cpus=1 --memory=768m --pids-limit=256 \
  --read-only --tmpfs /run --tmpfs /tmp \
  -p 127.0.0.1:55433:5432 \
  --env-file /etc/gdb-staging/postgres.env \
  -v gdb-staging-postgres-data:/var/lib/postgresql \
  postgres:18-alpine

sudo systemctl enable --now gdb-staging-backend.service
```

Database bootstrap, Flyway V1–V3, gateway startup, private routing, and health checks require separate approved commands using secrets supplied outside logs or shell history. No Nginx or Tailscale command is included in the approved change set until the route owner supplies the final service names, grants, certificate/renewal owner, and rollback syntax.

### Expected interruption and rollback

Expected interruption to existing services: **none**. The change is intended to start only new GDB-owned resources. Any route reaching GDC, GDCLab, or GT2.0; non-loopback listener; wrong Flyway target; isolation/revocation failure; backup failure; or resource breach is an immediate stop/rollback trigger.

Owner-authorized rollback is limited to GDB resources, beginning with:

```bash
sudo systemctl disable --now gdb-staging-backend.service
docker stop gdb-staging-postgres
```

Preserve logs and evidence first. Teardown/removal of the exact GDB account, paths, container, volume, and network requires a separate teardown approval; no destructive database rollback is authorized.

### Required authorization fields

Before any mutation, the owner must provide: named operator; named rollback authority; second-person reviewer; approved IST change window; final artifact/image digests; secret-delivery and key custodian; private route names and grants; backup destination, retention, RPO/RTO and restore owner; and explicit acceptance or remediation of the unresolved GDC/GDCLab health limitation.

**Checkpoint status: OWNER-AUTHORIZED, AWAITING MANUAL BATCH 4 EXECUTION.**
The missing GDC/GDCLab application-level health endpoints remain a documented
limitation, but they do not authorize any change to those applications.

## 9. 2026-11-02 local application-source health review

This section records a local source/documentation review only. No new SSH connection, server request, service inspection, database access, or production-data access was performed for this section.

### GDC2.0

Local source reviewed: /Users/gutta/Documents/GDC2.0/GDC2.0/backend.

- application.properties configures the Spring application, PostgreSQL, JPA validation, Flyway, and scan storage, but no Actuator management endpoint.
- pom.xml contains the web, JPA, validation, Flyway, and test starters, but no Spring Boot Actuator or Spring Security dependency.
- No SecurityFilterChain, security configuration, or health controller was found in the reviewed source.
- Read-only application routes include patient, treatment, appointment, pharmacy, payment, dashboard, and related business routes. These can return business data and are not suitable as an unauthenticated health probe.
- No safe, non-business GDC health endpoint was identified.

**Local conclusion:** application-level health remains **NOT VERIFIED**. A future separately approved remediation should add a deliberately designed health/readiness endpoint or an owner-approved authenticated diagnostic check; this review does not modify GDC2.0.

### GDCLab

Local source reviewed: /Users/gutta/Documents/RWP_Repos/GDCLab/backend.

- application.properties configures port 8090, PostgreSQL, JPA validation, the gdclab schema, and Flyway, but no Actuator management endpoint.
- pom.xml contains the web, JPA, validation, Flyway, and test starters, but no Spring Boot Actuator or Spring Security dependency.
- No SecurityFilterChain, security configuration, or health controller was found in the reviewed source.
- Read-only catalog routes include GET /api/v1/test-categories, GET /api/v1/tests, and GET /api/v1/tests/{testCode}. These appear to be diagnostic catalog routes, not health endpoints; their response bodies must not be printed or captured until server-side handling is approved.
- No dedicated health/readiness route was identified.

**Local conclusion:** application-level health remains **NOT VERIFIED**. The catalog routes are only candidates for a future redacted functional smoke check, not substitutes for a proper readiness endpoint.

### Authentication limitation

The absence of Spring Security in the reviewed source indicates that application-level authentication was not detected in these source trees. It does not establish that the deployed server routes are unauthenticated: Nginx, Tailscale, network policy, or another deployment layer may apply access control. A future authorized read-only session must verify only status codes and bounded metadata, without printing response bodies or business records.

### Revised Gate 1 recommendation

**NO-GO pending explicit authorization for a new read-only SSH session.** After authorization, the session may:

1. Recheck systemd state, listeners, resource pressure, and existing-service health.
2. Perform harmless GET requests only against verified routes.
3. Record status code, timing, content type, and bounded response metadata without bodies.
4. Confirm whether the previously observed 404 results are current.

If no safe GDC endpoint is confirmed, GDC health must remain **NOT VERIFIED** and Gate 1 must not authorize staging. GDCLab may receive a separately approved redacted catalog smoke check only if the route is confirmed and the response is not recorded. No GDC, GDCLab, GT2.0, Nginx, Tailscale, or database change is authorized by this section.

## 10. 2026-11-02 authorized read-only health verification

An additional strictly read-only SSH session was authorized for this verification. No sudo or privilege escalation, database connection, SQL, secret access, business-data endpoint, filesystem write, Docker resource change, service restart, Nginx change, Tailscale change, or deployment was performed. HTTP response bodies were discarded; only status code, content type, and timing were recorded.

### 10.1 Current host baseline

| Check | Verified result |
|---|---|
| Host | rwp-server |
| Uptime/load | 7 hours 5 minutes; load averages 0.08, 0.02, 0.01 |
| CPU | 12 logical CPUs |
| Memory | 7.2 GiB total, 1.8 GiB used, 5.3 GiB available |
| Swap | 4.0 GiB total, 0 B used |
| Root filesystem | 98G total, 11G used, 83G available, 12% used |

The current load and resource pressure do not show an immediate staging capacity warning. This remains a point-in-time observation, not a sustained workload or capacity guarantee.

### 10.2 Service and listener status

| Application | Service state | Listener result |
|---|---|---|
| GDC2.0 | gdc-backend.service active/running; ExecMainStatus 0 | 0.0.0.0:8080, Java listener |
| GDCLab | gdclab-backend.service active/running; ExecMainStatus 0 | 0.0.0.0:8090, Java listener |
| GT2.0 | gt2-backend.service active/running; ExecMainStatus 0 | 127.0.0.1:8091 |

The proposed staging ports were all unbound at inspection time:

- 8192: UNBOUND
- 8193: UNBOUND
- 8194: UNBOUND
- 55433: UNBOUND

The existing GDC2.0 and GDCLab listeners are wildcard-bound rather than loopback-only. This is an existing service property and was not changed.

### 10.3 Status-only HTTP probes

Only standard non-business paths were probed: /actuator/health, /health, /healthz, and /. Response bodies were not displayed.

| Port/application | Probe result |
|---|---|
| GDC2.0, 8080 | All four paths returned HTTP 404 with application/json |
| GDCLab, 8090 | All four paths returned HTTP 404 with application/json |
| GT2.0, 8091 | /actuator/health returned HTTP 200 with Actuator content type; /health, /healthz, and / returned HTTP 401 |

### 10.4 Health conclusions

#### GDC2.0

- Service/process readiness: **VERIFIED at inspection time** through active/running systemd state, successful process exit status, and the expected 8080 listener.
- Application health: **NOT VERIFIED**. No safe health endpoint responded successfully.
- Existing source review found no Actuator dependency, Spring Security configuration, or dedicated health controller. Business GET routes were not probed because they may expose patient or other business data.
- Recommended future baseline: retain the service/listener checks and add a separately approved non-sensitive readiness endpoint or authenticated diagnostic check. Do not use patient, treatment, financial, or dashboard routes as health probes.

#### GDCLab

- Service/process readiness: **VERIFIED at inspection time** through active/running systemd state, successful process exit status, and the expected 8090 listener.
- Application health: **NOT VERIFIED**. No safe health endpoint responded successfully.
- Existing source review found no Actuator dependency, Spring Security configuration, or dedicated health controller. Catalog routes were not probed because they are functional business endpoints rather than readiness endpoints.
- Recommended future baseline: retain the service/listener checks and add a separately approved non-sensitive readiness endpoint. A redacted catalog smoke check may be considered separately, but must not replace a health endpoint or record response bodies.

#### GT2.0 baseline

- Service/process readiness: **VERIFIED at inspection time**.
- Application health: **VERIFIED only for /actuator/health**, which returned HTTP 200 in this session.
- GT2.0 was not modified or otherwise inspected beyond the permitted status-only probe.

### 10.5 Revised Gate 1 recommendation

**GO for the completed read-only health verification.** The current service states, resource baseline, listener availability, and permitted status-only probes are recorded.

**NO-GO for isolated GDB staging deployment.** GDC2.0 and GDCLab application-level health remain NOT VERIFIED, although their service/process and listener baselines are healthy at this point in time. No staging deployment should begin until the owner explicitly accepts this limitation or separately authorizes remediation of the existing applications' health-check design. This health-verification authorization does not authorize Gate 1 staging resource creation, Gate 2, Gate 3, or Phase 3C.

Stop after this inspection. No server-side change was performed.

## 11. 2026-11-02 Gate 1 execution attempt

The owner authorized immediate Gate 1 isolated staging deployment for this supervised session. The required final read-only preflight completed before any mutation:

- Target ports 8192, 8193, 8194, and 55433 were unbound.
- Staging account, filesystem paths, Docker network, volume, and container names were absent.
- GDC2.0, GDCLab, GT2.0, Nginx, Docker, containerd, and Tailscale services were active/running.
- Existing GApps PostgreSQL remained on loopback port 5432; existing application listeners remained on 8080, 8090, and 8091.
- Current host load was 0.05/0.01/0.00, with 5.3 GiB available memory and 83 GiB available root disk.

Execution stopped before resource creation because the SSH operator does not have non-interactive sudo authority. The exact read-only capability check returned:

~~~text
sudo: interactive authentication is required
passwordless-sudo:unavailable
~~~

This blocks the authorized staging procedure because Gate 1 requires creation of a dedicated system account, protected paths under /etc and /var, a systemd unit, and controlled Docker resources. The current user can inspect Docker metadata, but that does not provide the required account, systemd, filesystem, and secret-file isolation.

### Execution result

**FAIL / BLOCKED — no staging resources were created.**

No account, directory, environment file, Docker network, Docker volume, PostgreSQL container, database, role, Flyway migration, systemd unit, Nginx route, Tailscale route, or GDB process was created or changed. No existing GDC2.0, GDCLab, GT2.0, Nginx, Docker, Tailscale, or database resource was modified.

### Required resolution

The owner must provide one of the following before a new execution attempt:

1. A supervised SSH operator with the minimum required sudo capability for only the explicitly named GDB staging resources; or
2. An approved, pre-staged privileged execution mechanism that preserves the exact account, path, service, Docker, and secret isolation in the Phase 3B.4 runbook.

Do not substitute a user-level service, reuse the gutta account, reuse existing volumes/networks, embed secrets in command arguments, or grant broad privileges merely to proceed. After the privilege issue is resolved, repeat the read-only conflict check before resuming. Gate 2, Gate 3, tablet enrollment, HTTPS publication, backup/recovery, and Phase 3C remain unauthorized.

## 12. Privileged execution recovery attempt

A supervised IDE terminal was opened for the owner-authenticated SSH workflow. The terminal could not resolve the local rwp-server SSH alias and returned a hostname-resolution error before connecting. The controlled command runner can resolve the alias, but it cannot safely perform interactive sudo authentication. No password prompt was reached, requested, captured, stored, or transmitted.

**Final Gate 1 execution result: FAIL / BLOCKED before mutation.**

## 13. Gate 1 resumed continuation preparation

The owner supplied the following current state on 2026-11-02:

- Batch 1 read-only baseline: PASS.
- Batch 2 staging account and filesystem: PASS; the application root is
  `/opt/gdb-staging` because `/home/gutta` parent permissions were not changed.
- Batch 3 dedicated Docker network and volume: PASS; `gdb-staging-private` is
  an internal bridge and `gdb-staging-postgres-data` is a local volume.
- No GDB PostgreSQL container had been created at the supplied checkpoint.

These are owner-reported results pending the next manual terminal output. No
Batch 4–7 result is recorded as passed here.

The local continuation script
[`deployment/gdb-staging-gate1.sh`](../../deployment/gdb-staging-gate1.sh)
was syntax-checked locally. It uses the approved PostgreSQL 18.6 image,
loopback port 55433, the verified `/var/lib/postgresql` PostgreSQL 18 mount,
the two approved database roles, the migration-only Flyway step, the
loopback-only backend on 8192, and no gateway or route changes. It generates
secrets only on the server in restricted files and never prints them. The
script is a command handoff, not evidence that any of its server batches have
executed.

The next safe action is for the owner to run, in the already authenticated
interactive SSH terminal, `bash deployment/gdb-staging-gate1.sh preflight`
and return its non-secret output for review. Only after that output is
reviewed should `batch4`, `batch5`, `batch6`, and `batch7` be run separately.

The approved staging-only rollback subcommand is `rollback`; it stops only the
GDB staging backend and PostgreSQL container and preserves logs without
deleting the database, volume, network, or secret files. No rollback has been
executed.

## 14. Gate 1 Batch 4 networking remediation — prepared, not executed

The owner reported that the PostgreSQL container is healthy and accepting
connections, but its Docker host-port metadata is `{"5432/tcp":null}` rather
than the requested loopback publication. A fresh read-only assessment found:

- `gdb-staging-postgres` is PostgreSQL 18.6, healthy, and attached only to the
  internal bridge `gdb-staging-private` (`172.20.0.0/16`).
- The Ubuntu host has a route to the container address `172.20.0.2` through
  the Docker bridge, and a TCP-only probe to `172.20.0.2:5432` succeeded.
- Direct container-IP use is not selected for the Spring Boot backend: the
  address is container-assigned and is not a stable endpoint across a
  container recreation. The host does not receive Docker's internal service
  DNS as a stable systemd resolver contract.
- `gapps-postgres` remains separate, healthy, and mapped only to its existing
  `127.0.0.1:5432`; no GApps object was changed.

### Recommended correction

Use a dedicated TCP proxy rather than attaching PostgreSQL to another bridge:

| Resource | Exact value | Purpose |
|---|---|---|
| Proxy container | `gdb-staging-postgres-proxy` | Host-to-internal TCP bridge |
| Proxy image | `haproxy:3.2.26-alpine`, executed by the image’s non-root `haproxy` user and pinned to the pulled repository digest | Minimal TCP forwarding |
| Existing internal network | `gdb-staging-private` | Proxy-to-PostgreSQL path only |
| New standard bridge | `gdb-staging-proxy-edge` | Dedicated proxy attachment; no other containers permitted |
| Host binding | `127.0.0.1:55433 -> proxy:5432` | Backend loopback connection only |
| Proxy limits | 0.25 CPU, 128 MiB RAM, no swap, 64 PIDs | Conservative staging envelope |

The PostgreSQL container remains attached only to `gdb-staging-private` with no
host port. The proxy is the only container attached to the new standard
bridge. The proxy has a read-only filesystem, tmpfs `/run` and `/tmp`, all
Linux capabilities dropped, and `no-new-privileges` enabled. Its configuration
uses Docker DNS for `gdb-staging-postgres:5432`, TCP health checks, and a local
HAProxy stats socket for non-sensitive backend-state verification.

The local continuation script now contains a separate
`batch4-network-fix` command. It performs a read-only assessment first,
refuses pre-existing proxy names/configuration, preserves the PostgreSQL
container and volume, and records bounded non-secret proxy diagnostics on
failure. It does not run migrations or start the backend.

### Owner approval and rollback

Owner approval is required for the new proxy container, the
`gdb-staging-proxy-edge` bridge, and the exact HAProxy image digest. After
approval, the only new resources should be those two Docker objects and the
root-owned configuration file `/etc/gdb-staging/haproxy-postgres.cfg`.

The existing `rollback` command now stops the proxy before stopping the
PostgreSQL staging container and preserves proxy/PostgreSQL logs. It does not
delete the proxy network, PostgreSQL network, volume, database, or credentials.

No networking remediation, proxy creation, migration, or backend start has
been executed.

## 15. Batch 4 network-fix execution result — PASS

The owner manually executed the reviewed script after the expected hash check
passed. The output demonstrated:

- Direct host-to-container TCP reachability over `gdb-staging-private`.
- HAProxy image digest resolution:
  `haproxy@sha256:5893840576071e6217cd52ebdcc14f454c6f7a631056fa6f4e0f40d3cac4215b`.
- Successful localhost TCP proxy connectivity.
- HAProxy reported the internal PostgreSQL backend `UP`.
- The script’s failure trap preserved proxy diagnostics under
  `/var/log/gdb-staging`.

The initial execution stopped after the substantive checks because a
display-only `ss | grep` expression used an over-escaped word-boundary pattern
and returned status 1 under `set -e`. This was corrected without recreating
resources. Batch 5 was not run.

The script was corrected locally to use a whitespace-safe listener expression
and now provides a read-only `batch4-network-verify` command. The corrected
script hash is:

~~~text
7774b642513ee1434757eea04e471c5ec930fadbd059300b2155de25138d83c9
~~~

Copy the corrected script only after review, verify this hash, and run:

~~~bash
bash /tmp/gdb-staging-gate1.sh batch4-network-verify
~~~

The owner then ran the corrected verification command successfully. Verified
output included:

- `127.0.0.1:55433` listening through `docker-proxy`.
- Proxy container `gdb-staging-postgres-proxy` running and healthy.
- HAProxy image digest
  `haproxy@sha256:5893840576071e6217cd52ebdcc14f454c6f7a631056fa6f4e0f40d3cac4215b`.
- Proxy running as non-root user `haproxy`.
- Script result: **Batch 4 networking verification PASS**.

The verification command did not recreate or modify the proxy, PostgreSQL
container, networks, volume, database, or existing GApps resources.

**Gate 1 Batch 4 networking status: PASS. Batch 5 remains unauthorized in this
record and has not been executed.**

## 16. Batch 5 bootstrap result — recovery completed

The owner subsequently invoked the original `batch5` command. It stopped at
the bootstrap step with:

~~~text
ERROR: role "gdb_staging_migrator" already exists
~~~

No Batch 5 migration was rerun by that failed invocation. A staging-only
metadata check then verified:

- Both approved roles already exist with `NOSUPERUSER`, `NOCREATEDB`,
  `NOCREATEROLE`, and `NOREPLICATION` attributes.
- `gdb_staging` is owned by `gdb_staging_migrator`.
- `gdb_core.flyway_schema_history` exists and the successful migration version
  is `3`.
- Runtime schema/table/function privileges are present (`t|t|t`).
- A transaction-scoped runtime `CREATE TABLE` probe was rejected with
  `permission denied for schema gdb_core`.
- PostgreSQL and the proxy remain healthy; port 55433 remains loopback-only.
- No Batch 5 environment files currently exist under `/etc/gdb-staging`.
- No bootstrap SQL file remains under `/etc/gdb-staging`.

The original Batch 5 command was non-idempotent for the already-migrated
staging database and was not rerun. The bounded recovery path preserved
Flyway version 3, rotated only the two existing staging role passwords,
recreated the restricted environment files, and repeated runtime privilege
checks without rerunning Flyway or altering application data.

The corrected local script hash is:

~~~text
f1a44496ba4ad58f93463747f94c37b0bef482b35885d153a3808bcceb167fa6
~~~

The owner then repaired the Flyway-disabled environment guard and ran
`batch5-verify`. The actual result was:

~~~text
GATE1: Batch 5 recovery verification PASS; credentials, environment files, Flyway version 3, and runtime DDL isolation verified
~~~

**Gate 1 Batch 5 status: PASS.** No backend service was started. Batch 6,
tablet enrollment, HTTPS publication, backup/recovery, Gate 2, Gate 3, and
Phase 3C remain stopped/unauthorized.

## 17. Gate 1 Batch 6 backend result — PASS

The owner executed the authorized Batch 6 command. Verified output:

- `gdb-staging-backend.service` was enabled and started.
- `ActiveState=active`, `SubState=running`.
- Main process ran as `gdb-staging:gdb-staging`.
- Backend health endpoint returned HTTP 200 on loopback.
- No Nginx, Tailscale, gateway, GDC, GDCLab, GT2.0, or existing application
  service was changed.

**Gate 1 Batch 6 status: PASS.** Batch 7 comprehensive verification,
administrator/browser access, tablet enrollment, HTTPS publication,
backup/recovery, Gate 2, Gate 3, and Phase 3C remain stopped pending separate
authorization.

## 18. Gate 1 Batch 7 final verification — PASS

The owner executed the read-only final verification successfully. Evidence
included:

- PostgreSQL container running and healthy.
- PostgreSQL proxy published only on `127.0.0.1:55433`.
- Backend listener on `[::ffff:127.0.0.1]:8192`, an IPv4-mapped loopback
  address accepted by the verifier.
- Ports 8193 and 8194 remained unbound.
- Existing GDC, GDCLab, GT2.0, Nginx, Docker, containerd, and Tailscale units
  remained active.
- PostgreSQL container usage was approximately 3.83% CPU and 38.29 MiB of
  its 768 MiB limit.
- Host available memory was approximately 4.9 GiB and root disk free space
  approximately 82 GiB.
- Backend MainPID was 107893 with recorded memory and CPU counters.

The verification reported:

~~~text
GATE1: Gate 1 verification complete; tablet, HTTPS publication, backup, Gate 2, Gate 3, and Phase 3C remain out of scope
~~~

**Isolated GDB staging Gate 1: PASS.** No tablet enrollment, HTTPS route
publication, backup operation, existing application modification, Gate 2,
Gate 3, or Phase 3C operation was performed.

## 19. Gate 2 staging functional acceptance — PASS — 2026-11-02

The owner authorized and executed the corrected loopback-only Gate 2
acceptance harness against synthetic staging data. The reviewed harness hash
was:

~~~text
fff92104d94318374d8fef2539876f7ae0df472626a754413ebb9e836256eed9
~~~

Before the final run, the failed harness's synthetic administrator recovery
was separately authorized and completed in a guarded transaction:

- Exactly 2 gate2admin session rows were removed.
- Exactly 1 gate2admin administrator row was removed.
- Bootstrap eligibility was restored.
- Application, tenant, device, synchronization, snapshot, tombstone, and
  audit baselines were preserved.
- The post-recovery read-only verification returned backend HTTP 200,
  database gdb_staging, role gdb_staging_migrator, administrator/session
  counts 0/0, application/tenant/device counts 1/1/2, synchronization
  counts 3/3/3, snapshots 0, events/tombstones 2/0, and audit counts
  7/5/3.

The corrected harness then completed successfully with the following actual
evidence:

- Backend health: HTTP 200.
- Administrator bootstrap, login, session, dashboard: 201, 200, 200,
  200.
- Application, tenant, enrollment-code, and two-device lifecycle: successful;
  one-use enrollment-code reuse rejected with HTTP 401.
- Unauthenticated synchronization rejected with HTTP 401; authenticated
  device status returned HTTP 200.
- Initial event accepted; stable cursor replay returned no events.
- Duplicate event returned DUPLICATE.
- Concurrent update winner returned ACCEPTED; stale update returned
  CONFLICT.
- Tombstone accepted and later pulled with the update.
- Cross-tenant forged push rejected with HTTP 403.
- Isolated tenant pull returned no events.
- Snapshot upload/download succeeded; checksum matched
  323bbd00af394fe82cf82986c08c211940cd2476df77d96484277ed920159613.
- Cross-tenant snapshot access rejected with HTTP 404; invalid checksum
  rejected with HTTP 400.
- Device credential rotation invalidated the old credential with HTTP 401;
  the rotated credential succeeded, and revocation subsequently returned
  HTTP 401.

The owner-run harness reported:

~~~text
GATE2 PASS: loopback staging functional acceptance completed with synthetic data
~~~

**Gate 2 loopback staging functional acceptance: PASS.** This result covers
the GDB backend and staging PostgreSQL API behavior only. Physical Android
offline/online acceptance, private HTTPS publication, live Tailscale policy
testing, off-server backup/recovery, Gate 3, and Phase 3C remain unexecuted
and unauthorized. Existing GDC, GDCLab, GT2.0, Nginx, Tailscale, production
databases, and existing GApps resources were not modified.

## 20. Owner direction update — Gate 3 deferred; core platform priority

The owner has discontinued the separate Gate 3 MacBook off-server backup and
independent restore workflow. This is a project-direction decision, not a
claim that disaster recovery passed.

Current status:

- **Gate 1:** isolated GDB staging established and final verification passed.
- **Gate 2:** synthetic loopback functional synchronization acceptance passed.
- **Gate 3:** off-server disaster recovery **DEFERRED by owner decision**.
- **MacBook backup destination:** cancelled; no backup artifact or encryption
  key was created.
- **Next development/testing priority:** physical Android tablet
  synchronization and secure private connectivity to the GDB staging service.
- **Production readiness:** not authorized.

No SSH, server, database, Docker, Nginx, Tailscale, or existing GApps service
operation was performed for this rollback. The GDB staging server remains a
single point of failure while independent disaster recovery is deferred.
Gate 3 planning references elsewhere in the repository are historical or
deferred requirements; they must not be interpreted as executed backup or
restore evidence.
