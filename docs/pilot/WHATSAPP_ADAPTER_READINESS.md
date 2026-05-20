# WhatsApp Adapter Readiness

Stage: Stage 10D inbound-only/mock-ready adapter.

The WhatsApp adapter parses WhatsApp Business-style webhook payloads and converts supported inbound text messages into normalized `ChannelMessage` records through `ChannelGatewayService`.

## Supported Payload Shape

- `object`
- `entry[]`
- `changes[]`
- `value`
- `messages[]`
- `contacts[]`

Only inbound text messages are accepted in Stage 10D. Unsupported message types are ignored safely.

## Safety Boundary

- No real WhatsApp production token is required.
- No real Meta app secret is required.
- Signature verification exists only as a placeholder contract and reports `NOT_CONFIGURED_STAGE_10D`.
- No outbound WhatsApp production sending is implemented.
- No ERP, 1C, accounting, warehouse, CRM, payment, or connector writes are enabled.
- No real AI provider is called.

## Not Yet Implemented

- Meta app setup.
- Real webhook signature verification with a Meta app secret.
- Business phone onboarding.
- Outbound approved message templates.
- Per-channel rate limits.
- Abuse and spam controls.
- Customer identity linking UI.
