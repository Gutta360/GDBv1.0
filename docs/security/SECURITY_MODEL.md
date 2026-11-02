# Security model

- Device enrollment codes are random, expiring, single-use values. Only SHA-256 hashes are stored.
- Device bearer tokens are random per installation, returned once, stored only as hashes, and invalidated on revocation.
- Tenant/application/device identity is derived from the authenticated principal, not trusted from client JSON.
- Admin bootstrap token and snapshot master key are environment secrets; no credentials are committed or embedded in a client bundle.
- Snapshot payloads are checksum-verified and encrypted at rest with AES-256-GCM using a random IV and tenant/snapshot associated data.
- Application codes are strictly validated and mapped to a derived schema identifier. Arbitrary SQL identifiers are not accepted.
- CORS is configured from `GDB_ALLOWED_ORIGINS`; the default server binds loopback. HTTPS/private-network termination is an operations responsibility.
- Routine logs and error messages do not contain event payloads, snapshot bytes, or credentials.
- PostgreSQL is accessed by the backend only; GApps do not receive database access.
- Retain and independently back up audit logs, event history, current state, and encrypted snapshot files.

V1.0’s bootstrap token is limited to initial provisioning or approved emergency recovery. Normal administration uses expiring, revocable HttpOnly sessions backed by BCrypt-hashed administrator accounts with `ADMIN` and read-only `AUDITOR` roles. SSO/MFA and full operator account management remain required before broader exposure.
