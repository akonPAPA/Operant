# Processing Job Architecture

Stage 3 introduces `processing_job` as a placeholder queue table.

## Job types

- `DOCUMENT_PROCESSING`
- `MESSAGE_PROCESSING`
- `ATTACHMENT_PROCESSING`

## Statuses

- `QUEUED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

## Current behavior

Accepted documents and messages enqueue a placeholder job. Stage 3 does not run OCR or AI. Stage 4 will add AI-assisted understanding and can consume these job records through a safe worker contract.