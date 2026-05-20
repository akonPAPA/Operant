# Validation Safety Model

Stage 5 preserves the OrderPilot authority boundary:

- AI output is input, not authority.
- Deterministic rules validate AI-assisted extraction.
- Validation reads extraction and business mirror data.
- Validation does not mutate master business data.
- Validation does not create final quote/order records.
- Validation does not write ERP, 1C, accounting, warehouse, customer, product, inventory, or pricing systems.
- `ApprovalRequirement` is workflow-only in Stage 5.
- Human approval in Stage 5 approves validation decisions only, not external writes.

Any future business mutation must still go through typed command services, authentication, RBAC/ABAC, tenant isolation, deterministic validation, approval gates, transaction service, audit event, and outbox integration when required.
