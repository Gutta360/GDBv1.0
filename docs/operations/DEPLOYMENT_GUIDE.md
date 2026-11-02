# Deployment guide

Deployment target is conceptually `/home/gutta/gdb` or `/srv/gdb` on `rwp-server`; this repository does not create that path or modify existing GDC/GDCLab/GT2.0 services. The proposed production backend port is `8092`, with loopback Nginx gateways on `8093` (admin) and `8094` (sync), all requiring final conflict checks. GDB Admin is a centrally hosted Angular web application at a private HTTPS URL, not a device-installed APK.

## Required preparation

1. Provision a dedicated PostgreSQL database and least-privilege role for GDB. Do not reuse a GApp database or schema.
2. Create a protected, independently backed-up snapshot directory.
3. Generate the bootstrap administrator credential and a base64 32-byte `GDB_SNAPSHOT_MASTER_KEY` out of band; use the bootstrap credential only for initial provisioning or emergency recovery.
4. Store environment values in root-readable secret configuration, not Git.
5. Rehearse with a fresh PostgreSQL database and a private HTTPS/Tailscale path.
6. Confirm the intended port, firewall, Tailscale ACL, storage capacity, backup destination, and rollback procedure.

Review [GDB Admin hosting](../architecture/GDB_ADMIN_HOSTING.md), the [read-only server preflight](../deployment/RWP_SERVER_PREFLIGHT.md), and the [controlled deployment plan](../deployment/DEPLOYMENT_PLAN.md) before requesting any server mutation.

## Build and run

```bash
cd backend
mvn clean package
java -jar target/gdb-backend-1.0.0-SNAPSHOT.jar
curl -fsS http://127.0.0.1:8092/actuator/health
```

Use [deployment/systemd/gdb.service.example](../../deployment/systemd/gdb.service.example), [deployment/nginx/gdb.private.conf.example](../../deployment/nginx/gdb.private.conf.example), and [deployment/systemd/gdb-healthcheck.example.sh](../../deployment/systemd/gdb-healthcheck.example.sh) only as reviewed templates. They intentionally contain no production secrets. The example Docker Compose file is for isolated development, not automatic production deployment.

## Backup/upgrade/rollback

Back up PostgreSQL and the snapshot directory to independent storage before upgrades. Stop the GDB service, take a database backup, deploy the immutable artifact, let Flyway apply forward migrations, verify health and API smoke tests, then restore traffic. Roll back by stopping the service, restoring the tested artifact and—only if a migration is explicitly reversible—following the approved database rollback plan. Never use `git reset --hard` or overwrite a live database as a rollback method.

No Nginx, Tailscale, firewall, existing database, or running application should be changed without explicit approval. See [ROLLBACK_PLAN.md](../deployment/ROLLBACK_PLAN.md) for the controlled recovery sequence.
