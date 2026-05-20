# Webhook Signature Verification Contract

Stage 10E defines provider-neutral webhook verification boundaries without requiring production secrets.

## Interface

`WebhookSignatureVerifier` exposes:

- `verify(requestHeaders, rawBody, channelType, tenantId)`
- `verificationMode()`
- `providerName()`

Modes:

- `NOT_CONFIGURED_STAGE_10E`
- `DISABLED_FIXTURE_MODE`
- `CONFIGURED_VERIFY_ONLY`
- `FAILED`

## WhatsApp

`WhatsAppSignatureVerifier` is a Stage 10E contract. It does not require a real Meta app secret and does not claim production verification.

Local fixture mode is explicit through `X-OrderPilot-Fixture-Mode: true`.

If verification is required before provider-specific secrets exist, the verifier returns `FAILED` and inbound handling rejects the payload safely.

## Telegram

`TelegramSecretTokenVerifier` models the Telegram webhook secret-token boundary. It can report `NOT_CONFIGURED_STAGE_10E` or explicit fixture mode locally.

## Production Gap

Production verification requires provider-specific secret management and provider setup later. No real provider secrets are stored in Stage 10E.
