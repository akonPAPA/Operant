# Security and Controlled Write Paths

OrderPilot Core v1 treats security, audit, tenant isolation, and explainability as product architecture.

## Core Pattern

- Controllers accept HTTP input and delegate to services.
- Application services own workflow, policy checks, validation, transactions, and audit.
- Repositories own persistence and tenant-scoped queries.
- AI, bot, frontend, and connectors cannot directly write master business data.
- External writes require a future ChangeRequest/approval path and are out of scope for Stage 9.

## Current High-Risk Paths

- Bot Runtime Lite receives Telegram-style hostile input and can only create internal bot/RFQ/handoff records.
- Reconciliation computes expected stock deterministically and creates auditable reconciliation cases.
- Analytics is read-only and tenant scoped.
- Audit events are appended by service calls and have no public mutation API.

## Production TODOs

- Replace placeholder tenant header with authenticated tenant/user claims.
- Add DB-level audit append-only controls.
- Add signed webhook validation and rate limiting.
- Add CI dependency and secret scanning.
