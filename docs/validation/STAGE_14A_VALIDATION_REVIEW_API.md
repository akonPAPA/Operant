# OP-CAP-14A — Validation Review API Surface / Operator Result Contract

Read-only backend contract that composes already-persisted deterministic validation artifacts into a
single bounded payload for the operator review workspace (consumed by OP-CAP-14B). It adds **no**
business-write path.

## Endpoints

Both are GET, tenant-scoped via `X-Tenant-Id` / `TenantContext`, and added to the canonical
`ValidationController` (`/api/v1/validations`):

| Method & path | Purpose |
| --- | --- |
| `GET /api/v1/validations/{validationRunId}/review` | Review detail for a validation run |
| `GET /api/v1/validations/extractions/{extractionResultId}/review` | Review detail for the latest validation run of an extraction result |

Composition lives in `ValidationReviewQueryService`; the bounded DTOs in
`com.orderpilot.api.dto.ValidationReviewDtos`.

## Permission

GET under `/api/v1/validations` is guarded by **`VALIDATION_READ`** through the existing
`ApiPermissionInterceptor` read-prefix mapping. No new permission was introduced (no real gap existed —
the read/view convention already covers this prefix). Non-GET on the prefix remains `VALIDATION_RUN`.

## Response sections (`ValidationReviewDetailResponse`)

1. **extraction** (`ExtractionReviewSummary`) — extraction result id, source type, source id, detected
   intent, document type, worker status (bounded token parsed from the advisory wrapper), validation
   status, overall confidence, `advisoryOnly=true`.
2. **validationRun** (`ValidationRunReviewSummary`) — run id, status, overall status, routing decision,
   blocking issue count, warning/review issue count, approval requirement count, created/started/
   completed timestamps.
3. **fields** (`ExtractedFieldReviewItem[]`) — field name, extracted/normalized value, value type,
   confidence, validation status, source evidence pointer, referenced issue ids.
4. **lineItems** (`ExtractedLineItemReviewItem[]`) — line number, raw SKU, matched product id + match
   status, description, normalized quantity, UOM, confidence, validation status, source evidence
   pointer, referenced issue ids.
5. **issues** (`ValidationIssueReviewItem[]`) — issue id, severity, code (issue type), operator-visible
   message, target type (`FIELD` / `LINE_ITEM` / `EXTRACTION` / `VALIDATION_RUN`), target id, target
   line number, blocking flag, status.
6. **sourceEvidence** (`SourceEvidenceReviewItem[]`) — bounded snippets only (≤ 280 chars), evidence
   type, page/offset references. No full document/message body.
7. **auditTimeline** (`AuditTimelineItem[]`) — bounded audit metadata for the run (actor, action,
   entity type/id, occurred-at), newest first, capped at 50 rows. Raw audit metadata JSON is not
   exposed.
8. **allowedActions** (`AllowedReviewAction[]`) — declarative hints only, each with an `enabled` flag
   and the permission a future action would require:
   `REVIEW_FIELDS`, `FIX_LINE_ITEM`, `APPROVE_SUBSTITUTE_REQUIRES_PERMISSION`,
   `RERUN_VALIDATION_ALLOWED`, `CREATE_DRAFT_QUOTE_NOT_IMPLEMENTED` (always disabled here). No
   executable command is generated.

## Tenant / security behavior

- Tenant resolved server-side; never accepted from request body.
- All loads use tenant-scoped repository methods (`findByIdAndTenantId`,
  `findByTenantId...`). A missing or foreign-tenant validation run / extraction result fails closed as
  a bounded `404 NOT_FOUND` (`validation_run_not_found` / `extraction_result_not_found`) via the
  existing `GlobalExceptionHandler`.
- Read-only: no handoff, validation run, draft/quote/order creation, or connector action is triggered.

## Intentionally NOT exposed

- Raw AI-worker advisory JSON payload (`resultJson`) or wrapper-only markers
  (`untrustedUntilValidation`, `schemaVersion`, etc.).
- Full document or message bodies (only bounded evidence snippets).
- Prompt text, provider secrets, tokens, connector credentials, stack traces.
- Raw audit metadata JSON.

## No-mutation guarantee

The endpoints and `ValidationReviewQueryService` are `@Transactional(readOnly = true)` and call only
read repository methods. No quote/order/customer/inventory/price/discount/margin/connector/ERP/1C
record is created or mutated. Tests assert deterministic issue counts match the persisted artifacts
and that no raw advisory/secret content surfaces.

## Known limitations

- The review resolves a single validation run (by run id, or the latest run for an extraction result).
  Historical run-by-run diffing is out of scope.
- Audit timeline is keyed on `entityType="ValidationRun"` and the run id; it does not aggregate
  related extraction/line-level audit rows. This reuses existing audit query support rather than
  building a new audit search subsystem.
- Matched product is exposed as an id + match status; SKU/name resolution is left to the product
  picker surface to avoid extra per-line lookups.

## Next intended frontend stage

OP-CAP-14B — operator validation review workspace UI consuming this contract.
