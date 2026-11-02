# Multi-device synchronization verification

GDB uses one generic server event log per registered application and explicit application/tenant/device scope. Each device owns its local SQLite/SQLCipher database, durable outbox, applied-event markers, and cursor. The GDB client exposes `LocalOutboxStore` so an integrating GApp can commit local changes, pulled events, and cursor advancement in one local transaction.

## Evidence completed locally

`client/typescript/src/multi-device.integration.test.ts` runs two independent stores against a deterministic in-process GDB protocol model. It verifies:

- device A's create is pulled by device B;
- device B's update is pulled by device A;
- the two stores converge on the same entity version and payload;
- each device advances its own cursor independently;
- an outbox is empty after acknowledged delivery; and
- a stale concurrent update is retained as a conflict while the accepted server event is applied locally.

This is a reusable-client protocol simulation, not evidence of PostgreSQL or physical-device acceptance. The real Spring Boot/Testcontainers suite performs the corresponding server checks when Docker or another disposable PostgreSQL runtime is available.

## Integrating GApp responsibility

GDB does not implement a business-specific SQLite adapter. The GApp must provide the `LocalOutboxStore` transaction, local entity/version application, conflict presentation, durable cursor persistence, and offline retry scheduling. Until that adapter exists, snapshot and sync behavior can be verified only at the generic client/backend protocol boundary.
