# Local Demo Verification Report - Stage 11A

## Stage Objective

Build the RFQ to internal draft quote workflow with deterministic parsing, tenant policy enforcement, validation issues, and audit events.

## Changed Files

- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage11ADtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/DraftQuoteController.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/workspace/RfqToDraftQuoteService.java`
- `apps/core-api/src/main/java/com/orderpilot/common/errors/GlobalExceptionHandler.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/workspace/DraftQuote.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/workspace/DraftQuoteLine.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/workspace/DraftQuoteRepository.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/workspace/QuoteValidationIssue.java`
- `apps/core-api/src/main/java/com/orderpilot/domain/workspace/QuoteValidationIssueRepository.java`
- `apps/core-api/src/main/resources/db/migration/V15__draft_quote_workflow.sql`
- `apps/core-api/src/test/java/com/orderpilot/api/rest/DraftQuoteControllerTest.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/workspace/RfqToDraftQuoteServiceTest.java`
- `docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md`
- `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_11A.md`

## Commands Run

To be completed during final verification:

- `powershell -ExecutionPolicy Bypass -File scripts\check-no-secrets.ps1`
- targeted Maven tests for policy, boundaries, sandbox, draft quote, RFQ, connector safety, channel safety, and webhook safety
- `mvn test`
- `powershell -ExecutionPolicy Bypass -File scripts\start-local-demo.ps1`
- `powershell -ExecutionPolicy Bypass -File scripts\check-local-demo.ps1 -AllowFixtureMode`
- final `powershell -ExecutionPolicy Bypass -File scripts\check-no-secrets.ps1`

## Safety Confirmations

- Draft quotes are internal OrderPilot records only.
- No connector command is created automatically.
- No sandbox execution is triggered automatically.
- No compensation plan is created or executed.
- No ERP, 1C, accounting, or warehouse write is enabled.
- No real AI provider is called.
- No real Telegram, WhatsApp, Meta, or provider call is enabled.
- No dependency changes are required.
- No UI redesign is included.
- Tenant context remains authoritative.

## Known Limitations

- Product matching is exact SKU or active alias only.
- Customer matching is exact tenant-scoped account code only.
- Pricing uses simple tenant-scoped active price-rule matching.
- Margin is recorded as not evaluated unless later stages add richer margin data.
- Telegram RFQ to quote automation is not wired into production webhook handling in Stage 11A.

## Next Recommended Stage

Stage 11B should be Product Catalog + SKU/Alias/OEM Matching Hardening.
