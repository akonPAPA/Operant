# Backend Service-Info Endpoint Hardening Plan

## 1. Status

```text
Canonical Stage-Source Freeze: PASS
Product stage status: PARTIAL
Product capability freeze: ACTIVE
Canonical current-stage pointer: docs/product/current-stage.md
Detailed evidence source: docs/product/STAGE_STATUS_RECONCILIATION.md
Owner-decision matrix: docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md
Backend artifact attribution: docs/product/BACKEND_CODE_ARTIFACT_ATTRIBUTION_SERVICE_INFO.md
```

This document is a docs-only hardening plan. It does not approve, stage, modify, or implement `ServiceInfoController.java`.

## 2. Scope

This plan defines requirements for a possible future backend slice if the owner decides to keep `ServiceInfoController.java` as a service metadata endpoint.

Non-scope:

```text
- no code edits
- no test edits
- no frontend edits
- no dependency changes
- no staging
- no commit
- no deletion
- no product capability implementation
```

## 3. Preflight result

| Required file | Exists? | Result |
|---|---:|---|
| `docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md` | yes | Preflight passed. |
| `docs/product/BACKEND_CODE_ARTIFACT_ATTRIBUTION_SERVICE_INFO.md` | yes | Preflight passed. |
| `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` | yes | Preflight passed. |

Prior classification from `docs/product/BACKEND_CODE_ARTIFACT_ATTRIBUTION_SERVICE_INFO.md`:

```text
POSSIBLE_RUNTIME_CODE
SERVICE_METADATA_ENDPOINT
DEMO_SUPPORT
OWNER_DECISION_REQUIRED
DO_NOT_TOUCH_DURING_FREEZE
STAGE_WITH_RELATED_BACKEND_TESTS_LATER if kept
```

Prior recommendation: keep as a service metadata endpoint only if a later backend slice hardens it, tests it, and performs security review. It must not be staged now.

## 4. Current artifact summary

Current tracked status:

- `git status --short -- apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` reports `??`, so the artifact is untracked and not staged.

Read-only artifact summary:

- Package/class: `com.orderpilot.api.rest.ServiceInfoController`
- Endpoints:
  - `GET /`
  - `GET /favicon.ico`
- Methods:
  - `serviceInfo()`
  - `favicon()`
- Response payload for `GET /`:
  - `service`: `orderpilot-core-api`
  - `status`: `UP`
  - `health`: `/actuator/health`
- Imports/dependencies:
  - `java.util.Map`
  - `org.springframework.http.ResponseEntity`
  - `org.springframework.web.bind.annotation.GetMapping`
  - `org.springframework.web.bind.annotation.RestController`
- Security annotations: none.
- Request context/tenant/auth: none.
- Apparent access posture: root-level endpoint outside the observed `/api/v1/**` `ApiPermissionInterceptor` path pattern; therefore it appears public or at least not covered by that interceptor.
- Metadata exposure: service name, coarse status, and actuator health path.
- Sensitive metadata exposure in current file: no direct secrets, tokens, profiles, hostnames, datasource URLs, usernames, filesystem paths, tenant IDs, customer data, or commit hashes. The actuator path is semi-sensitive operational metadata because it advertises a management endpoint location.

Existing convention evidence:

- `HealthController` uses `@RequestMapping("/api/v1/health")`.
- Most controllers under `apps/core-api/src/main/java/com/orderpilot/api/rest` use `/api/v1/...` or explicit stage paths.
- `ApiSecurityWebConfig` registers `ApiPermissionInterceptor` for `/api/v1/**`.
- Runbooks and frontend health checks mostly use `/api/v1/health`; some runbooks also reference `/actuator/health`.

## 5. Endpoint purpose decision

The only acceptable purpose if kept:

```text
A service-info endpoint may expose minimal, non-sensitive service metadata for local demo verification, troubleshooting, and operator/developer confidence.
```

It must not become:

- a production readiness proof;
- a health endpoint replacement;
- an environment dump;
- a secret/config/debug endpoint;
- a tenant data endpoint;
- a backdoor for status or feature flags;
- an investor-demo claim that Core v1 is complete.

## 6. Recommended endpoint contract if kept

Proposed future endpoint:

```text
GET /api/v1/service-info
```

Proposed future response:

```json
{
  "service": "orderpilot-core-api",
  "status": "running",
  "apiVersion": "v1",
  "productStageStatus": "PARTIAL",
  "capabilityFreeze": "ACTIVE",
  "canonicalStageSource": "docs/product/current-stage.md",
  "timestamp": "2026-06-02T00:00:00Z"
}
```

This is a proposed future contract, not the current implementation.

Required contract constraints:

- Do not expose commit hash unless explicitly approved.
- Do not expose build time unless explicitly approved.
- Do not expose active Spring profiles.
- Do not expose hostnames.
- Do not expose usernames.
- Do not expose filesystem paths.
- Do not expose datasource URLs.
- Do not expose tenant counts.
- Do not expose environment variables.
- Do not expose API keys/tokens/secrets.
- Do not expose internal dependency versions unless owner approves.

The root path `/` should not be the default service-info API contract. If root behavior is needed for local demo ergonomics, it should be handled as a separate owner-approved choice with explicit tests and access policy.

## 7. Authentication and access policy

Recommended policy:

```text
Authenticated internal users only.
No tenant data.
No role-sensitive data.
No environment data.
```

Rationale:

- The current artifact is outside `/api/v1/**`, while current API permission interception is scoped to `/api/v1/**`.
- Moving the endpoint to `/api/v1/service-info` aligns with the API surface and makes access policy easier to reason about.
- The endpoint has no business data, so tenant scoping is not needed for response content, but request handling should still not bypass the project's normal API security posture.

Policy requirements:

- RBAC: require the least read permission that fits the existing permission model, or introduce an explicit low-risk service-info read permission only in a future implementation slice if necessary.
- Tenant scoping: no tenant-specific output; no tenant ID required for data lookup. If existing API middleware requires tenant context for all authenticated API calls, the endpoint should either satisfy that convention or document an explicit exception.
- Audit: no audit event required for a simple metadata read if consistent with existing read behavior; standard request/access logging is enough.
- Rate limiting: apply existing API rate limiting if available.
- CORS: expose to frontend only if a real frontend use exists. Current frontend health display uses `/api/v1/health`, so no frontend dependency is proven.

Alternative acceptable policy:

```text
Public in local/dev profile only, disabled or auth-protected outside local/dev.
```

Not recommended:

```text
Public unrestricted endpoint in production-like environments.
```

## 8. Security hardening requirements

### 8.1 No sensitive data exposure

Forbidden fields:

- secrets;
- tokens;
- API keys;
- passwords;
- datasource URLs;
- JDBC URLs;
- usernames;
- hostnames;
- filesystem paths;
- internal IPs;
- active Spring profiles;
- cloud metadata;
- tenant/customer/user counts;
- tenant IDs;
- full commit hash unless explicitly approved;
- dependency tree;
- environment variables.

### 8.2 Safe metadata only

Allowed fields:

- service name;
- static API version;
- coarse status string;
- product stage status, if intentionally included;
- capability freeze state, if intentionally included;
- timestamp generated at request time;
- canonical docs pointer if useful.

### 8.3 Error handling

- Endpoint must not throw stack traces to user.
- Endpoint must use the standard structured error format if it fails.
- Endpoint must not return build/debug internals on error.

### 8.4 Rate limiting

- Apply existing API rate limiting if available.
- Do not let endpoint become an unauthenticated high-frequency probe surface.

### 8.5 Observability

- Access may be logged via standard request logging.
- Do not log secrets or request sensitive data.
- If endpoint is public in local/dev only, document that clearly.

## 9. Architecture requirements

If kept, future implementation must:

- follow existing controller conventions;
- use existing DTO/response conventions if available;
- avoid business logic in controller;
- avoid direct database access;
- avoid reading environment variables directly in controller;
- avoid exposing `Environment`, `BuildProperties`, or `GitProperties` raw;
- avoid static misleading claims like `productionReady: true`;
- avoid touching tenant/customer/product/order data;
- avoid creating audit events unless existing conventions require read audit.

Preferred implementation style:

```text
Controller -> small service/info provider -> safe DTO
```

If the endpoint remains pure static safe metadata, a minimal controller can be acceptable only if it is consistent with existing project style, has a stable DTO/contract, and has tests proving no unsafe fields are exposed.

## 10. Testing requirements for future implementation slice

Do not add these tests in this slice. This is planning only.

Required future tests:

### 10.1 Contract test

Verify response includes only approved fields and uses the selected endpoint path.

### 10.2 Security test

Verify no forbidden fields appear:

- profiles;
- datasource;
- host;
- username;
- env;
- secret;
- token;
- password;
- tenant IDs;
- customer data.

### 10.3 Auth/access test

Depending on selected policy:

- unauthenticated request is rejected; or
- public access only works under local/dev profile and is disabled/protected elsewhere.

### 10.4 Stability test

Endpoint returns a stable shape and does not depend on external services.

### 10.5 No-business-data test

Endpoint does not query or expose tenant/customer/product/order data.

### 10.6 OpenAPI/API docs test if applicable

If project checks generated OpenAPI, ensure endpoint appears with correct description and security policy.

Additional future verification:

- Run backend compile/test verification in the future implementation slice.
- Do not accept the endpoint for staging until related backend tests pass.

## 11. Documentation requirements for future implementation slice

If owner keeps endpoint, later slice must update:

- API documentation/OpenAPI annotation if project uses it;
- runbook only if endpoint is used for local demo verification;
- security note explaining exposed fields;
- current-stage docs only if endpoint status affects repository-control evidence, which it probably should not.

Do not use this endpoint as evidence that Core v1 is complete.

## 12. Future implementation slice boundaries

If owner decides to keep the endpoint, future implementation slice should be named:

```text
Backend service-info endpoint hardening implementation
```

Future implementation slice may:

- edit `ServiceInfoController.java`;
- add DTO/service class if needed;
- add tests;
- update OpenAPI docs;
- update local demo runbook if needed.

Future implementation slice must not:

- add new business capability;
- expose sensitive metadata;
- update current product status above `PARTIAL`;
- stage unrelated dirty files;
- touch frontend unless a separate frontend slice is selected;
- use endpoint to claim production readiness;
- alter bot/AI/integration behavior.

## 13. Owner decision options

### Option A - Keep and harden later

Use if endpoint is useful for local demo/service metadata.

Requirements:

- security review;
- tests;
- safe contract;
- no sensitive fields;
- no production readiness claims.

### Option B - Keep as local-only demo endpoint

Use if endpoint is only needed for demo verification.

Requirements:

- local/dev-only exposure;
- clearly documented;
- protected/disabled outside local/dev.

### Option C - Do not keep

Use if artifact appears accidental or duplicative.

Requirements:

- delete only in a future owner-approved cleanup slice;
- do not delete during current freeze.

### Option D - Defer

Use if evidence is insufficient.

Requirements:

- leave untouched;
- revisit after capability freeze or in backend attribution group.

Recommendation:

```text
Option A - Keep and harden later.
```

Reason: the artifact is small and plausibly useful for service/demo ergonomics, and a modified smoke test already expects root/favion behavior. However, it should not remain root-level public runtime code by default. If owner keeps it, the future implementation should prefer `/api/v1/service-info`, minimal safe metadata, explicit access policy, and tests before staging.

## 14. Risk rating

| Risk | Level | Reason | Mitigation |
|---|---|---|---|
| Sensitive metadata exposure | MEDIUM | Current file is safe-ish, but service-info endpoints often expand into environment/build/config dumps. | Freeze allowed fields; test forbidden fields; do not expose raw `Environment`, `BuildProperties`, or `GitProperties`. |
| Public endpoint exposure | MEDIUM | Current root path appears outside `/api/v1/**` permission interception. | Prefer authenticated `/api/v1/service-info`; allow public only for local/dev with explicit controls. |
| Product-status confusion | MEDIUM | A polished root endpoint can be mistaken for readiness evidence. | Include `PARTIAL` if stage status is exposed; state it is not production readiness proof. |
| Accidental staging with docs | HIGH | The artifact is untracked runtime code in a broad dirty worktree. | Do not stage now; only stage in a backend implementation slice with tests. |
| Duplicate actuator/health function | MEDIUM | Current response points to `/actuator/health` and overlaps with `/api/v1/health`. | Define service-info as metadata only; do not replace health endpoints. |
| Missing tests | HIGH | No dedicated `ServiceInfoController` test was found; only a modified investor smoke test references the behavior. | Add focused contract/security/access tests before staging. |

## 15. Recommended next safe slice

```text
D. Owner decision checkpoint - keep/defer/discard ServiceInfoController
```

Do not start the next slice.

## 16. Verification

Commands run:

```powershell
git status --short
git diff --stat
git diff --name-status
git diff --cached --name-status
Test-Path docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md
Test-Path docs/product/BACKEND_CODE_ARTIFACT_ATTRIBUTION_SERVICE_INFO.md
Test-Path apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java
Get-Content docs/product/current-stage.md -TotalCount 120
Get-Content docs/product/STAGE_STATUS_RECONCILIATION.md -TotalCount 220
Get-Content docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md -TotalCount 260
Get-Content docs/product/CANONICAL_STAGE_TAXONOMY.md -TotalCount 220
Get-Content docs/product/HISTORICAL_STAGE_DOC_INDEX.md -TotalCount 260
Get-Content docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md -TotalCount 360
Get-Content docs/product/BACKEND_CODE_ARTIFACT_ATTRIBUTION_SERVICE_INFO.md -TotalCount 360
git status --short -- apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java
Get-Content apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java -TotalCount 260
Get-ChildItem apps/core-api/src/main/java/com/orderpilot/api/rest -Filter "*Controller.java" | Select-Object Name, FullName
rg -n "@RestController|@RequestMapping|@GetMapping|@PostMapping|ResponseEntity|ProblemDetail|SecurityRequirement|PreAuthorize|Operation|Tag|Authentication|Principal|Tenant|tenant|X-Tenant|Actuator|Health|InfoEndpoint|MeterRegistry|BuildProperties|GitProperties" apps/core-api/src/main/java apps/core-api/src/test docs
rg -n "ServiceInfoController|ServiceInfo|service-info|service info|/service-info|/api/v1/service-info|/info|/status|version|build|commit|profile|datasource|actuator" apps docs scripts README.md PROJECT_STATUS_CHECKPOINT.txt ORDERPILOT_CORE_V1_AI_DEV.md
rg -n "spring.profiles.active|datasource|jdbc|password|secret|token|api key|host|hostname|username|commit|build.time|git.commit|management.endpoints|actuator" apps/core-api docs
Get-Content apps/core-api/src/main/java/com/orderpilot/api/rest/HealthController.java -TotalCount 120
Get-Content apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java -TotalCount 220
Get-Content apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java -TotalCount 240
rg -n -e 'ServiceInfoController' -e 'serviceRootAndFavicon' -e 'get\("/"\)' -e 'get\("/favicon.ico"\)' apps/core-api/src/test apps/core-api/src/main/java docs/product
rg -n -e '/api/v1/health' -e '/actuator/health' -e 'HealthController' apps/core-api/src/main/java apps/core-api/src/test docs scripts
git status --short
git diff --stat
git diff --name-status
git diff --cached --name-status
Test-Path docs/product/BACKEND_SERVICE_INFO_ENDPOINT_HARDENING_PLAN.md
```

Compile/test commands:

```text
Not run. This is docs-only planning. Maven compile/tests can write target output and may require dependency resolution.
```

Files changed by this slice:

- `docs/product/BACKEND_SERVICE_INFO_ENDPOINT_HARDENING_PLAN.md`

Files intentionally not changed:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java`
- backend product code
- backend tests
- frontend code
- AI worker code
- bot/integration/analytics code
- tests/fixtures/scripts
- generated/tooling artifacts
- lockfiles
- dependency manifests

Staged area:

- `git diff --cached --name-status` returned no paths before this document was created.
- Final `git diff --cached --name-status` after file creation returned no paths.

Confirmations:

- No product capability was changed.
- `ServiceInfoController.java` was not edited.
- No tests were added or modified.
- No frontend files were edited.
- No files were staged.
- No files were committed.
- No files were deleted.
- No files were moved or renamed.
- No dependencies were installed.
- No lockfiles were changed.
- Canonical Stage-Source Freeze remains `PASS`.
- Product stage status remains `PARTIAL`.
- Product capability freeze remains `ACTIVE`.
