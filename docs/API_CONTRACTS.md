# API Contracts

## AI Understanding APIs

Phase 4 extraction APIs expose advisory read and run operations for tenant-scoped inbound documents and channel messages.

Read-only inspection:

```text
GET /api/v1/extractions/results
GET /api/v1/extractions/results/{id}
GET /api/v1/extractions/runs/{id}/result
GET /api/v1/extractions/runs/{id}/fields
GET /api/v1/extractions/runs/{id}/line-items
GET /api/v1/extractions/runs/{id}/evidence
GET /api/v1/extractions/runs/{id}/suggestions
GET /api/v1/extractions/sources/{sourceType}/{sourceId}/results
```

Run operation:

```text
POST /api/v1/extractions/runs/execute
```

Supported source types:

- `INBOUND_DOCUMENT`
- `CHANNEL_MESSAGE`

Supported document intents:

- `PURCHASE_ORDER`
- `RFQ`
- `AVAILABILITY_REQUEST`
- `PRICE_REQUEST`
- `ORDER_STATUS_REQUEST`
- `UNKNOWN`

## Business Authority Boundary

AI extraction APIs must not expose endpoints that directly mutate quotes, orders, inventory, customer accounts, products, price rules, discount rules, margin rules, users, roles, tenants, integration connections, or ERP state.

Deterministic validation and typed backend services own final business decisions. Human approval is required for risky actions and external writes.

## Validation APIs

Phase 5A validation APIs are tenant-scoped and read-only except for running validation. Running validation creates validation records, issues, substitute candidates, approval requirements, and audit events; it does not create quotes, orders, inventory movements, price changes, customer changes, or ERP writes.

```text
POST /api/v1/extractions/results/{id}/run-validation
GET /api/v1/extractions/results/{id}/validation
POST /api/v1/validations/runs
GET /api/v1/validations/runs
GET /api/v1/validations/runs/{id}
GET /api/v1/validations/runs/{id}/summary
GET /api/v1/validations/runs/{id}/issues
GET /api/v1/validations/runs/{id}/product-matches
GET /api/v1/validations/runs/{id}/uom-normalizations
GET /api/v1/validations/runs/{id}/inventory-checks
GET /api/v1/validations/runs/{id}/price-checks
GET /api/v1/validations/runs/{id}/discount-checks
GET /api/v1/validations/runs/{id}/margin-checks
GET /api/v1/validations/runs/{id}/substitute-candidates
GET /api/v1/validations/runs/{id}/approval-requirements
GET /api/v1/validations/sources/{sourceType}/{sourceId}/issues
```

Validation routing recommendations:

- `AUTO_READY_DRAFT_ALLOWED`: no blocking issues, warnings, or open approvals.
- `NEEDS_OPERATOR_REVIEW`: warnings or approval requirements exist.
- `BLOCKED_UNTIL_FIXED`: error or critical validation issues exist.

## Validation Review And Draft Preparation APIs

Phase 5B connects validation outcomes to the existing operator review workspace and internal draft preparation services.

```text
POST /api/v1/extractions/{extractionId}/validation/review-case
GET /api/v1/validation-review
GET /api/v1/validation-review/{reviewCaseId}
POST /api/v1/validation-review/{reviewCaseId}/approve
POST /api/v1/validation-review/{reviewCaseId}/reject
POST /api/v1/validation-review/{reviewCaseId}/corrections/uom
POST /api/v1/validation-review/{reviewCaseId}/corrections/quantity
POST /api/v1/validation-review/{reviewCaseId}/corrections/product
POST /api/v1/validation-review/{reviewCaseId}/substitutes/select
POST /api/v1/validation-review/{reviewCaseId}/substitutes/reject
POST /api/v1/validation-review/{reviewCaseId}/issues/acknowledge
POST /api/v1/validation-review/{reviewCaseId}/issues/override
POST /api/v1/validation-review/{reviewCaseId}/approvals/{approvalRequestId}/approve
POST /api/v1/validation-review/{reviewCaseId}/approvals/{approvalRequestId}/reject
GET /api/v1/validation-review/{reviewCaseId}/draft-preview?targetType=QUOTE
POST /api/v1/validation-review/{reviewCaseId}/prepare-draft-quote
POST /api/v1/validation-review/{reviewCaseId}/prepare-draft-order
```

Draft preparation endpoints are internal Core API operations. They may create internal draft quote/order records through existing typed services, but they do not approve quotes/orders, reserve inventory, mutate product/customer/pricing master data, create connector commands, or write to ERP/external systems.

Phase 6B correction endpoints are tenant-scoped command operations over review state. Operators may correct UOM/quantity, map a raw SKU to an existing product, select or reject substitute candidates, acknowledge non-blocking issues, or override an issue with a required reason. These commands update review/validation state only; they do not mutate product catalog, inventory, pricing, customer master data, ERP state, or external systems.

Phase 6C review detail responses include `draftPreparationAllowed`, `blockingReasons`, `issueStatuses`, `approvalRequirements`, `pendingApprovals`, `productCandidates`, `substituteCandidates`, `timeline`, and `correctionHistory`. Product candidates are derived from existing deterministic product match result candidate IDs and are tenant-scoped. Substitute candidates include existing risk, stock, margin, approval, and status cues where available.

Phase 6D review detail and draft preview responses include backend-authoritative readiness state: `readinessStatus`, `draftPreparationAllowed`, `blockingReasons`, `pendingApprovals`, `rejectedApprovals`, `resolvedApprovals`, and `nextRequiredActions`. Draft preview, draft quote preparation, and draft order preparation use the same readiness evaluator.

`INVALID_UOM_REQUIRES_REVIEW` is surfaced as review context, but draft readiness treats the underlying `INVALID_UOM` issue as a hard blocker until the UOM is corrected or explicitly overridden through validation review. Discount, margin, risky substitute, and low-confidence approval requirements are approval-backed blockers until approved.

Approval decision endpoints are tenant-scoped review commands. Approval requests support a decision reason through the existing review action request payload. Rejections require a reason; risky approvals such as discount, margin, low-confidence, and substitute approvals require a reason. Required rejected approvals remain hard blockers until the underlying issue is corrected or a new approval cycle is created.

The draft preview endpoint is safe and internal. It returns preview line items, selected product/substitute identifiers and labels, price, stock, margin, discount, validation status, blockers, and explicit `externalExecutionDisabled` / `inventoryReservationDisabled` flags. It does not create a draft, approve a quote/order, reserve inventory, create connector commands, or write to ERP.

Draft preparation rejection responses use `409 DRAFT_PREPARATION_BLOCKED` with `blockingReasons[]` entries containing `issueCode`, `severity`, `reason`, and `suggestedCorrectionAction`.

The Phase 6 dashboard consumes these endpoints through tenant-scoped API calls. UI actions are convenience controls over backend command services; the UI must display backend rejection reasons and must not suppress safety failures.

## Bot Runtime Lite APIs

Phase 7A bot runtime APIs are tenant-scoped and Telegram-oriented. They may capture inbound bot messages, deterministic intent classifications, RFQ request draft records, and human handoff records. They must not approve quotes/orders, approve discounts, approve substitutes, reserve inventory, mutate product/customer/price/inventory master data, create connector commands, or write to ERP.

```text
POST /api/v1/bot-runtime/messages/simulate
POST /api/v1/bot-runtime/telegram/webhook
GET /api/v1/bot-runtime/conversations
GET /api/v1/bot-runtime/conversations/{conversationId}
POST /api/v1/bot-runtime/conversations/{conversationId}/handoff
POST /api/v1/bot-runtime/conversations/{conversationId}/review-handoff
GET /api/v1/bot-runtime/conversations/{conversationId}/review-handoff
POST /api/v1/bot-runtime/conversations/{conversationId}/responses/draft
GET /api/v1/bot-runtime/conversations/{conversationId}/responses
POST /api/v1/bot-runtime/responses/{responseId}/mark-ready
POST /api/v1/bot-runtime/responses/{responseId}/stub-send
```

The simulation endpoint is for local/demo use. It accepts tenant-scoped text, classifies it with the bounded `BotIntent` vocabulary, applies `BotPolicyDecision`, stores bot runtime records, and returns a suggested safe response. Customer text is hostile input and cannot change policy.

The Telegram webhook endpoint accepts the Phase 7B subset of Telegram Update payloads: `update_id`, `message.message_id`, `message.chat.id`, `message.from.id`, `message.from.username`, `message.from.first_name`, `message.from.last_name`, `message.text`, and `message.date`. Unsupported update types are ignored without business writes. Empty text is rejected.

Telegram webhook verification supports local/demo fixture mode and configured secret-token verification. Configure the secret out of band with `ORDERPILOT_BOT_TELEGRAM_WEBHOOK_SECRET_TOKEN` / `orderpilot.bot.telegram.webhook-secret-token`; never commit real Telegram tokens. Production-style requests must present `X-Telegram-Bot-Api-Secret-Token`.

Phase 7C adds operator-assisted response drafting. Response text is deterministic template output only, policy-gated by `BotPolicyService`, and audit logged when drafted, marked ready, blocked, or stub-sent. `stub-send` uses the no-op Telegram transport and records local simulated send state only; it does not call Telegram, disclose unrestricted price/customer/product data, reserve stock, create connector commands, create final quotes/orders, approve anything, or write to ERP.

Phase 7D/7E adds conversation-to-operator-queue handoff. `review-handoff` creates or reuses an existing `ExceptionCase` with `sourceType=BOT_CONVERSATION` and `sourceId=<bot conversation id>`, then links it through `BotConversation.linkedReviewCaseId`. The handoff response exposes source channel, `sourceConversationId`, conversation id, source message id, detected intent, policy decision, latest message, RFQ request id when present, handoff reason, and descriptive `nextActions` such as `REQUEST_IDENTIFICATION`, `OPERATOR_REPLY_DRAFT`, `CREATE_MANUAL_RFQ_REVIEW`, `WAIT_FOR_CUSTOMER`, and `CLOSE_HANDOFF`.

Bot-originated handoff cases are operator queue cases, not validation-backed readiness cases. `/api/v1/validation-review/{reviewCaseId}` rejects bot-only cases with a clear not-validation-backed response unless a later phase explicitly converts them into extraction/validation-backed workflow records. Draft preview for a bot-only case returns `draftPreparationAllowed=false` with `BOT_HANDOFF_NOT_VALIDATION_BACKED`, and draft quote/order preparation is blocked without creating draft records. Bot-originated review handoff does not bypass Phase 6 readiness, manager approval, validation correction, or draft preparation gates.

Supported bot policy decisions:

- `ALLOW_SAFE_RESPONSE`
- `REQUIRE_HUMAN_HANDOFF`
- `BLOCK_UNSUPPORTED`
- `REQUIRE_CUSTOMER_IDENTIFICATION`
- `REQUIRE_OPERATOR_REVIEW`

## Stage 8 Commerce Intelligence APIs

Stage 8A adds tenant-scoped read-model analytics for the command center. These endpoints aggregate existing workflow records only; they do not create drafts, create connector commands, reserve inventory, send Telegram messages, call LLMs, or write to ERP.

```text
GET /api/stage8/analytics/command-center
GET /api/stage8/analytics/channel-volume
GET /api/stage8/analytics/operator-review
GET /api/stage8/analytics/bot-handoffs
```

The command-center response includes total inbound requests, bot-only handoff count, validation-backed review count, blocked unsafe bot-only draft attempts, exception rate, automation rate, draft quote/order preparation count, and channel mix. Bot-originated handoffs are counted from `ExceptionCase.sourceType=BOT_CONVERSATION` without validation/extraction backing and remain separate from validation-backed reviews.

`operator-review` exposes validation-backed review count, bot-only handoff count, open exception count, blocked unsafe draft attempts, average review cycle time where resolved timestamps exist, and discount/margin risk counts derived from existing validation issues and approval requirements. `bot-handoffs` focuses on bot-only handoff status and blocked bot-only draft preparation attempts from `DRAFT_PREPARATION_BLOCKED` audit events.

Stage 8B adds reconciliation read-model endpoints:

```text
GET /api/stage8/reconciliation/summary
GET /api/stage8/reconciliation/cases
GET /api/stage8/reconciliation/cases/{caseId}
GET /api/stage8/reconciliation/products/{productId}/timeline
POST /api/stage8/reconciliation/refresh
```

Inventory reconciliation compares mirrored movement records using the deterministic formula: opening stock plus purchases received, returns in, transfers in, and manual adjustments, minus sales, returns out, write-offs, and transfers out. Actual stock is taken from mirrored stock-count movement records. Unsupported movement types are reported in summary metadata; current Stage 8B movement types are supported by `inventory_movement`.

Reconciliation refresh creates or updates internal reconciliation cases and stale-inventory warnings only. It must not mutate `inventory_snapshot`, reserve inventory, create quote/order records, create connector commands, call Telegram, or write to ERP. Likely cause labels are constrained to `UNLINKED_ORDER_OR_INVOICE`, `MANUAL_ADJUSTMENT`, `STALE_INVENTORY_SNAPSHOT`, `MISSING_STOCK_MOVEMENT`, `POSSIBLE_SHIPMENT_DISCREPANCY`, and `UNKNOWN`.

Stage 8C adds pilot ROI and business-value analytics:

```text
GET /api/stage8/value/summary
GET /api/stage8/value/roi-assumptions
PUT /api/stage8/value/roi-assumptions
GET /api/stage8/value/leakage
GET /api/stage8/value/productivity
GET /api/stage8/value/export
```

Value analytics are tenant-scoped read models over existing workflow records. They estimate operator hours saved, labor cost saved, review and draft-preparation cycle time, blocked unsafe draft attempts, discount leakage, margin risk, substitute recovered revenue from draft lines, inventory discrepancy value where supporting value data exists, stale inventory risk, exception categories, and reconciliation issue categories.

ROI assumptions are stored per tenant in `roi_assumptions`: average manual handling minutes per request, fully loaded operator hourly cost, default currency, and attribution mode (`conservative`, `balanced`, or `aggressive`). If no tenant assumptions exist, endpoints return safe demo defaults and mark `defaultAssumptions=true`.

The export endpoint returns a JSON pilot ROI report payload with date range fields, inbound request volume, automation/exception rates, bot handoffs, draft quote/order counts, blocked unsafe attempts, estimated hours and labor savings, risk indicators, top exception categories, top reconciliation issues, and the assumptions used. These values are pilot indicators only. Draft quote/order amounts and substitute lines are not treated as closed revenue unless a future model explicitly adds accepted/closed revenue status.

## Stage 9 Integration Control APIs

Stage 9A adds a controlled integration facade for demo ERP proof points. It reuses tenant-scoped `IntegrationConnection`, `ChangeRequest`, `ConnectorSyncEvent`, and audit records. It does not add production ERP/1C writes, connector commands from bot handoffs, inventory reservation, inventory mutation, secrets, or external network calls.

```text
GET /api/stage9/integrations
GET /api/stage9/integrations/{connectionId}
POST /api/stage9/integrations/demo-erp
GET /api/stage9/change-requests
GET /api/stage9/change-requests/{id}
POST /api/stage9/change-requests
POST /api/stage9/change-requests/{id}/approve
POST /api/stage9/change-requests/{id}/reject
POST /api/stage9/change-requests/{id}/execute
GET /api/stage9/connector-sync-runs
```

`POST /api/stage9/change-requests` accepts approved validation-backed `DRAFT_QUOTE` or `DRAFT_ORDER` sources only. Bot-only handoffs and non-validation-backed cases are rejected before a `ChangeRequest` is created. Execution requires an approved `ChangeRequest` and routes only through the in-process Demo ERP adapter.

The Demo ERP adapter supports `CREATE_DRAFT_QUOTE`, `CREATE_DRAFT_ORDER`, and local status lookup behavior. It generates deterministic demo external references such as `DEMO-QUOTE-*` or `DEMO-ORDER-*`, records connector sync/audit events, supports simulated failure for tests, and performs no real network or external-system mutation.

Stage 9B adds connector safety and runbook-hardening endpoints:

```text
GET /api/stage9/connectors/policies
GET /api/stage9/change-requests/{id}/execution-safety
POST /api/stage9/change-requests/{id}/retry
POST /api/stage9/change-requests/{id}/cancel
GET /api/stage9/connector-sync-runs/{id}
GET /api/stage9/connector-audit
```

Execution policy reports `DEMO_ONLY`, capabilities, placeholder credential status, and production/network disabled flags. Stage 9B API responses expose `connectorIdempotencyKeyHash`, attempt count, max attempts, last/next retry timestamps, failure type/message, retryable state, and the external reference. Raw connector idempotency seeds are not stored in Stage 9B connector execution metadata, audit metadata, or frontend output.

Duplicate execution of an already executed demo ChangeRequest returns the existing external reference and records an idempotent replay audit event with `idempotencyKeyHash`, not a raw key. Failed executions require explicit manual retry and are allowed only when the failure is retryable and max attempts have not been reached. Cancel is allowed only before successful execution. Non-demo targets are blocked by policy and audited without creating `ConnectorCommand` records.

Stage 9B is closed for targeted demo-only integration-control safety. Stage 10 does not add product scope or production connector activation; it records full verification, security evidence, reliability runbooks, investor demo scripts, UAT checklist, and pilot-readiness documentation. Stage 10 full backend regression result: approved Maven `mvn test` passed with 312 tests, 0 failures, and 0 errors after sandboxed Maven was blocked by dependency/network restrictions. Production connectors remain disabled. Real ERP/1C writes remain out of scope.

## Security Contract

Every request must preserve authentication, tenant scoping, and permission checks. AI output remains advisory and tenant-owned. Prompt-injection text from customers is ordinary untrusted content and cannot alter system behavior.
