# Stage 3 Implementation Report

## 1. STATUS

PASS_WITH_MANUAL_STEPS

Stage 3 source, migration, services, APIs, frontend placeholders, AI worker placeholder, tests, and docs were implemented in the active repository. Full Maven, npm, Docker, and Java 21 verification could not be executed in this shell because those tools are unavailable or not configured in PATH.

## 2. Repository root used

`C:\OrderPilot\OrderPilot-Core`

## 3. Confirmation

The old Obsidian code path was not used for code changes. No source code was created, edited, restored, or continued in:

`C:\Users\mukha\Documents\Obsidian Vault\OrderPilot-AI-Programm\OrderPilot-Core`

All implementation work for Stage 3 was performed only inside:

`C:\OrderPilot\OrderPilot-Core`

## 4. Summary of implementation

- Added V3 omnichannel intake migration.
- Added intake domain entities and repositories.
- Added local-dev object storage abstraction with SHA-256 fingerprinting.
- Added deterministic file and message validation.
- Added inbound document intake with duplicate fingerprint handling.
- Added channel message intake with external message id deduplication.
- Added webhook event ledger and webhook security placeholders.
- Added processing job placeholder queue and retry endpoint.
- Added manual/API document, message, email, Telegram, WhatsApp-ready, webhook event, and processing job endpoints.
- Added dashboard placeholders for Inbox, Upload, Documents, Messages, Message Detail, Webhook Events, Processing Jobs, and Integrations.
- Added AI worker placeholder for future processing-job payloads while keeping output advisory-only.
- Added Stage 3 docs and tests.

## 5. File tree summary

```text
apps/core-api/
  src/main/java/com/orderpilot/domain/intake/
  src/main/java/com/orderpilot/application/services/*Intake* / *Webhook* / *ProcessingJob* / ObjectStorageService
  src/main/java/com/orderpilot/api/rest/IntakeDocumentController.java
  src/main/java/com/orderpilot/api/rest/IntakeMessageController.java
  src/main/java/com/orderpilot/api/rest/WebhookController.java
  src/main/java/com/orderpilot/api/rest/ProcessingJobController.java
  src/main/resources/db/migration/V3__omnichannel_intake.sql
apps/web-dashboard/app/(dashboard)/
  inbox/
  upload/
  documents/
  messages/
  messages/[id]/
  webhook-events/
  processing-jobs/
apps/ai-worker/orderpilot_ai_worker/tasks/process_processing_job.py
docs/architecture/
docs/security/
docs/product/
docs/runbooks/
```

## 6. Files created/modified

Created:

- `apps/core-api/src/main/resources/db/migration/V3__omnichannel_intake.sql`
- `apps/core-api/src/main/java/com/orderpilot/domain/intake/*`
- `apps/core-api/src/main/java/com/orderpilot/application/services/IntakeValidationService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/ObjectStorageService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/InboundDocumentService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/ChannelMessageService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/ProcessingJobService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/WebhookSecurityService.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/WebhookEventService.java`
- `apps/core-api/src/main/java/com/orderpilot/api/dto/Stage3Dtos.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/IntakeDocumentController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/IntakeMessageController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/WebhookController.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/ProcessingJobController.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/IntakeValidationServiceTest.java`
- `apps/core-api/src/test/java/com/orderpilot/application/services/Stage3MigrationFileTest.java`
- `apps/web-dashboard/app/(dashboard)/upload/page.tsx`
- `apps/web-dashboard/app/(dashboard)/webhook-events/page.tsx`
- `apps/web-dashboard/app/(dashboard)/processing-jobs/page.tsx`
- `apps/web-dashboard/app/(dashboard)/messages/[id]/page.tsx`
- `apps/ai-worker/orderpilot_ai_worker/tasks/process_processing_job.py`
- `apps/ai-worker/tests/test_process_processing_job.py`
- `docs/product/STAGE_3_SCOPE.md`
- `docs/architecture/OMNICHANNEL_INTAKE.md`
- `docs/architecture/CHANNEL_GATEWAY.md`
- `docs/architecture/PROCESSING_JOB_ARCHITECTURE.md`
- `docs/security/WEBHOOK_SECURITY.md`
- `docs/security/FILE_UPLOAD_SECURITY.md`
- `docs/runbooks/STAGE_3_VERIFICATION.md`
- `docs/runbooks/STAGE_3_IMPLEMENTATION_REPORT.md`

Modified:

- `apps/web-dashboard/components/navigation.ts`
- `apps/web-dashboard/app/(dashboard)/inbox/page.tsx`
- `apps/web-dashboard/app/(dashboard)/documents/page.tsx`
- `apps/web-dashboard/app/(dashboard)/messages/page.tsx`
- `apps/web-dashboard/app/(dashboard)/integrations/page.tsx`
- `apps/web-dashboard/app/globals.css`
- `apps/ai-worker/README.md`

## 7. Database tables added

- `object_storage_record`
- `inbound_document`
- `channel_message`
- `inbound_attachment`
- `processing_job`
- `webhook_event`

## 8. API endpoints added

- `POST /api/v1/intake/documents/upload`
- `POST /api/v1/intake/documents/api-upload`
- `GET /api/v1/intake/documents`
- `GET /api/v1/intake/documents/{id}`
- `POST /api/v1/intake/messages`
- `GET /api/v1/intake/messages`
- `GET /api/v1/intake/messages/{id}`
- `GET /api/v1/intake/conversations/{conversationId}`
- `POST /api/v1/webhooks/email`
- `POST /api/v1/webhooks/telegram/{tenantKey}`
- `POST /api/v1/webhooks/whatsapp/{tenantKey}`
- `GET /api/v1/webhooks/events`
- `GET /api/v1/webhooks/events/{id}`
- `GET /api/v1/processing/jobs`
- `GET /api/v1/processing/jobs/{id}`
- `POST /api/v1/processing/jobs/{id}/retry`

## 9. Tests added

- `IntakeValidationServiceTest`
  - invalid file type rejected;
  - empty file rejected;
  - empty message rejected.
- `Stage3MigrationFileTest`
  - confirms intake migration contains required tables and tenant markers.
- `test_process_processing_job.py`
  - confirms AI worker processing-job placeholder remains advisory-only.

## 10. Verification performed

Passed in this shell:

- Active repository root exists at `C:\OrderPilot\OrderPilot-Core`.
- Stage 1 and Stage 2 docs were inspected.
- Stage 3 required docs and report were created.
- `pom.xml` parses as XML.
- Frontend `package.json` and `tsconfig.json` parse as JSON.
- V3 migration exists and includes required intake tables.
- REST controller endpoint mappings were added under `/api/v1`.
- AI worker advisory smoke test was run with bundled Python.

Blocked in this shell:

- `mvn test`: Maven is not available in PATH.
- Java 21 runtime verification: system `java` is Java 8.
- `npm install`, `npm run lint`, `npm run build`: npm is not available in PATH.
- `docker compose`: Docker is not available in PATH.
- `pytest`: pytest is not installed in the bundled Python runtime.

## 11. Manual verification commands

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

## 12. Example local API requests

```powershell
$tenantId = "00000000-0000-0000-0000-000000000001"
Invoke-RestMethod "http://localhost:8080/api/v1/health"
```

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/intake/messages" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"channel":"API","externalMessageId":"msg-1","textContent":"Need filters","rawPayload":"{\"source\":\"local\"}"}'
```

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/webhooks/telegram/demo" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"externalEventId":"tg-1","rawPayload":"{\"message\":{\"text\":\"Need brake pads\"}}"}'
```

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/intake/documents/api-upload" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"sourceChannel":"API_UPLOAD","documentType":"RFQ","originalFilename":"rfq.txt","contentType":"text/plain","contentBase64":"TmVlZCAxMCBmaWx0ZXJz","receivedFrom":"local-test"}'
```

## 13. Known limitations

- Webhook signature verification is an interface/placeholder, not production-grade provider verification.
- Telegram and WhatsApp endpoints are intake stubs and do not send replies.
- WhatsApp production integration is not complete or certified.
- Email provider-specific parsing is not implemented.
- Object storage is local-dev abstraction only.
- No OCR, LLM, PDF parsing, Excel parsing, product matching, quote/order creation, or substitution logic is implemented.
- Processing jobs are queued as placeholders; no worker polling infrastructure is implemented yet.

## 14. Security confirmation

- Frontend has no DB access.
- AI worker has no business DB write path.
- Stage 3 only queues processing jobs.
- Uploaded files are treated as untrusted.
- Webhook signature verification interface/placeholder exists.
- Tenant-owned intake tables include `tenant_id`.
- No quote/order/product/customer/inventory mutation occurs from intake.
- No external ERP/1C writes are implemented.

## 15. Next recommended stage

Stage 4 â€” AI-Assisted Understanding Pipeline:

- OCR/text extraction provider interface;
- LLM provider abstraction;
- structured extraction schema;
- field-level confidence;
- line-level confidence;
- evidence references;
- prompt injection guardrails;
- AI output stored only as suggestions/results;
- deterministic validation handoff.