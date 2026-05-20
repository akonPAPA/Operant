# Import Mirror Architecture

Stage 2 import flow:

```text
External file/API/manual row
  -> ImportJob
  -> ImportStagingRow
  -> deterministic validation
  -> ValidationReport
  -> apply or reject
  -> AuditEvent
```

## Rules

- Imports stage data first.
- Staged rows keep raw JSON and mapped JSON.
- Validation is deterministic and tenant-aware.
- Validation report is required before activation.
- Applying an import in Stage 2 marks a fully validated job as applied; broad upsert behavior is intentionally limited and can be expanded in Stage 2.1.
- External writes remain forbidden.

## Validation examples

- Product imports require `sku`, `name`, and `baseUom`; cost must be non-negative.
- Customer imports require `accountCode` and either `legalName` or `displayName`.
- Inventory imports require product and location identity plus non-negative quantity.
- Price imports require product identity, currency, positive minimum quantity, and non-negative price.