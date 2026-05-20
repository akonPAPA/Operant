# Production Auth/RBAC Proof Plan - Stage 10G

Stage 10G is a proof-plan stage. It does not implement production SSO, production IdP setup, real connector execution, production secrets, real AI provider calls, or UI redesign.

## Current State

What exists today:

- Tenant context is enforced in backend services through `TenantContext.requireTenantId()`.
- Important workflow actions emit application-level audit events through `AuditEventService`.
- ChangeRequest, transactional outbox, connector command, channel identity, webhook verification, and compensation-plan contracts exist as internal safety boundaries.
- Local demo mode can run with fixture behavior and no production provider secrets.

What is stubbed or demo-only:

- Tenant resolution is still header/local-demo oriented.
- RBAC/ABAC enforcement is not production-proven.
- Webhook verification can report Stage 10E not-configured or fixture modes.
- Connector command and compensation records are contracts only, not external executors.

What must not be considered production-ready:

- Production authentication.
- Production authorization/RBAC/ABAC.
- Production webhook secret verification.
- Real external connector execution.
- Production AI provider integration.
- Production channel outbound messaging.

## Production Auth Target

- OIDC/OAuth2 or SSO-ready model.
- Short-lived access tokens.
- Refresh/session strategy with revocation support.
- MFA/SSO for enterprise tenants later.
- Service-to-service auth for workers/connectors.
- No secrets in the repository.
- Tenant-scoped API tokens where applicable.
- Worker and connector service tokens scoped to exact connector type, tenant, and operation.

## RBAC Roles

- Owner/Admin
- Operator
- Sales/Quote Manager
- Inventory Manager
- Integration Admin
- Auditor
- Bot Manager
- Read-only Viewer

## ABAC And Policy Checks

- Tenant boundary.
- Branch/location boundary later.
- Margin visibility permission.
- Discount approval threshold.
- Connector write approval permission.
- Connector execution permission.
- Compensation plan approval permission.
- Bot/channel management permission.
- Audit read permission.

## Permission Matrix

| Action | Owner/Admin | Operator | Sales/Quote Manager | Inventory Manager | Integration Admin | Auditor | Bot Manager | Read-only Viewer |
|---|---|---|---|---|---|---|---|---|
| view inbound messages | yes | yes | yes | no | yes | yes | yes | yes |
| view documents | yes | yes | yes | yes | no | yes | no | yes |
| create draft quote | yes | yes | yes | no | no | no | no | no |
| approve quote | yes | no | yes | no | no | no | no | no |
| approve risky substitute | yes | no | yes | yes | no | no | no | no |
| approve below-margin discount | yes | no | yes, if threshold allows | no | no | no | no | no |
| create connector command | yes | no | no | no | yes | no | no | no |
| approve connector command | yes | no | no | no | yes, if policy allows | no | no | no |
| execute connector command | no until future proof | no | no | no | no until future proof | no | no | no |
| create compensation plan | yes | no | no | no | yes | no | no | no |
| approve compensation plan | yes | no | no | no | yes, if policy allows | no | no | no |
| view audit log | yes | no | no | no | no | yes | no | yes, limited |
| manage integration settings | yes | no | no | no | yes | no | no | no |
| manage Telegram/WhatsApp channel settings | yes | no | no | no | yes | no | yes | no |

## Production Proof Tests Required Later

- Tenant A cannot read Tenant B data.
- Operator cannot approve connector writes.
- Bot Manager cannot view margin unless explicitly allowed.
- Integration Admin cannot bypass approval policy.
- Auditor can read audit but not mutate workflows.
- Expired token is rejected.
- Missing tenant context is rejected.
- Service token scoped to one connector cannot access another connector.
- Replayed webhook is rejected.
- Idempotency key prevents duplicate mutation.
- Compensation plan cannot auto-execute external rollback by default.

## Explicit Non-Goals For Stage 10G

- No real SSO integration.
- No production IdP setup.
- No real external connector execution.
- No production secrets.
- No real AI provider.
- No UI redesign.
- No full RBAC framework rewrite.

## Production Exit Criteria

Before any real external write mode is considered, OrderPilot must prove:

- Authenticated principal identity.
- Tenant-scoped access token or session.
- RBAC and ABAC policy checks on every write path.
- Deterministic validation before connector write intent.
- Approval gate before connector command readiness.
- Audit event for each important state transition.
- Idempotency on ChangeRequest and ConnectorCommand.
- Compensation plan creation path for failed, unknown, or partially executed connector outcomes.
- Provider secrets stored outside the repo.
- Dry-run/sandbox execution evidence before production execution.
