# GDB Admin hosting model

GDB Admin is a centrally hosted Angular web application accessed by URL. It is not an Android application and must not be packaged as an APK.

The production browser arrangement is:

```text
private HTTPS URL
        |
        v
Tailscale Service: private HTTPS + ACL
        |
        v
Nginx: Angular static files + admin /api and /actuator proxy
        |
        v
GDB Spring Boot on loopback:8092
        |
        v
dedicated private PostgreSQL database
```

The browser uses same-origin `/api/...` requests by default. Local `ng serve` uses `frontend/proxy.conf.json`; an explicit backend URL remains available in Settings for controlled review environments. The portal never embeds a secret in the JavaScript bundle.

The Nginx example in `deployment/nginx/gdb.private.conf.example` is a review template only. It assumes approved Tailscale Services/ACLs and a release directory containing Angular's `dist/.../browser` output. A separate sync-only gateway is intended for the device Service. It does not expose PostgreSQL or bind the backend publicly.

GDB is permanently outside the GApps deployment hierarchy. It must not be placed under `/home/gutta/gapps`, and its future hosting location is a separately approved GDB directory such as `/home/gutta/gdb` or `/srv/gdb`.

## V1.0 operator access

V1.0 uses the private bootstrap token only for initial provisioning or approved emergency recovery. Normal portal access uses an expiring, revocable, HttpOnly `GDB_ADMIN_SESSION` cookie backed by BCrypt-hashed administrator accounts. Roles include `ADMIN` and read-only `AUDITOR`; SSO/MFA remain future hardening. Tailscale access does not replace GDB application authorization.
