# Stage 10E - Channel Identity and Signature Contracts

Status: PASS

Stage 10E adds tenant-scoped channel identity linking and webhook signature verification contracts for inbound WhatsApp and Telegram-style adapters.

## Scope

- `channel_identity` stores external channel sender identities by tenant, channel, and sender id.
- Inbound channel messages can reference a ChannelIdentity.
- Linked identities can attach `customer_account_id` and `customer_contact_id` context for future processing.
- Unlinked identities remain review-safe.
- Blocked identities are flagged and not queued for normal processing.
- WhatsApp and Telegram webhook verification contracts report explicit local modes.

## Safety Boundary

- No real WhatsApp token is committed or required.
- No real Meta app secret is committed or required.
- No outbound WhatsApp production sending is implemented.
- No ERP, 1C, accounting, warehouse, CRM, payment, or external connector writes are enabled.
- No real AI provider integration is added.
- Channel adapters do not create quotes, orders, ChangeRequests, or master-data mutations.
- Signature verification does not claim production verification until provider-specific secrets are configured later.

## Runtime Contracts

- `GET /api/v1/channel-identities`
- `GET /api/v1/channel-identities/{id}`
- `POST /api/v1/channel-identities/{id}/link`
- `POST /api/v1/channel-identities/{id}/unlink`
- `POST /api/v1/channel-identities/{id}/block`
- WhatsApp fixture mode can be signaled with `X-OrderPilot-Fixture-Mode: true`.
- Required signature verification without configured secrets fails safely.

## Remaining Product Blockers

- Production secret management.
- Real Meta app setup.
- Real Telegram webhook secret setup.
- Customer identity linking UI.
- Abuse, spam, and rate-limit hardening.
- Outbound approved template messaging.
- Production auth/RBAC proof.
