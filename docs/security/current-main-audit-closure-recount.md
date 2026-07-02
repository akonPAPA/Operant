# Current-main audit closure recount

## Audit identity

- Branch: `fix/current-main-audit-closure-recount`
- Base/main commit inspected: `8bcc1f87ae337603de33a1fe8941de6ad47e28cc`
- Recount date: 2026-07-02
- Product: Operant (repository and existing stage names remain unchanged)

This is a bounded current-main recount. It does not claim that the repository-wide audit is
closed. Findings are classified only as:

1. REAL CLOSED
2. VISIBILITY ONLY / PROOF PARTIAL
3. ACTIVE FIX REQUIRED
4. PRODUCT DECISION REQUIRED
5. OUT OF SCOPE FOR THIS BRANCH

## Files searched

The following production/test scopes were searched with exact-symbol or exact-risk-field `rg`
queries before edits:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/**/*.java`
  - controller return signatures; known entity/domain names; request bodies; internal/support routes
- `apps/core-api/src/main/java/com/orderpilot/api/dto/**/*.java`
  - authority fields; secret fields; raw JSON/payload fields; source IDs; actor IDs; failure/error fields
- `apps/core-api/src/main/java/com/orderpilot/application/services/**/*.java`
  - raw-ID repository access; trusted actor propagation; audit mappings; unbounded lists
- `apps/core-api/src/main/java/com/orderpilot/domain/**/*.java`
  - `findById`, tenant-aware repository methods, sensitive getters, `@JsonIgnore`, `findAll`, bounded queries
- `apps/core-api/src/test/java/**/*.java`
  - boundary, authority, leak, permission, wrong-tenant, pagination, and directly affected slice tests
- `apps/web-dashboard/{app,components,lib,tests}/**/*.{ts,tsx,mjs}`
  - authority fields; raw response fields; internal support routes/navigation; request payload construction
- `.github/workflows/*.{yml,yaml}`, `infra/github-actions/*.{yml,yaml}`,
  `apps/*/Dockerfile`, `infra/docker/**/*`
  - `continue-on-error`, job-level `if`, `skipTests`, `npm install`, unpinned runtime tags,
    Semgrep/Snyk/CodeQL references

Search result summary:

- No public controller method signature directly returns a `com.orderpilot.domain.*` type after the
  current-main DTO work; repository-wide reflection tests cover all REST controllers.
- Stage12 secret request/response shapes contain no public secret reference field.
- One active request-authority gap existed: workspace exception-case assignment accepted body `userId`.
- Active response leaks existed in raw actor IDs, source/internal IDs, raw failure details, validation
  JSON, and audit metadata/entity identifiers; the bounded occurrences listed below were removed.
- Multiple tenant-scoped list methods remain unbounded and need an API pagination/capping decision.
- One fleet-wide stale-processing reaper is deliberately cross-tenant but has no audit/metric
  compensating control.

## Files read line-by-line

### Secret/connector boundary

- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage12Dtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/ChannelConnectionController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/IntegrationConnectionController.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/channel/ChannelConnectionService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/integration/IntegrationConnectionService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/connector/SecretVaultService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/connector/LocalDevelopmentSecretVaultService.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/channel/ChannelConnection.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/integration/IntegrationConnection.java`
- `apps/core-api/src/test/java/com/orderpilot/api/dto/Stage12CredentialBoundaryTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/Stage12ControllerTest.java`

### Controller/DTO/actor boundaries

- `apps/core-api/src/main/java/com/orderpilot/api/rest/WorkspaceController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/DraftQuoteController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/QuoteReviewController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/ChangeRequestController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/ChannelRfqHandoffController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/ChannelIdentityController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/PilotController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/AiWorkController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage6Dtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage7Dtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage9Dtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage10BDtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage10CDtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage10DOmnichannelDtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage12CDtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/AiWorkDtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/CommandCenterDtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/ValidationReviewDtos.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/workspace/ExceptionCaseService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/workspace/OperatorReviewService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/aiwork/AiWorkService.java`
- `apps/core-api/src/main/java/com/orderpilot/security/RequestActorResolver.java`
- `apps/core-api/src/test/java/com/orderpilot/api/dto/ActorAuthorityRequestDtoTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/dto/ResponseDtoLeakContractTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/dto/Stage6AuthorityRequestContractTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/ClientAuthorityOverrideContractTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/ControllerEntityReturnBoundaryTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/ControllerEntityReturnBanTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/DirectEntityResponseBoundaryTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/WorkspaceDraftReviewControllerTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/PilotControllerActorAuthorityTest.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/ChannelIdentityControllerTest.java`

### Tenant/support/incident boundaries

- `apps/core-api/src/main/java/com/orderpilot/api/rest/InternalSupportController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/InternalIncidentController.java`
- `apps/core-api/src/main/java/com/orderpilot/security/ApiRouteSecurityPolicy.java`
- `apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java`
- `apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/intake/ProcessingJobRepository.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/WorkerJobLeaseService.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/incident/IncidentAlertRecordRepository.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/incident/BreakGlassAccessRequestRepository.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/intake/WebhookEventRepository.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/extraction/PromptTemplateVersionRepository.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/incident/IncidentResponseService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/channel/ChannelRfqHandoffService.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/InternalSupportVisibilityBoundaryTest.java`
- `apps/core-api/src/test/java/com/orderpilot/security/SupportAccessRoutePermissionTest.java`
- `apps/core-api/src/test/java/com/orderpilot/security/IncidentBreakGlassRoutePermissionTest.java`
- `apps/web-dashboard/lib/internal-support-access.mjs`
- `apps/web-dashboard/app/(dashboard)/internal-support/layout.tsx`
- `apps/web-dashboard/tests/internal-support-operations.test.mjs`

### CI/release and frontend contracts

- `.github/workflows/ci.yml`
- `.github/workflows/frontend.yml`
- `.github/workflows/ai-worker.yml`
- `.github/workflows/semgrep.yml`
- `.github/workflows/snyk.yml`
- `infra/github-actions/frontend.yml`
- `apps/core-api/Dockerfile`
- `apps/web-dashboard/Dockerfile`
- `scripts/check-core-api-release-dockerfile.ps1`
- `docs/security/ci-supply-chain-gates.md`
- `apps/web-dashboard/lib/ai-work-api.ts`
- `apps/web-dashboard/lib/bot-runtime-api.ts`
- `apps/web-dashboard/lib/channel-identity-api.ts`
- `apps/web-dashboard/lib/command-center-api.ts`
- `apps/web-dashboard/lib/quote-review-api.ts`
- `apps/web-dashboard/lib/rfq-handoff-api.ts`
- `apps/web-dashboard/lib/stage9-integration-api.ts`
- `apps/web-dashboard/lib/validation-review-api.ts`
- `apps/web-dashboard/lib/validation-review-command-api.ts`
- `apps/web-dashboard/lib/validation-review-detail-api.ts`
- `apps/web-dashboard/components/ai-work-assistant-workspace.tsx`
- `apps/web-dashboard/components/rfq-handoff-workspace.tsx`
- `apps/web-dashboard/components/quote-review-cockpit.tsx`
- `apps/web-dashboard/components/operant-command-center.tsx`
- `apps/web-dashboard/components/validation-review-detail.tsx`
- `apps/web-dashboard/components/integration-control.tsx`
- `apps/web-dashboard/components/connector-audit-timeline.tsx`

## Status by category

| Category | Status | Evidence | Fix done | Tests |
|---|---|---|---|---|
| A Secret boundary | REAL CLOSED | `SecretConfigurationRequest.secretValue` is write-only; create DTOs have no secret reference; controller responses expose `secretConfigured`; entities ignore both secret getters | No new fix required | `Stage12CredentialBoundaryTest`; `Stage12ControllerTest` inspected; targeted boundary run passed |
| B Direct entity returns | REAL CLOSED | Repository-wide controller reflection finds no domain/JPA return type; direct workspace mappings use safe DTOs | Existing boundary retained; additional response fields reduced | `DirectEntityResponseBoundaryTest` and global response test passed |
| C Actor authority | REAL CLOSED | Global request-body reflection now forbids `userId`; controllers resolve actors from trusted context | Workspace assignment changed from body-selected user to backend-resolved self-assignment; actor response IDs removed | `ClientAuthorityOverrideContractTest`, `Stage6AuthorityRequestContractTest`, `WorkspaceDraftReviewControllerTest`, Pilot/Channel Identity tests passed |
| D DTO leaks | PRODUCT DECISION REQUIRED | Active raw actor/error/audit/validation JSON leaks found and fixed. Remaining AI Work response carries `structuredPayloadJson`/`evidenceRefsJson` used by UI; AI Work create DTO accepts `idempotencyKey`; several lineage/source IDs are screen-visible | Removed bounded active leaks and stale frontend fields | Global DTO leak test plus affected backend/frontend suites passed |
| E Cross-tenant scope | PRODUCT DECISION REQUIRED | Tenant-owned lookups inspected use tenant predicates. Fleet stale-job reaper is bounded/system-only/no HTTP, but deliberately has no audit or metric | No fleet model change guessed | Tenant/support/RFQ tests inspected; targeted permission tests passed; full wrong-tenant suite not rerun |
| F Support/internal visibility | VISIBILITY ONLY / PROOF PARTIAL | Backend route-edge `STAFF_*` permissions and tenant/grant checks exist. Frontend navigation hides route and layout fails closed. Real staff session/BFF is absent, so staff UI access is intentionally unavailable | No SSO/BFF implementation attempted | Internal visibility and permission tests passed; expiry/missing/wrong-tenant grant service proofs not rerun in this turn |
| G Entity defense-in-depth | VISIBILITY ONLY / PROOF PARTIAL | Secret entity getters have `@JsonIgnore`; controller/domain return guards prevent current serialization. Other high-risk entities still have sensitive getters but no public serialization path | No broad annotation sweep | Controller/entity return tests passed; accidental direct serialization of every high-risk entity not proven |
| H CI/supply-chain | VISIBILITY ONLY / PROOF PARTIAL | Backend/frontend/worker jobs are not job-level skipped; frontend uses `npm ci`; release Docker verify stage runs tests; Semgrep ERROR is blocking; Snyk high/critical is scheduled/manual | No workflow change required | Frontend local checks passed. CodeQL Default Setup repository setting and branch protection are not locally provable |
| I Performance | PRODUCT DECISION REQUIRED | Pricing hot-path fix preserved. Multiple tenant list services still use unbounded ordered repository methods; some endpoints already use `Pageable`/`Limit` | No arbitrary response cap/pagination contract imposed | Existing bounded paths inspected; large-data behavior for remaining lists not proven |

## ACTIVE FIX REQUIRED findings closed in this branch

- Workspace assignment body `userId` removed; trusted actor is passed to the service.
- `correctedByUserId` removed from Pilot correction responses.
- `linkedByUserId` removed from Channel Identity responses and frontend request/response types.
- `sourceMessageId` removed from Bot Response Draft responses.
- raw `failureReason` removed from Stage9 and default ChangeRequest responses.
- raw/internal Outbox event id, aggregate id, attempt count, and last error removed.
- raw validation `detailsJson` and suggested-fix `suggestionJson` removed.
- raw quote-review audit metadata removed.
- command-center and validation-review audit entity/actor identifiers removed.
- AI memory evaluation failure detail (which could include memory keys) removed.
- advisory validation handoff failure output renamed to bounded `failureCode`.
- stale frontend connector credential/capability/reference fields removed.

## PRODUCT DECISION REQUIRED findings

- AI Work:
  - replace `structuredPayloadJson`/`evidenceRefsJson` with typed, allowlisted display DTOs;
  - decide backend-generated idempotency semantics before removing body `idempotencyKey`;
  - this is an API/UI contract change and was not guessed in this branch.
- Fleet stale-processing recovery:
  - decide whether to add tenant-attributed audit, a dedicated system-maintenance audit stream,
    and/or metrics/alerts; current row terminal markers and bounded count are not enough for REAL CLOSED.
- List APIs:
  - decide cursor/page/limit contracts and compatibility behavior for remaining unbounded tenant lists
    (workspace quotes/orders/cases, connector syncs/connections, webhook/extraction/import/change-request lists).
- Source/lineage identifiers:
  - some screens intentionally render/filter by source validation/review handles; replacing them with
    opaque public workflow handles requires an API product decision.
- Staff frontend:
  - real staff SSO/session/BFF identity propagation is absent; current behavior intentionally denies
    all frontend staff access.

## OUT OF SCOPE FOR THIS BRANCH

- Implementing staff SSO/OIDC/BFF.
- Replacing every source/lineage identifier with a new opaque-handle architecture.
- Broad pagination migration across all list APIs.
- New fleet-maintenance audit infrastructure.
- Repository settings changes for CodeQL/branch protection.
- Broad entity annotation or optimistic-locking sweep.

## Business Logic & Visibility Boundary Gate

### Internal support access-grant routes

- Routes: create/revoke/list grant; approve/reject grant.
- Who should see it: Operant staff with the exact `STAFF_SUPPORT_GRANT_*` permission.
- Who must never see it: average tenant users, tenant operators, and tenant admins.
- What client can send: grantee selection, scope, support case reference, bounded TTL, decision note.
- What backend resolves: acting staff actor, target tenant context, status, approval state, expiry.
- Permission: `STAFF_SUPPORT_GRANT_MANAGE`, `STAFF_SUPPORT_GRANT_APPROVE`, or `STAFF_SUPPORT_READ`.
- Denial tests: `SupportAccessRoutePermissionTest`, `InternalSupportVisibilityBoundaryTest`.
- Valid-flow tests: route mapping plus valid staff diagnostics path; grant lifecycle service suite was not rerun.
- Remaining not proven: production staff identity transport; missing/expired/wrong-tenant grant service cases in this turn.

### Internal support diagnostics/locator/operations reads

- Routes: tenant search, support context, diagnostics, operations summary/timeline/data-repair view.
- Who should see it: Operant staff with `STAFF_SUPPORT_READ` and an applicable active grant.
- Who must never see it: all tenant personas, staff lacking the permission, staff lacking the grant.
- What client can send: bounded query/page/size and a selected tenant navigation handle.
- What backend resolves: actor, grant validity/scope, tenant match, returned diagnostic projection.
- Permission: `STAFF_SUPPORT_READ`; service layer additionally validates `DIAGNOSTICS`.
- Denial tests: backend permission/visibility tests; frontend navigation and fail-closed layout tests.
- Valid-flow tests: valid staff diagnostics and normal tenant analytics flow passed.
- Remaining not proven: real staff UI session is absent; frontend is intentionally unavailable.

### Internal maintenance/data-repair routes

- Routes: maintenance record; data-repair dry-run/request approval/approve/reject/disabled execute;
  approved processing-job repair execute.
- Who should see it: explicitly authorized Operant staff with matching support grant/scope.
- Who must never see it: average tenant users, tenant admins, support-read-only staff, wrong-tenant staff.
- What client can send: bounded target/reason/expected-state business intent.
- What backend resolves: actor, tenant, grant, approval state, execution authority, audit.
- Permission: dedicated `STAFF_MAINTENANCE_*` / `STAFF_DATA_REPAIR_*` permissions.
- Denial tests: `SupportAccessRoutePermissionTest`, `InternalSupportVisibilityBoundaryTest`.
- Valid-flow tests: route permission matrix passed; processing repair execution suite not rerun.
- Remaining not proven: production grant expiry/missing/wrong-tenant behavior in this turn.

### Internal incident/break-glass routes

- Routes: create/read/close incident; request/approve/reject/revoke break-glass.
- Who should see it: Operant incident staff with the exact `STAFF_INCIDENT_*` or
  `STAFF_BREAK_GLASS_*` permission.
- Who must never see it: tenant users/admins and staff holding only a different incident permission.
- What client can send: incident title/reason/severity/type; break-glass reason/scope/TTL; decision note.
- What backend resolves: actor, tenant, status, approval, expiry, separation of duties, audit.
- Permission: verb-specific `STAFF_INCIDENT_*` and `STAFF_BREAK_GLASS_*`.
- Denial tests: `IncidentBreakGlassRoutePermissionTest`.
- Valid-flow tests: route matrix passed; service lifecycle suite not rerun.
- Remaining not proven: complete incident service suite and production identity transport.

## Request/response contract result

- Client-owned authority removed: yes, for the active `WorkspaceController.assign` gap.
- Backend-owned fields protected: yes for actor/tenant/status/approval paths touched.
- Response forbidden fields absent: proven for the touched fields by global reflection and direct tests.
- Frontend payload safe: touched frontend types no longer declare linked-by actor or stale connector
  credential/capability authority fields; frontend test suite passed.
- Remaining contract risk: typed AI Work payload/idempotency decision and screen-required lineage IDs.

## Tenant isolation result

- Tenant-scoped lookup fixes: no new repository fix was required in the touched slices.
- Wrong-tenant tests: existing RFQ/support/incident tests were inspected; the full tenant suite was not rerun.
- System-wide operations explicitly classified: stale-processing recovery is PRODUCT DECISION REQUIRED.

## Tests run

Passed:

```text
mvn -f apps/core-api/pom.xml "-Dtest=Stage12CredentialBoundaryTest,ResponseDtoLeakContractTest,ActorAuthorityRequestDtoTest,ClientAuthorityOverrideContractTest,DirectEntityResponseBoundaryTest,InternalSupportVisibilityBoundaryTest,SupportAccessRoutePermissionTest,IncidentBreakGlassRoutePermissionTest,Stage6AuthorityRequestContractTest,ChangeRequestSafeResponseDtoTest,Stage9ConnectorResponseLeakTest" test
Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
```

An earlier run of the same command found two additional `failureReason` response leaks and failed
`ResponseDtoLeakContractTest`; those fields were removed/renamed and the command above then passed.

```text
mvn -f apps/core-api/pom.xml "-Dtest=WorkspaceDraftReviewControllerTest,PilotControllerActorAuthorityTest,ChannelIdentityControllerTest,BotRuntimeControllerTest,Stage9IntegrationControllerTest,ChangeRequestControllerActorAuthorityTest,CommandCenterReadServiceTest,AiMemoryEvaluationBatchRunnerServiceStage20Test,AiMemoryEvaluationServiceStage19Test,AdvisoryExtractionValidationHandoffServiceStage13ATest,ValidationReviewQueryStage14ATest" test
Tests run: 72, Failures: 0, Errors: 0, Skipped: 0
```

```text
npm ci
added 388 packages; 0 vulnerabilities

npm run lint
passed

npm run typecheck
passed

npm test
tests 442; pass 442; fail 0

npm run build
passed; 51 static pages generated
```

Not run:

```text
mvn -f apps/core-api/pom.xml -DskipITs test
```

The command was submitted but execution approval was rejected because the account execution usage
limit had been reached. It did not start. Full backend suite is therefore **not proven**.

## Remaining risks / not proven

- Full backend non-IT suite: not proven.
- Backend integration tests/PostgreSQL: not proven.
- Full CI: not proven.
- CodeQL Default Setup and required branch-protection settings: settings check required.
- Real staff SSO/session/BFF: not implemented/proven.
- Support grant expiry/missing/wrong-tenant service suite: not rerun.
- Fleet reaper audit/metrics: product decision required.
- Large-data behavior for unbounded list APIs: product decision required.
- AI Work typed output/idempotency contract: product decision required.
- Worker/runtime and connector production execution: not proven.

