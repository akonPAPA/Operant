# Engineering Phase 3 - Omnichannel Intake

Phase 3 adds the controlled inbound layer for OrderPilot Core v1. It accepts untrusted file, API, email, Telegram-like, and WhatsApp-like inputs, stores raw files/payloads through the object-storage abstraction, creates tenant-scoped intake records, audit logs each accepted event, and queues processing jobs for later phases.

No AI extraction, OCR, quote creation, order creation, Telegram bot conversation flow, WhatsApp production integration, or business-data mutation is implemented in this phase.

## Local Verification

Backend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn clean test
mvn spring-boot:run
```

Frontend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
npm.cmd run dev
```

Docker config:

```powershell
cd C:\OrderPilot\OrderPilot-Core
docker compose -f infra/docker/docker-compose.yml config
```

## Supported File Types

The file upload endpoint accepts PDF, CSV, XLS, XLSX, TXT, PNG, JPG, and JPEG. The default maximum size is 10 MB. Rejected files do not create `InboundDocument` records.

## Manual File Upload

Use the dashboard `/upload` page or call core-api directly:

```powershell
curl.exe -X POST http://localhost:8080/api/v1/intake/documents/upload `
  -H "X-Tenant-Id: <tenant-uuid>" `
  -F "file=@C:\Temp\rfq.csv;type=text/csv" `
  -F "sourceChannel=FILE_UPLOAD" `
  -F "documentType=CUSTOMER_RFQ" `
  -F "receivedFrom=buyer@example.test" `
  -F "subject=RFQ"
```

Expected result: an `InboundDocument` with status `QUEUED`, an object-storage record, a `ProcessingJob` with status `PENDING`, and an `AuditEvent`.

## API Document Upload

```powershell
curl.exe -X POST http://localhost:8080/api/v1/intake/documents `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: <tenant-uuid>" `
  -d "{\"sourceChannel\":\"API_UPLOAD\",\"documentType\":\"CUSTOMER_RFQ\",\"originalFilename\":\"rfq.txt\",\"contentType\":\"text/plain\",\"contentBase64\":\"TmVlZCBicmFrZSBwYWRz\",\"receivedFrom\":\"api-client\",\"subject\":\"API RFQ\",\"rawMetadata\":\"{}\"}"
```

## Telegram Webhook Stub

```powershell
curl.exe -X POST http://localhost:8080/api/v1/webhooks/telegram `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: <tenant-uuid>" `
  -H "X-OrderPilot-Webhook-Signature: dev-placeholder" `
  -d "{\"update_id\":1001,\"message\":{\"message_id\":501,\"chat\":{\"id\":\"chat-1\"},\"from\":{\"id\":\"sender-1\",\"username\":\"buyer\"},\"text\":\"Need filters\"}}"
```

Expected result: a `ChannelMessage` with channel `TELEGRAM`, an `InboundEventLedger` row, a `WebhookEvent`, raw payload storage, a `ProcessingJob`, and an audit event.

## Email Webhook Stub

```powershell
curl.exe -X POST http://localhost:8080/api/v1/webhooks/email `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: <tenant-uuid>" `
  -d "{\"externalMessageId\":\"email-1\",\"sender\":\"buyer@example.test\",\"subject\":\"RFQ\",\"bodyText\":\"Please quote attached list\",\"rawPayload\":\"{\\\"messageId\\\":\\\"email-1\\\"}\",\"attachments\":[{\"originalFilename\":\"rfq.csv\",\"contentType\":\"text/csv\",\"sizeBytes\":128,\"objectStorageKey\":\"metadata-only/email-1/rfq.csv\",\"fingerprintSha256\":\"abc123\"}]}"
```

Expected result: an `EMAIL` `ChannelMessage` and metadata-only `InboundAttachment` rows for attachment descriptors. Attachment bytes are not downloaded or parsed in this phase.

## WhatsApp Webhook Stub

```powershell
curl.exe -X POST http://localhost:8080/api/v1/webhooks/whatsapp `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: <tenant-uuid>" `
  -d "{\"entry\":[{\"changes\":[{\"value\":{\"contacts\":[{\"wa_id\":\"77001112233\",\"profile\":{\"name\":\"Buyer One\"}}],\"messages\":[{\"id\":\"wamid.1\",\"from\":\"77001112233\",\"type\":\"text\",\"text\":{\"body\":\"Need oil filters\"}}]}}]}]}"
```

Expected result: a `WHATSAPP` `ChannelMessage`, raw payload storage, an event ledger entry, a pending processing job, and audit event.

## Deduplication

Documents are deduplicated by tenant and SHA-256 fingerprint. Duplicate uploads create a second `InboundDocument` marked `DUPLICATE` and do not enqueue another processing job.

Webhook events are deduplicated by tenant, provider/source, external event id, and payload fingerprint. Replays are recorded as webhook/ledger entries with replay metadata but do not mutate product, customer, inventory, pricing, quote, or order data.

## Known Limitations

- Webhook signature verification is provider-ready but stubbed.
- Local development object storage writes under the application target directory.
- File content is not parsed and attachment bytes are not fetched from email providers.
- No async worker consumes `ProcessingJob` records yet.
- The dashboard upload form depends on browser access to `NEXT_PUBLIC_CORE_API_URL` and tenant ID configuration.

## Next Recommended Phase

Engineering Phase 4 should implement deterministic pre-processing workers for intake records: safe text extraction hooks, metadata enrichment, retry/error handling, and operator-visible processing state. AI/OCR should remain behind explicit later gates.
