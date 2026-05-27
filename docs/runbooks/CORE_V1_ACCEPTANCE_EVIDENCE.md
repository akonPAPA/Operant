# Core V1 Acceptance Evidence

- Timestamp: 2026-05-27T22:44:41Z
- Repository: C:\OrderPilot\OrderPilot-Core
- Branch: dev/stage-11a-demo-pilot-polish
- HEAD: 5a9b011131aa5cb2974959c5058498aae097456c
- Mode: runtime
- Production connectors remain disabled.
- Real ERP/1C writes remain disabled.
- External network calls, real secrets, raw connector secrets, inventory mutation, and bot-triggered connector commands remain disabled.

## Repo And Runtime Checks

| Check | Status | Evidence |
| --- | --- | --- |
| Current branch | PASS | dev/stage-11a-demo-pilot-polish |
| Working tree clean | PARTIAL | Working tree has local changes. |
| OrderPilot-Core HEAD | PASS | 5a9b011131aa5cb2974959c5058498aae097456c |
| Parent gitlink pointer | PASS | 160000 5a9b011131aa5cb2974959c5058498aae097456c 0	OrderPilot-Core |
| Required script: scripts/start-local-demo.ps1 | PASS | Found. |
| Required script: scripts/check-local-demo.ps1 | PASS | Found. |
| Required script: scripts/seed-local-demo.ps1 | PASS | Found. |
| Required script: scripts/run-core-v1-demo-check.ps1 | PASS | Found. |
| Required document: docs/runbooks/CORE_V1_LOCAL_DEMO_RUNBOOK.md | PASS | Found. |
| Required document: docs/runbooks/CORE_V1_DEMO_READINESS_CHECKLIST.md | PASS | Found. |
| Core v1 roadmap/scope source | PASS | Found: docs/product/core-v1-scope.md, docs/ROADMAP.md. |
| Backend health endpoint reachable | FAIL | Unable to connect to the remote server |
| Frontend demo reachable | FAIL | Unable to connect to the remote server |
| Backend health returns OK if service is running | FAIL | Unable to connect to the remote server |
| Database readiness | FAIL | Exception calling "Wait" with "1" argument(s): "One or more errors occurred." |
| Worker readiness | NOT_VERIFIED | Existing local check script has no worker readiness probe. |

## Scenario Matrix

| Check | Status | Evidence |
| --- | --- | --- |
| Telegram RFQ | PASS | Telegram webhook RFQ flow is covered by demo smoke test and fixture. Evidence: apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java. |
| PDF purchase order | PASS | PDF/file-upload intake boundary is documented or implemented. Evidence: docs/security/FILE_UPLOAD_SECURITY.md, docs/security/webhook-and-file-upload-security.md. |
| Out-of-stock substitute suggestion | PASS | Substitution/compatibility evidence and demo fixture coverage are present. Evidence: docs/product/SUBSTITUTION_COMPATIBILITY_STAGE_11C.md, apps/core-api/src/test/java/com/orderpilot/demo/DemoFixturesTest.java, apps/core-api/src/main/java/com/orderpilot/application/services/workspace/RfqToDraftQuoteService.java. |
| Discount violation approval | PASS | Discount approval/routing evidence exists. Evidence: docs/VALIDATION_ENGINE.md, docs/security/PRODUCTION_AUTH_RBAC_PROOF_PLAN_STAGE_10G.md, apps/core-api/src/main/java/com/orderpilot/application/services/validation/ValidationRunService.java, apps/core-api/src/main/java/com/orderpilot/domain/validation/DiscountCheckResult.java. |
| Inventory mismatch | PASS | Inventory mismatch is covered by demo smoke test and reconciliation fixture. Evidence: apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java, apps/core-api/src/test/resources/demo/core-v1-demo/reconciliation-demo.json, apps/core-api/src/main/java/com/orderpilot/application/services/reconciliation/InventoryReconciliationService.java. |
| Bad AI output rejection | PASS | AI output safety and sanitizer/schema evidence exists. Evidence: docs/security/AI_OUTPUT_SAFETY.md, apps/ai-worker/tests/test_stage4_extraction.py, apps/core-api/src/main/java/com/orderpilot/application/services/extraction/ExtractionSchemaValidator.java. |
| Tenant isolation | PASS | Tenant isolation tests/docs exist. Evidence: apps/core-api/src/test/java/com/orderpilot/security/TenantIsolationBoundaryTest.java, apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java, docs/security/TENANT_ISOLATION_API_BOUNDARY_STAGE_10J.md. |
| Duplicate webhook idempotency | PASS | Webhook/idempotency evidence exists. Evidence: docs/security/WEBHOOK_SECURITY.md, docs/security/THREAT_MODEL.md, apps/core-api/src/main/java/com/orderpilot/domain/intake/InboundEventLedger.java. |
| Connector failure visibility | PASS | Connector failure/policy-block visibility evidence exists. Evidence: docs/runbooks/CONNECTOR_FAILURE_RUNBOOK.md, docs/integrations/CONNECTOR_SAFETY_MODEL.md, apps/core-api/src/main/java/com/orderpilot/application/services/integration/ConnectorExecutionSafetyService.java, apps/core-api/src/main/java/com/orderpilot/application/services/integration/sandbox/ConnectorSandboxExecutionService.java. |
| Audit review | PASS | Audit service/UI/security evidence exists. Evidence: apps/core-api/src/test/java/com/orderpilot/domain/audit/AuditEventServiceTest.java, docs/security/SECURITY_BASELINE.md, apps/web-dashboard/app/(dashboard)/audit-log/page.tsx. |

## Safety Guardrail Matrix

| Check | Status | Evidence |
| --- | --- | --- |
| Production connectors disabled | PASS | Documented in docs/runbooks/CORE_V1_LOCAL_DEMO_RUNBOOK.md. |
| Demo ERP only / mock connector only | PASS | Documented in docs/runbooks/CORE_V1_LOCAL_DEMO_RUNBOOK.md, docs/security/THREAT_MODEL.md, docs/integrations/CONNECTOR_SAFETY_MODEL.md. |
| No real ERP/1C write mode | PASS | Documented in docs/runbooks/CORE_V1_LOCAL_DEMO_RUNBOOK.md. |
| No AI/bot/frontend direct DB write path documented | PASS | Documented in docs/security/THREAT_MODEL.md, docs/security/SECURITY_BASELINE.md. |
| ChangeRequest required for external writes | PASS | Documented in docs/security/DATA_AUTHORITY_MODEL.md. |
| No raw secrets in demo config | PASS | Demo config files contain placeholders/local values only. |

## Failed Checks

- Runtime checks: Backend health endpoint reachable - Unable to connect to the remote server
- Runtime checks: Frontend demo reachable - Unable to connect to the remote server
- Runtime checks: Backend health returns OK if service is running - Unable to connect to the remote server
- Runtime checks: Database readiness - Exception calling "Wait" with "1" argument(s): "One or more errors occurred."

## Not Verified Checks

- Runtime checks: Worker readiness - Existing local check script has no worker readiness probe.

## Status Semantics

- PASS: evidence or runtime check is present and meets the Stage 11C acceptance runner criteria.
- PARTIAL: meaningful evidence exists, but this runner does not prove the full end-to-end live scenario.
- FAIL: required evidence or strict runtime behavior is missing.
- NOT_VERIFIED: intentionally skipped or not covered by preflight evidence.

## Next Recommended Stage

- Run `scripts/run-core-v1-acceptance.ps1 -RequireRuntime` after local services are started and seeded, then use remaining PARTIAL/NOT_VERIFIED items as the Stage 11D/11E demo hardening queue.
- Keep production connectors disabled until a separate production connector acceptance gate is implemented and approved.
