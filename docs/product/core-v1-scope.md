# Core v1 Scope

OrderPilot Core v1 is the product slice for a secure B2B transaction intelligence platform for auto and industrial parts distributors. The live repository already contains modules beyond the Stage 1 foundation; this document states the product boundary those modules must continue to respect.

Core v1 must eventually prove:

- Omnichannel intake.
- Order/RFQ processing.
- Deterministic validation.
- Substitution intelligence.
- Bot runtime lite.
- Commerce intelligence.
- Exception and audit cockpit.

Stage 1 implements only the platform foundation needed to build these safely.

Stage 1 foundation requirements are:

- Spring Boot core-api with `/api/v1/health`.
- PostgreSQL with Flyway migrations.
- Tenant, user, role, permission, audit, and idempotency schema.
- Safe structured API errors and global exception handling.
- Tenant context placeholder and audit event service.
- Next.js dashboard shell with core navigation pages.
- Python ai-worker skeleton with advisory `process_inbound_document()`.
- Docker Compose for PostgreSQL, Redis, core-api, web-dashboard, and ai-worker.
- Architecture, security, product, and local-development docs.
- CI/test hooks for backend, frontend, AI worker, and Compose config.

Core v1 must not become a generic chatbot, simple OCR demo, ERP replacement, accounting engine, warehouse system, or autonomous AI mutation engine.

Out of scope for foundation work unless a later task explicitly opens it: new AI/OCR behavior, Telegram/WhatsApp integration behavior, CSV seed/import behavior, and new business workflow execution.
