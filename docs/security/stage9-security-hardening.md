# Stage 9 Security Hardening

Date: 2026-05-23

Stage 9 hardens the approved Stage 1-8 OrderPilot Core v1 surfaces for investor, developer, and early design-partner review. It does not make the system fully production-ready.

## Security Principle

AI suggests. Rules validate. Human approves if risky. Backend writes. Audit records.

## Approved Stage 1-8 Surfaces

- Intake: file upload, API upload, email webhook stub, Telegram local/dev intake, inbound event ledger, documents, messages, and processing jobs.
- Understanding: advisory extraction runs/results, confidence, evidence, and prompt-injection safeguards.
- Validation: deterministic validation runs, issues, approval requirements, and substitute suggestions only.
- Review: operator review cases, grouped issues, internal notes, and review-state actions only.
- Bot runtime: inbound Telegram-oriented runtime, deterministic intent classification, conservative policy decisions, and handoff records.
- Analytics: read-only tenant-scoped operational metrics.

## API Permission Boundary

Core API now recognizes the `X-OrderPilot-Permissions` request header as a lightweight demo-stage permission boundary. When the header is present, the request must include the required permission for the endpoint. When the header is absent, local demo compatibility remains unchanged.

Current permission vocabulary:

- `ANALYTICS_READ`
- `INTAKE_READ`
- `INTAKE_WRITE`
- `EXTRACTION_READ`
- `EXTRACTION_RUN`
- `VALIDATION_READ`
- `VALIDATION_RUN`
- `REVIEW_READ`
- `REVIEW_ACTION`
- `BOT_READ`
- `BOT_ACTION`
- `AUDIT_READ`
- `ADMIN_SETTINGS_READ`

This is not a replacement for production authentication, user sessions, SSO, JWT verification, or full RBAC. It is a Stage 9 guardrail for explicit permission-denial behavior and demo hardening.

## OWASP API Checks

- Broken object-level authorization: tenant-scoped repositories and service lookups must use current `TenantContext`; cross-tenant IDs must return not found or denied.
- Broken function-level authorization: explicit permission header checks cover Stage 1-8 API categories when provided.
- Excessive data exposure: analytics returns counts/statuses, not raw payload bodies, credentials, file contents, or connector secrets.
- Unsafe file upload: uploaded content remains untrusted and is referenced through object storage metadata; production malware scanning and quarantine remain future work.
- Webhook replay/signature risk: local/dev webhook replay checks exist, but production signed-webhook verification and replay windows are not complete.
- Prompt injection and insecure LLM output handling: AI output is advisory only and must not create quotes, orders, ERP writes, customer writes, product writes, price writes, or inventory writes.

## Audit Safety

Audit events are append-oriented by service convention. Normal application services should record new audit events and should not update or delete audit records. A future production hardening task should add database-level append-only controls such as restricted DB roles, WORM storage, trigger protection, or hash chaining.

Audit-critical paths include extraction run lifecycle, validation run lifecycle, review actions, bot message/handoff actions, integration/change-request safety paths, and reconciliation updates. Analytics reads should not spam audit logs.

## Secrets And Sensitive Data

- Do not commit real API keys, bot tokens, connector credentials, private keys, passwords, or customer secrets.
- Connector credential DTOs must expose metadata or secret references only, never raw secret values.
- API responses must not include stack traces.
- Logs must avoid raw uploaded file contents, unrestricted message text, prompt/system instructions, credentials, and connector payload secrets.

## Reliability And Observability

Minimum Stage 9 monitoring targets:

- API error rate and structured error counts.
- Tenant context missing/denied requests.
- Processing job status, failed jobs, and stale jobs.
- Extraction failures and low-confidence extraction counts.
- Validation blocked/needs-review counts and top issue codes.
- Review backlog and escalated cases.
- Bot handoff volume and unknown intent volume.
- Failed webhook and replay events.
- Analytics endpoint latency.

Reliability notes:

- Idempotency is required for imports, webhook intake, and connector/change-request paths.
- Processing jobs need visible failed/stale states and a safe retry story.
- Backup/restore must be documented and exercised before production claims.

## Explicit Non-Scope

Stage 9 does not implement final quote automation, final order automation, ERP/1C/SAP/Dynamics/Oracle writes, connector execution, ChangeRequest execution, production outbound messaging, production WhatsApp/WeChat runtime, paid OCR/LLM integration, autonomous AI approval, Local Windows Connector, or desktop agent behavior.
