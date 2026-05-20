# Deterministic Validation Engine

```text
ExtractionResult
  -> customer match
  -> product / alias / OEM match
  -> UOM normalization
  -> inventory check
  -> price rule check
  -> discount rule check
  -> margin guardrail check
  -> compatibility check
  -> substitute candidate generation
  -> validation issues
  -> approval requirements
  -> Stage 6 quote/order workspace
```

AI output is advisory input only. Stage 5 services read extraction records and tenant mirror data, then write validation workflow records under `validation_*`, match result, check result, substitute candidate, and approval requirement tables.

Controllers delegate business operations to services. Services use `TenantContext`, deterministic repository lookups, validation checks, and audit events. Stage 5 does not mutate product, customer, inventory, pricing, quote, order, ERP, 1C, accounting, or warehouse state.
