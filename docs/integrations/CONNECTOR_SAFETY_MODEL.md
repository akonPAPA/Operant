# Connector Safety Model

Stage 9B keeps connector execution demo/local-only. The active execution mode is `DEMO_ONLY`; production ERP/1C writes are disabled.

Every future external write must require:

- tenant-scoped `ChangeRequest`;
- validation-backed approved draft quote/order source;
- operator approval;
- stable connector idempotency key hash;
- connector audit event;
- retry/failure metadata;
- secrets manager credential reference, not a database or plaintext environment secret.

Connector capabilities are explicit: `READ_CUSTOMERS`, `READ_PRODUCTS`, `READ_INVENTORY`, `CREATE_DRAFT_QUOTE`, `CREATE_DRAFT_ORDER`, and `FETCH_STATUS`. In Stage 9B, create capabilities route only to the in-process Demo ERP adapter.

Bot-only handoffs and non-validation-backed cases are not eligible for connector ChangeRequests.

Stage 9B stores and returns connector execution idempotency as a `sha256:*` hash. Raw idempotency key seeds must not appear in database fields, API responses, frontend rendering, audit metadata, logs, tests, or docs. Demo ERP replay audits use the stored hash and existing external reference to prove reuse without a second external execution.

The database column `connector_idempotency_key` currently stores hashed values only, never raw idempotency seeds. API and frontend contracts expose this as `connectorIdempotencyKeyHash`. TODO: rename the database column to `connector_idempotency_key_hash` in a later cleanup migration when migration compatibility can be handled deliberately.

Verification note: sandboxed Maven may fail while resolving Maven Central artifacts if network access is restricted. In that case, backend verification is blocked until dependency resolution is approved; frontend `npm.cmd run lint`, `npm.cmd run test`, and `npm.cmd run build` remain the expected dashboard verification commands.
