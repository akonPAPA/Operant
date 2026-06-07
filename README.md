# OrderPilot Core

The developers contributors: akonPAPA, Ayan2k6

## License and proprietary notice

OrderPilot is proprietary and confidential software.

Copyright (c) 2026 OrderPilot / Akan Mukhametgali. All rights reserved.

No public open-source license is granted for this repository unless a specific file,
package, or directory explicitly states otherwise. See LICENSE, NOTICE,
THIRD_PARTY_NOTICES.md, and docs/legal/ for details.

Status note: This document is historical/superseded for current-stage tracking. The canonical current-stage source is `docs/product/current-stage.md`, which points to `docs/product/STAGE_STATUS_RECONCILIATION.md`. Do not use this file alone to determine the active implementation stage.

OrderPilot Core is the Stage 1 foundation for an investor-grade transaction intelligence platform for auto and industrial parts distributors.

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
docker compose -f "infra/docker/docker-compose.yml" up -d postgres redis
docker exec -it orderpilot-postgres psql -U orderpilot -d orderpilot
```

For the full Windows local setup, use `docs/runbooks/local-development.md`. The primary Compose entrypoint is `infra/docker/docker-compose.yml` from the repo root. The local Postgres role/database is `orderpilot/orderpilot`; do not use `postgres/postgres` unless you intentionally created that role outside this repo.

Local parity check:

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\dev\check-local.ps1"
```

## Stage status

Current backend milestone: Stage 11E prepares approved internal quotes for controlled external-write handoff readiness without executing connector commands or ERP/1C/accounting/warehouse writes. See `docs/product/QUOTE_HANDOFF_STAGE_11E.md` and `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_11E.md`.
