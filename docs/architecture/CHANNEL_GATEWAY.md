# Channel Gateway

The channel gateway is the backend boundary for untrusted external inputs.

## Stage 3 channels

- Manual document upload.
- API document upload.
- Generic message intake.
- Provider-neutral email webhook.
- Telegram webhook stub.
- WhatsApp-ready webhook stub.

## Rules

- Customer text and uploaded files are untrusted.
- Webhook payloads are persisted as raw events before or while normalizing.
- Message payloads do not create quotes, orders, product changes, inventory changes, or ERP writes.
- Telegram and WhatsApp endpoints are intake stubs, not production-certified bot integrations.