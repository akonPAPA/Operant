# OP-CAP-06A — Messenger Chatbot Integration Layer (Channel ↔ Bot Runtime Bridge)

Status: implemented (controlled slice). Authorized by `docs/product/current-stage.md` and
`docs/product/STAGE_STATUS_RECONCILIATION.md` section 16. This is **not** "Stage 15". Stage 14 was
infrastructure / root-cause merge / CI / CodeQL stabilization.

## 1. What this slice adds

The messenger/chatbot integration layer (secure `channel.ChannelConnection` model, provider webhook
intake, normalized `InboundChannelEvent` ledger, controlled `BotRuntimeService`, and dashboard surfaces)
already existed from Stages 7/10/12. The genuine gap was that the **secure per-connection channel intake
path did not drive the controlled bot runtime** — there were two disconnected Telegram paths.

OP-CAP-06A bridges them with no new connection/message model:

```
Telegram webhook
  -> POST /api/v1/webhooks/channels/bot/telegram/{connectionId}
  -> ChannelEventNormalizationService.normalize()   (tenant ownership + provider match + ACTIVE + verify + replay dedup)
  -> ChannelBotRuntimeBridgeService                  (idempotency short-circuit + provider gating)
  -> BotRuntimeService.handleTelegramUpdate()        (intent + policy + controlled flow)
  -> reviewable RFQ / draft / handoff (internal only)
  -> InboundChannelEvent linked to BotConversation/BotMessage (status ROUTED)
  -> audit timeline; externalExecution=DISABLED
```

## 2. Endpoints

- `POST /api/v1/webhooks/channels/bot/telegram/{connectionId}` — provider-verified webhook that drives the
  controlled bot runtime. Lives under the existing un-permissioned webhook prefix; trust is enforced by the
  managed connection + verifier, never by request-body tenant claims.
- `GET /api/v1/channels/bot-events` — operator read (inherits `ADMIN_SETTINGS_READ`) linking normalized
  channel events to their bot conversation / runtime status. No secrets or raw payloads.

Dashboard: `/messenger-bridge` (read-only) shows provider, normalized message, event status, verification,
bot runtime status, and the linked bot conversation. The existing per-connection webhook
`/api/v1/webhooks/channels/telegram/{connectionId}` (intake-only) is unchanged for backward compatibility.

## 3. Allowed in this slice

- Bridge verified Telegram intake into the existing controlled bot flows.
- Link `InboundChannelEvent` ↔ `BotConversation`/`BotMessage` (additive columns; status `ROUTED`).
- Create reviewable RFQ / draft / handoff through existing bot command paths only.

## 4. Limitations (explicitly not in this slice)

- No autonomous outbound Telegram/WhatsApp sends — outbound transport remains a no-op; `externalExecution=DISABLED`.
- No ERP / 1C / accounting / warehouse / connector writes.
- No final quote, order, or discount approval by the bot.
- No inventory, price, customer, or product master-data mutation by the bot.
- Telegram-first. WhatsApp/Viber/Meta/WeChat remain intake-only here (the bridge returns `NOT_BRIDGED` and
  stores the event); the provider-agnostic extension point is preserved.
- Provider credentials are handled by secret **reference** only (`secretRef`/`secretReferenceId`); raw bot
  tokens are never stored in plaintext columns, returned in DTOs, logged, or written to audit metadata.
- No no-code bot builder and no broad AI-agent tool-use layer.

## 5. Idempotency

- Provider replay dedup: existing unique index `uq_inbound_channel_event_external_id (tenant_id,
  provider_type, external_event_id)` plus payload-hash dedup in `ChannelEventNormalizationService`.
- Bridge short-circuit: a re-delivered event already linked to a bot conversation returns
  `DUPLICATE_IGNORED` and drives no new bot workflow.
- Bot-layer dedup: `BotRuntimeService` deduplicates `BotMessage` by tenant + channel + chat + message id.
- Net effect: duplicate delivery creates no duplicate conversation, message, RFQ, draft, or handoff.

## 6. Local/demo webhook simulation (no real token required)

```powershell
# 1. Create + activate a Telegram channel connection (returns {id})
curl -s -X POST http://localhost:8080/api/v1/channels/connections `
  -H "X-Tenant-Id: <seeded-tenant-id>" -H "Content-Type: application/json" `
  -d '{"providerType":"TELEGRAM","displayName":"Telegram","secretRef":"vault://telegram/dev-ref"}'
curl -s -X POST http://localhost:8080/api/v1/channels/connections/<id>/activate -H "X-Tenant-Id: <seeded-tenant-id>"

# 2. Drive the controlled bot runtime with a simulated Telegram update (no real token)
curl -s -X POST http://localhost:8080/api/v1/webhooks/channels/bot/telegram/<id> `
  -H "X-Tenant-Id: <seeded-tenant-id>" -H "Content-Type: application/json" `
  -d '{"update_id":"1","message":{"message_id":"m1","chat":{"id":"chat-1"},"text":"Please quote 10 of BRK-100"}}'

# 3. Inspect the linkage
curl -s http://localhost:8080/api/v1/channels/bot-events -H "X-Tenant-Id: <seeded-tenant-id>"
```

Verification mode stays `DISABLED_FOR_LOCAL_DEV` unless a connection configures a real verifier.

## 7. Verification

- `mvn -Dtest=ChannelBotRuntimeBridgeServiceTest,ChannelEventNormalizationServiceTest,ChannelConnectionServiceTest,BotRuntimeServiceTest,BotTelegramWebhookControllerTest,ChannelWebhookSecurityTest,TelegramWebhookVerifierTest,ChannelToQuoteWiringServiceTest test` → 69 passed, 0 failed.
- `node --test tests/*.test.mjs` (web-dashboard) → 64 passed, 0 failed.
- `npx eslint .`, `npx tsc --noEmit`, `npm run build` → clean; `/messenger-bridge` route emitted.

## 8. Migration

`V34__channel_bot_runtime_bridge.sql` — additive only: adds `bot_conversation_id`, `bot_message_id`,
`bot_runtime_status` to `inbound_channel_event` and a tenant-scoped linkage index. No table renames, no data
deletion, no new unique constraint (replay idempotency already covered by the existing unique index).
