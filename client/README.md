# GDB Sync Client

`typescript/src/index.ts` is a platform-neutral reference client. It does not own SQLite, SQLCipher, business validation, or a UI. The integrating GApp supplies a `LocalOutboxStore` implementation that makes local business writes, outbox changes, pulled event application, and cursor advancement durable and atomic.

The client prevents overlapping sync runs, uploads bounded batches, acknowledges only accepted/duplicate events, preserves failed/conflicting events locally, pulls in server sequence order, and provides a snapshot upload adapter.

```bash
cd client/typescript
npm ci
npm test
npm run build
```
