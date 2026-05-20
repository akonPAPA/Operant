# Webhook Security

Webhook payloads are untrusted input.

## Stage 3 controls

- WebhookEvent stores provider, external event id, raw payload, headers placeholder, status, and replay marker.
- WebhookSecurityService exposes signature verification and replay detection placeholders.
- Duplicate external event ids can be detected per provider.
- Telegram and WhatsApp webhook routes do not send business replies or mutate business data.

## Limitations

Production-grade provider signature validation is not complete in Stage 3. Each provider must get a real verifier before production use.