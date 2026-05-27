# Data Foundation

Stage 2 creates the first controlled operational mirror for OrderPilot.

## Authority model

External ERP, 1C, Excel, local databases, accounting systems, and warehouse systems remain the source-of-truth by default. OrderPilot stores a controlled operational mirror plus workflow state, validation reports, and audit events.

## Core data groups

- Customers: `customer_account`, `customer_segment`. Customer contacts: `customer_contact`.
- Organization: `location`, `department`.
- Products: `product`, `product_alias`, `oem_reference`, `product_compatibility`, `product_substitute`, `customer_substitution_preference`.
- Vehicles: `vehicle_make`, `vehicle_model`, `vehicle_year`, `vehicle_configuration`.
- Commercial rules: `price_rule`, `discount_rule`, `margin_rule`.
- Inventory: `inventory_snapshot`.
- Import mirror: `data_source`, `import_job`, `import_staging_row`, `import_validation_issue`, `validation_report`.

All tenant-owned tables include `tenant_id`.

## Service boundary

Controllers call services. Services enforce tenant context, deterministic checks, repository access, and audit events. Controllers must not write repositories directly.