# OrderPilot Core V1 Threat Model

## Scope

This threat model covers the current Core API through Stage 9. It is not a claim of production certification. It documents current controls, tenant isolation assumptions, audit expectations, and gaps before a future pilot launch.

## Assets

- Tenants and tenant-scoped business data.
- Users, roles, permissions, and future authentication claims.
- Product, customer, inventory, price, discount, margin, quote draft, order draft, validation, and reconciliation records.
- Uploaded documents, extracted document text, source evidence, bot messages, and Telegram-style external chat/message identifiers.
- Audit events and operator timeline records.
- Connector credentials and webhook secrets when connectors are added.

## Trust Boundaries

- Frontend to Core API: user-controlled requests must be validated by controllers and application services.
- Bot/webhook to Core API: customer messages are hostile input; external chat IDs are correlation identifiers, not identity.
- AI worker to Core API: AI is advisory and must not write business tables directly.
- Core API to database: all business mutations must go through application services, transactions, tenant filters, validation, and audit.
- Core API to external connectors: no direct ERP writes exist in Core v1 stages so far.
- Database to operators/auditors: audit events are append-only by service convention today; production DB-level immutability is still a TODO.

## Major Threats

### Cross-tenant data exposure

Threat: a query omits `tenant_id` and exposes another tenant's bot, reconciliation, analytics, import, or workspace records.

Mitigations present:
- `TenantContext.requireTenantId()` is used by command/read services.
- Repositories for high-risk flows include tenant-scoped methods.
- Tests cover bot tenant isolation, reconciliation isolation, and analytics isolation.

Gaps/TODOs:
- Replace the current header-based placeholder tenant resolver with production authentication and authorization.
- Add broader controller-level authorization tests when auth exists.

### Prompt injection and unsafe AI output

Threat: documents or messages instruct AI systems to bypass policy or output unsafe business mutations.

Mitigations present:
- AI is advisory, not authoritative.
- Stage 4 output is treated as extraction evidence and suggestions only.
- Stage 5 deterministic validation and Stage 6 internal workflow records mediate downstream actions.

Gaps/TODOs:
- Add production LLM gateway controls before any real LLM calls.
- Add model-output provenance and policy enforcement around future action proposals.

### Bot/webhook abuse

Threat: malformed payloads, duplicate messages, unsupported message types, or excessive request volume create duplicate RFQs or unsafe actions.

Mitigations present:
- Telegram webhook validates required structure and rejects missing text.
- Duplicate tenant/channel/chat/message IDs are rejected through `BotWebhookSecurityService`.
- Bot Runtime Lite cannot approve quotes/orders or mutate inventory, prices, or customers.
- No Telegram API token or external Telegram API call exists.

Gaps/TODOs:
- Add signed webhook verification.
- Add replay windows and per-tenant/IP rate limiting.
- Add bounded request size controls.

### File upload abuse

Threat: malicious files, oversized payloads, or hidden content exploit document processing.

Mitigations present:
- Intake and extraction are separated from authoritative business writes.
- Existing security docs cover file upload validation expectations.

Gaps/TODOs:
- Add file type sniffing, antivirus scanning, size limits, and quarantine storage before production uploads.

### Direct DB mutation bypass

Threat: code or operators mutate master business data directly, bypassing validation and audit.

Mitigations present:
- Controllers delegate to services.
- Stage boundaries require backend command/application services.
- Tests verify bot and reconciliation flows do not mutate product/customer/price/inventory master data.

Gaps/TODOs:
- Enforce least-privilege DB roles in production.
- Require migration review for new write paths.
- No direct DB writes should be allowed outside approved services.

### Audit log tampering

Threat: audit records are updated or deleted after sensitive actions.

Mitigations present:
- `AuditEventService` appends new audit events.
- No public audit mutation endpoint exists.
- Stage 7 RFQ and Stage 8 discrepancy creation emit audit events.

Gaps/TODOs:
- Add database permissions, append-only trigger/policy, and a separate `audit_append_only` DB role.
- Add audit export and retention policy.

### Connector credential leakage

Threat: connector credentials, API keys, webhook secrets, or bot tokens leak through source code or logs.

Mitigations present:
- No real Telegram token is required in Stage 7.
- `scripts/check-no-secrets.ps1` scans for obvious hardcoded secret patterns.

Gaps/TODOs:
- Use a secrets manager before production connectors.
- Add CI secret scanning.

### Replay attacks

Threat: a webhook replay creates duplicate RFQ or handoff records.

Mitigations present:
- Telegram duplicate detection uses tenant/channel/chat/message uniqueness at service level.

Gaps/TODOs:
- Add signed webhook timestamps and replay windows.
- Add persisted webhook idempotency records for every provider.

### Excessive request volume / abuse

Threat: repeated bot/API requests exhaust resources or create noisy workflow records.

Mitigations present:
- Stage 8 reconciliation case listing is paginated.
- Analytics uses tenant-scoped count queries.

Gaps/TODOs:
- Add Redis-backed or gateway-backed per-tenant/IP rate limits.
- Add request body limits and abuse monitoring.
