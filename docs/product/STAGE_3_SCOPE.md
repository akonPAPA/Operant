# Stage 3 Scope

## Goal

Build omnichannel intake: a safe tenant-scoped layer that receives files, API uploads, email webhook payloads, Telegram webhook messages, and WhatsApp-ready webhook stubs.

## Included

- Inbound document metadata and object-storage record tracking.
- Channel message normalization.
- Attachment table foundation.
- Webhook event ledger.
- Processing job placeholder queue.
- File validation, SHA-256 fingerprinting, and deduplication.
- Webhook security interface/placeholders.
- Inbox/upload/webhook/job frontend placeholders.

## Excluded

- OCR and AI extraction.
- Telegram bot business replies.
- WhatsApp production integration.
- Email provider production integration.
- Product matching, quote/order creation, substitution ranking, and analytics.
- ERP/1C writes and ChangeRequest execution.