# Device lifecycle

1. An administrator selects an active application and tenant and calls the enrollment-code endpoint.
2. GDB stores only a hash of a short-lived `GDBE_...` code and returns the raw value once.
3. A GApp submits the code and device metadata to `/devices/register`.
4. GDB consumes the code under a database lock, creates a device record, stores only a hash of a random `GDBD_...` token, and returns the token once.
5. The client stores credentials in its protected local credential facility and uses them for sync.
6. An administrator revokes the device. Subsequent authentication fails, including pull, push, and snapshot access.

Registered and last-seen timestamps are evidence of prior contact, not a live-connectivity guarantee. An administrator can rotate a device credential through `POST /api/v1/admin/devices/{id}/rotate`; the previous token is invalidated and the new raw token is returned once.
