# OrderPilot Core V1 Threat Model

## Scope

This threat model covers OrderPilot Core v1 through Stage 10 hardening. It is security evidence for pilot readiness, not production certification. Stage 9B integration execution remains demo-only.

## Trust Boundaries

- Frontend boundary: the Next.js dashboard is an API client only. It must not access the database directly or make authority decisions that bypass backend services.
- Tenant boundary: tenant identity is enforced in backend services and repository queries. Cross-tenant reads or execution are treated as security defects.
- Tenant isolation is a primary control: every tenant-owned read or mutation must include tenant ownership checks.
- Bot boundary: customer messages and external chat identifiers are untrusted. They are correlation evidence, not authenticated customer identity.
- AI worker boundary: AI may extract, classify, suggest, summarize, and rank. It must not directly mutate business tables or approve actions.
- Connector boundary: Stage 9B allows only the in-process Demo ERP adapter. Production ERP/1C connectors and network writes are disabled.
- ChangeRequest boundary: external-write intent is represented by tenant-scoped `ChangeRequest` records. Stage 9B execution uses ChangeRequest state, not `ConnectorCommand`.
- Audit boundary: sensitive mutations and denied execution attempts must emit audit events. Audit events are append-only by service convention.
- Credential boundary: raw credentials, tokens, webhook secrets, and raw idempotency seeds must not appear in DB fields, API responses, UI, logs, tests, or docs.
- Data boundary: no direct DB writes are allowed from frontend, bot, AI worker, or connector surfaces; approved backend application services own business mutations.

## Key Threats And Controls

### Cross-Tenant Execution Or Data Leak

Threat: tenant A reads or executes tenant B data.

Controls:
- Tenant-scoped repository methods are used in analytics, review, bot handoff, reconciliation, and integration services.
- `ChangeRequestService` loads executable requests by `id` and current tenant.
- Stage 9B tests verify tenant B cannot execute tenant A ChangeRequest.

### Connector Policy Bypass

Threat: a non-demo target or unapproved request reaches connector execution.

Controls:
- Stage 9B execution requires approved `ChangeRequest`.
- Only `DEMO_ERP` target and draft quote/order source types are executable.
- Non-demo targets emit `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`.
- No `ConnectorCommand` is created by Stage 9B demo execution.

### Idempotency And Replay

Threat: repeated execution creates duplicate external references or duplicate connector actions.

Controls:
- Executed ChangeRequests replay the existing external reference.
- Replay audit contains `replay:true` and `networkCall:false`.
- Connector execution idempotency is stored and displayed as `sha256:*` hash only.
- Targeted tests verify no second sync event and no ConnectorCommand on replay.

### Raw Secret Or Raw Key Exposure

Threat: secrets or raw idempotency seeds leak through code, DB, API, UI, logs, tests, or docs.

Controls:
- Stage 9B credential status is placeholder-only.
- No real connector credentials are stored.
- `connectorIdempotencyKeyHash` is the API/frontend contract.
- The DB column `connector_idempotency_key` currently stores only `sha256:*` hash values.

TODO: rename `connector_idempotency_key` to `connector_idempotency_key_hash` in a later cleanup migration when migration compatibility can be handled deliberately.

### Prompt Injection

Threat: document/message text instructs AI or operators to bypass policy.

Controls:
- AI output is advisory only.
- Bot and AI cannot approve quotes, orders, discounts, substitutes, inventory changes, or connector execution.
- Backend validation, review, approval, and ChangeRequest gates remain authoritative.

### Production Connector Activation Risk

Threat: production ERP/1C writes are enabled without security acceptance.

Controls:
- Stage 9B is `DEMO_ONLY`.
- Real ERP/1C writes and external connector network calls remain out of scope.
- Production activation requires separate security/runbook acceptance, secret-manager custody, idempotency review, rate limiting, monitoring, and rollback/compensation plans.
