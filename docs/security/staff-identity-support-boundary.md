# Staff Identity & Support Boundary — Operant Support & Maintenance Access Plane

Status: **evidence/documentation consolidation** (PR #246).
Scope: this document describes the **already-implemented** owner-company support/maintenance
boundary (OP-CAP-51 through OP-CAP-57). It introduces **no new model** and changes **no production
behavior**. It exists so the boundary is provable from one place and so the remaining production gaps
are stated honestly.

> Core invariant: **Tenant admin is not Operant staff. Tenant operator permission is not staff
> permission. Hidden UI is not security. Backend denial is proven by test.**

---

## 1. Four access planes

Operant Core API distinguishes four distinct identity/authorization planes. They never substitute for
one another.

| Plane | Who | Identity source | Authorization | Business mutation? |
|-------|-----|-----------------|---------------|--------------------|
| **Tenant User Access** | A tenant's own operators/admins | Trusted gateway actor header + `X-Tenant-Id` tenant context | Tenant business `ApiPermission` (e.g. `REVIEW_READ`, `QUOTE_ACTION`, `ADMIN_SETTINGS_MANAGE`) via `ApiRolePermissionMatrix` roles | Yes — through command services, validated, audited |
| **External Customer Access** | A buyer/customer outside the tenant | No tenant/actor header; opaque expiring token in the path (secure tracking link) or provider-signed webhook | Token/signature is the sole credential; scope derived from the token, never the request | No — read-only tracking, or provider→intake only |
| **Service Account Access** | AI worker / connector / bot machine callers | Trusted gateway actor header with a machine actor | Narrow intake/result permissions (`AI_RESULT_INTAKE`, `EXTRACTION_RUN`, `INTAKE_WRITE`, `BOT_ACTION`, …) | No direct business write — advisory/intake only |
| **Operant Support & Maintenance Access Plane** | Operant owner-company staff (support / SRE / DevOps / security / internal admin) | Trusted gateway actor header resolving to a `staff_user` row (separate identity domain) | Route-edge `STAFF_*` `ApiPermission` **plus** a scoped, reasoned, unexpired `SupportAccessGrant` | Read-only by default; only one bounded, approval-gated repair target may mutate a control-plane row |

This document is about the **fourth** plane. It is **not** normal tenant authorization, **not** external
customer access, and **not** service-account access.

---

## 2. Staff identity model

- **`StaffUser`** (`domain/support/StaffUser.java`) — the internal owner-company staff principal. It is a
  **separate identity domain** from tenant customers/operators. Holding a staff role grants **no** tenant
  access on its own. Fields are safe: a stable `handle` (e.g. email/login — never a secret), a `role`, a
  `status` (`ACTIVE`/`DISABLED`), and `created_at`. `isActive()` and `permits(scope)` are the only
  authorization-relevant behaviors.
- **`StaffRole`** (`domain/support/StaffRole.java`) — declares the **closed set** of support scopes a
  staff principal may ever be granted. Intentionally coarse (this is a bounded foundation, not a full IAM):
  - `SUPPORT_VIEWER` → `{DIAGNOSTICS}`
  - `MAINTENANCE_ENGINEER` → `{DIAGNOSTICS, MAINTENANCE}`
  - `SUPPORT_ENGINEER` → `{DIAGNOSTICS, MAINTENANCE, DATA_REPAIR}`
- **Runtime identity source.** At runtime the acting staff id is carried by the **trusted gateway actor
  header** and resolved through the **`StaffIdentityResolver` seam** (PR #247, see §12), whose only
  implementation today wraps `RequestActorResolver.resolveVerifiedActor(...)` — the same trusted-gateway
  boundary described in [`TRUSTED_GATEWAY_HEADER_BOUNDARY.md`](TRUSTED_GATEWAY_HEADER_BOUNDARY.md). The
  staff id is **never** read from a request body or query parameter. The staff row exists so the actor can
  be validated (`exists + ACTIVE`) and so the role bounds which scopes may be granted. A future
  SSO/OIDC/BFF-backed staff identity swaps in behind the resolver seam without changing support
  authorization semantics.

> The staff identity model is a **validation + scoping** layer over the trusted actor header. It is **not**
> an identity provider and **not** an SSO/OIDC integration (see §9, Not Proven).

---

## 3. Support grant model — `SupportAccessGrant`

`domain/support/SupportAccessGrant.java` is the core of the safety model: a scoped, reasoned, expiring
authorization for **one** staff principal to support **exactly one** tenant for **exactly one**
`StaffSupportScope`.

Backend-owned fields (a client can never supply them): `status`, `approvalStatus`, `expiresAt`,
`createdAt`, `createdBy`, `approvedBy`, `revokedAt`.

- **Reason required.** `support_case_ref` is mandatory and non-blank (`SupportAccessService.createGrant`
  rejects blank; max 200 chars). No anonymous access.
- **Tenant-scoped.** `tenant_id` is the sole tenant the grant ever authorizes. There is **no** "all
  tenants" / permanent grant representation by construction.
- **Scope-scoped.** `StaffSupportScope` ∈ `{DIAGNOSTICS, MAINTENANCE, DATA_REPAIR}`. A grant authorizes
  exactly one scope. The grantee's `StaffRole` must permit the scope (`createGrant` rejects otherwise).
- **Status.** `Status` ∈ `{ACTIVE, REVOKED}`. `revoke(...)` is first-write-wins idempotent.
- **Approval status (OP-CAP-52).** `ApprovalStatus` ∈ `{AUTO_APPROVED, PENDING_APPROVAL, APPROVED,
  REJECTED}`. Only low-risk `DIAGNOSTICS` is `AUTO_APPROVED` (usable immediately). `MAINTENANCE` and
  `DATA_REPAIR` are born `PENDING_APPROVAL` and are **not usable** until an approver with
  `STAFF_SUPPORT_GRANT_APPROVE` approves. Separation of duties: the actor who created the grant request
  cannot approve/reject it (`SupportAccessService.decideGrant` denies self-approval and audits it).
- **TTL — everything expires.** `expires_at` is mandatory. `isExpired(now)` = `!now.isBefore(expiresAt)`.
  `SupportAccessService.MAX_GRANT_TTL = 24h` is a hard ceiling; a longer TTL is rejected at creation.
- **Usability.** `isUsable(now)` = `status == ACTIVE && isApproved() && !isExpired(now)`. A revoked,
  rejected, pending, or expired grant is never usable.

### Authorization decision (`SupportAccessService.authorize`)

A support action is authorized **only when all** hold, checked in order and fail-closed:

1. staff principal exists and is `ACTIVE`;
2. the staff role permits the requested scope;
3. an `ACTIVE`, approved, unexpired `SupportAccessGrant` exists for `(staffUserId, tenantId, scope)`.

Any failure throws a **generic** `SupportAccessDeniedException` (it never leaks which condition failed)
and emits a `SUPPORT_ACCESS_DENIED` audit event with a `reasonCode`. On success it emits
`SUPPORT_ACCESS_GRANTED` and returns a backend-owned `SupportSession`. The service is read-only with
respect to business truth: it only reads staff/grant rows and writes audit + the grant lifecycle it owns.

---

## 4. Route permissions & default-deny

The internal surface is mounted under `/api/v1/internal/support/**` in `InternalSupportController`.
Two layers protect it:

1. **Route-edge (`STAFF_*` permission).** `ApiRouteSecurityPolicy.supportDecision(method, path)` maps each
   exact route+verb to a dedicated `STAFF_*` permission. The full family:
   `STAFF_SUPPORT_READ`, `STAFF_SUPPORT_GRANT_MANAGE`, `STAFF_SUPPORT_GRANT_APPROVE`,
   `STAFF_MAINTENANCE_RECORD`, `STAFF_DATA_REPAIR_DRYRUN`, `STAFF_DATA_REPAIR_APPROVE`,
   `STAFF_DATA_REPAIR_EXECUTION_ATTEMPT`, `STAFF_PROCESSING_JOB_REPAIR_EXECUTE`,
   `STAFF_INCIDENT_CREATE/READ/CLOSE`, `STAFF_BREAK_GLASS_REQUEST/APPROVE/REVOKE`.
   - Distinct verbs map to distinct permissions, so a read grant can never reach grant-management,
     maintenance recording, a data-repair dry-run, approval, or execution.
   - **Fail-closed:** only the explicit route/method matrix is classified. Any unknown internal-support
     sub-route or wrong-method variant returns `Optional.empty()` and falls through to the global
     `/api/**` default-deny. Prefix collisions cannot widen access (e.g. a `.../supporting` path is not
     matched by the support matrix).
2. **`STAFF_*` is never a tenant permission.** `ApiRolePermissionMatrix` removes the entire `STAFF_*`
   family from every tenant role (owner-admin, operator, auditor, …). A tenant operator/admin/demo
   permission header therefore can **never** satisfy a `STAFF_*` route edge.
3. **Service-layer grant (for tenant-scoped actions).** Diagnostics / operations / maintenance /
   data-repair additionally require `SupportAccessService.authorize(actor, tenant, scope)` — the grant
   check above.

### Tenant scope binding

Where a `{tenantId}` path variable is present it must equal the trusted `X-Tenant-Id` context
(`InternalSupportController.requireMatchingTenant`). A mismatch is a cross-tenant attempt and fails
closed **before** any service read. The cross-tenant locator (`/tenants/search`) intentionally has no
single tenant context; the locator service filters discovery to tenants the actor holds an active
`DIAGNOSTICS` grant for.

---

## 5. Diagnostics endpoint(s) & read-only surface

Primary read-only diagnostics:

```
GET /api/v1/internal/support/tenants/{tenantId}/diagnostics
    route-edge:  STAFF_SUPPORT_READ
    grant scope: DIAGNOSTICS
```

`SupportDiagnosticsService.diagnose(tenantId)` returns a bounded, redacted summary built from existing
safe aggregates only: processing-job status counts, total jobs, last activity timestamp, and a derived
health enum (`HEALTHY` / `ATTENTION` / `NO_RECENT_ACTIVITY`). It exposes **no** raw payloads, secrets,
connector credentials, documents, or customer PII, and it **never mutates** a business row.

Other read-only staff routes (all `STAFF_SUPPORT_READ` + `DIAGNOSTICS` grant): tenant locator search
(`/tenants/search`), per-tenant support context (`/tenants/{id}/support-context`), operations summary and
timeline (`/tenants/{id}/operations/summary|timeline`), and data-repair operations view. Grant-management,
maintenance-record, data-repair, incident, and break-glass routes use their own stronger `STAFF_*`
permissions and scopes as listed in §4.

---

## 6. Safe DTO boundary

`api/dto/SupportInternalDtos.java` (and the sibling operations/locator DTOs) enforce contract law:

- **Request DTOs carry business intent only** — never tenant id, staff/actor id, status, expiry, approval
  state, execution authority, audit metadata, or any SQL/script/raw-target field. The tenant comes from
  `X-Tenant-Id`; the actor from the trusted actor header.
- **Response DTOs expose only operator-safe fields** — no secrets, connector credentials, raw
  webhook/document/AI payloads, internal stack traces, cross-tenant data, or internal grant/staff ids
  beyond what the operator legitimately needs. `SupportTenantDiagnosticsResponse` carries
  `externalExecution = DISABLED`.

Proven by `SupportAccessContractTest` (reflection: request records carry no authority field) and
`ResponseDtoLeakContractTest` (response records expose no banned/leak field).

---

## 7. Audit behavior

Every support decision — **allow and deny** — is audited by `SupportAccessService` via
`AuditEventService`:

- allow → `SUPPORT_ACCESS_GRANTED` (`decision=ALLOWED`, scope, tenant, grantId);
- deny → `SUPPORT_ACCESS_DENIED` (`decision=DENIED`, `reasonCode`, scope, tenant);
- grant lifecycle → `SUPPORT_ACCESS_GRANT_CREATED`, `SUPPORT_GRANT_APPROVAL_REQUESTED`,
  `SUPPORT_GRANT_APPROVED/REJECTED`, `SUPPORT_GRANT_APPROVAL_DENIED` (self-approval),
  `SUPPORT_ACCESS_GRANT_REVOKED`.

Audit metadata is who/what/when/why/tenant/scope at a safe internal level. It does not embed secrets or
raw payloads. Public/operator responses never expose raw audit-event internals.

---

## 8. Forbidden actions (enforced, not aspirational)

- **No direct production DB access** — the surface reads through repositories only.
- **No direct customer business mutation** — no order/quote/inventory/customer/price/master-data write on
  any staff route. The one bounded exception (OP-CAP-54 `PROCESSING_JOB_STATUS_REPAIR`) mutates only a
  wedged **control-plane** processing-job's own status, under an `APPROVED` `DataRepairRequest` +
  deterministic validation + `STAFF_PROCESSING_JOB_REPAIR_EXECUTE`; every other repair target stays a
  disabled dry-run/stub.
- **No arbitrary SQL/script** — no free-form SQL/script/table/field/JSON-patch field exists anywhere in
  the request contracts.
- **No secret/credential exposure** — no connector credential, `secretRef`/`secretReferenceId`/
  `secretValue`, API key, or token is ever returned.
- **No silent impersonation** — staff act as staff (their own `staff_user` id in audit), never as a
  tenant user; there is no tenant-user impersonation path.
- **`externalExecution` stays DISABLED** — no connector/ERP/1C write, no outbox emission from this surface.

---

## 9. What remains NOT PROVEN (production gaps)

This is a bounded foundation. The following are **explicitly not implemented/proven** by the current code
or tests, and this PR does **not** claim them:

- **Real SSO / OIDC / SAML** staff identity. Runtime staff identity is still the trusted gateway actor
  header; there is no federated identity provider.
- **Production BFF / server-side staff session.** The dashboard has no server-side staff session
  convention. `lib/internal-support-access.mjs` fails closed (`STAFF_AUTHENTICATION_UNAVAILABLE`) precisely
  because no trustworthy staff identity can be propagated yet.
- **Production gateway identity integration** for staff (hardware-backed / mTLS / verified staff subject).
- **Full support console UX** — the frontend surfaces are read-only/fail-closed proofs, not a production
  support console.
- **Any internal/support route or behavior not covered by the tests listed below** — coverage is the
  proof boundary; untested variants are not claimed.
- **Production deployment/config** for the staff plane (secrets, network segmentation, approver identity
  provisioning) is out of scope here.

---

## 10. Test evidence map

Backend (`apps/core-api`):

- `SupportAccessServiceTest` — grant validation: `noGrantIsDeniedAndAudited`, `expiredGrantIsDenied`,
  `wrongTenantGrantIsDenied`, `wrongScopeGrantIsDenied`, `roleThatDoesNotPermitScopeIsDeniedEvenWithAGrantRow`,
  `unknownPrincipalIsDenied`, `revokedGrantIsNoLongerUsable`, `validGrantWithPermittingRoleIsAllowedAndAudited`,
  `createGrantRequiresSupportCaseReferenceAndBoundedTtl`, `createGrantPersistsExpiringActiveGrantAndAudits`.
- `SupportGrantApprovalServiceTest` — OP-CAP-52 approval workflow + separation of duties.
- `InternalSupportControllerSecurityTest` — route-edge denials: unauthenticated→401, tenant
  operator/admin→403, weaker `STAFF_*`→403, wrong-tenant-header denied before service read, valid staff read
  returns bounded safe results.
- `InternalSupportVisibilityBoundaryTest` — `averageTenantUserCannotReadInternalSupportAndServicesAreNotInvoked`,
  `tenantAdminCannotMutateOperantMaintenanceAndServiceIsNotInvoked`,
  `validStaffReadWithMatchingTenantReturnsOnlySafeDiagnostics`,
  `validTenantAnalyticsReadStillReachesNormalBusinessService` (business-flow regression).
- `InternalSupportPermissionMatrixTest`, `SupportAccessRoutePermissionTest`, `ApiRouteSecurityClassificationTest`
  — permission mapping + default-deny + route inventory.
- `InternalSupportGatewaySecurityTest` — trusted-gateway staff header boundary.
- `SupportTenantLocatorServiceTest` / `SupportTenantLocatorBoundaryTest` — cross-tenant locator JIT grant boundary.
- `SupportAccessContractTest` / `ResponseDtoLeakContractTest` — request no-authority + response no-leak.
- `ApiPermissionRoleMatrixTest` (with `ApiRolePermissionMatrix`) — `STAFF_*` excluded from every tenant role.

Frontend (`apps/web-dashboard/tests/internal-support-operations.test.mjs`):

- `tenant navigation does not expose the Operant staff-only Internal Support plane`;
- `staff route group fails closed while no server-side staff session boundary exists`;
- `browser input cannot manufacture staff frontend access` (`resolveInternalSupportFrontendAccess` accepts
  no browser-controlled arguments and denies forged `{staffUserId, permission}`);
- GET-only client with no authority payload; no banned raw/leak field; no mutation/execution control.

---

## 11. Business Logic & Visibility Boundary Gate (summary)

**Tenant User Access** — tenant operators/admins use tenant business routes with tenant permissions; they
must **never** reach `/api/v1/internal/support/**`. Proven by `InternalSupportVisibilityBoundaryTest`
(tenant user/admin denied, services not invoked) and the `STAFF_*` exclusion in `ApiRolePermissionMatrix`.
Normal tenant flow still works: `validTenantAnalyticsReadStillReachesNormalBusinessService`.

**External Customer Access** — not applicable; external customers have no tenant/actor header and never
reach staff routes (they use token/webhook planes only). No staff route is on any public allowlist.

**Service Account Access** — machine callers (AI worker/connector/bot) hold only narrow intake/result
permissions, which are outside the `STAFF_*` family and thus cannot satisfy any staff route edge; there is
no explicit service-account→staff path by design.

**Operant Support & Maintenance Access Plane** — Operant staff, gated by a `STAFF_*` route permission
**and** a scoped/approved/unexpired `SupportAccessGrant`. Missing staff identity → 401/403; tenant admin →
403; expired/wrong-tenant/wrong-scope grant → denied (`SupportAccessServiceTest`); denied requests do not
invoke diagnostics/mutation services (`InternalSupportVisibilityBoundaryTest`); allowed action emits
`SUPPORT_ACCESS_GRANTED` audit. Not proven: real SSO/BFF/session, production gateway identity, full console.

---

## 12. Staff Identity Resolver Seam (PR #247)

`InternalSupportController` no longer resolves the acting staff actor through the generic tenant/operator
actor path directly. It depends on a dedicated abstraction:

- `application/services/support/StaffIdentityResolver` — `ResolvedStaffPrincipal resolveRequired(HttpServletRequest)`.
- `application/services/support/ResolvedStaffPrincipal` — a minimal, backend-owned record
  (`staffUserId`, `role`, `handle`, `source`). It is never built from a client body/query and carries no
  secret, no tenant authority, and no support-grant id.
- `application/services/support/TrustedGatewayStaffIdentityResolver` — the **only implementation today**.

**Current implementation wraps trusted gateway actor resolution.** The resolver calls the existing
`RequestActorResolver.resolveVerifiedActor(request, tenantContext)` (signed/verified trusted gateway actor
header, bound to the current tenant context) and then validates the resolved actor against the
`staff_user` registry. It adds **no** new trust source.

**Fail-closed at the identity boundary.** The resolver denies (`SupportAccessDeniedException`, i.e. the
same safe 403 `SUPPORT_ACCESS_DENIED`) when the trusted actor is the unauthenticated-fallback `SYSTEM`
sentinel, is unknown to `staff_user`, or maps to a disabled staff user; a malformed/absent trusted actor
still surfaces as the existing 400/401. A **tenant user/admin or a machine/service-account actor is not a
staff identity** — it has no `staff_user` row, so it can never be resolved as staff.

**Authorization semantics unchanged.** `SupportAccessService.authorize(...)` is untouched and still
independently validates the staff principal, role→scope, and the tenant-scoped/approved/unexpired grant,
and still emits the allow/deny audit. The seam only changes *where the staff actor comes from*, not the
allow/deny outcome for the grant-backed routes. (Grant-management routes — create/approve/reject/revoke —
now also resolve the acting actor through the seam, which strictly tightens them to real staff; it does
not alter grant lifecycle logic.)

**Staff identity is never accepted from the client request body.** The resolver reads only the trusted
request context; the frontend cannot propose `staffUserId`, and request DTOs carry no staff/grant authority
(proven by `SupportAccessContractTest`).

**Not implemented / not proven (unchanged by this seam):** real SSO/OIDC/SAML, production BFF/server-side
staff session, and production gateway identity integration all remain out of scope. The seam exists
precisely to preserve authorization semantics during a future identity-provider migration — not to claim
one is done.

Proven by `StaffIdentityResolverTest` (missing/malformed/unknown/disabled/tenant-operator/tenant-admin/
service-account all fail closed; a valid ACTIVE staff resolves to the expected principal) plus the
regression-updated `InternalSupportControllerSecurityTest` and `InternalSupportVisibilityBoundaryTest`.
