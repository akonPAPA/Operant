# Data Authority Model

## Source-of-truth

ERP, 1C, accounting, warehouse, Excel bases, and client databases are external source-of-truth by default.

## OrderPilot authority

OrderPilot owns:

- controlled operational mirror;
- workflow state;
- import staging and validation reports;
- suggestions and validation outcomes;
- audit trail.

## Non-negotiable controls

- AI worker has no direct business DB write path.
- Frontend has no DB access.
- Tenant-owned tables include `tenant_id`.
- Imports stage before apply.
- Important changes emit audit events where implemented.
- External writes are not implemented in Stage 2.
- Future external writes require ChangeRequest, approval, transaction service, audit event, and outbox event.