# GDB V2 roadmap

- Replacement-device enrollment and restore authorization workflow.
- Point-in-time and selective entity restoration.
- Historical reconstruction and conflict resolution approval workflows.
- PostgreSQL disaster-recovery automation, redundant storage, and recovery drills.
- Operator identities, roles, MFA, and credential rotation API.
- Resumable multipart snapshot uploads and richer retention policies.
- Per-application schema-specific projections where a GApp opts in.

V1.0 still requires basic independent backups of PostgreSQL and snapshot storage; deferring the restore interface is not permission to run without backups.
