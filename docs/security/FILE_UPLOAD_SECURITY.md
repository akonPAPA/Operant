# File Upload Security

Uploaded files are untrusted.

## Stage 3 controls

- Allowed content types are explicitly listed.
- Empty files are rejected.
- Default max file size is 10 MB.
- SHA-256 fingerprint is calculated.
- Duplicate fingerprints within a tenant do not enqueue duplicate processing.
- Files are stored through ObjectStorageService, not under the web dashboard.

## Forbidden in Stage 3

- No OCR in the request thread.
- No PDF/Excel parsing into business tables.
- No AI extraction.
- No quote/order/product/customer/inventory mutation from uploaded content.