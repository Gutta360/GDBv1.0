# GDB v1.0 — RWP Server Phase 3B Read-Only Preflight

Inspection date: 2026-11-01 (Asia/Kolkata)

## 1. Executive summary

**Phase 3B assessment: CONDITIONAL PASS.** The authorized read-only SSH inspection completed successfully. The measured host has enough apparent capacity for a carefully isolated GDB installation, and port 8092 was unbound at inspection time. Docker, PostgreSQL 18, Nginx, systemd, and Tailscale are operational.

This is not deployment approval. Existing routing and database infrastructure cannot be reused casually: Nginx's existing `/api/` route belongs to GDC, Tailscale Serve currently sends its private HTTPS root to GT2.0 on port 8091, and PostgreSQL is an existing GApps container with a persistent volume. GDB requires a separately reviewed hostname/route, database/role/storage isolation, backups, and a deployment authorization.

Phase 3A remains an explicit blocker: its four PostgreSQL/Testcontainers scenarios were skipped locally because the development Mac had no compatible container runtime. No production database, service, configuration, or filesystem was modified during this inspection.

## 2. Authorization and inspection boundary

The user explicitly authorized read-only SSH inspection of `rwp-server`, with no sudo or privilege escalation. The following were performed without sudo:

- OS, kernel, CPU, memory, swap, filesystem, uptime, and load inspection.
- Listening TCP port and running systemd service inventory.
- Docker/container and PostgreSQL deployment metadata inspection.
- Nginx version and redacted routing metadata inspection.
- Tailscale version, self status, and Serve routing status inspection.
- Deployment path, volume, backup-directory, and logrotate metadata inspection.

The following were not performed:

- No SQL, `psql` session, database record query, migration, backup, restore, or container shell.
- No secret, environment value, private key, token, credential, or business record access.
- No package installation, configuration write, permission change, firewall/ACL change, service restart, container start/stop, Nginx reload, or Tailscale change.

## 3. Verified server infrastructure inventory

| Item | Verified finding | Assessment |
|---|---|---|
| Operating system | Ubuntu 26.04 LTS (Resolute Raccoon) | Compatible with the proposed Spring Boot deployment. |
| Kernel/architecture | Linux 7.0.0-38-generic, x86_64 | VERIFIED. |
| CPU | 12 logical CPUs | Sufficient in principle for a modest additional service; no load test performed. |
| RAM | 7.2 GiB total, 5.4 GiB available at inspection | Appears feasible but requires monitoring and limits. |
| Swap | 4.0 GiB, unused | Available safety margin; not a substitute for RAM capacity. |
| Root filesystem | 98 GiB total, 11 GiB used, 83 GiB available, 12% used | Appears feasible for initial artifacts; snapshot growth requires retention and backup planning. |
| Uptime/load | 4h59m uptime; load average 0.00/0.00/0.00 | Low pressure at inspection time only. |
| Docker | 29.7.0, active systemd service, overlayfs | Available as a deployment mechanism; no GDB container was created. |
| Java | Not independently queried on host in this inspection | Existing Java services are running; exact default Java version remains NOT VERIFIED. |
| PostgreSQL host package | `psql` absent; systemd PostgreSQL inactive/not-found | PostgreSQL is containerized rather than a native systemd service. |

The resource result is a point-in-time measurement, not a capacity guarantee. Existing GDC, GDCLab, GT2.0, Docker, Nginx, and PostgreSQL workloads remain the priority.

## 4. Active services and port inventory

### Running services

Verified active systemd services include:

- `gdc-backend.service` — `/usr/bin/java -jar /home/gutta/backend-0.0.1-SNAPSHOT.jar`, user `gutta`, PID 2304, port 8080.
- `gdclab-backend.service` — `/usr/bin/java -jar /home/gutta/gdclab-backend-0.0.1-SNAPSHOT.jar`, user `gutta`, PID 2373, port 8090.
- `gt2-backend.service` — `/usr/bin/java ... -jar /opt/gt2/current/gt2-backend.jar`, user `gt2`, PID 2326, port 8091.
- `nginx.service` — active.
- `docker.service` and `containerd.service` — active.
- `tailscaled.service` — active.

All three application units were active with successful current state. `gdclab-backend.service` reported one historical restart in the inspected systemd metadata; the other inspected application/service units reported zero restarts.

### Listening ports

| Port/interface | Observed use | GDB implication |
|---|---|---|
| `0.0.0.0:22`, `[::]:22` | SSH | Existing administration path. |
| `0.0.0.0:80`, `[::]:80` | Nginx HTTP | Existing shared web entry point. |
| `127.0.0.1:5432` | `gapps-postgres` Docker container | Existing private PostgreSQL; do not reuse casually. |
| `*:8080` | GDC backend | Existing application; must remain untouched. |
| `*:8090` | GDCLab backend | Existing application; must remain untouched. |
| `127.0.0.1:8091` | GT2.0 backend | Existing application; Tailscale Serve targets it. |
| `*:8092` | No listener observed | **Available as a candidate at inspection time**, not reserved or approved. |
| Tailscale IPv4/IPv6 `:443` | Tailscale Serve path | Current private HTTPS route targets GT2.0, not GDB. |

No listener was bound or reserved by this inspection.

## 5. PostgreSQL deployment assessment

### Verified deployment

- Docker container: `gapps-postgres`.
- Image tag: `postgres:18`.
- State: running and healthy.
- Port mapping: `127.0.0.1:5432 -> 5432/tcp`.
- Docker Compose project: `postgres`.
- Compose working directory: `/home/gutta/gapps/postgres`.
- Persistent volume: `postgres_postgres_data`, mounted at the container's PostgreSQL data directory through Docker's local volume store.
- Container restart policy: `unless-stopped`.
- Container resource limits: no explicit memory or CPU limit was reported.
- Native host `psql` and systemd `postgresql.service`: unavailable/not active.

The exact PostgreSQL minor/server build and database/role inventory were not queried. No SQL was executed and no production records were accessed.

### Recommendation

GDB should use a dedicated database and restricted role at minimum. The safer deployment option is a separate GDB PostgreSQL container and volume, with loopback-only publication or an internal Docker network, so GDB Flyway and retention operations cannot affect the existing GApps database. Reusing `gapps-postgres` would require a separately approved database/role and isolation review; it is not approved by this preflight.

The host's measured capacity makes an isolated database technically plausible, but storage I/O, database size, backup destination, and retention capacity remain to be designed and approved.

## 6. Nginx and browser-access architecture

### Verified current routing

Nginx 1.28.3 is active and currently configured through `/etc/nginx/sites-enabled/gdc`:

- Listens on HTTP port 80 for the default wildcard server.
- Serves the GDC Angular application from `/var/www/gdc`.
- Routes `/api/` to `http://127.0.0.1:8080`.
- Serves GDCLab at `/gdclab/` from `/var/www/gdclab`.
- Routes `/gdclab/api/` to `http://127.0.0.1:8090/api/`.
- No active Nginx 443 listener was observed in the readable site configuration.

### GDB recommendation

Do not add GDB to the existing global `/api/` route: that route is already GDC's API namespace. Use a dedicated virtual host/private hostname with the repository's same-origin design:

```text
private HTTPS GDB hostname
  ├── Angular static files
  └── /api/ -> loopback GDB Spring Boot backend on candidate port 8092
```

The exact hostname and TLS termination point require a separate route review. No Nginx file was changed or reloaded.

## 7. Tailscale and private HTTPS feasibility

### Verified current state

- Tailscale version: 1.98.10.
- Tailscale is active as `tailscaled.service`.
- The node reports tailnet-only private HTTPS at `https://rwp-server.tail203f6d.ts.net`.
- Current Tailscale Serve status is `/` proxied to `http://127.0.0.1:8091`, the GT2.0 backend.

### Assessment

Private HTTPS is operational, but the current root route belongs to GT2.0. Adding GDB to that root or changing the existing Serve route would risk interference and is outside this phase. GDB could use a separately reviewed Tailscale hostname/route or a dedicated Nginx TLS virtual host, subject to ACL and certificate review. No Tailscale route, ACL, or firewall rule was changed.

## 8. Resource capacity assessment

### Measured facts

- 12 logical CPUs.
- 7.2 GiB RAM, 5.4 GiB available.
- 4 GiB unused swap.
- 83 GiB free on the 98 GiB root filesystem.
- Load average 0.00/0.00/0.00 at inspection time.
- Existing GDB paths `/home/gutta/gdb`, `/srv/gdb`, and `/var/www/gdb` are absent.

### Assessment

The server is **likely capable** of hosting one modest Spring Boot backend and Angular static assets. A separate PostgreSQL service is plausible but must have explicit memory/CPU expectations, storage retention limits, monitoring, and independent backup capacity. The existing PostgreSQL container has no explicit CPU or memory limit, so GDB must not be added until the combined workload is reviewed.

No benchmark, load test, database query, or stress test was performed.

## 9. Proposed independent GDB deployment structure

Use a dedicated location outside the existing GApps hierarchy:

```text
/home/gutta/gdb/ or /srv/gdb/
├── backend/releases/<version>/gdb-backend-<version>.jar
├── frontend/releases/<version>/browser/
├── current -> approved release
├── config/                 # protected environment file location
├── snapshots/              # protected, independently backed up
└── operations/
```

Use a dedicated `gdb` service account, a separate systemd unit, candidate backend port 8092 only after approval, and a dedicated PostgreSQL database/role/container or volume. Do not place GDB under `/home/gutta/gapps`; that path is already used for GApps and the existing PostgreSQL Compose project.

No directory, account, symlink, service, volume, or database was created.

## 10. Backup, monitoring, and rollback

### Verified metadata

- `/home/gutta/gdc-backups` exists and was approximately 28 KiB at inspection time.
- `/home/gutta/gdc-scans` exists and was approximately 1.3 MiB at inspection time.
- `/home/gutta/gapps/postgres` exists as the current PostgreSQL Compose project directory.
- A GDB-specific backup directory does not exist.
- The readable logrotate inventory showed Nginx rotation but no GDB-specific policy.
- Docker data usage could not be measured reliably from the unprivileged inspection account; root filesystem free space was measured.

### Recommendations

GDB requires an independently backed-up PostgreSQL database and encrypted snapshot directory, a health check, service logs with rotation, retention limits, and a tested rollback path. Stopping or rolling back GDB must be independent of GDC, GDCLab, GT2.0, and their databases. No backup, restore, log rotation, or recovery drill was initiated.

## 11. Identified conflicts and risks

| Finding | Classification | Impact/recommendation |
|---|---|---|
| Phase 3A has 4 skipped PostgreSQL tests | BLOCKER | Must be resolved before Phase 3C. |
| Existing Nginx `/api/` routes to GDC | CONFLICT | GDB needs a dedicated hostname or nonconflicting reviewed route. |
| Tailscale Serve `/` routes to GT2.0:8091 | CONFLICT | Do not reuse or alter the existing root route without separate approval. |
| PostgreSQL is an existing GApps container/volume | RISK | Prefer a dedicated GDB container/volume and role. |
| Port 8092 was unbound | VERIFIED / CANDIDATE | Availability is point-in-time; it is not reserved or approved. |
| Existing PostgreSQL has no CPU/RAM limit | RISK | Review combined memory/I/O before adding another database workload. |
| GDB paths and backup policy absent | RISK | Create only during separately approved deployment. |
| `gdclab-backend.service` has one reported restart | OBSERVATION | Monitor existing service stability; do not alter it. |
| V1 admin access uses a bootstrap token | RISK | Keep private and add stronger operator identity controls before broader exposure. |

## 12. Security considerations

- Keep PostgreSQL on loopback/private Docker networking; do not expose it through Nginx or Tailscale.
- Keep GDB outside `/home/gutta/gapps` and use a dedicated service account and database role.
- Use a dedicated private HTTPS hostname/route; do not share GDC's global `/api/` namespace.
- Keep device bearer credentials separate from the administrator bootstrap token.
- Store GDB secrets outside Git and do not print them through systemd, Docker, Nginx, or diagnostics.
- Preserve the existing GDC/GDCLab/GT2.0 service and database boundaries.
- No permissions, ownership, firewall, ACL, Tailscale, Nginx, systemd, Docker, or database changes were made.

## 13. Proposed deployment and rollback sequence

This is a future plan only:

1. Resolve Phase 3A on a disposable PostgreSQL runtime and obtain 7 executed backend tests with zero skips.
2. Approve a dedicated GDB database/role/storage and confirm backup destination and retention.
3. Review a dedicated private hostname/Tailscale or Nginx route without changing the existing GDC/GT2.0 routes.
4. Stage versioned backend and Angular artifacts under the approved GDB path.
5. Obtain separate explicit deployment authorization.
6. Install and start only the GDB unit and any separately approved GDB database container.
7. Verify health, Flyway state, admin access, enrollment, push/pull, snapshot validation, and monitoring in a test tenant.
8. Roll back by withdrawing only GDB traffic, stopping only GDB components, restoring the prior GDB artifact, and following a reviewed migration-specific database recovery plan.

No deployment, migration, service restart, route change, or recovery drill was initiated.

## 14. Outstanding verification items

- Exact host Java version and GDB runtime sizing under the existing workload.
- Exact PostgreSQL minor/server build and nonsecret database/role/storage metadata.
- Independent GDB database/container/volume design and backup destination.
- Private hostname, TLS certificate ownership, Tailscale ACL, and route design.
- Nginx configuration change review without touching existing routes.
- GDB service account, systemd unit, logrotate, monitoring, and rollback review.
- Completion of all four skipped PostgreSQL integration tests.
- Backup/restore rehearsal for GDB PostgreSQL and encrypted snapshots.

## 15. Phase 3C prerequisites

Phase 3C must not begin until:

1. Phase 3A has all 7 backend tests executed with 0 skips and 0 failures.
2. Flyway migration and all PostgreSQL scenarios pass against disposable PostgreSQL.
3. The dedicated GDB database/role/storage and backup design are approved.
4. Port 8092 and the dedicated browser/API route are approved after a nonconflicting review.
5. Private HTTPS/Tailscale/Nginx routing and ACLs are reviewed.
6. Deployment and rollback procedures are reviewed.
7. Separate explicit deployment authorization is granted.

## 16. Final answers

1. **Is the current RWP server likely capable of hosting GDB?** — **Likely, conditionally.** Measured resources are reasonable, but combined workload and storage/backup design still require approval.
2. **Is port 8092 available?** — **Yes at inspection time:** no listener was observed. It is not reserved or approved.
3. **Can GDB safely use an independent PostgreSQL database?** — **Feasible, but not yet approved.** Use a dedicated database/role and preferably separate container/volume; do not reuse the existing GApps volume casually.
4. **Can Angular Admin Portal use a private browser URL?** — **Yes in principle.** Tailscale private HTTPS is operational, but its current root route belongs to GT2.0; GDB needs a separately reviewed hostname/route.
5. **Can existing Nginx and Tailscale support it without interfering with other apps?** — **Yes in principle, not through the current shared routes.** Existing Nginx `/api/` belongs to GDC and Tailscale `/` belongs to GT2.0.
6. **What are the outstanding risks or blockers?** — Phase 3A skipped integration tests, route conflicts, shared existing PostgreSQL infrastructure, unapproved storage/backups, and the V1 bootstrap-token administration model.
7. **What exact prerequisites remain before Phase 3C?** — Complete Section 15; then obtain separate deployment authorization. No Phase 3C work is authorized by this report.

## 17. Stop condition

Phase 3B stops here. The authorized read-only inspection and report update are complete. No Phase 3C activity, installation, deployment, configuration, restart, migration, backup, restore, or production-data access was performed.
