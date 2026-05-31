# Quote Review Security Notes

## Boundary Rules

- No ERP/1C production write is introduced.
- No frontend-to-database write path exists.
- No bot or AI actor can directly approve or finalize a quote.
- Quote review commands are tenant-scoped and verify quote, issue, line, and substitute ownership.
- Terminal quote statuses such as `APPROVED`, `REJECTED`, and `CONVERTED_TO_INTERNAL_ORDER` cannot be corrected by review commands.

## Permissions

`/api/v1/quote-review` is registered with the API permission interceptor:

- GET requires `REVIEW_READ`.
- Non-GET requires `REVIEW_ACTION`.

Service-level tenant checks use `TenantContext.requireTenantId()` and command tenant matching.

## Audit

Every mutation writes an audit event with previous/new values, actor, reason code, validation summary, and `externalExecution=DISABLED`.

## Substitute Safety

Substitute selection is allowed only for candidates returned by `ProductSubstitutionService` for the current quote line. Blocked customer substitutes fail. High-risk substitutes are not silently applied; they create approval requirements.

## Residual Risk

Assignment is currently not persisted. If assignment becomes writable later, it must be tenant-scoped and audited with `QUOTE_REVIEW_ASSIGNED`.
