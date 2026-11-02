# Snapshot design

The GApp creates a consistent logical export from its local database, excluding local users and credentials. It supplies a format version, creation timestamp, record counts, exact byte length, and SHA-256 checksum. The reusable client sends the bytes as base64 to `POST /api/v1/snapshots`.

GDB decodes, bounds-checks, verifies the exact-byte checksum, stores the payload under a tenant/snapshot key encrypted with AES-256-GCM, and records metadata in PostgreSQL. Retention is configurable per deployment; the current service applies the global count to available snapshots for a tenant. Downloads verify storage integrity again.

A device snapshot is not consolidated tenant state. A tablet may be incomplete or stale. V1.0 implements creation/upload/validation/preservation/monitoring. Interactive restore, point-in-time reconstruction, replacement-device orchestration, and disaster drills are V2.0 scope.
