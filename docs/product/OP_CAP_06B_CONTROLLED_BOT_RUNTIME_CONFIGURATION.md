# OP-CAP-06B — Controlled Bot Runtime Configuration / Bot Builder Lite (in-app)

Date: 2026-06-04
Status: implemented (controlled configuration layer)

> This is **not** "Stage 15" and not a new product stage. Stage 14 was infrastructure / root-cause
> merge / CI / CodeQL stabilization. OP-CAP-06B is the next product capability after OP-CAP-06A and
> is authorized in `docs/product/STAGE_STATUS_RECONCILIATION.md` section 16.

## 1. Purpose

OP-CAP-06B lets an admin/operator configure, from inside OrderPilot, **which controlled bot flows are
enabled per tenant + channel connection, how risky flows are routed, what safe response policies
apply, and how human handoff behaves** — without turning the bot into a free-form AI agent or a
no-code chatbot builder.

## 2. Relationship to OP-CAP-06A

- OP-CAP-06A connected verified channel intake (`channel.ChannelConnection` →
  `ChannelEventNormalizationService`) into the controlled bot runtime (`BotRuntimeService`) via
  `ChannelBotRuntimeBridgeService`.
- OP-CAP-06B **configures what that controlled runtime is allowed to do** for each connection. It
  reuses the existing bridge and runtime; it does not add a second runtime, a second channel/event
  ledger, or a parallel architecture.

## 3. Runtime safety model

```
verified channel webhook
  -> ChannelEventNormalizationService (tenant/provider/verification/replay-dedup)
  -> ChannelBotRuntimeBridgeService
       -> BotRuntimeConfigurationService.getOrCreateDefaultForConnection(connectionId)  [safe default]
       -> RuleBasedBotIntentClassifier.detect(text) -> BotFlow
       -> BotRuntimePolicyService.decide(config, flow, context) -> BotFlowPolicyDecision
            if NOT allowed -> mark event CONFIG_BLOCKED, audit, return safe handoff result (runtime NOT invoked)
            if allowed     -> BotRuntimeService.handleTelegramUpdate(...) (existing controlled flows)
  -> InboundChannelEvent linked to BotConversation / BotMessage
  -> audit trail; externalExecution=DISABLED
```

The configuration can only **constrain** the bot. A disabled or policy-blocked flow never reaches
the runtime, so it cannot produce an RFQ/draft/price/availability output. The runtime's own deeper
safety (unknown-customer → review, stale-inventory → handoff, operator-review routing) still applies
to allowed flows.

## 4. Configuration options

Per `channel_connection`, tenant-scoped (`channel_bot_runtime_configuration`):

| Field | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | bool | true | Master switch for the connection's bot. |
| `greetingEnabled` | bool | true | Allow safe greeting replies. |
| `availabilityCheckEnabled` | bool | true | Allow availability flow (runtime still checks freshness). |
| `priceCheckMode` | `BotFlowMode` | OPERATOR_REVIEW_ONLY | Price flow handling. |
| `rfqCaptureMode` | `BotFlowMode` | OPERATOR_REVIEW_ONLY | RFQ capture handling. |
| `substituteSuggestionMode` | `BotFlowMode` | OPERATOR_REVIEW_ONLY | Substitute flow handling. |
| `orderStatusMode` | `BotFlowMode` | DISABLED | Order-status flow handling. |
| `unknownCustomerMode` | `UnknownCustomerMode` | HANDOFF | Behavior when sender is not a known customer. |
| `humanHandoffEnabled` | bool | true | Allow human handoff. |
| `handoffQueueKey` | string | BOT_REVIEW | Operator queue for handoffs. |
| `inventoryFreshnessMaxMinutes` | int | 1440 | Snapshot age threshold. |
| `inventoryFreshnessPolicy` | `InventoryFreshnessPolicy` | WARN_AND_HANDOFF | Stale-stock behavior. |
| `priceVisibilityPolicy` | `PriceVisibilityPolicy` | IDENTIFIED_CUSTOMER_ONLY | When price may be considered. |
| `safeGreetingTemplate` / `safeFallbackTemplate` / `handoffTemplate` | string | safe copy | Response copy only — never executed. |

Enums: `BotFlowMode` = DISABLED / OPERATOR_REVIEW_ONLY / CONTROLLED_DRAFT / CONTROLLED_RESPONSE;
`PriceVisibilityPolicy` = NEVER / IDENTIFIED_CUSTOMER_ONLY / AUTHORIZED_CUSTOMER_ONLY;
`UnknownCustomerMode` = HANDOFF / SAFE_GENERIC_REPLY / REJECT;
`InventoryFreshnessPolicy` = STRICT / WARN_AND_HANDOFF / ALLOW_WITH_WARNING.

Rejected unsafe combinations (HTTP 400): price visibility not `NEVER` while `priceCheckMode=DISABLED`;
`priceCheckMode=CONTROLLED_RESPONSE` with `NEVER`; `CONTROLLED_RESPONSE` price with
`unknownCustomerMode=SAFE_GENERIC_REPLY`; any `OPERATOR_REVIEW_ONLY` flow while handoff disabled;
freshness threshold outside 1..10080; templates that are empty, >500 chars, or contain `<`/`>`.

## 5. API

Routed under `/api/v1/bot-runtime/**` so it inherits the existing permission mapping
(`BOT_READ` for GET, `BOT_ACTION` for writes); tenant is always resolved server-side from
`X-Tenant-Id`, never from the request body.

- `GET /api/v1/bot-runtime/configurations` — eligible connections + config status.
- `GET /api/v1/bot-runtime/configurations/{connectionId}` — full config (creates a safe default if absent).
- `PUT /api/v1/bot-runtime/configurations/{connectionId}` — update safe fields (validated, audited).
- `POST /api/v1/bot-runtime/configurations/{connectionId}/reset-defaults` — restore safe defaults.

## 6. Security boundaries

- Tenant isolation: `TenantContext` + connection ownership on every read/write.
- No tokens/secrets/credentials/raw payloads in entity, DTOs, API, audit, logs, or UI.
- Audit events: `BOT_RUNTIME_CONFIG_DEFAULT_CREATED`, `BOT_RUNTIME_CONFIG_UPDATED`,
  `BOT_RUNTIME_CONFIG_RESET_DEFAULTS`, and `BOT_CHANNEL_EVENT_BLOCKED_BY_CONFIG` (safe metadata only).
- `externalExecution=DISABLED` preserved end-to-end.

## 7. Explicit limitations

- **Telegram-first.** WhatsApp/Viber/Meta/WeChat are listed as bot-capable and configurable, but the
  bridge runs the controlled runtime for Telegram only; other providers remain intake-only
  (`NOT_BRIDGED`).
- **No outbound sends, no ERP/1C/connector writes, no autonomous approvals.** The bot can only create
  reviewable RFQ/draft/handoff records through existing command services.
- **Bridge treats inbound as unidentified.** Per-customer identity resolution stays in the runtime;
  the bridge conservatively routes identity-dependent flows (price/status) to review. Full
  identity-aware price disclosure via the bridge is a documented follow-up.
- **No no-code flow-graph builder, no DSL, no prompt/tool execution.** Templates are response copy
  only and are never executed.
- **Frontend `/bot-runtime` is a read surface.** Updates are applied through the permissioned API
  (`PUT`, `BOT_ACTION`). An interactive in-page editor is the recommended next slice.
- **Controller-level permission enforcement is inherited** from the existing `/api/v1/bot-runtime`
  interceptor mapping (`BOT_READ`/`BOT_ACTION`); a dedicated `@WebMvcTest` is a recommended follow-up.

## 8. Verification evidence

Backend (H2 `test` profile; schema via Hibernate `create-drop`, V35 applies on the Postgres
integration profile):

- `BotRuntimePolicyServiceTest` — 5 passed.
- `BotRuntimeConfigurationServiceTest` — 6 passed.
- `ChannelBotRuntimeConfigGatingTest` — 3 passed (disabled RFQ blocks runtime/no draft; disabled price
  produces no price output; tenant A config does not affect tenant B).
- `ChannelBotRuntimeBridgeServiceTest` (OP-CAP-06A regression) — 8 passed.
- `ChannelEventNormalizationServiceTest` — 3 passed; `ChannelConnectionServiceTest` — 3 passed.

Frontend (from `apps/web-dashboard`):

- `node --test tests/bot-runtime-settings.test.mjs tests/messenger-bridge.test.mjs` — 12 passed.
- `node --test tests/*.test.mjs` — 70 passed.
- `npx eslint .` — clean; `npx tsc --noEmit --incremental false` — clean; `npm run build` — success
  (`/bot-runtime` route emitted).

## 9. Local/demo

To simulate without a real Telegram token: create + activate a Telegram `ChannelConnection`
(secret stored by reference only), `GET /api/v1/bot-runtime/configurations/{connectionId}` to
materialize a safe default, optionally `PUT` to disable a flow, then `POST` a Telegram update to
`/api/v1/webhooks/channels/bot/telegram/{connectionId}` and observe the bridge result + audit trail.
