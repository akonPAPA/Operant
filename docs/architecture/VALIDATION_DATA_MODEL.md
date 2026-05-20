# Validation Data Model

Stage 5 adds tenant-owned workflow/advisory decision tables:

- `validation_run`
- `validation_issue`
- `customer_match_result`
- `product_match_result`
- `uom_normalization_result`
- `inventory_check_result`
- `price_check_result`
- `discount_check_result`
- `margin_check_result`
- `substitute_candidate`
- `approval_requirement`

Every tenant-owned validation table includes `tenant_id`.

These records are review artifacts for deterministic validation. They are not final quote/order state and are not ERP, 1C, accounting, warehouse, customer, product, inventory, or pricing master data.
