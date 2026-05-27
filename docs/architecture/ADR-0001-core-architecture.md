# ADR-0001: Core Architecture

## Status

Accepted for Stage 1. The repository has since grown beyond Stage 1, but this ADR remains the foundation boundary for the current implementation.

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

The live repository currently contains later-stage modules for intake, validation, workspace, connectors, pilot/demo paths, and analytics. Those modules extend this foundation; they do not change the authority model:

- `apps/core-api` owns trusted business state and command/service boundaries.
- `apps/web-dashboard` is a UI/client surface and must not connect to the database directly.
- `apps/ai-worker` may produce advisory extraction or analysis results, but must not write business tables directly.
- Bot/chat/channel layers may normalize inbound events or hand off drafts, but must not create trusted business mutations outside core-api services.
- PostgreSQL schema changes are versioned through Flyway migrations.

## Consequences

- Backend business rules stay strongly typed and transactionally controlled.
- Python remains useful for AI/OCR without owning business truth.
- Future services can be split later when workload pressure is real.
- Stage 1 avoids premature microservices and unsafe direct data paths.
- Later-stage code must preserve this architecture instead of bypassing it for convenience.
