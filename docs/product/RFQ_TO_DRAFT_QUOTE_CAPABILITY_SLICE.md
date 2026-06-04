# RFQ to Draft Quote Capability Slice

## 1. Status

- Canonical Stage-Source Freeze: PASS
- Product stage status: Backend/API review layer PASS; read-only operator UI surfacing PASS; mutation/operator action layer intentionally not implemented
- Owner decision: controlled capability work resumed for this slice only
- Scope: backend RFQ -> internal draft quote / reviewable quote request hardening

## 2. What changed

- `apps/core-api/src/main/java/com/orderpilot/application/services/workspace/QuoteDraftService.java`
  - Added audit events when RFQ validation issues are persisted.
  - Added RFQ validation completion audit metadata with issue counts, blocking counts, review status, and `externalExecution=DISABLED`.
  - Added review-required audit routing when blocking issues or approval requirements exist.
- `apps/core-api/src/test/java/com/orderpilot/application/services/workspace/QuoteDraftServiceStage12ATest.java`
  - Added assertions that valid RFQ draft creation does not approve a quote and does not emit connector commands.
  - Added invalid-quantity coverage for review routing, validation issue creation, audit events, and no external write.
  - Hardened unknown-product coverage to assert validation issue audit and review-required audit behavior.

## 3. What the flow does

The existing `QuoteDraftService.createFromRfq(...)` path creates an internal `DraftQuote` from deterministic RFQ input under the current tenant context. It resolves the customer through existing customer resolution, resolves products through existing SKU/alias/OEM matching, validates quantity and UOM, checks deterministic price, inventory, discount, margin, and substitution rules where existing services support them, then writes only internal draft quote, line, validation issue, and approval request records.

Valid low-risk requests produce an internal `DRAFT` quote. Risky or incomplete requests produce internal `NEEDS_REVIEW` or `PENDING_APPROVAL` quote state with validation issues and approval requirements. The flow does not approve a quote, create a final order, or execute an external connector.

## 4. Safety guarantees

- no external ERP write
- no final quote approval
- validation issues for risky/incomplete data
- audit events
- tenant isolation

## 5. Pre-draft channel conversion rejection evidence

Channel/message/document conversion attempts that cannot proceed to `QuoteDraftService` now leave tenant-scoped pre-draft evidence in the existing `QuoteConversionAttempt` model before returning the existing controlled response. No `DraftQuote` is created for these paths, and audit metadata records `externalExecution=DISABLED`.

Persisted rejection evidence includes source type, source id, source channel, safe external source reference, normalized intent, conversion attempt id, resolved customer id when one exists, deterministic reason codes, review-required state, and a null draft quote id. Reused or added reason codes include `CUSTOMER_UNRESOLVED`, `NO_LINE_ITEMS`, `SELECTED_LINE_NOT_IN_SOURCE`, `INVALID_QUANTITY`, `SKU_OR_DESCRIPTION_REQUIRED`, `POLICY_DENIED`, and terminal fallback statuses such as `NEEDS_REVIEW` or `REJECTED_VALIDATION_FAILED`.

Audit events now cover pre-draft evidence explicitly:

- `CHANNEL_TO_QUOTE_VALIDATION_ISSUE_CREATED`
- `CHANNEL_TO_QUOTE_REVIEW_REQUIRED`
- `CHANNEL_TO_QUOTE_CONVERSION_REJECTED`

Policy-denied conversion attempts are audited with `POLICY_DENIED` and still throw the controlled policy exception. This slice still does not add a public RFQ API, a broad natural-language parser, frontend behavior, AI worker behavior, ERP/1C writes, connector commands, or external execution.

## 6. Operator read model for quote conversion attempts

The quote review backend now exposes a read-only, tenant-scoped query model for quote conversion attempts through:

- `GET /api/v1/quote-review/conversion-attempts`
- `GET /api/v1/quote-review/conversion-attempts/{attemptId}`

The list endpoint supports narrow filters for `status`, `reviewRequired`, `reasonCode`, `sourceChannel`, `draftQuoteLinked`, `createdFrom`, and `createdTo`. Results are always scoped through `TenantContext.requireTenantId()` and are mapped into safe DTOs rather than exposing raw JPA entities.

List and detail responses expose safe fields only: attempt id, source type/id, source channel, channel message id when the source is a channel message, inbound document id when the source is a document, nullable draft quote id, draft-link flag, status, review-required flag, deterministic reason codes, issue count, customer resolution status, line count, request mode, triggering actor metadata, and created time. Detail responses also include sanitized validation-summary metadata and issue DTOs. They do not expose raw message text, raw document content, raw payload JSON, secrets, connector credentials, or unrestricted AI output.

Pre-draft conversion failures appear with `draftQuoteId=null` and deterministic reason codes such as `CUSTOMER_UNRESOLVED` or `NO_LINE_ITEMS`. Draft-linked attempts appear with `draftQuoteId` populated when a channel/document conversion reached draft creation. This is read-only coverage only: no mutation endpoint, no frontend, no public RFQ creation API, no broad natural-language parser, no ERP/1C write, and no connector command behavior was added.

## 7. Exception Cockpit API contract freeze

The Exception Cockpit backend contract for quote conversion attempts is frozen around the existing read-only quote review routes:

- `GET /api/v1/quote-review/conversion-attempts`
- `GET /api/v1/quote-review/conversion-attempts/{attemptId}`

The list contract accepts only narrow review filters: `status`, `reviewRequired`, `reasonCode`, `sourceChannel`, `draftQuoteLinked`, `createdFrom`, and `createdTo`. It returns a mixed queue of pre-draft failures and draft-linked attempts so operators can triage unresolved channel/document conversions before a draft quote exists.

The list response is safe-by-contract and contains only: attempt id, source type/id, source channel, channel message id, inbound document id, nullable draft quote id, draft-link flag, status, review-required flag, primary reason code, deterministic reason-code list, issue count, customer-resolution status, line count, request mode, triggering actor metadata, and creation timestamp.

The detail response extends the same shape with `safeMetadata` and validation issues. `safeMetadata` remains limited to sanitized review summary fields such as line count, customer resolution, and issue count. The controller contract does not expose raw channel payloads, raw message text, raw document text, object storage keys, unrestricted metadata JSON, connector credentials, secrets, or raw AI output.

Pre-draft failures remain visible with `draftQuoteId=null` and `draftQuoteLinked=false`; draft-linked attempts remain visible with `draftQuoteId` populated and `draftQuoteLinked=true`. Missing attempt ids return the shared structured `NOT_FOUND` API error. This freeze still does not add mutations, public RFQ creation, frontend cockpit UI, broad natural-language parsing, AI-worker behavior, ERP/1C execution, connector commands, service-info behavior, or any external write path.

## 8. Tests

- `QuoteDraftServiceStage12ATest.happyPathCreatesDraftQuoteWithoutApproval`
  - Proves a valid RFQ creates an internal draft quote without final approval or connector commands.
- `QuoteDraftServiceStage12ATest.unknownProductCreatesValidationIssueAndLineIsNotAutoApproved`
  - Proves unknown products create validation issues, review-required audit events, and no connector commands.
- `QuoteDraftServiceStage12ATest.invalidQuantityCreatesReviewIssueAuditAndNoExternalWrite`
  - Proves non-positive quantity creates a validation issue, routes the quote to review, audits the transition, and does not emit connector commands.
- `ChannelToQuoteWiringServiceTest.unresolvedCustomerRoutesToReviewAndDoesNotCreateQuote`
  - Proves unresolved pre-draft customer conversion attempts persist tenant-scoped attempt evidence, emit issue/review audit events, do not create a draft quote, and do not link a quote source.
- `ChannelToQuoteWiringServiceTest.noLineItemsRejectsWithoutDraftQuote`
  - Proves no-line-item pre-draft conversion attempts persist deterministic rejection evidence, emit issue/rejection audit events, and do not create a draft quote.
- `QuoteConversionAttemptReviewQueryServiceTest`
  - Proves tenant-scoped conversion attempt listing, pre-draft failure read-model fields, draft-linked read-model fields, reason-code filtering, and tenant-safe detail lookup.
- `QuoteReviewControllerTest`
  - Proves the conversion-attempt list/detail API contract, filter binding, pre-draft and draft-linked response shapes, structured not-found behavior, and absence of unsafe raw payload/text/secret fields from the controller response.
- Existing tenant-isolation and lifecycle tests continue to cover cross-tenant data boundaries and approval/conversion safety.

## 9. Limitations

- This slice does not add a new public RFQ API.
- This slice does not change bot autonomy or outbound messaging.
- This slice does not add full natural-language RFQ parsing beyond existing deterministic inputs.
- This slice does not add production ERP/1C execution, reservations, invoicing, or payment behavior.
- This slice does not add approve, reject, correct, retry, create, or connector-execution operator actions.
- Idempotency remains limited to the existing quote idempotency key behavior.
- Existing broader dirty worktree artifacts remain unreviewed and unstaged.

## 10. Correct next slice after UI surfacing

Read-only operator UI surfacing for the RFQ / Channel -> Draft Quote review layer is now implemented. This completion does not automatically authorize mutation work or a jump to a new layer.

The correct next slice must be chosen from the reconciled roadmap. Mutation/operator actions, retries, quote creation actions, connector commands, ERP/1C writes, public RFQ API work, and AI-worker changes remain separate layers and are not enabled by this UI surface.

## 11. RFQ / Channel -> Draft Quote Review Layer Gate

Gate date: 2026-06-03

Gate scope: RFQ / Channel -> Draft Quote Review Layer completion through read-only operator UI surfacing. This gate did not add mutation endpoints, operator actions, AI-worker code, a public RFQ API, ERP/1C writes, connector commands, dependency changes, staging, or commits.

Final gate decision:

- Backend/API review layer: PASS.
- Read-only operator UI surfacing: PASS.
- Mutation/operator action layer: intentionally not implemented.
- Accepted backend boundary: read-only review APIs, tenant-scoped query model, deterministic validation/review evidence, audit evidence, safety exclusions, and scoped tests.
- Accepted UI boundary: dashboard list/detail routes that display the frozen conversion-attempt review contract without mutation controls.

### Final Acceptance Matrix

| Requirement | Status | Evidence / limitation |
| --- | --- | --- |
| Valid RFQ creates internal `DraftQuote` only | PASS | `QuoteDraftService` creates tenant-scoped internal draft quote state and keeps external execution disabled; tests assert no connector command or final approval. |
| Invalid/risky RFQ creates validation/review evidence | PASS | Blocking issues create `quote_validation_issue` evidence, review-required status, and audit metadata. |
| Pre-draft rejection creates `QuoteConversionAttempt` evidence | PASS | `ChannelToQuoteWiringService` persists tenant-scoped attempts for unresolved customer, no-line, invalid-line, and policy-denied paths before returning controlled review/rejection results. |
| Review-required and rejection audit events exist | PASS | RFQ and channel conversion paths emit validation issue, review-required, conversion rejection, and validation completion audit events with `externalExecution=DISABLED`. |
| Read-only tenant-scoped review model exists | PASS | `QuoteConversionAttemptReviewQueryService` reads attempts through `TenantContext.requireTenantId()` and tenant-scoped repositories. |
| List/detail review API exists | PASS | `GET /api/v1/quote-review/conversion-attempts` and `GET /api/v1/quote-review/conversion-attempts/{attemptId}` expose list/detail review contracts. |
| Controller contract tests exist | PASS | `QuoteReviewControllerTest` covers filter binding, list/detail shape, not-found behavior, and unsafe-field exclusions. |
| Unsafe raw fields are excluded | PASS | DTO/controller contract exposes safe review fields and sanitized metadata only; tests reject raw payload/text/document, secret, and connector credential strings. |
| No external ERP/1C writes | PASS | The layer records internal draft/review evidence only; audit metadata remains `externalExecution=DISABLED`. |
| No connector commands | PASS | RFQ tests assert connector command count remains zero; channel conversion does not execute connector behavior. |
| Operator-facing read-only review UI exists | PASS | `/conversion-review` lists attempts, `/conversion-review/{attemptId}` shows detail, and both consume the existing tenant-scoped REST API. |
| Loading, empty, and error states exist | PASS | The conversion review component renders loading rows, no-match empty copy, and sanitized error messages. |
| Safe metadata display exists | PASS | Detail renders `safeMetadata` as controlled key-value rows and does not render raw JSON or HTML. |
| Mutation/operator actions remain absent | PASS | The conversion review UI has no approve/reject/correct/retry/create buttons and does not import mutation API functions. |
| No public RFQ API | PASS | Existing coverage uses internal service and channel/document conversion paths; no public RFQ creation endpoint is introduced by this gate. |
| No AI worker changes | PASS | The slice relies on deterministic backend validation/read models and does not modify AI-worker behavior. |
| Tenant isolation is tested | PASS | Query service, RFQ draft, and channel conversion tests cover tenant-scoped reads, foreign-source not found behavior, and cross-tenant draft/product boundaries. |
| Known limitation for missing tenant context is documented | PASS | Missing tenant context is treated as a hard backend precondition through `TenantContext.requireTenantId()`; `RfqToDraftQuoteServiceTest.missingTenantContextDeniesDraftQuoteCreation` covers this for RFQ draft creation. |
| Documentation/status updated | PASS | Product/status docs now mark backend/API and read-only UI surfacing complete while keeping mutation and external-write layers out of scope. |

Layer status: PASS for backend/API review coverage and PASS for read-only operator UI surfacing.

The implemented layer completes the controlled path from valid RFQ/channel evidence to internal draft quote or reviewable conversion-attempt evidence. It also completes read-only operator visibility for conversion attempts with tenant scoping, safe DTOs, contract tests, audit evidence, dashboard list/detail routes, and explicit external-write exclusions.

Verification for this gate passed on 2026-06-03 with scoped Maven selector `QuoteReviewControllerTest,QuoteConversionAttemptReviewQueryServiceTest,ChannelToQuoteWiringServiceTest,RfqToDraftQuoteServiceTest,QuoteDraftServiceStage12ATest`: 56 tests, 0 failures, 0 errors, 0 skipped.

Frontend verification also passed on 2026-06-03:

- `node --test tests/conversion-review.test.mjs`: passed, 3 tests, 0 failures.
- `npm.cmd run lint`: passed.
- `npx.cmd tsc --noEmit --incremental false`: passed. The package `npm.cmd run typecheck` script was not used for final verification because it attempted to rewrite the existing `tsconfig.tsbuildinfo` file and failed with `EPERM`.
- `npm.cmd run build`: passed and emitted `/conversion-review` plus `/conversion-review/[attemptId]`.

Remaining limitation: mutation/operator actions are intentionally not implemented. Any approve/reject/correct/retry/create behavior, connector command, ERP/1C write, public RFQ API, or AI-worker behavior remains a separate gated slice.
