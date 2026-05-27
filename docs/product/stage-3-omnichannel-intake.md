# Stage 3 - Omnichannel Intake Stabilization

Stage 3 receives untrusted customer requests and preserves them for later processing. It does not run OCR, AI extraction, validation, substitution, quote creation, order creation, or external ERP writes.

## Supported Channels

- File upload: `POST /api/v1/intake/documents/upload`
- API upload: `POST /api/v1/intake/api-upload`
- Email stub: `POST /api/v1/webhooks/email`
- Telegram stub: `POST /api/v1/webhooks/telegram`
- WhatsApp-ready stub: `POST /api/v1/webhooks/whatsapp`

All endpoints require tenant context through `X-Tenant-Id`.

## Stored Records

- `InboundDocument` for uploaded files or base64 document API submissions.
- `ChannelMessage` for API, email, Telegram, and WhatsApp-style messages.
- `InboundEventLedger` for normalized intake events and fingerprints.
- `WebhookEvent` for webhook receipt, verification mode, replay status, and raw payload pointer.
- `ObjectStorageRecord` for local-dev object metadata.
- `ProcessingJob` placeholder rows with `PENDING` status.

## Boundaries

Raw files and webhook payloads are stored only as untrusted inputs. Parsing, OCR, and AI must happen later through worker jobs so API requests return quickly. Channel adapters must not create products, quotes, orders, inventory changes, or external writes.

## Idempotency

Document uploads are fingerprinted with SHA-256 and duplicate submissions are marked `DUPLICATE`. Channel messages use tenant, channel, and external message id to avoid crashing on duplicate events. Webhook events are ledgered with external ids and payload fingerprints.

## Known Limitations

Webhook verification is local-dev stub verification unless a token is configured. Tenant mapping for Telegram uses `X-Tenant-Id` in this stage. Attachments on email webhooks are metadata-only. Processing jobs are queued in the database but are not executed by real AI/OCR in Stage 3.
