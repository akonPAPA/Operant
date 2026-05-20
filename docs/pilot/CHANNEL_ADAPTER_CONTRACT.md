# Channel Adapter Contract

Stage: Stage 10D omnichannel gateway.

Channel adapters normalize external inbound messages into one backend contract. They must not write business/master tables directly.

## Channel Types

- `TELEGRAM`
- `WHATSAPP`
- `EMAIL`
- `API`
- `WEB_UPLOAD`
- `VIBER_STUB`
- `MESSENGER_STUB`

## NormalizedInboundMessage

- `tenant_id`
- `channel_type`
- `external_message_id`
- `external_conversation_id`
- `external_sender_id`
- `sender_display_name`
- `sender_phone`
- `raw_text`
- `attachment_refs`
- `received_at`
- `raw_payload_json`
- `idempotency_key`

## Required Behavior

- Resolve tenant from the current backend tenant context.
- Reject a normalized tenant id that conflicts with the current tenant context.
- Deduplicate by tenant, channel, and external message id, with idempotency key as the fallback message id.
- Persist only normalized `ChannelMessage` records.
- Mark messages `PENDING_PROCESSING`.
- Emit audit-compatible `CHANNEL_GATEWAY_MESSAGE_RECEIVED` events.
- Optionally enqueue internal message processing jobs.
- Do not create final quotes, orders, ChangeRequests, ERP writes, inventory writes, customer writes, or product writes.

## Product Positioning

Telegram is demo-friendly but not the product dependency. WhatsApp is a first-class target channel. Email/API/web-upload channels use the same gateway contract, and Viber/Messenger remain future adapters.
