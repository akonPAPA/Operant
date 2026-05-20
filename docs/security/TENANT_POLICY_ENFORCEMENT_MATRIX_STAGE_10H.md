# Tenant Policy Enforcement Matrix - Stage 10H

Stage: Stage 10H - Tenant Policy Enforcement Matrix + Permission Tests

Status: PASS

## Objective

Stage 10H converts the Stage 10G auth/RBAC proof plan into a deterministic backend policy boundary. The goal is to prove that dangerous workflow, connector, compensation, bot, and tenant-admin actions are denied before future connector dry-run or executor work begins.

This is not production SSO, OIDC, IdP, JWT/session, or frontend auth work.

## Implementation

The policy layer is implemented under `com.orderpilot.security.policy`:

- `TenantPolicyAction`
- `TenantPolicyContext`
- `TenantPolicyDecision`
- `TenantPolicyService`
- `TenantPolicyException`
- `ActorRole`
- `ExecutionMode`
- `ResourceType`

Policy evaluation is deterministic and in-memory. It does not call external systems, does not write to the database, does not emit connector commands, does not call AI providers, and does not mutate trusted business data.

## Default Deny Rules

- Missing policy context is denied.
- Missing tenant id is denied.
- Missing target tenant id is denied.
- Actor tenant mismatch is denied.
- Missing action is denied.
- Missing or unsupported role is denied.
- Missing actor id is denied unless a valid system actor is explicitly represented.
- Human roles cannot execute connector commands.
- Stage 10H connector execution policy allows only dry-run mode for the system connector worker.
- Below-margin approval is denied unless the owner/admin role explicitly allows it.
- Compensation approval is denied unless an explicitly allowed role performs it.
- Bot/channel roles cannot approve quotes, orders, discounts, connector commands, or compensation plans.

## Role Matrix

| Role | Current Stage 10H policy |
|---|---|
| `OWNER_ADMIN` | Can approve connector commands and compensation plans, manage users/roles/tenant settings, manage integrations/channels, view audit logs, and perform quote/order approvals. Connector execution is still denied for human roles. |
| `OPERATOR` | Can view inbound messages/documents/quotes/orders/products/inventory, view channel messages, and create internal draft quotes/orders. Cannot approve connector commands, discounts, compensation, or manage integration/user/role settings. |
| `SALES_QUOTE_MANAGER` | Can view sales workflow data, create draft quotes/orders, and approve normal quote/order workflow actions. Cannot approve below-margin discounts, connector commands, connector execution, or compensation plans. |
| `INVENTORY_MANAGER` | Can view documents/orders/products/inventory. Cannot approve quotes, discounts, connector commands, connector execution, or integration settings. |
| `INTEGRATION_ADMIN` | Can manage integration settings, create connector commands, view connector commands, create/view compensation plans, and manage channel settings. Cannot approve connector commands or execute connectors. |
| `AUDITOR` | Can view audit log and read workflow records. Cannot mutate workflows, create/approve connector commands, approve compensation, or manage integration/user/role settings. |
| `BOT_MANAGER` | Can manage channel settings, manage bot flows, and view channel messages. Cannot approve quote/order workflow actions, discounts, connector commands, connector execution, or compensation plans. |
| `READ_ONLY_VIEWER` | Can use safe read-only views. Cannot mutate workflows, connectors, compensation, channels, users, roles, or tenant settings. |
| `SYSTEM_CONNECTOR_WORKER` | Can pass connector execution policy only for an approved, ready connector command in dry-run mode. It cannot perform human admin actions. |

## Action Coverage

Stage 10H covers these action groups:

- Read/workflow: inbound messages, documents, quotes, orders, products, inventory, analytics, audit log.
- Quote/order: draft quote/order creation and quote/order approval boundaries.
- Sensitive approvals: risky substitute and below-margin discount approval.
- Connector/integration: integration settings, connector command creation, connector command approval, connector command execution, connector command viewing.
- Compensation: compensation plan creation, approval, and viewing.
- Channels/bot: channel settings, bot flows, and channel messages.
- Admin: users, roles, and tenant settings.

## ABAC Fields

`TenantPolicyContext` includes:

- `tenantId`
- `actorId`
- `actorRoles`
- `targetTenantId`
- `resourceType`
- `resourceId`
- `action`
- `riskLevel`
- `monetaryAmount`
- `marginImpact`
- `discountPercent`
- `connectorCommandState`
- `compensationPlanStatus`
- `channelType`
- `systemActor`
- `approved`
- `executionMode`

Only the fields needed by the current action are evaluated in Stage 10H. Additional fields are present so future command services can enforce thresholds, branch/location boundaries, connector state, and compensation state without changing the policy contract shape.

## Current Integration Status

Stage 10H adds the policy service and direct permission matrix tests. It does not force broad service rewrites where command endpoints are not yet ready for policy injection.

Stage 10I now integrates this policy boundary with the connector sandbox executor. The integration remains dry-run only: `SYSTEM_CONNECTOR_WORKER` may pass policy only for an approved, ready connector command with `ExecutionMode.DRY_RUN`, while human roles and `ExecutionMode.REAL` remain denied.

Intended future integration points:

- ChangeRequest approval and risky workflow transitions.
- ConnectorCommand creation, approval, and readiness outside the Stage 10I dry-run sandbox.
- CompensationPlan creation and approval.
- Channel/bot management endpoints.
- Audit read endpoints and privileged operational reporting.

Stage 11A now uses this policy service for `CREATE_DRAFT_QUOTE` before RFQ input can create internal draft quote state.

## Non-Goals

- No real SSO/OIDC setup.
- No production IdP integration.
- No JWT/session rewrite.
- No frontend auth redesign.
- No UI redesign.
- No real external connector execution.
- Stage 10I adds a dry-run-only connector sandbox executor; Stage 10H itself did not.
- No ERP, 1C, accounting, or warehouse writes.
- No real WhatsApp, Telegram, Meta, or provider calls.
- No real AI provider integration.
- No new dependencies.
- No broad refactor.

## Production Auth Integration Later

Production auth must later bind authenticated identities and service tokens to this policy context. Required future proof points remain:

- Tenant A cannot read Tenant B data.
- Expired token is rejected.
- Missing tenant context is rejected.
- Service token scoped to one connector cannot access another connector.
- Human users cannot execute connector commands directly.
- Replayed webhooks are rejected before workflow mutation.
- Idempotency prevents duplicate external mutation.
- Compensation plans cannot auto-execute external rollback by default.
