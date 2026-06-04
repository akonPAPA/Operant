# Bot Runtime Security Notes

## Security Constraints

- No ERP/1C production writes.
- No autonomous quote, order, discount, or substitute approval.
- No direct bot-to-database business mutation outside tenant-scoped backend services.
- No customer/product/price/inventory master-data mutation by the bot.
- No unrestricted tenant data search.
- No final delivery promise when stock freshness is unknown or stale.

## Tenant Isolation

All runtime records include `tenant_id`. Services resolve the active tenant from `TenantContext` and use tenant-scoped repositories for conversations, messages, handoffs, products, inventory, customers, pricing, and source messages.

Cross-tenant access is blocked by repository lookups and existing controller/interceptor tenant policy.

## Webhook Hardening

Telegram inbound payloads are verified through `TelegramSecretTokenVerifier`. Unsupported update types are ignored safely. Empty or malformed message text is rejected. Channel ingestion deduplicates external message ids, and the bot runtime also idempotently ignores duplicate Telegram update/message ids.

## Abuse Controls

- per-tenant/per-conversation rate limit events are recorded
- excessive messages produce `BOT_RATE_LIMITED` and handoff
- message content is persisted as source evidence but not logged as unrestricted diagnostic output
- unsupported attachments are handled by the existing channel validation path
- response templates are plain text and not rendered as raw HTML

## Policy Rules

The allowed flow registry is:

- `GREETING`
- `CHECK_AVAILABILITY`
- `CHECK_PRICE`
- `REQUEST_QUOTE`
- `SUGGEST_SUBSTITUTE`
- `ORDER_OR_QUOTE_STATUS`
- `HUMAN_HANDOFF`
- `UNSUPPORTED_REQUEST_SAFE_REPLY`

Disabled bot state or disabled flow state creates `BOT_POLICY_DENIED` audit and routes to handoff.

Price checks require resolved customer identity. Substitute suggestions never show blocked substitutes and route approval-required substitutes to handoff. RFQ requests are review-only and use Stage 12B/12C paths.

## Audit

Important bot actions emit audit metadata with:

- tenant id
- bot connection id
- conversation id
- channel message id
- customer id when resolved
- intent and flow
- policy decision and reason code
- quote id or handoff id when applicable
- actor type
- `externalExecution=DISABLED`

## Known Limitations

- Real Telegram outbound calls are disabled in this stage.
- Bot status lookup is not automated.
- Full async queue processing is not yet separated from the service method, although heavy processing is avoided and idempotency is enforced.
