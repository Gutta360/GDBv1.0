# Synchronization protocol v1

All device requests use `Authorization: Bearer <device-token>`. The server derives `appCode`, `tenantId`, and `deviceId` from that credential and requires matching body identifiers.

## Event

```json
{
  "eventId":"uuid",
  "entityType":"ORDER",
  "entityId":"uuid",
  "operation":"UPDATE",
  "baseVersion":3,
  "entityVersion":4,
  "payloadVersion":1,
  "payload":{"applicationOwned":"value"},
  "createdAt":"2026-11-01T00:00:00Z"
}
```

`CREATE` requires base 0/version 1. `UPDATE` and `DELETE` require the next entity version and an existing state. DELETE payloads remain application-owned; the operation is the tombstone signal.

## Push

`POST /api/v1/sync/push` accepts `{contractVersion,appCode,tenantId,deviceId,batchId,events[]}`. The default maximum is 100 events. Each event is processed idempotently. The batch ID and full request hash are retained, so reusing a batch with different content returns `409 BATCH_ID_REUSED`.

Results are `ACCEPTED`, `DUPLICATE`, `REJECTED`, `CONFLICT`, or `RETRYABLE_FAILURE`. Only accepted/duplicate results may be removed from a local outbox. A conflict is preserved server-side and must remain visible to the owning GApp.

## Pull

`GET /api/v1/sync/pull?after=<cursor>&limit=<n>` returns accepted events for the authenticated tenant in `server_sequence` order. A client commits all events in a page in one local transaction and advances its cursor in the same transaction. Repeated pages are safe because the client applies event IDs idempotently.

## Offline behavior

The local business transaction and outbox insert are the GApp’s responsibility. If GDB is unavailable, local business operations continue. Retry/backoff and platform scheduling are client responsibilities; a background timer is not assumed to run while Android is suspended.
