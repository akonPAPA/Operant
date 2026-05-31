# Channel-to-Quote Security Notes

Stage 12B keeps inbound channels outside trusted business mutation authority.

## Controls

- Tenant context is required for every command and lookup.
- Source ids are resolved with tenant-scoped repositories.
- Conversion requires `CREATE_DRAFT_QUOTE` permission.
- Idempotency prevents duplicate draft creation for the same tenant, source, and key.
- Source traceability is persisted in `quote_source_link`.
- Conversion attempts are persisted in `quote_conversion_attempt`.
- Audit events are emitted through the existing audit event service and include source references, conversion attempt id, actor type, reason codes, and external execution disabled.
- Dry-run and create idempotency are separated by request mode so a preview cannot suppress a later controlled business mutation.
- Selected extraction line ids are accepted only if they belong to the tenant-scoped source being converted.

## AI, Bot, and Channel Boundaries

- AI and extraction results are advisory input only.
- Bots and channels cannot approve quotes, approve substitutions, approve discount/margin risk, create orders, reserve inventory, execute connectors, or write to ERP.
- The frontend can request conversion, but the backend resolves source ownership, customer, line items, validation, review status, and quote creation.

## Payload Handling

Audit metadata stores references, reason codes, actor type, and validation summaries. It must not store full raw customer messages or document content unless a future audit policy explicitly allows redacted payload capture.

## External Execution

All Stage 12B audit metadata marks external execution as disabled. The implementation does not call ERP, 1C, accounting, warehouse, or connector write APIs.
