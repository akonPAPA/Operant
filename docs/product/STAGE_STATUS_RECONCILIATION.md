# OrderPilot Stage Status Reconciliation

Date: 2026-06-01

## 1. Purpose

This document reconciles the current OrderPilot Core v1 repository state against the implemented code, tests, and stage documentation. It is a stage-gate document, not a new product capability.

The immediate goal is to stop treating conflicting stage documents as equally authoritative and to establish the next safe executable slice.

## 2. Repository baseline

- Repository root: `C:\OrderPilot\OrderPilot-Core`
- Branch observed: `stage-14-master-controlled-core-v1`
- Backend stack: Java 21, Spring Boot, Maven, Flyway, PostgreSQL/H2 test profile
- Frontend stack: Next.js and TypeScript under `apps/web-dashboard`
- AI worker stack: Python under `apps/ai-worker`
- Local orchestration: `infra/docker/docker-compose.yml`

The repository is an existing modular monolith with late-stage Core v1 code present. It is not a Stage 1 greenfield baseline.

## 3. Dirty worktree before this reconciliation

Commands run before editing:

```powershell
git status --short
git diff --stat
```

Dirty files that appear related to the immediately previous completed slice:

- `apps/core-api/src/main/java/com/orderpilot/application/services/validation/ProductMatchingService.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/validation/ValidationRunServiceStage5Test.java`
- `docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md`

Pre-existing or unclear dirty files observed before this reconciliation:

- `apps/core-api/src/main/java/com/orderpilot/application/services/bot/BotRuntimeService.java`
- `apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java`
- `apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/BotTelegramWebhookControllerTest.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/bot/BotRuntimeServiceTest.java`
- `apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java`
- `apps/core-api/src/test/java/com/orderpilot/demo/DemoDataService.java`
- `apps/core-api/src/test/java/com/orderpilot/demo/DemoFixturesTest.java`
- `apps/core-api/src/test/resources/demo/core-v1-demo/customers-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/import-validation-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/inventory-movements-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/products-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/reconciliation-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/telegram-rfq-demo.json`
- `apps/web-dashboard/components/bot-conversations-workspace.tsx`
- `apps/web-dashboard/components/demo-dashboard.tsx`
- `apps/web-dashboard/lib/demo-api.ts`
- `apps/web-dashboard/next-env.d.ts`
- `apps/web-dashboard/tests/demo-dashboard.test.mjs`
- `docs/investor/DEMO_DATASET_CORE_V1.md`
- `docs/investor/DEMO_SCREENSHOT_CHECKLIST.md`
- `docs/investor/DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md`
- `docs/investor/INVESTOR_DEMO_HANDOFF.md`
- `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md`
- `docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md`
- `docs/investor/demo-api-walkthrough.http`
- `docs/investor/demo-evidence/README_STAGE_9J.md`
- `docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md`
- `docs/runbooks/LOCAL_DEMO_RUNBOOK.md`
- `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md`
- `docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md`
- `packages/test-fixtures/stage3-intake/api-upload.sample.json`
- `packages/test-fixtures/stage3-intake/email-webhook.sample.json`
- `packages/test-fixtures/stage3-intake/telegram-update.sample.json`
- `packages/test-fixtures/stage3-intake/tiny-upload.sample.txt`
- `scripts/check-local-demo.ps1`
- `scripts/seed-local-demo.ps1`

Untracked files observed before this reconciliation:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java`
- `docs/investor/DATA_ROOM_CHECKLIST.md`
- `docs/investor/INVESTOR_NARRATIVE_V1.md`
- `docs/investor/STAGE_13C_DEMO_REHEARSAL_REPORT.md`
- `docs/investor/STAGE_13D_DEMO_LIMITATIONS_AND_RISK_NOTES.md`
- `docs/investor/STAGE_13D_INVESTOR_DEMO_FREEZE.md`
- `docs/investor/STAGE_13E_DEMO_PREFLIGHT_EVIDENCE.md`
- `docs/investor/STAGE_13E_FINAL_DEMO_SIGNOFF.md`
- `docs/investor/investor-demo-script.md`
- `docs/runbooks/STAGE_13D_DEMO_PREFLIGHT_CHECKLIST.md`
- `docs/runbooks/STAGE_13D_INVESTOR_WALKTHROUGH_CHECKLIST.md`
- `docs/runbooks/STAGE_13E_BROWSER_RESET_AND_STARTUP.md`

Pre-edit diff stat summary: 40 tracked files changed, 381 insertions, 133 deletions. This includes the previous narrow normalized SKU validation slice.

## 4. Current verified test baseline

Backend verification immediately before this reconciliation:

- `mvn -Dtest=ValidationRunServiceStage5Test test`: passed, 11 tests, 0 failures, 0 errors, 0 skipped.
- `mvn test`: passed, 379 tests, 0 failures, 0 errors, 0 skipped.

This reconciliation must rerun backend verification after documentation changes. Frontend and AI worker verification are useful for a full demo freeze, but this pass changes only documentation and does not install or alter dependencies.

Verification after this reconciliation:

- Sandboxed `mvn test` failed before execution because Maven Central access was blocked by the sandbox (`Permission denied: getsockopt` while resolving `spring-boot-starter-parent`).
- Approved `mvn test` passed: 379 tests, 0 failures, 0 errors, 0 skipped.
- `npm.cmd run lint` in `apps/web-dashboard` passed. No dependency install was run.

## 5. Stage/layer mapping

| Core v1 layer | Current classification | Repo-grounded evidence | Notes |
| --- | --- | --- | --- |
| 1. Secure SaaS Foundation | Partially implemented | `tenant`, `user`, `audit`, security package, tenant isolation tests, API permission interceptor | Tenant context and audit exist; production auth/RBAC is still not proven as complete. |
| 2. Business Data Mirror | Partially implemented | customer, product, pricing, inventory, imports, fixtures, import activation tests | Mirror/import paths exist, but source-system authority and staging/activation completeness need explicit acceptance. |
| 3. Product Intelligence | Partially implemented | product aliases, OEM references, normalized SKU, substitution and compatibility services/tests | Deterministic matching and substitutes exist; fitment depth and historical substitute learning are limited. |
| 4. Commercial Rules Engine | Partially implemented | price, discount, margin services and validation tests | Rules are deterministic and tested in backend; advanced commercial policy coverage remains staged. |
| 5. Inventory Intelligence | Partially implemented | inventory snapshots, reconciliation service/tests, inventory UI route | Latest stock and discrepancy foundations exist; reservation and warehouse authority remain out of scope. |
| 6. Channel Gateway | Partially implemented | channel adapters, inbound documents/messages, webhook verification, intake tests | File/API/email/Telegram/WhatsApp-ready adapters exist; production channel hardening remains gated. |
| 7. Understanding Pipeline | Partially implemented | extraction domain/services, AI worker tests, sanitizer and prompt injection tests | Mock/advisory behavior exists; real OCR/LLM production pipeline is not verified. |
| 8. Validation & Risk Engine | Implemented with limits | validation services, `ValidationRunServiceStage5Test`, product matching hardening | Backend validates customer/product/UOM/inventory/price/discount/margin/substitution and approvals; current slice hardened normalized SKU lookup. |
| 9. Quote/Order Workspace | Partially implemented | workspace services, draft quote/order services, quote review tests, frontend quote/review routes | Internal draft and review flows exist; final order/ERP authority remains gated. |
| 10. Bot Runtime Lite | Partially implemented | bot domain/services/controllers/tests, Telegram webhook tests, no-op outbound transport | Controlled inbound/handoff path exists; real outbound Telegram and autonomous actions remain blocked. |
| 11. Commerce Intelligence | Partially implemented | analytics and reconciliation services/controllers/tests, dashboard analytics routes | Read models/metrics exist; production BI completeness is not claimed. |
| 12. Integration Control Layer | Partially implemented | ChangeRequest, demo ERP adapter, connector safety/idempotency tests, integration UI | Demo/local control path exists; production ERP/1C writes remain blocked. |

## 6. Implemented capabilities

The following capabilities are present in code and have backend test coverage:

- Tenant-scoped entities and tenant isolation boundary tests.
- Audit event persistence and audit service tests.
- Product/customer/import mirror foundation, including seed/demo fixture support.
- Deterministic product code normalization: uppercase, trim, collapse whitespace, remove dash, underscore, space, and slash for code matching.
- Product matching through active tenant-scoped `normalized_sku`, aliases, and OEM references.
- Deterministic validation runs with validation issues, approval requirements, product/UOM/inventory/price/discount/margin/substitution checks.
- Operator review, correction, approval, draft preview, and internal draft quote/order preparation services.
- Quote lifecycle/review/handoff services with backend tests.
- Controlled bot runtime and Telegram-style inbound webhook handling.
- Read-only commerce analytics and reconciliation foundations.
- Demo/local integration control with ChangeRequest, demo ERP adapter, idempotency, retry/cancel metadata, and connector safety tests.
- Frontend routes/components for dashboard, intake, validations, validation review, quote review, quotes, orders, bot, analytics, reconciliation, integrations, products, inventory, and audit surfaces.

## 7. Docs-only or unverified capabilities

The following must not be treated as production-complete without a new implementation or verification slice:

- Production authentication, RBAC/ABAC, SSO/OIDC, and real tenant administration.
- Production OCR/LLM provider integration and paid AI extraction pipeline.
- Real Telegram outbound sends or autonomous customer messaging.
- Production WhatsApp, Viber, WeChat, or Meta messenger operation.
- Production ERP/1C/accounting/warehouse writes.
- Unrestricted connector execution or connector secret custody.
- Final source-of-truth inventory mutation, reservation, accounting, invoicing, tax, or payment logic.
- Full no-code bot builder or autonomous negotiation.
- Complete production observability, disaster recovery drills, WORM audit storage, rate limiting, malware scanning, and credential rotation.

## 8. Stale/conflicting documentation

The following documents conflict or need an explicit successor note:

- `docs/product/current-stage.md` says Stage 9 is the active baseline and Stage 10+ surfaces should be treated as experimental until reconciliation.
- `docs/ROADMAP.md` says the current phase is Stage 10 security, reliability, and investor demo hardening.
- `README.md` says the current backend milestone is Stage 11E.
- `ORDERPILOT_CORE_V1_AI_DEV.md` describes later completed phases up through Stage 9/10 style work and should be treated as a roadmap/instruction source, not direct proof that every stage is production-complete.
- `PROJECT_STATUS_CHECKPOINT.txt` says Stage 13 was restored and stabilized.
- `docs/product/stage-12-universal-channel-integration-foundation.md`, `docs/product/stage-13-connector-security-provider-onboarding.md`, and `docs/stages/STAGE_12*.md` describe later-stage capability slices that need code/test reconciliation before being used as the current gate.
- `docs/investor/STAGE_13*.md` and `docs/runbooks/STAGE_13*.md` describe demo/freeze/readiness flows; several are currently untracked or dirty and should not be used as the only source of truth.
- Several `LOCAL_DEMO_VERIFICATION_REPORT_STAGE_*` files are historical evidence, not the current canonical status.

No stale document was deleted in this reconciliation.

## 9. Architectural invariants that remain mandatory

- AI, bot, connector, and frontend code must not directly write trusted business data or external systems.
- Business mutations must go through backend command/application services.
- Tenant isolation is mandatory for tenant-owned reads and writes.
- Important mutations must emit audit events.
- Risky discount, margin, substitute, low-confidence extraction, high-value transaction, and external-write paths must require deterministic validation and approval where policy requires it.
- LLM output is advisory only and must be schema-validated/sanitized before any backend decision consumes it.
- External writes remain blocked unless they go through ChangeRequest, policy, validation, approval, audit, and controlled execution.
- Demo/local connector behavior must not be reinterpreted as production ERP/1C readiness.
- Customer/channel/document content is hostile input.

## 10. Current stage-gate decision

Current recommended status: PARTIAL

Reason: the backend test suite is green and code evidence shows broad Core v1 medium-layer implementation, but stage documentation is inconsistent, the worktree is broadly dirty, and several later-stage/demo artifacts are untracked or unverified as the authoritative current state.

What must not be done next:

- Do not start new bot, analytics, integration, AI, or product-expansion work.
- Do not turn demo ERP/connector behavior into production external writes.
- Do not treat dirty Stage 13/13E investor docs as canonical without a freeze/signoff pass.
- Do not reset or clean the dirty worktree without explicit user direction.

What should be done next:

- Run a dedicated stage/status freeze slice that updates exactly one canonical status source and marks older stage docs as historical or superseded.
- Preserve the normalized SKU validation fix and backend green baseline.
- Then choose the next executable product slice only from the reconciled stage gate.

## 11. Next safe executable slice

Recommended next slice: canonical stage-source freeze.

Scope:

- Update one canonical status document to point to this reconciliation.
- Add short superseded/status notes to the most misleading status docs only, starting with `README.md`, `docs/ROADMAP.md`, and `docs/product/current-stage.md`.
- Do not implement new business behavior.
- Rerun backend tests and the least disruptive frontend/AI worker checks if the slice touches related docs or demo claims.

Acceptance criteria:

- There is exactly one current stage gate reference.
- Older stage reports remain available but are clearly historical.
- The next product implementation prompt can cite the stage gate without re-auditing the whole repo.

## 12. Commands run

```powershell
git status --short
git diff --stat
rg -n "Stage 12|Stage 13|Stage 13A|Stage 13B|Stage 13C|Stage 13D|Stage 13E|Core v1|medium-layer|validation|product matching|bot runtime|commerce intelligence|exception cockpit|integration control|investor demo|freeze" docs README.md ORDERPILOT_CORE_V1_AI_DEV.md PROJECT_STATUS_CHECKPOINT.txt
Get-Content docs\product\current-stage.md
Get-Content docs\ROADMAP.md
Get-Content README.md
Get-ChildItem docs\product,docs\stages,docs\investor,docs\runbooks,docs\architecture,docs\security -File
Get-ChildItem apps\core-api\src\main\java\com\orderpilot\domain
Get-ChildItem apps\core-api\src\main\java\com\orderpilot\api\rest
Get-ChildItem apps\core-api\src\main\resources\db\migration
Get-ChildItem apps\core-api\src\test\java\com\orderpilot -Recurse -Filter *Test.java
Get-ChildItem apps\web-dashboard\app,apps\web-dashboard\components,apps\web-dashboard\lib,apps\web-dashboard\tests -Recurse -Depth 2
mvn test
npm.cmd run lint
```

Backend Maven required approved network access after the sandboxed dependency-resolution failure. No frontend package installation, lockfile update, or broad build command was run for this documentation-only reconciliation.

## 13. Files changed by this reconciliation

- `docs/product/STAGE_STATUS_RECONCILIATION.md`
- `docs/product/current-stage.md`

## 14. Known limitations / open questions

- This reconciliation classifies implemented areas from code structure, migrations, and tests; it does not manually exercise every UI path.
- Frontend and AI worker verification are not yet rerun in this document.
- The dirty worktree contains broad existing changes. Some may be valid Stage 13/14 work, but they are not separated from the previous slice in Git.
- There are multiple stage-numbering systems in docs: early Stage 1-9, Phase 5-7, Stage 8-10, Stage 11A-13, and investor-demo freeze stages. A canonical stage taxonomy is still needed.
- The untracked `ServiceInfoController.java` appears to be a local service info endpoint, but this reconciliation did not audit or accept it as stage scope.

## 15. RFQ / Channel -> Draft Quote Review Layer Gate

Gate date: 2026-06-03

Layer status:

- Backend/API review layer: PASS.
- Read-only operator UI surfacing: PASS.
- Mutation/operator action layer: intentionally not implemented.

Decision basis:

- Valid RFQ input creates internal `DraftQuote` state only; no final quote approval, external write, ERP/1C write, or connector command is introduced.
- Invalid or risky RFQ input creates deterministic validation issues, review-required routing, and audit evidence.
- Pre-draft channel/document conversion failures create tenant-scoped `QuoteConversionAttempt` evidence before returning controlled review/rejection results.
- `QuoteConversionAttemptReviewQueryService` exposes a read-only tenant-scoped review model for conversion attempts.
- `QuoteReviewController` exposes read-only list/detail routes at `/api/v1/quote-review/conversion-attempts` and `/api/v1/quote-review/conversion-attempts/{attemptId}`.
- The dashboard exposes read-only operator routes at `/conversion-review` and `/conversion-review/[attemptId]`.
- Controller and service tests cover contract shape, tenant isolation, unsafe raw-field exclusions, pre-draft evidence, audit events, and RFQ validation/review behavior.
- Frontend tests, lint, no-incremental typecheck, and Next build cover the new read-only route surface.
- Missing tenant context remains a documented backend precondition; RFQ draft creation denies missing context instead of falling back to tenantless reads/writes.

Final acceptance matrix:

| Requirement | Status | Evidence / limitation |
| --- | --- | --- |
| Valid RFQ creates internal `DraftQuote` only | PASS | Internal draft quote state is created without final approval, external write, ERP/1C write, or connector command. |
| Invalid/risky RFQ creates validation/review evidence | PASS | Deterministic validation issues, review-required routing, and audit evidence are covered by service tests. |
| Pre-draft channel/document rejection evidence exists | PASS | `QuoteConversionAttempt` evidence is persisted for controlled pre-draft review/rejection paths. |
| Read-only tenant-scoped review model exists | PASS | `QuoteConversionAttemptReviewQueryService` uses tenant context and tenant-scoped repositories. |
| List/detail review API contract exists | PASS | `QuoteReviewController` exposes the frozen read-only conversion-attempt list/detail routes. |
| Unsafe raw payload/text/secret fields are excluded | PASS | DTO/controller tests assert safe response fields and reject raw payload/text/document and secret strings. |
| Read-only operator UI surfacing exists | PASS | `/conversion-review` lists conversion attempts and `/conversion-review/[attemptId]` renders attempt details, validation issues, safe metadata, pre-draft state, and draft-linked state. |
| UI loading, empty, and error states exist | PASS | The conversion review cockpit renders loading, no-match empty, and sanitized error states. |
| Mutation/operator actions remain absent | PASS | The conversion review UI has no approve/reject/correct/retry/create buttons and does not import mutation API functions. |
| No public RFQ API, AI worker, ERP/1C write, or connector command added | PASS | This gate adds only read-only dashboard surfacing and reviewed backend behavior remains internal/read-only. |
| Dependency, lockfile, staging, and commit changes avoided | PASS | No dependency install, lockfile update, staging, or commit was performed for this gate. |

Changed files for this gate:

- `apps/web-dashboard/lib/quote-review-api.ts`
- `apps/web-dashboard/components/conversion-review-cockpit.tsx`
- `apps/web-dashboard/components/navigation.ts`
- `apps/web-dashboard/app/(dashboard)/conversion-review/page.tsx`
- `apps/web-dashboard/app/(dashboard)/conversion-review/[attemptId]/page.tsx`
- `apps/web-dashboard/tests/conversion-review.test.mjs`
- `docs/product/RFQ_TO_DRAFT_QUOTE_CAPABILITY_SLICE.md`
- `docs/product/STAGE_STATUS_RECONCILIATION.md`
- `docs/product/current-stage.md`

Verification status for this gate: scoped backend rerun passed on 2026-06-03.

Scoped command:

- `mvn test '-Dtest=QuoteReviewControllerTest,QuoteConversionAttemptReviewQueryServiceTest,ChannelToQuoteWiringServiceTest,RfqToDraftQuoteServiceTest,QuoteDraftServiceStage12ATest'`: passed, 56 tests, 0 failures, 0 errors, 0 skipped.

Per-class test results:

- `QuoteReviewControllerTest`: passed, 3 tests, 0 failures, 0 errors, 0 skipped.
- `QuoteConversionAttemptReviewQueryServiceTest`: passed, 5 tests, 0 failures, 0 errors, 0 skipped.
- `ChannelToQuoteWiringServiceTest`: passed, 14 tests, 0 failures, 0 errors, 0 skipped.
- `RfqToDraftQuoteServiceTest`: passed, 13 tests, 0 failures, 0 errors, 0 skipped.
- `QuoteDraftServiceStage12ATest`: passed, 21 tests, 0 failures, 0 errors, 0 skipped.

Verification note: the first sandboxed Maven run was blocked while resolving Maven Central artifacts (`Permission denied: getsockopt`). The same scoped Maven commands passed after approved dependency access. No dependency install or lockfile update was run.

Frontend verification for UI surfacing:

- `node --test tests/conversion-review.test.mjs`: passed, 3 tests, 0 failures.
- `npm.cmd run lint`: passed.
- `npx.cmd tsc --noEmit --incremental false`: passed. The package `npm.cmd run typecheck` script attempted to rewrite the existing `tsconfig.tsbuildinfo` file and failed with `EPERM`, so the final typecheck used the no-incremental TypeScript command.
- `npm.cmd run build`: passed and emitted `/conversion-review` and `/conversion-review/[attemptId]`.

No AI-worker, public RFQ API, connector command, service-info endpoint, ERP/1C write, dependency, lockfile, staging, or commit work is part of this gate.

Remaining limitation:

- Mutation/operator actions remain intentionally not implemented. Any approve/reject/correct/retry/create behavior, connector command, ERP/1C write, public RFQ API, or AI-worker behavior remains a separate gated slice.
