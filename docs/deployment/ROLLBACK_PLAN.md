# GDB rollback plan

Rollback is a controlled operation and requires approval. It must not touch GT2.0, GDC, GDCLab, existing databases, or unrelated services.

1. Stop accepting new GDB traffic through the private proxy.
2. Preserve service logs, audit records, Flyway history, and the current release metadata.
3. Stop only the GDB systemd unit if it is running.
4. Restore the previously approved GDB JAR and Angular release from the immutable release directory.
5. If the release changed the database, follow the migration-specific rollback decision. Prefer a tested forward fix; never run an unreviewed destructive SQL rollback.
6. Restore PostgreSQL and encrypted snapshot data only from a separately verified backup when data recovery is required.
7. Start GDB, verify health and API smoke tests, then restore private proxy traffic.
8. Record the incident, restored artifact, database state, backup identifiers, and follow-up action.

The deployment templates do not perform rollback automatically. A database backup and snapshot-directory backup must exist before every approved upgrade.
