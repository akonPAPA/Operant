# Tenant Isolation API Boundary - Stage 10J

## Objective

Stage 10J proves cross-tenant access is blocked across high-risk OrderPilot backend surfaces using negative tests and small API-boundary hardening.

This is not a product feature stage, not production SSO/OIDC, not real connector execution, and not a UI stage.

## Tested Resource Surfaces

Stage 10J covers current implemented service/API paths for:

- Connector commands
- Connector sandbox executions
- Compensation plans
- Change requests
- Channel identities
- Channel messages
- Telegram webhook verification boundary
- Commerce analytics summary
- Inventory reconciliation cases
- Audit event tenant-scoped queries

Where a public endpoint does not yet exist, tests run at the service or repository boundary instead of inventing a new API.

## Tenant Isolation Rules

- Current tenant context is authoritative.
- Request payload tenant ids cannot override `TenantContext`.
- Service reads and mutations must use tenant-scoped repository lookups such as `findByIdAndTenantId`.
- Cross-tenant object ids should resolve as not found or safe denial.
- Denied access must not mutate command state, create sandbox executions, create compensation plans, enqueue processing, or create business records.

## Safe Denial Behavior

Current behavior by boundary:

- Missing tenant context: explicit tenant context error.
- Cross-tenant service lookup: `NotFoundException` where the service uses the common not-found model.
- Channel identity cross-tenant lookup: safe `IllegalArgumentException` with no returned resource, matching existing channel service style.
- Tenant id mismatch in normalized channel messages: explicit bad request before side effects.
- Invalid Telegram webhook verification: bad request before bot runtime processing.
- Dry-run cross-tenant command id: not found before tenant policy and before adapter simulation.

This avoids leaking whether another tenant's resource exists through successful reads or mutations.

## Webhook Tenant Resolution Rule

Webhook payloads are untrusted.

Telegram webhook processing must not use raw payload tenant ids to choose a tenant. The controller now blocks rejected verifier results before calling bot runtime. Channel gateway processing requires normalized inbound tenant id, when present, to match current tenant context.

Fixture mode remains an explicit local/demo verification mode and is not a production verification claim.

## Repository And Query Scoping Rule

High-risk service/API boundaries should prefer:

- `findByIdAndTenantId`
- `findByTenantIdAnd...`
- `countByTenantId...`
- tenant-scoped page/list queries

ID-only repository methods may still exist for framework use, internal tests, or controlled setup, but service/API boundaries should not use them for tenant-owned resources.

## Dry-Run Boundary Rules

Stage 10I dry-runs remain dry-run only:

- Tenant-scoped command lookup happens before policy/adapters.
- Tenant mismatch denies before sandbox adapter simulation.
- `ExecutionMode.REAL` remains denied by tenant policy.
- Sandbox responses use fake `sandbox-dryrun-*` references.
- Connector command status is not marked as real external `EXECUTED`.
- No compensation execution is triggered.

## Hardened In Stage 10J

- Telegram webhook controller now blocks `accepted=false` verifier results before bot runtime processing.
- `ConnectorSandboxExecutionService` exposes tenant-scoped sandbox execution read access for boundary tests and future internal use.
- `CompensationPlanningService` exposes tenant-scoped compensation plan read access for boundary tests and future internal use.
- `AuditEventRepository` now has a tenant-scoped audit query method.
- Added a tenant isolation negative-test matrix across implemented high-risk surfaces.

## Future Work

- Public audit-log controller boundary tests once an audit endpoint exists.
- Public connector command/sandbox execution controller tests once those endpoints exist.
- Production auth binding from real authenticated identity to `TenantContext`.
- Rate limiting and abuse controls for webhook and dry-run endpoints.
- Expanded negative tests for inbound document APIs as those surfaces mature.

## Explicit Non-Goals

- No real connector executor.
- No ERP, 1C, accounting, or warehouse writes.
- No external API calls.
- No provider credentials or production secrets.
- No production SSO/OIDC implementation.
- No WhatsApp, Telegram, Meta, or AI provider calls.
- No UI redesign.
- No customer-facing connector marketplace.
- No compensation execution.
- No broad refactor.
- No dependency changes.
- No new business workflow feature.
