# GDB access model

## Device access

- Enrollment codes are one-use and expire.
- Registration returns a device bearer credential once; GDB stores only its hash.
- Push, pull, status, and snapshot access derive application, tenant, and device scope from the authenticated credential.
- Rotation invalidates the previous credential. Revocation rejects subsequent requests.
- Client JSON cannot widen the authenticated scope.

## Administration access

V1.0 uses the environment-provided bootstrap token only for initial administrator provisioning or explicitly approved emergency recovery. Normal access uses a database-backed administrator account and an expiring, revocable, HttpOnly `GDB_ADMIN_SESSION` cookie. Passwords are stored with BCrypt hashes, session tokens are stored as SHA-256 hashes, and successful/failed login, logout, bootstrap, and session-revocation events are audited.

The initial implementation provides `ADMIN` and read-only `AUDITOR` roles. It does not yet provide SSO/MFA or a full operator account-management UI. The bootstrap endpoint must be disabled or the bootstrap secret rotated out of normal operations after provisioning.

Therefore the approved deployment posture is private HTTPS plus network ACL/Tailscale policy, loopback-only backend binding, secret storage outside Git, expiring session cookies, server-side session revocation, and bootstrap-secret rotation. Broader operator identity, SSO, and MFA remain a future hardening phase.

## Boundary

PostgreSQL remains private and dedicated to GDB. No GDB endpoint connects to an existing GT2.0/GDC/GDCLab database, and no administration credential is shared with a GApp device credential.
