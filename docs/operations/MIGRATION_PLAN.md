# Future GT2.0-to-GDB migration

Migration is not part of V1.0 deployment. GT2.0 remains an independent working system until GDB passes physical acceptance and receives explicit migration approval.

1. Register `GDC` in GDB and create a tenant mapping without changing GT2.0.
2. Add a GDB Sync Client adapter to a test copy of the GDC application. Keep local SQLite patient/outbox transactions intact.
3. Define the GDC `PATIENT` payload contract and a domain-aware conflict policy.
4. Perform a one-time validated export/import only through an approved migration tool; preserve source UUIDs and tombstones.
5. Run two-device convergence, revocation, snapshot, restore, and offline continuity acceptance against an isolated tenant.
6. Operate in a monitored parallel period, then approve any GApp backend retirement separately.

No existing GT2.0 schema, service, credentials, or user data is altered by this project.
