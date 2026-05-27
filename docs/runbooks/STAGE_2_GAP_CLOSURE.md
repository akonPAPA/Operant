# Stage 2 Gap Closure

This pass keeps the existing Stage 2 implementation and closes gaps found after later stages were already present.

## Added

- Additive Flyway migration `V22__stage2_data_foundation_gap_closure.sql`.
- `customer_contact`, vehicle make/model/year/configuration tables, and `import_validation_issue`.
- Dedicated JPA domain classes and repositories for the added tables.
- `/api/v1/imports` compatibility route in addition to existing import job routes.
- `POST /api/v1/imports/{id}/activate` as the explicit activation endpoint.
- CSV content staging through `ImportJobRequest.csvContent`.
- Product and inventory import activation from valid staging rows into operational mirror tables.
- Deterministic duplicate SKU detection within a product import file.
- `GET /api/v1/products/search?q=...` while preserving the existing `query` parameter.
- `GET /api/v1/inventory` while preserving `/api/v1/inventory/latest`.
- Read endpoints for `/api/v1/discounts` and `/api/v1/margins`.

## Safety Boundaries

- Imports still stage first and require explicit activation.
- Activation supports `PRODUCTS` and `INVENTORY` only.
- Product activation creates new mirror rows and rejects duplicates instead of overwriting trusted operational data.
- Inventory activation appends snapshots; it does not update ERP, 1C, accounting, or warehouse systems.
- Activation records `import_job.activated` audit events.

## Verification

Passed locally:

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn clean test

cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm.cmd run typecheck
npm.cmd test
npm.cmd run build

cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f infra/docker/docker-compose.yml config
```
