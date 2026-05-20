# Stage 1 Scope

## Goal

Build the repository and platform foundation for OrderPilot Core v1.

## Included

- Monorepo structure.
- Java 21 Spring Boot core-api with health endpoint.
- PostgreSQL connection configuration.
- Flyway migration for tenant, user, role, permission, user_role, role_permission, audit_event, and idempotency_key tables.
- Tenant-aware request context placeholder.
- AuditEvent service.
- Structured API error response.
- Next.js TypeScript dashboard shell with serious B2B navigation.
- Python AI worker skeleton with advisory-only mock document processing.
- Windows connector placeholder.
- Docker Compose for postgres, redis, core-api, web-dashboard, and ai-worker.
- GitHub Actions workflow definitions.
- Security, architecture, product, and runbook documentation.

## Excluded

- Full authentication and login.
- Telegram, WhatsApp, email ingestion, or production file upload.
- AI extraction production logic.
- Substitution engine.
- Product, inventory, customer, pricing, quote, order, or analytics domains.
- ERP, 1C, accounting, or warehouse writes.
- External connector runtime.

## Acceptance posture

Stage 1 proves that the repository, security posture, and foundational app skeletons exist. It does not claim the full OrderPilot product is implemented.