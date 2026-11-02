# GDB private Tailscale Services — review template

This is a design template only. It does not modify a tailnet, ACL policy, or live Serve configuration.

The recommended private routing uses two separately approved Tailscale Services rather than assuming a second per-node hostname:

| Service | Local target | Intended clients |
|---|---|---|
| `svc:gdb-admin` | `127.0.0.1:8093` | administrator group only |
| `svc:gdb-sync` | `127.0.0.1:8094` | approved managed test-tablet tag only |

The existing node root that serves GT2.0 remains unchanged. Tailscale Services provide a distinct MagicDNS name/TailVIP and can advertise an HTTPS endpoint to a local service. The owner must verify plan availability, service approval, hostname, and ACL/grant syntax before use.

Illustrative endpoint commands, to be reviewed and executed only after separate authorization:

```text
tailscale serve --service=svc:gdb-admin --https=443 127.0.0.1:8093
tailscale serve --service=svc:gdb-sync --https=443 127.0.0.1:8094
tailscale serve status
```

Illustrative least-privilege policy intent:

```text
administrator identities  -> svc:gdb-admin:443
tag:gdb-sync-device       -> svc:gdb-sync:443
tag:gdb-sync-device       -X-> svc:gdb-admin, GDC, GDCLab, GT2.0, PostgreSQL
```

The GDB application still authenticates administrators and devices. Tailscale policy is network authorization only and must not be treated as GDB tenant or device authentication.

Reference capability documentation:

- [Tailscale Services](https://tailscale.com/docs/features/tailscale-services)
- [Tailscale Serve CLI](https://tailscale.com/docs/reference/tailscale-cli/serve)
- [Android installation and MDM](https://tailscale.com/docs/install/android)
- [Auth keys](https://tailscale.com/docs/features/access-control/auth-keys)
- [Key expiry](https://tailscale.com/docs/features/access-control/key-expiry)
- [Grants and ACLs](https://tailscale.com/docs/features/access-control/acls)
