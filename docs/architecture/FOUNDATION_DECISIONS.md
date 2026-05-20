# Foundation Decisions

Source reviewed:

- `ORDERPILOT_CORE_V1_AI_DEV.md`
- `OrderPilot_Obsidian_Founder_Roadmap_and_Dev_Blueprint.md`

## Core product decision

OrderPilot is a secure transaction intelligence layer for B2B auto and industrial parts distributors. It converts messy customer requests from email, PDF, Excel, Telegram/WhatsApp-ready channels, portal, and APIs into validated draft business workflows.

OrderPilot is not a generic chatbot, simple OCR tool, ERP replacement, accounting system, warehouse system, or autonomous AI business database writer.

## Architecture decision

Stage 1 uses a modular monolith foundation:

- Java 21 + Spring Boot 3.x for the core backend and business truth.
- Python 3.12+ for AI/OCR worker tasks only.
- Next.js + TypeScript for the dashboard shell.
- PostgreSQL as the primary operational database.
- Redis for cache, rate limiting, and future queue coordination.
- Flyway for database migrations.
- Docker Compose for local development.
- GitHub Actions for CI.

Transactional outbox is the preferred first eventing pattern. Kafka, Redpanda, or NATS should be deferred until real scale requires them.

## Authority decision

Java core-api owns future business mutations. Python AI worker can extract, classify, summarize, and suggest, but its output is advisory and must be validated by deterministic backend logic.

Client ERP, 1C, accounting, warehouse, Excel bases, and local databases remain external source-of-truth by default.

## Repository decision

The Stage 1 repository is created at:

`C:\OrderPilot\OrderPilot-Core`

The markdown instruction folder remains source-of-truth and is not modified.

## Scope decision

Stage 1 is foundation only. It does not implement Telegram, WhatsApp, AI extraction production logic, substitutions, analytics, ERP integration, import mirror, quote/order workflows, or full auth.