# Operant Core

Operant is intelligent o2c/e2e/intelligent e-commercesoftware
The developers contributors: akonPAPA, Ayan2k6, almuhxmed,jobegamests-code

## License and proprietary notice

Operant is proprietary and confidential software.

Copyright (c) 2026 Operant / Akan Mukhametgali. All rights reserved.

No public open-source license is granted for this repository unless a specific file,
package, or directory explicitly states otherwise. See LICENSE, NOTICE,
THIRD_PARTY_NOTICES.md, and docs/legal/ for details.

Status note: This document is historical/superseded for current-stage tracking. The canonical current-stage source is `docs/product/current-stage.md`, which points to `docs/product/STAGE_STATUS_RECONCILIATION.md`. Do not use this file alone to determine the active implementation stage.

Operant Core is the Stage 1 foundation for an investor-grade transaction intelligence platform for auto and industrial parts distributors.

This repository is intentionally scoped to platform foundation only:

- Java 21 Spring Boot core API
- Next.js TypeScript dashboard shell
- Python 3.12 AI/OCR worker skeleton
- PostgreSQL, Redis, Flyway migrations, Docker Compose
- Security and architecture documentation

AI, frontend, chatbot, and connector components must never directly write trusted business data. Future mutations must go through typed core-api command services, authentication, authorization, tenant policy, deterministic validation, approval gates, transactions, audit events, and outbox events.

## Local quick start

```powershell
cd "C:\OrderPilot\OrderPilot-Core\infra\docker"
docker compose up -d postgres redis
Test-NetConnection localhost -Port 55432
docker exec -it orderpilot-postgres psql -U orderpilot_local_user -d orderpilot_local
```

For the full Windows local setup, use `docs/runbooks/local-development.md`. The primary Compose entrypoint is `infra/docker/docker-compose.yml` from `C:\OrderPilot\OrderPilot-Core\infra\docker`. Host tools use `localhost:55432`; containers use Docker service DNS `postgres:5432`. The default local app role/database is `orderpilot_local_user/orderpilot_local`; do not use `postgres/postgres` unless you intentionally created that role outside this repo.

Local parity check:

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\dev\check-local.ps1"
```

## Stage status

Current stage:OP-CAP-47 Operator fulfillment and visibility timeline api
