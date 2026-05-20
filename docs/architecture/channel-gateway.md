# Channel Gateway Foundation

Stage 12 defines channels as customer communication inputs: Email, file upload, API upload, Telegram, WhatsApp, Meta Messenger, Viber, WeChat, and future adapters.

Core rules:
- Every channel connection is tenant-scoped.
- Default mode is `READ_ONLY`.
- Webhook payloads are untrusted input and must be normalized into `inbound_channel_event` before later processing.
- Channel adapters do not create quotes, orders, ERP records, replies, or business mutations directly.
- Raw provider payloads are stored for audit/debugging only; secrets are referenced by `secret_ref` and are not exposed by API responses.

Stage 12 status: WhatsApp, Meta Messenger, Viber, WeChat, Telegram, Email, File Upload, and API are adapter-ready stubs. They are not production-certified provider integrations yet.
