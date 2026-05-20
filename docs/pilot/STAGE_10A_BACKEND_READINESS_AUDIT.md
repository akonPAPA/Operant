# Stage 10A Backend Readiness Audit

## Scope

This audit inspected the existing Core API domain, application services, migrations, architecture docs, and security docs for pilot/shadow-mode readiness.

No backend code was changed for Stage 10A.

## Summary

The backend already has most of the safe foundation needed for shadow-mode planning:

- Intake records for documents/messages.
- Advisory extraction records.
- Field and line-item confidence.
- Deterministic validation records.
- Validation issues and approval requirements.
- Internal draft quote/order workspace.
- Audit events.
- Import staging/mirror flow.
- Tenant context requirement in services.

The backend does not yet have a dedicated ChangeRequest/external-write model, production authentication/RBAC/ABAC, transactional outbox, or real external connector write controls. That is good for Stage 10A because external writes must remain disabled.

## Entity and Contract Inventory

| Required item | Current status | Evidence | Stage 10A readiness |
| --- | --- | --- | --- |
| `InboundDocument` | Exists | `domain/intake/InboundDocument.java`, `inbound_document` table. | Ready for intake/shadow-mode source tracking. |
| `ExtractedField` | Exists | `domain/extraction/ExtractedField.java`, `extracted_field` table. | Ready for field-level prediction/correction metrics. |
| `ExtractedLineItem` | Exists | `domain/extraction/ExtractedLineItem.java`, `extracted_line_item` table. | Ready for line-level prediction/correction metrics. |
| `ValidationIssue` | Exists | `domain/validation/ValidationIssue.java`, `validation_issue` table. | Ready for exception and issue-rate measurement. |
| `DraftQuote` | Exists | `domain/workspace/DraftQuote.java`, `draft_quote` table. | Internal-only draft workflow exists; not an external quote. |
| `DraftOrder` | Exists | `domain/workspace/DraftOrder.java`, `draft_order` table. | Internal-only draft workflow exists; not an ERP/order-system write. |
| `AuditEvent` | Exists | `domain/audit/AuditEvent.java`, `AuditEventService`. | Ready for important internal mutation audit where services record events. |
| `ImportJob` or equivalent | Exists | `domain/imports/ImportJob.java`, `ImportStagingRow.java`, `ValidationReport.java`. | Ready for staged/mirror imports; not production overwrite. |
| `ApprovalRequest` or equivalent | Partial equivalent exists | `ApprovalRequirement` for validation and `ApprovalDecision` for internal workspace. | Good for internal review; not sufficient for external writes. |
| `ChangeRequest` or equivalent | Not implemented | Docs reference future ChangeRequest requirement; no code/table found. | Missing by design; external writes must remain disabled. |
| AI extraction result/suggestion tables | Exist | `ExtractionResult`, `AiSuggestion`, `SourceEvidence`, `PromptTemplateVersion`, V4 migration. | Ready for advisory extraction and evidence tracking. |

## Backend Readiness Findings

### Intake and Import

`InboundDocument`, `ChannelMessage`, `InboundAttachment`, `ProcessingJob`, `WebhookEvent`, and import staging entities provide a safe intake/mirror foundation. They support real-like input capture and processing status without overwriting external source-of-truth systems.

`ImportJob` supports staged, validating, validated, applied, failed, and rejected states. For Stage 10 pilot planning, `APPLIED` must remain an OrderPilot-controlled mirror/staging concept unless a later stage explicitly defines external writes.

### Advisory Extraction

Stage 4 extraction entities support:

- extraction run tracking;
- extracted text;
- structured result JSON;
- field and line-item confidence;
- source evidence;
- AI suggestions and warnings;
- prompt/template metadata.

This is enough for shadow-mode predictions and correction tracking. It is not enough for autonomous actions, and that is intentional.

### Deterministic Validation

Stage 5 validation entities support:

- customer matching;
- product matching;
- UOM normalization;
- inventory checks;
- price checks;
- discount checks;
- margin checks;
- substitute candidates;
- validation issues;
- approval requirements.

These are suitable for measuring validation issue rate, exception categories, substitution acceptance, discount/margin issue rate, and draft readiness.

### Internal Workspace

Stage 6 workspace entities support:

- exception cases;
- suggested fixes;
- internal draft quotes;
- internal draft orders;
- internal approval decisions;
- operator actions;
- workspace notes.

These are workflow records only. They do not create final customer-facing quotes, final orders, ERP records, inventory reservations, or warehouse/accounting writes.

### Audit and Tenant Boundary

`TenantContext.requireTenantId()` is used by application services to require tenant context. `AuditEventService.record(...)` writes append-only audit events at application level.

For Stage 10B and beyond, audit coverage should be checked endpoint-by-endpoint before using real design-partner data. Stage 10A does not need code changes for this.

## Gaps Before Production Pilot Actions

These gaps block real external writes but do not block Stage 10A shadow-mode planning:

- No dedicated ChangeRequest model for external writes.
- No transactional outbox model for connector events.
- No real ERP/1C/accounting/warehouse connector write path.
- No production authentication/RBAC/ABAC proof for design partner access.
- No tenant-specific pilot metrics tables yet.
- No dedicated correction-tracking table yet.
- No pilot freeze configuration per tenant yet.
- No connector idempotency or rollback contract.

## Stage 10A Decision

Do not add backend placeholder code in Stage 10A. The existing backend is sufficient for documentation, audit, and safe pilot contract planning.

Recommended Stage 10B: add a mock-only pilot metrics/correction tracking contract and tests, still with no real AI provider and no external writes.
