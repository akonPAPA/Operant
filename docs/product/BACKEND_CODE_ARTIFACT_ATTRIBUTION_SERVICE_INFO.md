# Backend Code Artifact Attribution — ServiceInfoController

## 1. Status

```text
Canonical Stage-Source Freeze: PASS
Product stage status: PARTIAL
Product capability freeze: ACTIVE
Canonical current-stage pointer: docs/product/current-stage.md
Detailed evidence source: docs/product/STAGE_STATUS_RECONCILIATION.md
Dirty worktree attribution: docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md
Owner-decision matrix: docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md
```

This document is an attribution record only. It does not approve, stage, delete, modify, or implement the artifact.

## 2. Scope

This pass investigates the uncertain backend artifact:

```text
apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java
```

Non-scope:

```text
- no code edits
- no tests edits
- no frontend edits
- no dependency changes
- no staging
- no commit
- no deletion
- no product capability implementation
```

## 3. Preflight result

`docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md` exists.

Relevant owner-decision classification from that file:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` is an untracked backend runtime code-like artifact.
- It is classified as `POSSIBLE_RUNTIME_CODE`, `STAGE_WITH_RELATED_CAPABILITY_LATER`, and `DO_NOT_TOUCH_DURING_FREEZE`.
- It adds `GET /` service info and `GET /favicon.ico` no-content behavior.
- Recommended safe handling is a dedicated backend attribution slice, with no edit, stage, delete, move, or docs-only staging.

## 4. Worktree status for target artifact

| Path | Exists? | Tracked/untracked/modified? | Source of status | Notes |
|---|---:|---|---|---|
| `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` | yes | untracked | `git status --short -- apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` | Appears as `??`; not staged. |

The artifact appears to be an untracked new backend runtime file. It is not absent, not tracked modified, and not staged.

## 5. Artifact content summary

The file appears to contain a small Spring MVC controller:

- Package: `com.orderpilot.api.rest`
- Class: `ServiceInfoController`
- Annotations: `@RestController` on the class; `@GetMapping("/")` and `@GetMapping("/favicon.ico")` on methods.
- Endpoint paths:
  - `GET /`
  - `GET /favicon.ico`
- Methods:
  - `serviceInfo()`: returns a `Map<String, Object>`.
  - `favicon()`: returns `ResponseEntity.noContent().build()`.
- Response payload shape for `GET /`:
  - `service`: `orderpilot-core-api`
  - `status`: `UP`
  - `health`: `/actuator/health`
- Imports/dependencies:
  - `java.util.Map`
  - `org.springframework.http.ResponseEntity`
  - `org.springframework.web.bind.annotation.GetMapping`
  - `org.springframework.web.bind.annotation.RestController`
- Security annotations: none.
- Tenant/auth behavior: none in the controller. The current `ApiSecurityWebConfig` interceptor registration applies `ApiPermissionInterceptor` only to `/api/v1/**`, so this root endpoint and favicon endpoint do not appear to be covered by that interceptor path pattern.
- Service metadata exposure: yes, it exposes service name, service status, and an actuator health path.
- Sensitive information exposure: no secrets, hostnames, internal file paths, usernames, profiles, datasource URLs, tokens, tenant data, or raw commit hashes are present in the file as read. It does expose the presence of `/actuator/health`.

Conceptual compile assessment: the code appears syntactically valid Spring Boot Java. `Map.of(...)` can satisfy the declared `Map<String, Object>` return through target typing, and `ResponseEntity<Void>` is compatible with `noContent().build()`. No compile command was run in this slice.

## 6. Reference search results

### 6.1 Backend references

Direct references found:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` defines the class and endpoints.
- `apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java` contains `serviceRootAndFaviconDoNotReturnInternalError`, which expects:
  - `GET /` returns HTTP 200 and JSON fields `service=orderpilot-core-api`, `status=UP`.
  - `GET /favicon.ico` returns HTTP 204.

Related existing backend health behavior:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/HealthController.java` already exposes `GET /api/v1/health`.
- `apps/core-api/src/test/java/com/orderpilot/api/rest/HealthControllerTest.java` tests `GET /api/v1/health`.
- `apps/core-api/pom.xml` includes `spring-boot-starter-actuator`, so `/actuator/health` may exist depending on runtime actuator configuration.

Controller conventions:

- Most existing controllers in `apps/core-api/src/main/java/com/orderpilot/api/rest` use `/api/v1/...` or explicit stage paths.
- `ServiceInfoController` is unusual because it maps the application root `/` and `/favicon.ico`, outside `/api/v1`.
- `HealthController` is the closest existing service-health convention and is already under `/api/v1/health`.

### 6.2 Test references

One direct test reference was found:

- `apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java`

No focused `@WebMvcTest(ServiceInfoController.class)` or dedicated controller test for `ServiceInfoController` was found.

### 6.3 Frontend references

No web-dashboard code was found calling `GET /` or `/favicon.ico`.

The dashboard command-center page calls `GET /api/v1/health`, not the new root service-info endpoint:

- `apps/web-dashboard/app/(dashboard)/command-center/page.tsx`

### 6.4 Documentation/runbook references

Direct documentation references to `ServiceInfoController.java` appear only in governance/attribution docs:

- `docs/product/STAGE_STATUS_RECONCILIATION.md`
- `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md`
- `docs/product/HISTORICAL_STAGE_DOC_INDEX.md`
- `docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md`

General health documentation and runbooks reference existing health endpoints such as:

- `http://localhost:8080/api/v1/health`
- `http://localhost:8080/actuator/health`

No runbook was found documenting `GET /` as an accepted service-info endpoint.

### 6.5 Investor/demo references

`CoreV1InvestorDemoSmokeTest` includes the only direct investor/demo-style test coverage for the root and favicon endpoints.

Investor and demo docs reference backend health checks, mainly `/api/v1/health` and sometimes `/actuator/health`, but no investor/demo doc was found that instructs use of `GET /` as a demo step.

## 7. Risk analysis

### 7.1 Security risk

The endpoint exposes limited service metadata: service name, `UP` status, and `/actuator/health` location. It does not expose internal paths, usernames, hostnames, secrets, active profiles, datasource URLs, tokens, commit hashes, or tenant data.

The root and favicon endpoints are outside `/api/v1/**`, so they do not appear to be covered by the current API permission interceptor path pattern. This may be acceptable for a public service-info endpoint, but it should be an explicit decision. If kept, the endpoint should have a documented public-access posture or an auth/RBAC decision.

The endpoint does not perform tenant reads or writes and does not bypass tenant isolation for business data. It also does not mutate state.

### 7.2 Architecture risk

The controller fits Spring MVC mechanically, but its path conventions differ from most API controllers, which generally use `/api/v1/...` or explicit stage paths. There is already a `HealthController` at `/api/v1/health`, so this artifact partly duplicates health/status information and points users toward actuator health.

Because the controller lives in `api/rest`, it would be included in component scanning if compiled and staged. That makes it runtime code, not just demo documentation.

The code does not bypass service/application layers for business operations because it has no business operations. Its main architecture question is whether root-level service metadata belongs in the production API surface or only in demo/local ergonomics.

### 7.3 Product-stage risk

This artifact must not be used as evidence that Core v1 is production complete. A root service-info endpoint can make the API look more polished for local/demo use, but it does not change the product status from `PARTIAL`.

If kept for investor/demo readiness, it should be described as demo/service-health ergonomics only. It must not promote Stage 13/13E demo readiness or service reachability into product capability acceptance.

### 7.4 Worktree risk

The file is untracked runtime code and could be accidentally staged with docs unless explicitly avoided. It also has at least one modified backend smoke test expecting the root and favicon behavior, which suggests it may belong with a future backend/demo-runtime slice rather than a standalone docs-control change.

If kept, it should be staged only with related backend tests and security review. It should not be staged during the current capability freeze or under a docs-only PR.

## 8. Classification

| Classification axis | Value | Reason |
|---|---|---|
| Artifact type | `POSSIBLE_RUNTIME_CODE / SERVICE_METADATA_ENDPOINT / DEMO_SUPPORT` | It is a Spring controller that adds runtime endpoints for service metadata and favicon handling; the only direct test reference is in an investor demo smoke test. |
| Current authority | `NON_CANONICAL` | It is untracked and not approved by the canonical stage-control chain. |
| Stage relation | `OWNER_DECISION_REQUIRED / FUTURE_BACKEND_SLICE` | It is runtime code outside docs-control scope and should be accepted or rejected only in a backend slice. |
| Staging recommendation | `DO_NOT_STAGE_NOW / STAGE_WITH_BACKEND_TESTS_LATER` | It should not be mixed with docs-control or staged during freeze; if kept, stage with tests/security review. |
| Risk level | `MEDIUM` | The code is small and read-only, but it creates public root-level runtime API behavior outside `/api/v1/**` permission interception and is untracked. |

Recommended classification:

```text
POSSIBLE_RUNTIME_CODE
SERVICE_METADATA_ENDPOINT
DEMO_SUPPORT
OWNER_DECISION_REQUIRED
DO_NOT_TOUCH_DURING_FREEZE
STAGE_WITH_RELATED_BACKEND_TESTS_LATER if kept
```

## 9. Required future acceptance criteria if owner keeps it

If the owner decides to keep `ServiceInfoController.java`, a later backend implementation/review slice should require:

- endpoint purpose documented;
- endpoint path matches API conventions or the root path exception is explicitly approved;
- sensitive fields are not exposed;
- auth/RBAC/tenant behavior is explicit;
- tests added or retained in the correct backend test layer;
- OpenAPI docs updated if relevant;
- frontend/demo usage documented if relevant;
- no production-readiness claim derived from this endpoint;
- audit/security implications reviewed;
- compile/test verification required in future backend slice.

## 10. Recommended owner decision

Recommended decision:

```text
A. Keep as service metadata endpoint, but only stage later with tests/security review.
```

Reason: the endpoint is small, read-only, and appears useful for service-root/demo ergonomics. However, it is untracked runtime code outside `/api/v1/**`, overlaps with existing health endpoints, and has direct test coupling in a modified investor demo smoke test. It should therefore remain untouched now and be reviewed in a future backend slice before any staging.

Do not delete now. Do not stage now. Do not edit now.

## 11. Recommended next safe slice

```text
Backend service-info endpoint hardening plan, docs-only first
```

Do not start the next slice from this document.

## 12. Verification

Commands run:

```powershell
git status --short
git diff --stat
git diff --name-status
git diff --cached --name-status
Test-Path docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md
Get-Content docs/product/current-stage.md -TotalCount 120
Get-Content docs/product/STAGE_STATUS_RECONCILIATION.md -TotalCount 220
Get-Content docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md -TotalCount 260
Get-Content docs/product/CANONICAL_STAGE_TAXONOMY.md -TotalCount 220
Get-Content docs/product/HISTORICAL_STAGE_DOC_INDEX.md -TotalCount 260
Get-Content docs/product/UNTRACKED_ARTIFACT_OWNER_DECISIONS.md -TotalCount 320
Test-Path apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java
git status --short -- apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java
Get-Content apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java -TotalCount 260
rg -n "ServiceInfoController|service-info|service info|/service|/info|build|version|commit|health|actuator|demo service|ServiceInfo|status endpoint|api info" apps docs scripts README.md PROJECT_STATUS_CHECKPOINT.txt ORDERPILOT_CORE_V1_AI_DEV.md
Get-ChildItem apps/core-api/src/main/java/com/orderpilot/api/rest -Filter "*Controller.java" | Select-Object Name, FullName
rg -n "@RestController|@RequestMapping|@GetMapping|@PostMapping|ResponseEntity|SecurityRequirement|PreAuthorize|Operation|Tag" apps/core-api/src/main/java/com/orderpilot/api/rest
rg -n "service-info|ServiceInfo|/service|/info|/api/v1.*info|health|actuator|status" apps/core-api/src/test apps/web-dashboard docs
rg -n -e ServiceInfoController -e orderpilot-core-api -e favicon.ico -e '/actuator/health' apps docs scripts README.md PROJECT_STATUS_CHECKPOINT.txt ORDERPILOT_CORE_V1_AI_DEV.md
rg -n -e '/api/v1/health' -e '/actuator/health' -e HealthController apps/core-api/src/main/java apps/core-api/src/test docs scripts
rg -n -e ServiceInfoController -e service-info -e ServiceInfo -e favicon.ico apps/core-api/src/test apps/web-dashboard docs
Get-Content apps/core-api/src/main/java/com/orderpilot/api/rest/HealthController.java -TotalCount 160
Get-Content apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java -TotalCount 220
Get-Content apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java -TotalCount 220
Get-Content apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java -TotalCount 120
Get-Content apps/core-api/src/test/java/com/orderpilot/api/rest/HealthControllerTest.java -TotalCount 80
Get-Content apps/web-dashboard/app/(dashboard)/command-center/page.tsx -TotalCount 80
git status --short
git diff --stat
git diff --name-status
git diff --cached --name-status
```

Compile/test command:

```text
Not run. This slice is documentation-control only; Maven compile can write build output and may require dependency resolution. Future backend slice must run compile/test verification before staging this runtime code.
```

Files changed by this slice:

- `docs/product/BACKEND_CODE_ARTIFACT_ATTRIBUTION_SERVICE_INFO.md`

Files intentionally not changed:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java`
- backend product code
- backend tests
- frontend code
- AI worker code
- bot/integration/analytics code
- fixtures
- scripts
- generated/tooling artifacts
- lockfiles
- dependency manifests

Staged area:

- `git diff --cached --name-status` returned no paths before this document was created.
- Final `git diff --cached --name-status` after file creation returned no paths.

Confirmations:

- No product capability was changed.
- `ServiceInfoController.java` was not edited.
- No files were staged.
- No files were committed.
- No files were deleted.
- No files were moved or renamed.
- No dependencies were installed.
- No lockfiles were changed.
- Canonical Stage-Source Freeze remains `PASS`.
- Product stage status remains `PARTIAL`.
- Product capability freeze remains `ACTIVE`.
