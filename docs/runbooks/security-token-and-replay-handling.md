# Security Token and Replay Handling

Date: 2026-06-15

## Quote Mutation Replay

Use `Idempotency-Key` on every quote mutation request. A client retry, browser retry, double click, or manual replay with the same key and same payload must return the original safe response without repeating side effects.

Operational checks:

- `409 IDEMPOTENCY_KEY_CONFLICT`: same key was reused for another request or actor context. Treat as a client/security error, not a retryable transport failure.
- `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`: the first request has not completed. The client should wait and retry with the same key.
- 4xx business validation errors are not success and should not be assumed to have created a quote mutation.
- 5xx responses require investigation; do not mark external execution successful unless the authoritative business state proves it.

## Token and Secret Handling

- Do not log `Authorization`, `Cookie`, refresh tokens, API keys, connector secrets, HMAC secrets, or raw customer payloads.
- Browser code must not contain connector secrets or shared HMAC secrets.
- Signed actor headers are supported when `orderpilot.security.actor-signing-secret` is configured; without it, local/dev falls back to unsigned trusted headers.
- Refresh-token rotation and cookie CSRF enforcement are not implemented by OP-CAP-17C and remain separate auth hardening work.

## Connector/Webhook Replay

OP-CAP-17C does not change connector/webhook verification. Existing channel and connector replay protections remain separate:

- Webhooks should use provider signature verification where configured.
- Provider event ids or payload fingerprints should be used for webhook replay dedupe.
- Connector writes must continue through controlled backend services and ChangeRequest/approval paths.
