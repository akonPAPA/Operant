# Quote Transactions API

Stage 12B adds source-to-draft-quote endpoints under `/api/v1/quote-transactions`.

All calls require `X-Tenant-Id`.

## Create From Channel Message

`POST /api/v1/quote-transactions/from-channel-message/{messageId}`

```json
{
  "idempotencyKey": "telegram-rfq-123",
  "requestedCustomerAccountId": "00000000-0000-0000-0000-000000000000",
  "requestedQuoteType": "RFQ",
  "operatorNotes": "Customer asked for urgent delivery",
  "dryRun": true,
  "forceReview": false,
  "selectedLineItemIds": [],
  "selectedSubstituteIds": {}
}
```

The request body carries **business intent only**. The tenant is supplied via the `X-Tenant-Id`
header and the acting user is resolved by the backend from the trusted actor context. `actorId`,
`actorType`, `tenantId`, `sourceId`, and any approval/execution/status fields are backend-owned
authority and are **never** read from the request body; if supplied they are ignored.

## Create From Inbound Document

`POST /api/v1/quote-transactions/from-inbound-document/{documentId}`

Uses the same request shape as channel message conversion. If extraction lines exist for the document, they are used as candidate lines.

## Create From Extraction Result

`POST /api/v1/quote-transactions/from-extraction/{extractionId}`

Uses extracted line items directly from the selected extraction result.

## Get Conversion Attempt

`GET /api/v1/quote-transactions/conversion-attempts/{attemptId}`

Returns the conversion status, quote id when created, source type, and validation issues (the same operator-safe shape documented under Response Shape).

## Get Quote Source Context

`GET /api/v1/quotes/{quoteId}/source-context`

Returns an operator-safe source summary: source type, source channel, source external reference,
received timestamp, conversion status, candidate line count, review flag, and validation summary.
Internal identifiers (raw source id, conversion attempt id, triggering/creating actor id, raw source
metadata) are **not** part of this response — diagnostics live behind a separate admin endpoint.

## Response Shape

```json
{
  "status": "READY_FOR_DRAFT_QUOTE",
  "quoteId": "00000000-0000-0000-0000-000000000000",
  "sourceType": "CHANNEL_MESSAGE",
  "customerResolution": "RESOLVED",
  "lineCount": 1,
  "acceptedLineCount": 1,
  "validationIssues": [],
  "reviewRequired": false
}
```

The response returns operator-safe workflow output only. The conversion attempt id exists internally
for runtime/bot correlation but is `@JsonIgnore`d on the public response, so it is not part of the
public JSON. Raw `sourceId`, `auditEventIds`, and other internal/storage/audit identifiers are not
exposed on the public response.

`dryRun=true` never creates a quote. Review and rejection statuses do not approve quotes or execute external writes.

## Idempotency

Idempotency is scoped by tenant, source type, source id, request mode, and idempotency key. `DRY_RUN` and `CREATE` are separate modes, so previewing a source does not block later draft creation with the same source key.

## Source Selection

`selectedLineItemIds` must refer to extracted line items loaded from the same tenant-scoped source. Unknown or foreign line ids produce a blocking `SELECTED_LINE_NOT_IN_SOURCE` validation issue and do not create a quote.
