# Dirty Worktree Attribution & Staging Plan

## 1. Status

- Canonical Stage-Source Freeze: PASS
- Product stage status: PARTIAL
- Product capability freeze: ACTIVE
- This document is an attribution/staging plan only, not a capability implementation.

## 2. Source of truth

- `docs/product/current-stage.md` is the canonical current-stage pointer.
- `docs/product/STAGE_STATUS_RECONCILIATION.md` is the detailed evidence source.
- All other stage/status docs are non-authoritative unless explicitly pointed to by the canonical pointer.

## 3. Dirty worktree snapshot

Snapshot commands were run before this document was created. The worktree was already broadly dirty before this slice. This slice did not create the broad dirty state; it only adds this attribution document.

Tracked changed files in the pre-slice snapshot: 45.

Untracked files in the pre-slice snapshot: 13.

Current untracked files after this slice: 14, including `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md`.

### `git status --short`

```text
 M ORDERPILOT_CORE_V1_AI_DEV.md
 M PROJECT_STATUS_CHECKPOINT.txt
 M README.md
 M apps/core-api/src/main/java/com/orderpilot/application/services/bot/BotRuntimeService.java
 M apps/core-api/src/main/java/com/orderpilot/application/services/validation/ProductMatchingService.java
 M apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java
 M apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java
 M apps/core-api/src/test/java/com/orderpilot/api/rest/BotTelegramWebhookControllerTest.java
 M apps/core-api/src/test/java/com/orderpilot/application/services/bot/BotRuntimeServiceTest.java
 M apps/core-api/src/test/java/com/orderpilot/application/services/validation/ValidationRunServiceStage5Test.java
 M apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java
 M apps/core-api/src/test/java/com/orderpilot/demo/DemoDataService.java
 M apps/core-api/src/test/java/com/orderpilot/demo/DemoFixturesTest.java
 M apps/core-api/src/test/resources/demo/core-v1-demo/customers-demo.json
 M apps/core-api/src/test/resources/demo/core-v1-demo/import-validation-demo.json
 M apps/core-api/src/test/resources/demo/core-v1-demo/inventory-movements-demo.json
 M apps/core-api/src/test/resources/demo/core-v1-demo/products-demo.json
 M apps/core-api/src/test/resources/demo/core-v1-demo/reconciliation-demo.json
 M apps/core-api/src/test/resources/demo/core-v1-demo/telegram-rfq-demo.json
 M apps/web-dashboard/components/bot-conversations-workspace.tsx
 M apps/web-dashboard/components/demo-dashboard.tsx
 M apps/web-dashboard/lib/demo-api.ts
 M apps/web-dashboard/next-env.d.ts
 M apps/web-dashboard/tests/demo-dashboard.test.mjs
 M docs/ROADMAP.md
 M docs/investor/DEMO_DATASET_CORE_V1.md
 M docs/investor/DEMO_SCREENSHOT_CHECKLIST.md
 M docs/investor/DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md
 M docs/investor/INVESTOR_DEMO_HANDOFF.md
 M docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md
 M docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md
 M docs/investor/demo-api-walkthrough.http
 M docs/investor/demo-evidence/README_STAGE_9J.md
 M docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md
 M docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md
 M docs/product/current-stage.md
 M docs/runbooks/LOCAL_DEMO_RUNBOOK.md
 M docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md
 M docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md
 M packages/test-fixtures/stage3-intake/api-upload.sample.json
 M packages/test-fixtures/stage3-intake/email-webhook.sample.json
 M packages/test-fixtures/stage3-intake/telegram-update.sample.json
 M packages/test-fixtures/stage3-intake/tiny-upload.sample.txt
 M scripts/check-local-demo.ps1
 M scripts/seed-local-demo.ps1
?? apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java
?? docs/investor/DATA_ROOM_CHECKLIST.md
?? docs/investor/INVESTOR_NARRATIVE_V1.md
?? docs/investor/STAGE_13C_DEMO_REHEARSAL_REPORT.md
?? docs/investor/STAGE_13D_DEMO_LIMITATIONS_AND_RISK_NOTES.md
?? docs/investor/STAGE_13D_INVESTOR_DEMO_FREEZE.md
?? docs/investor/STAGE_13E_DEMO_PREFLIGHT_EVIDENCE.md
?? docs/investor/STAGE_13E_FINAL_DEMO_SIGNOFF.md
?? docs/investor/investor-demo-script.md
?? docs/product/STAGE_STATUS_RECONCILIATION.md
?? docs/runbooks/STAGE_13D_DEMO_PREFLIGHT_CHECKLIST.md
?? docs/runbooks/STAGE_13D_INVESTOR_WALKTHROUGH_CHECKLIST.md
?? docs/runbooks/STAGE_13E_BROWSER_RESET_AND_STARTUP.md
```

### `git diff --stat`

```text
 ORDERPILOT_CORE_V1_AI_DEV.md                       |  2 +
 PROJECT_STATUS_CHECKPOINT.txt                      |  1 +
 README.md                                          |  2 +
 .../services/bot/BotRuntimeService.java            | 12 +++
 .../validation/ProductMatchingService.java         | 10 +--
 .../security/ApiPermissionInterceptor.java         |  3 +
 .../orderpilot/security/ApiSecurityWebConfig.java  | 10 +++
 .../api/rest/BotTelegramWebhookControllerTest.java | 31 ++++++-
 .../services/bot/BotRuntimeServiceTest.java        | 69 +++++++++-------
 .../validation/ValidationRunServiceStage5Test.java | 19 ++++-
 .../demo/CoreV1InvestorDemoSmokeTest.java          | 22 +++++
 .../java/com/orderpilot/demo/DemoDataService.java  |  6 +-
 .../java/com/orderpilot/demo/DemoFixturesTest.java | 38 ++++++++-
 .../demo/core-v1-demo/customers-demo.json          | 10 +--
 .../demo/core-v1-demo/import-validation-demo.json  |  6 +-
 .../core-v1-demo/inventory-movements-demo.json     | 12 +--
 .../resources/demo/core-v1-demo/products-demo.json | 44 +++++-----
 .../demo/core-v1-demo/reconciliation-demo.json     |  4 +-
 .../demo/core-v1-demo/telegram-rfq-demo.json       |  2 +-
 .../components/bot-conversations-workspace.tsx     |  2 +-
 apps/web-dashboard/components/demo-dashboard.tsx   |  2 +-
 apps/web-dashboard/lib/demo-api.ts                 |  7 +-
 apps/web-dashboard/next-env.d.ts                   |  2 +-
 apps/web-dashboard/tests/demo-dashboard.test.mjs   | 42 +++++++++-
 docs/ROADMAP.md                                    |  2 +
 docs/investor/DEMO_DATASET_CORE_V1.md              |  2 +-
 docs/investor/DEMO_SCREENSHOT_CHECKLIST.md         |  2 +-
 ...DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md |  2 +-
 docs/investor/INVESTOR_DEMO_HANDOFF.md             |  2 +-
 docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md      |  2 +-
 docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md    |  4 +-
 docs/investor/demo-api-walkthrough.http            | 16 ++--
 docs/investor/demo-evidence/README_STAGE_9J.md     |  2 +-
 docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md |  2 +
 .../RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md       |  2 +-
 docs/product/current-stage.md                      | 11 ++-
 docs/runbooks/LOCAL_DEMO_RUNBOOK.md                |  2 +-
 .../LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md     |  2 +-
 docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md            | 17 ++--
 .../stage3-intake/api-upload.sample.json           |  2 +-
 .../stage3-intake/email-webhook.sample.json        |  2 +-
 .../stage3-intake/telegram-update.sample.json      |  2 +-
 .../stage3-intake/tiny-upload.sample.txt           |  2 +-
 scripts/check-local-demo.ps1                       |  2 +-
 scripts/seed-local-demo.ps1                        | 94 ++++++++++++++++++----
 45 files changed, 398 insertions(+), 134 deletions(-)
```

`git diff --stat` and `git diff --name-status` also emitted repeated line-ending warnings that LF will be replaced by CRLF the next time Git touches many modified files. Those warnings are not classified as content changes.

### `git diff --name-status`

```text
M	ORDERPILOT_CORE_V1_AI_DEV.md
M	PROJECT_STATUS_CHECKPOINT.txt
M	README.md
M	apps/core-api/src/main/java/com/orderpilot/application/services/bot/BotRuntimeService.java
M	apps/core-api/src/main/java/com/orderpilot/application/services/validation/ProductMatchingService.java
M	apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java
M	apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java
M	apps/core-api/src/test/java/com/orderpilot/api/rest/BotTelegramWebhookControllerTest.java
M	apps/core-api/src/test/java/com/orderpilot/application/services/bot/BotRuntimeServiceTest.java
M	apps/core-api/src/test/java/com/orderpilot/application/services/validation/ValidationRunServiceStage5Test.java
M	apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java
M	apps/core-api/src/test/java/com/orderpilot/demo/DemoDataService.java
M	apps/core-api/src/test/java/com/orderpilot/demo/DemoFixturesTest.java
M	apps/core-api/src/test/resources/demo/core-v1-demo/customers-demo.json
M	apps/core-api/src/test/resources/demo/core-v1-demo/import-validation-demo.json
M	apps/core-api/src/test/resources/demo/core-v1-demo/inventory-movements-demo.json
M	apps/core-api/src/test/resources/demo/core-v1-demo/products-demo.json
M	apps/core-api/src/test/resources/demo/core-v1-demo/reconciliation-demo.json
M	apps/core-api/src/test/resources/demo/core-v1-demo/telegram-rfq-demo.json
M	apps/web-dashboard/components/bot-conversations-workspace.tsx
M	apps/web-dashboard/components/demo-dashboard.tsx
M	apps/web-dashboard/lib/demo-api.ts
M	apps/web-dashboard/next-env.d.ts
M	apps/web-dashboard/tests/demo-dashboard.test.mjs
M	docs/ROADMAP.md
M	docs/investor/DEMO_DATASET_CORE_V1.md
M	docs/investor/DEMO_SCREENSHOT_CHECKLIST.md
M	docs/investor/DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md
M	docs/investor/INVESTOR_DEMO_HANDOFF.md
M	docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md
M	docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md
M	docs/investor/demo-api-walkthrough.http
M	docs/investor/demo-evidence/README_STAGE_9J.md
M	docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md
M	docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md
M	docs/product/current-stage.md
M	docs/runbooks/LOCAL_DEMO_RUNBOOK.md
M	docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md
M	docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md
M	packages/test-fixtures/stage3-intake/api-upload.sample.json
M	packages/test-fixtures/stage3-intake/email-webhook.sample.json
M	packages/test-fixtures/stage3-intake/telegram-update.sample.json
M	packages/test-fixtures/stage3-intake/tiny-upload.sample.txt
M	scripts/check-local-demo.ps1
M	scripts/seed-local-demo.ps1
```

## 4. Attribution categories

Category key:

- A. Canonical stage/control docs
- B. Historical investor/demo docs
- C. Backend runtime/capability code
- D. Backend tests/fixtures
- E. Frontend UI/capability code
- F. Scripts/local demo tooling
- G. Reconciliation/freeze docs
- H. Untracked new artifacts
- I. Unknown / needs owner decision

| Path | State | Category | Likely purpose | Related to canonical freeze/reconciliation? | Safe next action |
| --- | --- | --- | --- | --- | --- |
| `ORDERPILOT_CORE_V1_AI_DEV.md` | Tracked modified | A | Adds superseded-status note pointing readers to canonical stage docs. | Yes, as pointer hygiene. | Review with Group 1; stage later only with canonical-doc cleanup. |
| `PROJECT_STATUS_CHECKPOINT.txt` | Tracked modified | A | Adds superseded-status note pointing readers away from historical checkpoint as current-stage source. | Yes, as pointer hygiene. | Review with Group 1; stage later only with canonical-doc cleanup. |
| `README.md` | Tracked modified | A | Adds superseded-status note for current-stage routing. | Yes, as pointer hygiene. | Review with Group 1; stage later only with canonical-doc cleanup. |
| `docs/ROADMAP.md` | Tracked modified | A | Adds superseded-status note for current-stage routing. | Yes, as pointer hygiene. | Review with Group 1; stage later only with canonical-doc cleanup. |
| `docs/product/current-stage.md` | Tracked modified | A | Sets canonical pointer, PARTIAL status, active gate, and capability freeze language. | Yes, canonical pointer. | Keep; review first; stage later only after owner accepts canonical wording. |
| `docs/product/STAGE_STATUS_RECONCILIATION.md` | Untracked | H | Detailed reconciliation source named by the canonical pointer; documents PARTIAL state, dirty baseline, and verification. | Yes, detailed evidence source. | Keep; review as Group 2 before staging. |
| `docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md` | Tracked modified | G | Documents normalized SKU matching behavior for deterministic validation. | Indirect; cited as prior validation slice evidence. | Review with reconciliation evidence; stage later separately from UI/demo work. |
| `docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md` | Tracked modified | B | Aligns historical workflow example to frozen RFQ text. | No; historical/demo consistency only. | Review with historical investor/demo docs; do not make canonical. |
| `docs/investor/DEMO_DATASET_CORE_V1.md` | Tracked modified | B | Updates recommended RFQ message to frozen Steppe Logistics payload. | No; demo consistency only. | Review with Group 3; stage later if demo docs are accepted. |
| `docs/investor/DEMO_SCREENSHOT_CHECKLIST.md` | Tracked modified | B | Updates screenshot checklist RFQ text. | No. | Review with Group 3; stage later if demo docs are accepted. |
| `docs/investor/DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md` | Tracked modified | B | Updates old Stage 9J evidence text to frozen RFQ wording. | No; historical evidence may need caution. | Investigate before staging because editing completed historical evidence can confuse provenance. |
| `docs/investor/INVESTOR_DEMO_HANDOFF.md` | Tracked modified | B | Updates expected RFQ text in investor handoff. | No. | Review with Group 3; stage later if demo docs are accepted. |
| `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md` | Tracked modified | B | Updates demo script RFQ text. | No. | Review with Group 3; stage later if demo docs are accepted. |
| `docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md` | Tracked modified | B | Aligns Stage 13B script with `2 EA`, SKU, and frozen RFQ text. | No; Stage 13B remains non-authoritative. | Review with Group 3; do not canonize Stage 13/13E docs. |
| `docs/investor/demo-api-walkthrough.http` | Tracked modified | B | Makes demo HTTP walkthrough runnable with seeded IDs, frozen RFQ text, and analytics permission header. | No; local demo runnable artifact. | Review with Group 3 or Group 6 depending on owner preference. |
| `docs/investor/demo-evidence/README_STAGE_9J.md` | Tracked modified | B | Updates old evidence README RFQ text. | No; historical evidence may need caution. | Investigate before staging because editing historical evidence can confuse provenance. |
| `docs/runbooks/LOCAL_DEMO_RUNBOOK.md` | Tracked modified | B | Updates local demo runbook RFQ text. | No. | Review with Group 3 or Group 6; stage later if accepted. |
| `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md` | Tracked modified | B | Updates old verification report RFQ text. | No; historical report may need caution. | Investigate before staging because completed reports should not silently change. |
| `docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md` | Tracked modified | B | Aligns Stage 13B route steps with frozen RFQ and defaults. | No; Stage 13B remains non-authoritative. | Review with Group 3; do not canonize Stage 13/13E docs. |
| `docs/investor/DATA_ROOM_CHECKLIST.md` | Untracked | H | New investor data-room checklist outline. | No. | Keep; owner decision before staging. |
| `docs/investor/INVESTOR_NARRATIVE_V1.md` | Untracked | H | Empty or placeholder investor narrative file. | No. | Investigate; do not stage until content/owner intent is clear. |
| `docs/investor/STAGE_13C_DEMO_REHEARSAL_REPORT.md` | Untracked | H | Stage 13C historical rehearsal report with `INVESTOR_WALKTHROUGH_READY` framing and verification summary. | No; historical, not canonical. | Keep; review with Group 3; do not mark canonical. |
| `docs/investor/STAGE_13D_DEMO_LIMITATIONS_AND_RISK_NOTES.md` | Untracked | H | Stage 13D limitations, risk notes, and investor communication guardrails. | No; freeze support doc only. | Keep; review with Group 3 or Group 7; do not mark canonical. |
| `docs/investor/STAGE_13D_INVESTOR_DEMO_FREEZE.md` | Untracked | H | Stage 13D investor-demo freeze package and frozen payload/defaults/safety posture. | No; demo freeze doc, not current-stage authority. | Keep; owner decision before staging; do not mark canonical. |
| `docs/investor/STAGE_13E_DEMO_PREFLIGHT_EVIDENCE.md` | Untracked | H | Stage 13E preflight evidence template and verification checklist. | No; evidence template only. | Keep; owner decision before staging; do not mark canonical. |
| `docs/investor/STAGE_13E_FINAL_DEMO_SIGNOFF.md` | Untracked | H | Stage 13E signoff guidance with pending go/no-go fields. | No; signoff template only. | Keep; owner decision before staging; do not mark canonical. |
| `docs/investor/investor-demo-script.md` | Untracked | H | Older investor demo script dated 2026-05-23 with broad Stage 9 limitations. | No; historical. | Investigate; likely index as historical rather than stage. |
| `docs/runbooks/STAGE_13D_DEMO_PREFLIGHT_CHECKLIST.md` | Untracked | H | Stage 13D preflight checklist for environment, demo data, safety gates, and verification. | No; runbook support only. | Keep; owner decision before staging. |
| `docs/runbooks/STAGE_13D_INVESTOR_WALKTHROUGH_CHECKLIST.md` | Untracked | H | Stage 13D route-by-route investor walkthrough checklist and do-not-claim guidance. | No; runbook support only. | Keep; owner decision before staging. |
| `docs/runbooks/STAGE_13E_BROWSER_RESET_AND_STARTUP.md` | Untracked | H | Browser/session reset and startup runbook for investor walkthrough. | No; runbook support only. | Keep; owner decision before staging. |
| `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` | Untracked | H | Adds service root and favicon responses for local/demo friendliness. | No; backend runtime artifact. | Split into backend PR only if owner chooses capability/runtime cleanup; do not touch during freeze. |
| `apps/core-api/src/main/java/com/orderpilot/application/services/bot/BotRuntimeService.java` | Tracked modified | C | Requires tenant existence before processing Telegram webhook and emits seeded-demo guidance on missing tenant. | No; backend runtime/capability behavior. | Do not touch; split into backend/runtime PR if accepted. |
| `apps/core-api/src/main/java/com/orderpilot/application/services/validation/ProductMatchingService.java` | Tracked modified | C | Changes product lookup to canonical normalized SKU and active tenant-scoped product status. | Indirect; mentioned by reconciliation as prior normalized SKU slice. | Review with backend validation PR; do not mix with docs. |
| `apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java` | Tracked modified | C | Lets CORS preflight OPTIONS bypass permission resolution. | No; backend/security behavior. | Split into backend/security PR; do not touch during freeze. |
| `apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java` | Tracked modified | C | Adds CORS mappings for local dashboard origins and demo headers. | No; backend/security behavior. | Split into backend/security PR; do not touch during freeze. |
| `apps/core-api/src/test/java/com/orderpilot/api/rest/BotTelegramWebhookControllerTest.java` | Tracked modified | D | Adds CORS preflight tests for Telegram webhook. | No; tests for backend/security behavior. | Stage only with matching backend/security changes. |
| `apps/core-api/src/test/java/com/orderpilot/application/services/bot/BotRuntimeServiceTest.java` | Tracked modified | D | Updates bot tests to create persisted tenants instead of random tenant IDs. | No; tests for backend runtime change. | Stage only with bot runtime changes. |
| `apps/core-api/src/test/java/com/orderpilot/application/services/validation/ValidationRunServiceStage5Test.java` | Tracked modified | D | Adds normalized SKU matching test and normalizer usage for aliases/OEM references. | Indirect; tests prior validation slice. | Stage with backend validation PR only. |
| `apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java` | Tracked modified | D | Adds service root/favicon test and missing seed controlled error test. | No; tests backend demo/runtime behavior. | Stage only with matching backend runtime changes. |
| `apps/core-api/src/test/java/com/orderpilot/demo/DemoDataService.java` | Tracked modified | D | Switches demo fixture SKU keys to frozen `PAD-*` values. | No; fixture/test support. | Review with fixtures/demo PR; do not mix with freeze docs. |
| `apps/core-api/src/test/java/com/orderpilot/demo/DemoFixturesTest.java` | Tracked modified | D | Adds frozen story assertions and seed-script assertions. | No; tests for fixtures/scripts. | Stage only with matching fixtures/scripts. |
| `apps/core-api/src/test/resources/demo/core-v1-demo/customers-demo.json` | Tracked modified | D | Changes demo customer to Steppe Logistics, `CUST-001`, and USD. | No; demo fixture. | Review with fixture PR; preserve if accepted. |
| `apps/core-api/src/test/resources/demo/core-v1-demo/import-validation-demo.json` | Tracked modified | D | Updates demo import validation payload to frozen SKU/quantity/customer. | No; demo fixture. | Review with fixture PR. |
| `apps/core-api/src/test/resources/demo/core-v1-demo/inventory-movements-demo.json` | Tracked modified | D | Updates inventory fixture to `PAD-OE-04465` and `WH-ALM`. | No; demo fixture. | Review with fixture PR. |
| `apps/core-api/src/test/resources/demo/core-v1-demo/products-demo.json` | Tracked modified | D | Updates demo products, warehouse, UOM, costs, and currency to frozen investor story. | No; demo fixture. | Review with fixture PR. |
| `apps/core-api/src/test/resources/demo/core-v1-demo/reconciliation-demo.json` | Tracked modified | D | Updates reconciliation fixture to frozen SKU/location. | No; demo fixture. | Review with fixture PR. |
| `apps/core-api/src/test/resources/demo/core-v1-demo/telegram-rfq-demo.json` | Tracked modified | D | Updates Telegram RFQ fixture to frozen payload. | No; demo fixture. | Review with fixture PR. |
| `packages/test-fixtures/stage3-intake/api-upload.sample.json` | Tracked modified | D | Updates sample intake message to frozen SKU/quantity text. | No; sample fixture. | Review with fixture PR. |
| `packages/test-fixtures/stage3-intake/email-webhook.sample.json` | Tracked modified | D | Updates email sample to frozen SKU/quantity text. | No; sample fixture. | Review with fixture PR. |
| `packages/test-fixtures/stage3-intake/telegram-update.sample.json` | Tracked modified | D | Updates Telegram sample to frozen SKU/quantity text. | No; sample fixture. | Review with fixture PR. |
| `packages/test-fixtures/stage3-intake/tiny-upload.sample.txt` | Tracked modified | D | Updates tiny upload sample to frozen SKU/quantity text. | No; sample fixture. | Review with fixture PR. |
| `apps/web-dashboard/components/bot-conversations-workspace.tsx` | Tracked modified | E | Changes bot conversation default text to `2 EA PAD-OE-04465`. | No; frontend demo UI. | Split into frontend demo UI PR; do not touch during freeze. |
| `apps/web-dashboard/components/demo-dashboard.tsx` | Tracked modified | E | Changes missing tenant fallback text from placeholder to `not configured`. | No; frontend demo UI clarity. | Split into frontend demo UI PR. |
| `apps/web-dashboard/lib/demo-api.ts` | Tracked modified | E | Updates demo RFQ text and adds analytics permission header. | No; frontend API/demo behavior. | Split into frontend demo UI/API PR. |
| `apps/web-dashboard/next-env.d.ts` | Tracked modified | E | Changes Next type import path to `.next/dev/types/routes.d.ts`. | No; likely local generated/tooling drift. | Investigate; do not stage without frontend owner confirmation. |
| `apps/web-dashboard/tests/demo-dashboard.test.mjs` | Tracked modified | E | Adds static assertions for frozen Stage 13D/13E demo story and safety language. | No; frontend tests. | Stage only with matching frontend demo UI changes. |
| `scripts/check-local-demo.ps1` | Tracked modified | F | Updates demo check script RFQ payload. | No; local demo tooling. | Review with Group 6; do not run/stage automatically. |
| `scripts/seed-local-demo.ps1` | Tracked modified | F | Updates deterministic seed data, normalized SKU values, prices/currency, output, and verification SQL. | No; local demo tooling and fixtures. | Split into scripts/fixtures PR; do not touch during freeze. |
| `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md` | Untracked | H | New non-invasive dirty-worktree attribution and future staging plan created by this slice. | Yes; project-control documentation. | Keep; review as this slice output; do not stage unless explicitly requested later. |

No file is left unclassified. Files with uncertain owner intent are classified by path/state, but their safe action is `investigate` or `owner decision`.

## 5. Do-not-touch list

Do not modify these areas during the active capability freeze unless explicitly selected as the next executable slice:

- Bot runtime: `apps/core-api/src/main/java/com/orderpilot/application/services/bot/`
- Validation/matching services: `apps/core-api/src/main/java/com/orderpilot/application/services/validation/`
- Frontend demo UI: `apps/web-dashboard/components/`, `apps/web-dashboard/lib/demo-api.ts`
- Investor demo scenarios: `docs/investor/`, `docs/runbooks/`
- Local seed scripts: `scripts/seed-local-demo.ps1`
- Fixtures: `apps/core-api/src/test/resources/demo/`, `packages/test-fixtures/`
- Untracked investor/runbook artifacts.
- Any file with uncertain attribution, including empty placeholders and generated-looking frontend type files.

## 6. Recommended staging strategy

Do not stage anything during this slice. Future staging should be owner-approved and split by intent.

Group 1 - Canonical stage-source freeze docs:

- `docs/product/current-stage.md`
- `README.md`
- `ORDERPILOT_CORE_V1_AI_DEV.md`
- `PROJECT_STATUS_CHECKPOINT.txt`
- `docs/ROADMAP.md`
- `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md`

Group 2 - Reconciliation evidence docs:

- `docs/product/STAGE_STATUS_RECONCILIATION.md`
- `docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md`

Group 3 - Investor/demo historical docs:

- `docs/investor/DEMO_DATASET_CORE_V1.md`
- `docs/investor/DEMO_SCREENSHOT_CHECKLIST.md`
- `docs/investor/DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md`
- `docs/investor/INVESTOR_DEMO_HANDOFF.md`
- `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md`
- `docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md`
- `docs/investor/STAGE_13C_DEMO_REHEARSAL_REPORT.md`
- `docs/investor/STAGE_13D_DEMO_LIMITATIONS_AND_RISK_NOTES.md`
- `docs/investor/STAGE_13D_INVESTOR_DEMO_FREEZE.md`
- `docs/investor/STAGE_13E_DEMO_PREFLIGHT_EVIDENCE.md`
- `docs/investor/STAGE_13E_FINAL_DEMO_SIGNOFF.md`
- `docs/investor/investor-demo-script.md`
- `docs/investor/demo-api-walkthrough.http`
- `docs/investor/demo-evidence/README_STAGE_9J.md`
- `docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md`
- `docs/runbooks/LOCAL_DEMO_RUNBOOK.md`
- `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md`
- `docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md`

Group 4 - Backend validation/bot/test changes:

- `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/bot/BotRuntimeService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/validation/ProductMatchingService.java`
- `apps/core-api/src/main/java/com/orderpilot/security/ApiPermissionInterceptor.java`
- `apps/core-api/src/main/java/com/orderpilot/security/ApiSecurityWebConfig.java`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/BotTelegramWebhookControllerTest.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/bot/BotRuntimeServiceTest.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/validation/ValidationRunServiceStage5Test.java`
- `apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java`
- `apps/core-api/src/test/java/com/orderpilot/demo/DemoDataService.java`
- `apps/core-api/src/test/java/com/orderpilot/demo/DemoFixturesTest.java`

Group 5 - Frontend demo UI changes:

- `apps/web-dashboard/components/bot-conversations-workspace.tsx`
- `apps/web-dashboard/components/demo-dashboard.tsx`
- `apps/web-dashboard/lib/demo-api.ts`
- `apps/web-dashboard/tests/demo-dashboard.test.mjs`
- `apps/web-dashboard/next-env.d.ts` only after owner confirms it is intentional and not generated drift.

Group 6 - Scripts/fixtures/local demo changes:

- `apps/core-api/src/test/resources/demo/core-v1-demo/customers-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/import-validation-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/inventory-movements-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/products-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/reconciliation-demo.json`
- `apps/core-api/src/test/resources/demo/core-v1-demo/telegram-rfq-demo.json`
- `packages/test-fixtures/stage3-intake/api-upload.sample.json`
- `packages/test-fixtures/stage3-intake/email-webhook.sample.json`
- `packages/test-fixtures/stage3-intake/telegram-update.sample.json`
- `packages/test-fixtures/stage3-intake/tiny-upload.sample.txt`
- `scripts/check-local-demo.ps1`
- `scripts/seed-local-demo.ps1`

Group 7 - Untracked artifacts requiring owner decision:

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

## 7. Risk assessment

- Mixing documentation freeze changes with capability changes can hide real backend/frontend drift under apparently administrative cleanup.
- Accidentally canonizing stale Stage 13/13E docs would conflict with the source-of-truth model where `docs/product/current-stage.md` points to `docs/product/STAGE_STATUS_RECONCILIATION.md`.
- Deleting or ignoring untracked investor/runbook artifacts could lose useful rehearsal context before an owner decides what belongs in history.
- Staging frontend/backend capability changes together with docs could make review harder and obscure behavior changes such as CORS, tenant validation, normalized SKU lookup, or demo API headers.
- Claiming full production readiness would contradict the current PARTIAL status and the controlled local/demo limitations captured by the reconciliation.
- Silent edits to completed historical evidence files can blur what was actually verified at the time versus what was later aligned for demo wording.

## 8. Next safe slices

1. Canonical stage taxonomy cleanup: review only pointer and superseded-status wording, then decide whether Group 1 should be staged.
2. Historical investor/demo doc indexing: classify Stage 9J, 13B, 13C, 13D, and 13E docs as historical or active support artifacts without making them canonical.
3. Untracked artifact owner-decision pass: decide keep/index/stage later/investigate for untracked investor and runbook files, including the empty `INVESTOR_NARRATIVE_V1.md`.

## 9. Verification

Commands run for this documentation-only slice:

```powershell
git status --short
git diff --stat
git diff --name-status
git diff --unified=0 -- docs/product/current-stage.md docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md docs/ROADMAP.md README.md ORDERPILOT_CORE_V1_AI_DEV.md PROJECT_STATUS_CHECKPOINT.txt
git diff --unified=0 -- docs/investor docs/runbooks
git diff --unified=0 -- apps/core-api/src/main/java apps/core-api/src/test apps/core-api/src/test/resources/demo
git diff --unified=0 -- apps/web-dashboard packages/test-fixtures scripts
Get-Content -LiteralPath <untracked-file> -TotalCount <bounded count>
```

Files changed by this slice:

- `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md`

Confirmations:

- No product capability was intentionally changed.
- No backend capability code was edited.
- No frontend capability code was edited.
- No tests, fixtures, or scripts were edited.
- No files were staged.
- No files were committed.
- No files were deleted.
- Current stage remains PARTIAL.
- Canonical Stage-Source Freeze remains PASS.
