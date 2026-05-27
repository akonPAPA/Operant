# Webhook and File Upload Security

Phase 3 treats every inbound channel payload as untrusted. The goal is controlled receipt and traceability, not business execution.

## File Upload Controls

- Accepted content types are restricted to PDF, CSV, XLS, XLSX, TXT, PNG, JPG, and JPEG.
- Default max file size is 10 MB.
- Empty files are rejected.
- Rejected files do not create `InboundDocument`, object-storage, processing-job, or audit records.
- SHA-256 fingerprints are used for deterministic duplicate detection.
- File bytes are stored through `ObjectStorageService`; controllers do not write arbitrary local files.

## Webhook Controls

- Webhook endpoints accept headers and payloads through a `WebhookVerificationService` interface.
- Provider-specific signature verification is stubbed for this phase but the controller/service boundary is ready for real verifier implementations.
- Raw webhook payloads are stored through object storage.
- Event replay checks are tenant-scoped and use provider/source plus external event id and payload fingerprint.
- Webhook receipt creates `InboundEventLedger` and audit records for traceability.

## No Secret Hardcoding

Channel secrets are represented by references (`secret_ref` / secret reference fields) rather than raw tokens. Development stubs must not hardcode production secrets.

## No Business Data Mutation

File uploads, API messages, and webhook stubs do not write products, customers, inventory, pricing, discounts, margins, quotes, orders, ERP, accounting, or warehouse data. Later processing must continue routing every trusted mutation through core-api service boundaries.

## Large File and Processing Boundary

Request handlers validate and store inbound content, then create `ProcessingJob` records. They do not parse large files, run OCR, run AI extraction, or perform long-running processing in the request thread.

## Known Limitations

- Signature/token verification is not provider-complete.
- Local development object storage is not production object storage.
- Attachment byte ingestion from email providers is not implemented.
- Malware scanning and archive expansion protections are not implemented yet.
- Processing jobs are queued but not consumed by a worker in this phase.
