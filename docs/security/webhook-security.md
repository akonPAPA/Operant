# Webhook Security

Stage 3 webhook endpoints are intake-only. They preserve raw payloads, create normalized records, and queue processing placeholders. They must not trigger quote, order, ERP, accounting, or warehouse writes.

## Rules

- Require tenant context for local Stage 3 testing.
- Treat all webhook payloads as untrusted.
- Preserve raw payloads through the object storage abstraction.
- Use configured secret tokens when available.
- Mark unsigned local traffic as dev-only.
- Return structured errors for malformed JSON and validation failures.
- Do not fetch external attachment URLs in webhook handlers.
- Do not run OCR, file parsing, or AI in the request thread.

## Token Headers

- Email stub: `X-OrderPilot-Webhook-Token`
- Telegram stub: `X-Telegram-Bot-Api-Secret-Token` or `X-OrderPilot-Webhook-Token`

If `orderpilot.webhooks.email.dev-token` or `orderpilot.webhooks.telegram.secret-token` is configured, the matching token is required. If no token is configured, `orderpilot.webhooks.dev-accept-unsigned=true` allows local development only.
