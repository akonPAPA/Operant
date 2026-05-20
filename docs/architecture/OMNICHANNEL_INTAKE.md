# Omnichannel Intake

Stage 3 flow:

```text
Inbound request/file/message
  -> webhook/file validation
  -> raw payload or file metadata storage
  -> deduplication
  -> normalized ChannelMessage or InboundDocument
  -> ProcessingJob queued
  -> AuditEvent emitted
  -> future Stage 4 AI extraction
```

Stage 3 does not parse files or message text into products, customers, quotes, or orders. It stores and queues intake records only.

## Normalized records

- `inbound_document` for uploaded files or document-like payloads.
- `channel_message` for email, Telegram, WhatsApp-ready, API, and other messages.
- `webhook_event` for provider raw payload ledger.
- `processing_job` for future async processing handoff.