# Stage 13A — Advisory Extraction → Core Validation Handoff

Stage 13A wires the AI-worker **advisory** extraction result into the existing **deterministic** Core
validation/risk workflow, without giving AI any business authority.

It builds on (and does not restate):

- AI-worker advisory layer / fail-closed convention — `docs/ai/STAGE_12_ACCEPTANCE.md`.
- Secure receiving layer (OP-CAP-07D) — `AiWorkerResultIntakeService`.
- Deterministic validation engine (Stage 4/5) — `ValidationRunService` / `ExtractionValidationService`.

## The boundary

```
AI worker (advisory only)
  → AiWorkerResultIntakeService (OP-CAP-07D): persists an untrusted advisory ExtractionResult
      (provider AI_WORKER, JSON wrapper: source=AI_WORKER, workerStatus, extraction{...})
  → AdvisoryExtractionValidationHandoffService (OP-CAP-13A)  ← THIS SLICE
      decompose advisory line_items/fields into UNTRUSTED ExtractedLineItem/ExtractedField rows
      → ExtractionValidationService.validateCompletedExtraction (deterministic engine)
          → ValidationRun + ValidationIssue + ApprovalRequirement + routing recommendation
  → operator review / draft preparation (OP-CAP-09A+) — unchanged, still human-gated
```

AI output is **advisory only** and remains untrusted until deterministic validation. The deterministic
engine owns every SKU/customer/price/stock/margin/discount/substitution decision; AI confidence
approves nothing.

## What this slice does

`AdvisoryExtractionValidationHandoffService.handoff(extractionResultId)`:

1. **Tenant isolation** — resolves the tenant server-side and loads the result via
   `findByIdAndTenantId`; another tenant's result is simply not found and fails closed.
2. **Source gate** — only AI-worker advisory results (`source=AI_WORKER`) are handed off here;
   core-internal extractions already own their normalized rows and are rejected
   (`not_ai_worker_advisory_result`).
3. **Fail-closed status gate** — a `FAILED`/`REJECTED` worker/validation status is **never** decomposed
   into business candidates and **never** validated; it returns a controlled `FAILED_EXTRACTION`
   handoff with the bounded safe reason and no `ValidationRun`.
4. **Unsafe-output guard (defense in depth)** — the nested advisory payload is re-scanned for any
   executable/business-action key (`create_order`, `approve_quote`, `update_inventory`, `erp_write`,
   `external_write`, `change_request`, `sql`, `tool_call`, …). Any hit → `UNSAFE_OUTPUT_REJECTED`,
   no decomposition, no run.
5. **Idempotency** — if a validation run already exists for the result, the latest is returned with
   `duplicate=true` and no duplicate rows/runs; a retry after partial decomposition reuses the rows.
6. **Decomposition** — advisory `fields` and `line_items` are copied into **untrusted** normalized
   `ExtractedField`/`ExtractedLineItem` rows (bounded; `normalized_uom` left to the deterministic UOM
   service; `normalized_quantity` parsed defensively). A bounded `SourceEvidence` row preserves
   per-line provenance.
7. **Deterministic validation** — delegates to `ExtractionValidationService.validateCompletedExtraction`,
   which produces issues / approvals / routing. It creates **only** validation artifacts.
8. **Audit** — emits bounded audit events
   (`advisory_validation_handoff.accepted|failed_closed|unsafe_rejected|duplicate|rejected`) carrying
   ids/status/reason only — never raw payloads, document bodies, or secrets.

The result is the bounded `AdvisoryValidationHandoffResult` DTO (handoff status, validation run id,
overall status, routing, issue/approval counts, decomposed line count, `advisoryOnly=true`, safe
failure reason). No executable/business-action surface.

## Controlled failure convention (unchanged)

13A does not invent a new failure convention. A failed/unsafe handoff returns a controlled DTO with a
safe reason and no business records — never `None`, never an uncontrolled exception path that mutates
state, and never a quote/order/inventory/customer/price change.

## Acceptance matrix

| # | Acceptance item | Status | Evidence (test) |
| --- | --- | --- | --- |
| 1 | Valid advisory extraction → safe validation handoff | ✅ | `acceptedHandoffDecomposesAndValidatesWithoutBusinessMutation` |
| 2 | Advisory output enters deterministic validation | ✅ | same — `ValidationRun` created, issues/approvals populated |
| 3 | Failed extraction → controlled failed state, no quote/order/run | ✅ | `failedExtractionFailsClosedWithoutDecompositionOrRun` |
| 4 | Unsafe/action-key output → no line items / business data | ✅ | `nestedBusinessActionKeyIsRejectedWithoutDecomposition` |
| 5 | Low confidence → routes to review | ✅ | `lowConfidenceRoutesToReview` |
| 6 | Malformed quantity → blocking validation issue | ✅ | `invalidQuantityCreatesBlockingIssue` |
| 7 | Tenant mismatch rejected / fail-closed | ✅ | `tenantMismatchIsRejectedFailClosed` |
| 8 | Retry/idempotent handoff does not duplicate | ✅ | `duplicateHandoffIsIdempotent` |
| 9 | Source/evidence context preserved | ✅ | accepted test: sourceType/sourceId echoed, line `sourceEvidenceId` set |
| 10 | Missing SKU / product handled by deterministic engine | ✅ | engine `PRODUCT_NOT_FOUND` path (`ValidationRunServiceStage5Test`) reused |
| 11 | No AI direct business write | ✅ | accepted test asserts draft quote/order counts unchanged |
| 12 | Auditable handoff transitions | ✅ | `advisory_validation_handoff.*` audit events |

## Intentionally NOT done in this slice

> **Follow-up: completed in [Stage 13B](STAGE_13B_HANDOFF_WIRING.md)** — the auto-trigger after intake
> and the guarded operator endpoint (`POST /api/v1/validations/advisory-handoff/{id}`, `VALIDATION_RUN`)
> were added there, reusing this exact service.

- **No REST endpoint** added (this slice). The handoff is exposed as the canonical service contract;
  wiring it into the intake/orchestration trigger (and an operator-facing endpoint + `ApiPermission`)
  was deferred to 13B to avoid permission/scope creep here.
- **No approved quote/order creation**, no inventory/customer/price/margin/discount write, no ERP/1C or
  connector write, no outbox.
- **No DB migration** — reuses existing `extraction_result` / `extracted_line_item` / `extracted_field`
  / `source_evidence` / `validation_*` tables.
- **No frontend change**, **no new provider/model**, **no real LLM/network/Ollama call**.

## Known limitations

- Idempotency is keyed on "a validation run already exists for this extraction result"; it does not add
  a new unique constraint (none is required — the existing run history is the dedupe key). Concurrent
  duplicate handoffs for the same result are not separately locked in this slice.
- The handoff is not yet auto-triggered after intake; it is invoked explicitly.
- Source evidence stores a bounded line snippet with null offsets (the AI-worker advisory payload does
  not carry reliable character offsets); richer evidence mapping is deferred.

## Targeted test command (from `apps/core-api`)

```
mvn -o -Dtest=AdvisoryExtractionValidationHandoffServiceStage13ATest test
```
