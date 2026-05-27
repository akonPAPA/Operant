# Data Authority Model

OrderPilot treats client/source-system data as mirrored operational context, not as data that can be silently overwritten.

## Authority Rules

- Core-api owns trusted business mutations.
- Frontend reads and submits commands through core-api only; it never connects to PostgreSQL directly.
- AI worker output is advisory and must not write business tables directly.
- Chatbot, bot, channel, and connector layers must not write business tables directly.
- Imported data must pass through staging, validation, and explicit activation.
- Important mutations must record audit events.
- Tenant isolation is mandatory for every read and write.

## Stage 2 Import Authority

CSV imports create `import_job` and `import_staging_row` records first. Validation creates `validation_report` and `import_validation_issue` records. Activation writes operational mirror tables only after validation succeeds.

This protects downstream stages: omnichannel intake, validation, substitution, quote/order workspaces, and analytics consume a stable tenant-scoped data mirror instead of untrusted raw files.
