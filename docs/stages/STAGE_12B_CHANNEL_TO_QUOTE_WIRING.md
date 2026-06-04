# Stage 12B - Channel-to-Quote Wiring

## Scope

Stage 12B connects tenant-scoped channel and document sources to the internal draft quote workflow. A channel message, inbound document, or extraction result can be converted into a controlled quote candidate and, when deterministic validation allows it, an internal `DraftQuote`.

Supported sources:

- `CHANNEL_MESSAGE`
- `INBOUND_DOCUMENT`
- `EXTRACTION_RESULT`
- Telegram-originated messages when stored as `ChannelMessage` metadata

## Non-goals

- No production ERP, 1C, accounting, warehouse, or external connector writes.
- No bot, AI worker, channel adapter, or frontend path can create an approved quote.
- No autonomous selling or automated customer commitment.
- No marketplace, analytics expansion, mobile scope, or no-code bot builder.

## Source-to-Quote Flow

1. API receives a tenant-scoped source id and conversion request.
2. Backend resolves the source by tenant and returns tenant-safe not found for cross-tenant ids.
3. Backend loads extraction lines when available, or a conservative message-text candidate for channel messages.
4. Customer is resolved from the request override or source customer link.
5. Source candidate lines are validated for required line data and quantity before draft creation.
6. `QuoteDraftService` performs the existing deterministic quote validation for product matching, UOM, inventory, pricing, discount, margin, substitution, review, and approval state.
7. `quote_conversion_attempt` records the candidate status, request mode, and validation summary.
8. Successful draft creation writes `quote_source_link` to preserve source traceability.
9. Audit events are emitted for attempts, dry-runs, rejection/review, draft creation, and source linking.
10. The response reflects downstream quote validation. If `QuoteDraftService` creates a quote that requires review or approval, the channel-to-quote response remains review-required rather than presenting the result as ready.

## API Endpoints

- `POST /api/v1/quote-transactions/from-channel-message/{messageId}`
- `POST /api/v1/quote-transactions/from-inbound-document/{documentId}`
- `POST /api/v1/quote-transactions/from-extraction/{extractionId}`
- `GET /api/v1/quote-transactions/conversion-attempts/{attemptId}`
- `GET /api/v1/quotes/{quoteId}/source-context`

Request body supports `idempotencyKey`, `requestedCustomerAccountId`, `requestedQuoteType`, `operatorNotes`, `dryRun`, `forceReview`, `selectedLineItemIds`, `selectedSubstituteIds`, `actorId`, and `actorType`.

## Data Model Additions

- `quote_conversion_attempt`: tenant, source type/id, status, quote id, failure code/message, validation summary, actor, actor type, idempotency key, request mode.
- `quote_source_link`: tenant, quote id, source type/id, channel, external reference, received timestamp, actor, actor type, metadata.

## Candidate Statuses

- `READY_FOR_DRAFT_QUOTE`
- `NEEDS_REVIEW`
- `REJECTED_INVALID_SOURCE`
- `REJECTED_NO_LINE_ITEMS`
- `REJECTED_CUSTOMER_UNRESOLVED`
- `REJECTED_VALIDATION_FAILED`

## Audit Events

- `CHANNEL_TO_QUOTE_ATTEMPTED`
- `CHANNEL_TO_QUOTE_DRY_RUN`
- `CHANNEL_TO_QUOTE_REJECTED`
- `CHANNEL_TO_QUOTE_REVIEW_REQUIRED`
- `CHANNEL_TO_QUOTE_DRAFT_CREATED`
- `QUOTE_SOURCE_LINKED`

Each event includes tenant context, source type/id, conversion attempt id when available, quote id when present, actor type, validation reason codes, and `externalExecution=DISABLED`.

## Idempotency

When `idempotencyKey` is supplied, the unique tuple is tenant, source type, source id, idempotency key, and request mode. Request mode is `DRY_RUN` or `CREATE`, so a dry-run preview cannot block a later real draft creation using the same source key. A replay within the same mode returns the prior conversion attempt and quote id instead of creating a duplicate quote. Without an explicit key, the service uses a source-based idempotency key for the downstream draft service but does not over-deduplicate unrelated operator retries at the conversion-attempt layer.

Failed and review-required attempts are persisted with their validation summary. Reusing the same idempotency key and request mode intentionally returns the previous attempt; operators should use a new idempotency key when retrying after correcting source data or customer selection.

## Review And Rejection Behavior

- `dryRun=true` creates no `DraftQuote` and no `quote_source_link`.
- `NEEDS_REVIEW` records a conversion attempt and audit event, but does not create a quote from unresolved customer context.
- `REJECTED_NO_LINE_ITEMS` and `REJECTED_VALIDATION_FAILED` record a failed attempt and audit event, but do not create a quote.
- Successful draft creation always creates `quote_source_link`, even if the draft quote itself is in review because deterministic quote validation found product, price, stock, margin, discount, or substitution issues.
- `selectedLineItemIds` are filtered against lines loaded from the tenant-scoped source; selecting a line outside the source creates a blocking validation issue.

## Security Constraints

- Every endpoint requires `X-Tenant-Id`.
- Source lookup is tenant-scoped.
- Actor permission is checked against `CREATE_DRAFT_QUOTE`.
- Bot/API/channel requests only enter draft/review workflow and cannot approve quotes.
- AI/extraction data is advisory; backend validation decides.
- Audit metadata stores reason codes and references, not full raw message/document payload content.

## Gate Review Evidence

- Backend service tests cover successful channel and document conversion, dry-run, idempotency replay, dry-run/create idempotency separation, unresolved customer review, no-line rejection, downstream invalid SKU propagation, selected-line source isolation, source-context tenant isolation, bot draft-only behavior, audit metadata, and source-link creation.
- Frontend tests cover conversion API client endpoints, source-page conversion controls, review/rejected messages, validation issue display, and quote source-context rendering.

## Deferred Items

- `selectedSubstituteIds` is accepted by the Stage 12B conversion request DTO for compatibility, but conversion does not apply substitutes. Stage 12C adds the dedicated backend command path for substitute decisions under `/api/v1/quote-review/{quoteId}/lines/{lineId}/substitutes/select`.
- Document conversion depends on existing extraction results. No new OCR, AI, or document parsing layer is introduced in Stage 12B.
- Source preview for plain text channel messages uses conservative line inference only when no extraction result exists; final product/price/inventory validation still happens in `QuoteDraftService`.

## Acceptance Criteria

- Valid `ChannelMessage` creates a tenant-scoped `DraftQuote`.
- Valid `InboundDocument` or extraction result creates a tenant-scoped `DraftQuote`.
- Created `DraftQuote` links back to source context.
- Dry-run conversion previews candidate and validations without quote creation.
- Review-required path does not create an approved quote.
- Idempotency prevents duplicate quote creation.
- Tenant isolation returns tenant-safe not found.
- Audit events are emitted.
- No production connector write or autonomous AI/bot approval is introduced.

## Manual Verification

Backend tests:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn test '-Dtest=ChannelToQuoteWiringServiceTest,QuoteTransactionControllerTest'
```

Frontend tests:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd test
npm.cmd run typecheck -- --incremental false
```

Local stack:

```powershell
cd C:\OrderPilot\OrderPilot-Core
.\scripts\start-local-demo.ps1
```

Dry-run with curl:

```bash
curl -X POST "http://localhost:8080/api/v1/quote-transactions/from-channel-message/{messageId}" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: {tenantId}" \
  -d '{"idempotencyKey":"manual-channel-preview-1","requestedCustomerAccountId":"{customerAccountId}","dryRun":true,"actorId":"{actorId}","actorType":"USER"}'
```

Create draft quote:

```bash
curl -X POST "http://localhost:8080/api/v1/quote-transactions/from-inbound-document/{documentId}" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: {tenantId}" \
  -d '{"idempotencyKey":"manual-document-draft-1","requestedCustomerAccountId":"{customerAccountId}","dryRun":false,"actorId":"{actorId}","actorType":"API"}'
```
