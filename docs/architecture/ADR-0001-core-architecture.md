# ADR-0001: Core Architecture

## Status

Accepted for Stage 1.

## Context

OrderPilot must be credible as an investor-grade B2B SaaS platform. It handles sensitive business workflows where unsafe AI or connector writes could damage customer trust and source systems.

## Decision

Use a modular monolith foundation:

- Core backend: Java 21 + Spring Boot 3.x.
- AI/OCR worker: Python 3.12+, advisory only.
- Web dashboard: Next.js + TypeScript.
- Primary database: PostgreSQL.
- Cache/coordination: Redis.
- Migrations: Flyway.
- Local dev: Docker Compose.
- CI/CD: GitHub Actions.

All future mutations must go through typed backend command services with auth, RBAC/ABAC, tenant isolation, deterministic validation, approval gates when risky, transaction boundaries, audit events, and outbox events for external integration.

## Consequences

- Backend business rules stay strongly typed and transactionally controlled.
- Python remains useful for AI/OCR without owning business truth.
- Future services can be split later when workload pressure is real.
- Stage 1 avoids premature microservices and unsafe direct data paths.