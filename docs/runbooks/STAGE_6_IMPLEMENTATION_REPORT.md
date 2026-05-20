# Stage 6 Implementation Report

## 1. STATUS

PASS_WITH_MANUAL_STEPS

Stage 6 source, migration, services, REST APIs, dashboard workspace pages, AI worker documentation note, tests, and docs were implemented in the active repository. Full Maven, npm, Docker, and Java 21 verification could not be executed in this shell because those tools are unavailable or not configured in PATH.

## 2. Repository root used

`C:\OrderPilot\OrderPilot-Core`

## 3. Old path confirmation

The old Obsidian code path was not used for code changes. No source code was created, edited, restored, or continued in:

`C:\Users\mukha\Documents\Obsidian Vault\OrderPilot-AI-Programm\OrderPilot-Core`

All Stage 6 implementation work was performed only inside:

`C:\OrderPilot\OrderPilot-Core`

## 4. ACTIVE_REPOSITORY.md confirmation

`docs\runbooks\ACTIVE_REPOSITORY.md` still states that the active repository root is:

`C:\OrderPilot\OrderPilot-Core`

## 5. Summary of implementation

- Added Stage 6 quote/order workspace and exception cockpit data model.
- Added Flyway migration `V6__quote_order_workspace_exception_cockpit.sql`.
- Added tenant-aware workflow services for exception cases, suggested fixes, internal draft quotes, internal draft orders, approval decisions, notes, timelines, summaries, and operator actions.
- Added `/api/v1/workspace/*` REST endpoints.
- Added validation-run convenience endpoints for creating exception cases, draft quotes, and draft orders.
- Added dashboard pages for exception cockpit, exception detail, draft quote workspace/detail, draft order workspace/detail, and command-center workspace cards.
- Updated AI worker README to state that Stage 6 is core-api/operator workflow only.
- Added Stage 6 product, architecture, security, verification, and implementation docs.

## 6. File tree summary

```text
apps/core-api/
  src/main/java/com/orderpilot/domain/workspace/
  src/main/java/com/orderpilot/application/services/workspace/
  src/main/java/com/orderpilot/api/rest/WorkspaceController.java
  src/main/java/com/orderpilot/api/rest/ValidationWorkspaceActionController.java
  src/main/java/com/orderpilot/api/dto/Stage6Dtos.java
  src/main/resources/db/migration/V6__quote_order_workspace_exception_cockpit.sql
  src/test/java/com/orderpilot/application/services/workspace/
apps/web-dashboard/
  app/(dashboard)/exception-cockpit/
  app/(dashboard)/quotes/
  app/(dashboard)/orders/
  components/timeline.tsx
docs/
  product/
  architecture/
  security/
  runbooks/
```

## 7. Files created/modified

Created:

- `apps/core-api/src/main/resources/db/migration/V6__quote_order_workspace_exception_cockpit.sql`
- `apps/core-api/src/main/java/com/orderpilot/domain/workspace/*`
- `apps/core-api/src/main/java/com/orderpilot/application/services/workspace/*`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage6Dtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/WorkspaceController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/ValidationWorkspaceActionController.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/workspace/Stage6MigrationFileTest.java`
- `apps/web-dashboard/components/timeline.tsx`
- `apps/web-dashboard/app/(dashboard)/exception-cockpit/page.tsx`
- `apps/web-dashboard/app/(dashboard)/exception-cockpit/[id]/page.tsx`
- `apps/web-dashboard/app/(dashboard)/quotes/[id]/page.tsx`
- `apps/web-dashboard/app/(dashboard)/orders/[id]/page.tsx`
- `docs/product/STAGE_6_SCOPE.md`
- `docs/architecture/QUOTE_ORDER_WORKSPACE.md`
- `docs/architecture/EXCEPTION_COCKPIT.md`
- `docs/architecture/APPROVAL_WORKFLOW.md`
- `docs/architecture/WORKSPACE_TIMELINE.md`
- `docs/security/WORKSPACE_SAFETY_MODEL.md`
- `docs/runbooks/STAGE_6_VERIFICATION.md`
- `docs/runbooks/STAGE_6_IMPLEMENTATION_REPORT.md`

Modified:

- `apps/web-dashboard/components/navigation.ts`
- `apps/web-dashboard/app/(dashboard)/command-center/page.tsx`
- `apps/web-dashboard/app/(dashboard)/quotes/page.tsx`
- `apps/web-dashboard/app/(dashboard)/orders/page.tsx`
- `apps/web-dashboard/app/(dashboard)/validations/[id]/page.tsx`
- `apps/web-dashboard/app/globals.css`
- `apps/ai-worker/README.md`
- Stage 5 validation entities received additional read getters needed by Stage 6 workflow services.

## 8. Database tables added

- `exception_case`
- `exception_case_issue`
- `suggested_fix`
- `draft_quote`
- `draft_quote_line`
- `draft_order`
- `draft_order_line`
- `approval_decision`
- `operator_action`
- `workspace_note`

## 9. API endpoints added

Exception cases:

- `POST /api/v1/workspace/exception-cases/from-validation/{validationRunId}`
- `GET /api/v1/workspace/exception-cases`
- `GET /api/v1/workspace/exception-cases/{id}`
- `GET /api/v1/workspace/exception-cases/{id}/issues`
- `POST /api/v1/workspace/exception-cases/{id}/assign`
- `POST /api/v1/workspace/exception-cases/{id}/status`
- `POST /api/v1/workspace/exception-cases/{id}/resolve`
- `POST /api/v1/workspace/exception-cases/{id}/reject`
- `POST /api/v1/workspace/exception-cases/{id}/cancel`

Suggested fixes:

- `POST /api/v1/workspace/suggested-fixes/generate/{validationRunId}`
- `GET /api/v1/workspace/suggested-fixes?validationRunId=`
- `GET /api/v1/workspace/suggested-fixes/{id}`
- `POST /api/v1/workspace/suggested-fixes/{id}/accept`
- `POST /api/v1/workspace/suggested-fixes/{id}/reject`

Draft quotes:

- `POST /api/v1/workspace/draft-quotes/from-validation/{validationRunId}`
- `GET /api/v1/workspace/draft-quotes`
- `GET /api/v1/workspace/draft-quotes/{id}`
- `GET /api/v1/workspace/draft-quotes/{id}/lines`
- `POST /api/v1/workspace/draft-quotes/{id}/approve-internal`
- `POST /api/v1/workspace/draft-quotes/{id}/reject`
- `POST /api/v1/workspace/draft-quotes/{id}/cancel`

Draft orders:

- `POST /api/v1/workspace/draft-orders/from-validation/{validationRunId}`
- `GET /api/v1/workspace/draft-orders`
- `GET /api/v1/workspace/draft-orders/{id}`
- `GET /api/v1/workspace/draft-orders/{id}/lines`
- `POST /api/v1/workspace/draft-orders/{id}/approve-internal`
- `POST /api/v1/workspace/draft-orders/{id}/reject`
- `POST /api/v1/workspace/draft-orders/{id}/cancel`

Other workspace endpoints:

- `POST /api/v1/workspace/approval-decisions`
- `GET /api/v1/workspace/approval-decisions?targetType=&targetId=`
- `GET /api/v1/workspace/timeline?targetType=&targetId=`
- `POST /api/v1/workspace/notes`
- `GET /api/v1/workspace/notes?targetType=&targetId=`
- `GET /api/v1/workspace/summary`
- `POST /api/v1/validations/runs/{id}/create-exception-case`
- `POST /api/v1/validations/runs/{id}/create-draft-quote`
- `POST /api/v1/validations/runs/{id}/create-draft-order`

## 10. Tests added

- `Stage6MigrationFileTest`

Manual test expansion is recommended once Maven and Java 21 are available for full service and controller coverage.

## 11. Verification performed

Passed in this shell:

- Active repository root exists at `C:\OrderPilot\OrderPilot-Core`.
- `ACTIVE_REPOSITORY.md` points to `C:\OrderPilot\OrderPilot-Core`.
- Stage 1-5 implementation reports and key architecture/security docs were inspected.
- `pom.xml` parses as XML.
- Frontend `package.json` and `tsconfig.json` parse as JSON using bundled Node.
- Stage 6 migration contains all required workspace tables.
- Stage 6 frontend and security docs use internal-only safety language.
- Obsolete Obsidian path appears only in historical reports and active-repository warnings, not as a code output location.

Blocked in this shell:

- `docker compose -f "infra/docker/docker-compose.yml" config`: Docker is not available in PATH.
- `mvn test`: Maven is not available in PATH.
- Java 21 execution: system Java is Java 8.
- `npm run lint` and `npm run build`: npm is not available in PATH.
- `pytest`: bundled Python exists, but pytest is not installed; `py` and `python` are not in PATH.
- `git status`: git is not available in PATH.

## 12. Manual verification commands

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f "infra/docker/docker-compose.yml" config
docker compose -f "infra/docker/docker-compose.yml" up --build
```

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn test
```

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run lint
npm run build
```

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
pytest
```

## 13. Example local API requests

```powershell
$tenantId = "00000000-0000-0000-0000-000000000001"
Invoke-RestMethod "http://localhost:8080/api/v1/health"
```

```powershell
$case = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/exception-cases/from-validation/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
```

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/suggested-fixes/generate/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
```

```powershell
$quote = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/draft-quotes/from-validation/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
$order = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/draft-orders/from-validation/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
```

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/draft-quotes/$($quote.id)/approve-internal" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/workspace/timeline?targetType=DRAFT_QUOTE&targetId=$($quote.id)" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/workspace/summary" -Headers @{ "X-Tenant-Id" = $tenantId }
```

## 14. Known limitations

- Full local compile/test execution is blocked until Maven, Java 21, npm, Docker, and pytest are available.
- Frontend pages are stable workspace placeholders, not live API-bound screens yet.
- Suggested fix generation is deterministic and simple.
- Draft quote/order totals use available validation prices and do not yet support manual line edits.
- Timeline currently combines operator actions, approval decisions, and notes; generic audit event merge can be expanded later.
- Authentication/RBAC remains Stage 1 placeholder-level.

## 15. Security confirmation

- `DraftQuote` and `DraftOrder` are internal OrderPilot workflow records only.
- Internal approval does not write ERP/1C/accounting/warehouse.
- Internal order approval does not reserve or decrement inventory.
- Workspace does not mutate product/customer/inventory/pricing master data.
- AI worker has no quote/order write path.
- Operator actions are audit logged where implemented.
- Tenant-owned workspace tables include `tenant_id`.
- No external writes were implemented.
- No customer email/Telegram/WhatsApp business reply sending was implemented.

## 16. Next recommended stage

Stage 7 - Bot Runtime Lite:

- Telegram bot connection.
- Safe bot flow configuration.
- Availability check.
- Price check through narrow backend API.
- RFQ creation as internal draft/request only.
- Substitute suggestion display.
- Human handoff.
- No bot approval.
- No bot external ERP writes.
