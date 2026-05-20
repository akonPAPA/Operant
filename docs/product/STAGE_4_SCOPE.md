# Stage 4 Scope

## Goal

Build the AI-assisted understanding layer that turns Stage 3 intake records into advisory structured extraction results.

## Included

- Extraction run tracking.
- Extracted document/message text storage.
- Rule-based/mock semantic extraction.
- Detected intent and document type.
- Field-level and line-level confidence.
- Source evidence snippets.
- Prompt injection warning suggestions.
- Output sanitizer and schema validation.
- Extraction review status changes for advisory data.

## Excluded

- Real paid LLM calls.
- Real OCR provider integration.
- Product/customer/inventory/price/quote/order mutation.
- Stock, price, margin, compatibility, or substitute validation.
- ERP/1C/accounting/warehouse writes.