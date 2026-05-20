# OrderPilot Core

OrderPilot Core is the Stage 1 foundation for an investor-grade B2B SaaS transaction intelligence platform for auto and industrial parts distributors.

This repository is intentionally scoped to platform foundation only:

- Java 21 Spring Boot core API
- Next.js TypeScript dashboard shell
- Python 3.12 AI/OCR worker skeleton
- PostgreSQL, Redis, Flyway migrations, Docker Compose
- Security and architecture documentation

AI, frontend, chatbot, and connector components must never directly write trusted business data. Future mutations must go through typed core-api command services, authentication, authorization, tenant policy, deterministic validation, approval gates, transactions, audit events, and outbox events.

## Local quick start

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
Copy-Item ".env.example" ".env"
docker compose -f "infra/docker/docker-compose.yml" up --build
```

For the current Windows local demo baseline, use `docs/runbooks/LOCAL_DEMO_RUNBOOK.md`. The Stage 9I flow starts Docker Desktop, runs repo-defined Postgres/Redis through Docker Compose, seeds deterministic demo data, starts backend/frontend, and verifies with the existing local scripts.

## Stage status

Current backend milestone: Stage 11E prepares approved internal quotes for controlled external-write handoff readiness without executing connector commands or ERP/1C/accounting/warehouse writes. See `docs/product/QUOTE_HANDOFF_STAGE_11E.md` and `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_11E.md`.
