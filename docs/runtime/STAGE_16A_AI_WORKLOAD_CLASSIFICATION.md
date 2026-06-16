# OP-CAP-16A — AI Workload Classification Foundation

## Purpose

OrderPilot must not call AI/OCR/heavy workers blindly. Before expensive processing, the backend
classifies a workload deterministically so later stages can meter usage, enforce quotas, rate-limit,
route jobs, and control cost.

Stage 16A delivers the **decision-only foundation**: a small deterministic domain plus a
side-effect-free classifier service with focused tests. It changes no existing extraction, provider,
or business write behavior.

## Non-scope (explicitly NOT in 16A)

- No new AI provider and no external AI calls.
- No billing.
- No quota enforcement.
- No rate limiting.
- No job runtime / queue.
- No change to existing extraction execution paths.
- No new frontend screen.
- No DB access from the classifier (no entity, no repository, no migration).
- No HTTP endpoint (service + tests only).

## Domain

Package: `com.orderpilot.application.services.runtime`

### Enums

- `AiWorkloadType` — `CHAT_INTENT, PRICE_REQUEST, AVAILABILITY_REQUEST, PURCHASE_LIST_EXTRACTION,
  DOCUMENT_EXTRACTION, PRODUCT_MATCHING, VALIDATION_EXPLANATION, DRAFT_GENERATION, ERP_RECONCILIATION,
  PAYMENT_RECONCILIATION, BULK_IMPORT, UNKNOWN`
- `WorkloadSize` — `SMALL, MEDIUM, LARGE, BULK`
- `ModelTier` — `NONE, RULES_ONLY, SMALL_LOCAL, MEDIUM, LARGE, HUMAN_REVIEW`

### Records

- `AiRoutingDecision(workloadType, workloadSize, selectedTier, asyncRequired, humanReviewRequired,
  estimatedInputUnits, reasonCode)` — advisory decision metadata. `reasonCode` is a stable token and
  never contains raw input text.
- `AiWorkloadEstimate(textLength, pageCount, attachmentCount, estimatedInputUnits,
  suspiciousPromptInjectionSignal, structuredIdentifierPresent, bulkLike)` — normalized,
  non-negative measurement signals. Holds measurements of the input, never the input text itself.
- `AiWorkloadClassificationRequest(requestedType, text, pageCount, attachmentCount,
  hasStructuredSkuOrIdentifier, suspiciousPromptInjectionSignal)` — classifier input. `text` may be
  null (treated as empty); negative counts are normalized to zero.

### Service

- `AiWorkloadClassifier#classify(AiWorkloadClassificationRequest)` — deterministic, no DB, no AI, no
  external calls. Registered as a `@Service` for future wiring; has no dependencies.

## Routing ladder (evaluation order)

1. **Suspicious prompt-injection signal** → `HUMAN_REVIEW`, `humanReviewRequired=true`
   (`suspicious_prompt_injection_review`). Safety wins over everything, including structured lookups.
2. **Empty input** (no text, no pages, no attachments) → `RULES_ONLY`, `SMALL`
   (`empty_input_rules_only`).
3. **Unknown workload type** → `HUMAN_REVIEW`, `humanReviewRequired=true` (`unknown_workload_review`).
4. **Structured deterministic lookup** (`PRICE_REQUEST` / `AVAILABILITY_REQUEST` / `PRODUCT_MATCHING`
   with a structured identifier and `SMALL` size) → `RULES_ONLY`
   (`structured_identifier_rules_path`).
5. **Bulk** size → `HUMAN_REVIEW`, `asyncRequired=true`, `humanReviewRequired=true`
   (`bulk_requires_review`).
6. **Large** size → `LARGE`, `asyncRequired=true` (`large_document_async`).
7. **Medium** size → `MEDIUM`, `asyncRequired=true` (`medium_document_async`).
8. **Small `CHAT_INTENT`** → `SMALL_LOCAL` (`short_chat_intent`).
9. **Any other small workload** → `SMALL_LOCAL` (`small_workload_local`).

### Size derivation

`estimatedInputUnits = ceil(textLength / 4) + pageCount * 300 + attachmentCount * 200`

- `BULK`: `pageCount >= 100` OR `attachmentCount >= 20` OR `units >= 50000`
- `LARGE`: `pageCount >= 6` OR `units >= 8000`
- `MEDIUM`: `pageCount >= 1` OR `attachmentCount >= 1` OR `units >= 1000`
- `SMALL`: otherwise

`estimatedInputUnits` is always non-negative and deterministic (same input → same value).

## Reason codes

Defined as constants in `AiWorkloadReasonCodes`:

```
structured_identifier_rules_path
short_chat_intent
small_workload_local
medium_document_async
large_document_async
bulk_requires_review
suspicious_prompt_injection_review
empty_input_rules_only
unknown_workload_review
```

## Security constraints honored

- No AI-to-DB business writes; classifier performs no writes at all.
- No frontend-to-DB access (no frontend touched).
- No external calls (none, so none inside transactions).
- No tenant leaks: classifier needs no `TenantContext` and reads no tenant data.
- No raw prompt/customer payload logging: the classifier neither logs nor returns input text; only
  measurements and stable reason codes are exposed. Tests assert the decision never contains the raw
  input.
- No secrets in code/logs.

## Resource-economy constraints honored

- Decision happens before any expensive processing.
- No unbounded queries (no queries at all).
- Suspicious inputs and unknown workloads gate to human review.
- Large/bulk inputs require async handling.
- Small deterministic lookups can short-circuit to `RULES_ONLY` (no model spend).

## How 16A prepares later stages

- **16B Usage Metering** — `estimatedInputUnits` + `workloadType` + `selectedTier` are the natural
  inputs to `UsageEvent` / `UsageCounter`.
- **16C Quotas** — `selectedTier` and `workloadSize` feed `QuotaPolicy` / `FeatureEntitlement`
  checks.
- **16D Job Routing** — `asyncRequired` and `selectedTier` decide sync vs. async dispatch and the
  target worker pool.

## Tests

`AiWorkloadClassifierStage16ATest` (pure unit, no Spring/DB/AI) covers:

- structured SKU price/availability → `RULES_ONLY`
- short chat intent → `SMALL_LOCAL`
- medium document → `asyncRequired=true`
- large document → `LARGE` + async
- bulk document and many-attachments → `BULK` + async + human review
- suspicious prompt injection → `HUMAN_REVIEW` + human review (and overrides structured lookup)
- empty input → deterministic `RULES_ONLY` default
- unknown / null type → safe human-review default
- null text and negative counts do not throw
- `estimatedInputUnits` deterministic and non-negative
- decision never contains raw input text
- classifier needs no DB or tenant context
