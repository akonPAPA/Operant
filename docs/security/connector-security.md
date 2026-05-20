# Connector Security

Stage 12 connector security posture:
- Tenant isolation is mandatory for channel connections, integration connections, inbound events, and sync events.
- Secret material is referenced through `secret_ref`; raw tokens, passwords, API keys, and webhook secrets must not be stored in ordinary response DTOs or logs.
- Read-only connector mode is the default.
- Disabled or inactive channel connections cannot process inbound webhooks.
- Webhook payload text is untrusted and cannot execute commands or create commerce records directly.
- AI output, bot messages, frontend calls, and connector adapters remain inputs to core backend command/application services, not authoritative write paths.
- External writes to WhatsApp, Meta, Viber, WeChat, 1C, ERP, accounting, inventory, or warehouse systems require a future explicit approval/change-request design.

Known limitations:
- Stage 12 adapter health checks are placeholders.
- Provider signature verification is not production-certified for every provider in this stage.
- Generic database support is adapter-ready only and does not open database sockets.
