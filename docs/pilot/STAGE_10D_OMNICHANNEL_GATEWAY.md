# Stage 10D - Omnichannel Gateway v1

Stage 10D adds a safe inbound Channel Gateway so OrderPilot is not dependent on Telegram as the product channel.

Telegram remains demo-friendly, but it is not a product dependency. WhatsApp is a first-class target channel, alongside email, API, web upload, and future Viber/Messenger adapters.

Stage 10C remains the ChangeRequest and Transactional Outbox PASS stage. Omnichannel Gateway and WhatsApp-ready Adapter verification recovery belong to Stage 10D.

## Scope

- Inbound-only normalized channel messages.
- Mock/sandbox-ready WhatsApp webhook parsing.
- Telegram alignment through the same `ChannelGatewayService`.
- Email/API compatibility through the shared normalized message contract.
- Tenant-scoped `ChannelMessage` persistence.
- Audit-compatible inbound gateway events.

## Safety Boundary

- No real WhatsApp credentials are required.
- No real Meta app secret is required.
- No outbound WhatsApp production sending is implemented.
- No real Telegram token is required.
- No ERP, 1C, accounting, warehouse, CRM, payment, or external connector writes are enabled.
- No real AI provider integration is added.
- Channel adapters only create normalized inbound events/messages through backend service contracts.
- Channel adapters do not create quotes, orders, ChangeRequests, or master-data mutations.

## Runtime Routes

- `POST /api/v1/channel-gateway/messages`
- `POST /api/v1/channel-gateway/whatsapp/webhook`

## Future Work

- Meta app setup.
- Webhook signature verification with a real secret.
- Business phone onboarding.
- Outbound approved message templates.
- Viber and Messenger adapters.
- Rate limits per channel.
- Abuse and spam controls.
- Customer identity linking UI.
