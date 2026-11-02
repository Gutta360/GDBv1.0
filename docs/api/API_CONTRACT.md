# API contract

Base path: `/api/v1`. JSON uses camelCase and UTC ISO-8601 instants. Errors are `{code,message,timestamp,details}`. Payload bodies and credentials are not echoed in errors.

## Admin

- `POST /auth/admin/bootstrap` — one-time initial administrator provisioning using the bootstrap credential; returns an HttpOnly session cookie.
- `POST /auth/admin/login` — authenticated administrator login; returns an expiring HttpOnly session cookie.
- `GET /auth/admin/me`, `POST /auth/admin/logout`, `POST /auth/admin/sessions/revoke-all` — session lifecycle endpoints.

- `POST /admin/applications` — register `appCode`, display name, version metadata, permitted datasets, and snapshot policy.
- `GET /admin/applications` — list applications.
- `POST /admin/applications/{id}/status?value=ACTIVE|INACTIVE` — change application status.
- `POST /admin/tenants` — create tenant with `appId`, code, and name.
- `GET /admin/tenants` — list tenants.
- `POST /admin/tenants/{tenantId}/enrollment-codes` — return a one-use expiring raw code once.
- `GET /admin/devices`, `POST /admin/devices/{deviceId}/revoke`, and `POST /admin/devices/{deviceId}/rotate` — lifecycle operations. Rotation returns a new raw credential once.
- `GET /admin/monitoring/dashboard`, `/admin/conflicts`, `/admin/snapshots`, `/admin/audit` — operational views.

Normal admin requests require the expiring administrator session cookie and must stay on a private network path. `X-GDB-Admin-Token` is accepted only before the first administrator is provisioned, for controlled bootstrap/emergency recovery.

## Device

- `POST /devices/register` — public bootstrap endpoint; consumes a code and returns one raw device token.
- `POST /sync/push` — bounded idempotent event upload.
- `GET /sync/pull` — tenant-scoped incremental event stream.
- `GET /sync/status` — server sequence, conflict count, and device last-sync metadata.
- `POST /snapshots` — base64 payload upload with format, byte length, SHA-256, and record-count metadata.
- `GET /snapshots` and `GET /snapshots/{id}` — authorized metadata and encrypted-at-rest payload download.

## Status mapping

`401` missing/invalid credential, `403` scope mismatch/revoked device, `404` authorized resource not found, `409` identity reuse, `413` configured size limit, `400` malformed request, and `5xx` retryable infrastructure failure.
