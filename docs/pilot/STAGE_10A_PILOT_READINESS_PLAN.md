# Stage 10A Pilot Readiness Plan

## Purpose

Stage 10A prepares OrderPilot for safe design-partner pilot planning without enabling real production actions.

This plan is intentionally conservative. It does not add real AI provider calls, real OCR providers, production credentials, ERP/1C/accounting/warehouse connectors, external write mode, autonomous quote/order approval, or direct business-table writes from AI, chatbot, frontend, or connector components.

## Target Design Partner Profile

Stage 10 should target 3-5 design partners that match this profile:

- B2B auto parts, industrial parts, or adjacent distributors with repeat RFQ/order workflows.
- High message/document volume through Telegram, email, PDF, Excel, or manual operator entry.
- Existing product, customer, inventory, and pricing data can be exported for staging/mirror import later.
- Operational team can provide human reviewers for shadow-mode comparison and correction logging.
- Pilot sponsor agrees that OrderPilot is advisory during Stage 10 and will not write to ERP or customer-facing systems.
- Initial scope can start with non-critical documents and low-risk customer requests.

Avoid design partners that require immediate autonomous ordering, production ERP writes, regulated payment flows, or replacing a live warehouse/accounting process on day one.

## Pilot Entry Criteria

Before a design partner enters pilot:

- Stage 9 local demo remains healthy and repeatable.
- Security baseline and no-secrets checks pass.
- Tenant boundary for pilot data is explicitly assigned.
- Data-sharing agreement or written approval covers any real business documents used in shadow mode.
- Only non-critical documents/messages are selected for the first run.
- Production credentials are not stored in the repo, local `.env`, frontend `NEXT_PUBLIC_*`, or demo fixtures.
- External writes are disabled and documented as unavailable.
- Human reviewer workflow is staffed and documented.
- Correction taxonomy and pilot metrics are agreed before processing real documents.
- Rollback/freeze owner is named for each pilot.

## Pilot Exit Criteria

A design partner may exit Stage 10 shadow mode only after:

- A representative sample of documents/messages has been processed in shadow mode.
- Human correction rate is measured by field, line item, and document/message type.
- Cycle time before/after is measured with the same workflow boundaries.
- Exception categories are stable enough to prioritize product work.
- Draft quote/order readiness rate is known and reviewed.
- Substitution, discount, margin, and validation issue rates are measured.
- ROI report is produced for that client.
- No external write incidents occurred.
- No tenant data leakage occurred.
- No hardcoded secret or production credential is introduced.
- Pilot sponsor accepts a written recommendation for the next stage.

Exiting shadow mode does not automatically allow ERP writes. External write mode needs a later ChangeRequest, approval, transactional outbox, integration audit, and rollback design.

## Data Allowed In Pilot

Allowed:

- Real non-critical RFQs, order requests, quote requests, and product inquiry messages approved for pilot use.
- Historical documents exported by the design partner for shadow-mode comparison.
- Product/customer/inventory/price extracts imported into staging or mirror tables only.
- Redacted or sampled documents when full customer data is not needed.
- Operator corrections, validation outcomes, exception categories, and timing metrics.
- Client-specific ROI assumptions supplied by the design partner.

All imported external data must land in staging or mirror tables first. It must not overwrite trusted source-of-truth systems.

## Data Not Allowed Yet

Forbidden in Stage 10A/initial pilot:

- Production ERP credentials.
- Real Telegram bot tokens or business-reply credentials.
- Real LLM provider keys committed to repo or shared through frontend-visible env vars.
- Payment data, card data, bank credentials, or invoice payment authorization data.
- Highly sensitive HR, payroll, legal, medical, or unrelated personal data.
- Live warehouse mutation data that would reserve, decrement, ship, or invoice inventory.
- Any data that the design partner has not approved for pilot processing.

## Shadow Mode Rules

Shadow mode means OrderPilot predicts, compares, and records learning evidence without becoming the system of record.

Rules:

- Real-like input may be processed only as advisory prediction.
- OrderPilot predictions are compared against human decisions or historical outcomes.
- Human correction is the authoritative answer.
- Corrections are recorded for measurement and later improvement.
- No OrderPilot output may create a final quote, final order, shipment, invoice, payment, ERP record, inventory reservation, customer update, product update, or price update.
- Draft quote/order workflow may stay internal only and must be clearly labeled as not ERP/customer-facing.
- Any future external write must be blocked until a later stage adds ChangeRequest, approval, outbox, and connector controls.

## Human Review Rules

- Every real or real-like pilot item needs a named human reviewer or reviewer queue.
- AI/extraction confidence can prioritize review order but cannot skip review.
- Validation issues, substitutions, discounts, and margin warnings must be reviewed before any draft is treated as ready.
- Human correction must identify what changed: field, line item, customer match, product match, UOM, quantity, price, discount, margin, substitute, exception category, or workflow status.
- Reviewers must mark whether the original request was automatable, partially automatable, or manual-only.
- Reviewers must be able to freeze the pilot for a tenant if data quality, security, or workflow behavior becomes unsafe.

## Correction Tracking Goals

Track correction rates at these levels:

- Document/message level: correct intent and document type.
- Field level: customer, SKU, description, quantity, UOM, requested date, price, discount, currency.
- Line-item level: product match, quantity normalization, substitute candidate, validation status.
- Workflow level: exception category, approval requirement, draft readiness.
- Safety level: prompt-injection warning, unsupported request, external-write attempt, tenant-boundary concern.

Correction data is a learning dataset, not an autonomous approval dataset. It should improve confidence thresholds, validation rules, and reviewer workflow, but it must not bypass human approval in Stage 10A.

## ROI Metrics

Each design partner should receive a pilot ROI report with:

- Documents/messages processed.
- Automation candidate rate.
- Human correction rate.
- Average cycle time before OrderPilot.
- Average cycle time in shadow mode.
- Estimated minutes saved per request.
- Exception category distribution.
- Draft quote/order readiness rate.
- Substitution acceptance rate.
- Discount and margin issue rate.
- Estimated monthly labor time saved.
- Estimated monthly cost saved.
- Key blockers to production readiness.

## Security Boundaries

Non-negotiable boundaries:

- Core API owns business truth.
- PostgreSQL remains the operational DB.
- Redis remains local cache/queue infrastructure.
- Python AI worker output is advisory only.
- AI, chatbot, frontend, and connector components must not directly write trusted business data.
- Tenant-owned records must preserve `tenant_id` isolation.
- Important mutations must use Core API command services and audit events.
- Imports must stage before apply.
- External writes remain disabled.
- No production secrets may be committed.
- `check-no-secrets.ps1` must keep passing.

## Rollback and Freeze Plan

For each pilot tenant:

- Freeze input processing by disabling that tenant's intake route or queue consumption.
- Stop any worker processing for that tenant if unsafe behavior is detected.
- Preserve raw input, predictions, corrections, audit events, and reviewer notes for investigation.
- Do not delete data unless a written retention/deletion instruction exists.
- Revert only Stage 10-specific configuration or docs; do not delete Docker volumes as a normal recovery path.
- Produce an incident note for any tenant-boundary issue, secret exposure, external-write attempt, or incorrect high-risk recommendation.

## No-Real-ERP-Write Rule

Stage 10A does not allow real ERP, 1C, accounting, warehouse, payment, customer-message, or external connector writes.

The only permitted write targets are OrderPilot-owned advisory, staging, validation, workflow, metrics, and audit records. Any future external write mode requires a separate stage with ChangeRequest, deterministic validation, approval, transaction boundary, audit event, outbox event, connector idempotency, rollback plan, and explicit design-partner acceptance.
