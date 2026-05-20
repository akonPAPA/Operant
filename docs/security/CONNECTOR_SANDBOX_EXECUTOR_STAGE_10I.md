# Connector Sandbox Executor - Stage 10I

## Objective

Stage 10I adds a connector sandbox executor that proves the future execution pipeline shape in `DRY_RUN` mode only.

The sandbox executor records what would be sent, which local sandbox adapter would handle it, which tenant policy decision allowed or blocked it, which validation rules passed or failed, and what fake provider response would be returned.

## Dry-Run-Only Guarantee

- `connector_sandbox_execution.execution_mode` is constrained to `DRY_RUN`.
- The service builds a `TenantPolicyContext` with `SYSTEM_CONNECTOR_WORKER`, `EXECUTE_CONNECTOR_COMMAND`, `EXECUTION_READY`, and `ExecutionMode.DRY_RUN`.
- `TenantPolicyService` still denies human connector execution and denies `ExecutionMode.REAL`.
- The executor never marks `connector_command` as externally executed.
- The sandbox adapter returns `externalWritePerformed=false`.
- Fake references use the `sandbox-dryrun-*` prefix.

## Non-Goals

- No real connector executor.
- No ERP, 1C, accounting, or warehouse writes.
- No external provider API calls.
- No provider credentials or production secrets.
- No WhatsApp, Telegram, Meta, or AI provider calls.
- No production SSO/OIDC change.
- No UI redesign.
- No customer-facing connector marketplace.
- No compensation execution.
- No broad refactor.

## Policy Dependency

The sandbox executor depends on the Stage 10H tenant policy layer. A dry-run can proceed only when policy permits a system connector worker to execute a connector command in `DRY_RUN` mode.

Policy-blocked attempts are persisted as `POLICY_BLOCKED` and audited with `CONNECTOR_SANDBOX_DRY_RUN_POLICY_BLOCKED`.

## Adapter Rules

`SandboxConnectorAdapter` implementations must be deterministic local adapters.

Adapters must not:

- Use HTTP clients, sockets, `RestTemplate`, `WebClient`, SDKs, or provider clients.
- Read real credentials or secrets.
- Create, update, or delete external records.
- Write outside the OrderPilot database and audit trail.
- Produce references that look like real provider ids.

Stage 10I includes `DemoErpSandboxConnectorAdapter`, which supports `DEMO_ERP` and returns a fake `sandbox-dryrun-*` response.

## State Transitions

Persisted sandbox execution statuses:

- `REQUESTED`
- `POLICY_BLOCKED`
- `VALIDATION_FAILED`
- `READY`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

The current implementation uses:

- `REQUESTED` when the execution row is created.
- `POLICY_BLOCKED` when tenant policy denies execution.
- `VALIDATION_FAILED` when command readiness or adapter validation fails.
- `RUNNING` while the local adapter simulation is being prepared.
- `SUCCEEDED` when deterministic local simulation succeeds.
- `FAILED` for unsupported sandbox targets or simulation failures.

## Audit Behavior

Audit events emitted:

- `CONNECTOR_SANDBOX_DRY_RUN_REQUESTED`
- `CONNECTOR_SANDBOX_DRY_RUN_POLICY_BLOCKED`
- `CONNECTOR_SANDBOX_DRY_RUN_SUCCEEDED`
- `CONNECTOR_SANDBOX_DRY_RUN_FAILED`

Audit metadata is intentionally small and excludes secrets, credentials, provider tokens, and real external identifiers.

## Idempotency Behavior

Dry-runs use a tenant-scoped `dry_run_key`.

The migration enforces a unique constraint on:

- `tenant_id`
- `dry_run_key`

Re-running the same dry-run with the same tenant and key returns the existing persisted result instead of creating a duplicate contradictory record.

## Future Real Executor Path

A real executor is not implemented in Stage 10I.

Before any future real executor exists, OrderPilot must add separate proof for production credentials handling, outbound network governance, rate limits, provider sandbox certification, irreversible-write compensation, operator approval gates, tenant isolation negative tests, and API boundary hardening.
