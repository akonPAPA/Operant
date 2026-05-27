# Demo Seed Data Runbook

This runbook loads the Stage 2 Core v1 demo data through core-api HTTP endpoints. It does not write PostgreSQL directly.

## Prerequisites

- PostgreSQL and Redis are running.
- `apps/core-api` is running at `http://localhost:8080`.
- PowerShell is running from the active repository root: `C:\OrderPilot\OrderPilot-Core`.

## Run

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
.\scripts\seed-demo-data\seed-core-v1.ps1
```

The script creates or reuses a demo tenant, imports CSV fixtures from `packages/test-fixtures/stage2-demo`, validates every import job, activates only valid jobs, and prints a summary with tenant id and imported counts.

## CSV Flow

The import flow is:

1. CSV content is submitted to core-api.
2. Rows are stored in `import_staging_row`.
3. Validation produces `validation_report` and `import_validation_issue` records.
4. Activation is allowed only when validation has zero invalid rows.
5. Activation writes operational mirror tables through core-api services and records audit events.

## Fixture Headers

- `locations.sample.csv`: `code,name,type,address,city,country,active`
- `customers.sample.csv`: `accountCode,legalName,displayName,defaultCurrency,locationCode`
- `products.sample.csv`: `sku,name,description,category,brand,manufacturer,baseUom,status,cost,currency`
- `product_aliases.sample.csv`: `sku,aliasType,rawAlias,confidenceDefault`
- `oem_references.sample.csv`: `sku,oemCode,manufacturer`
- `inventory.sample.csv`: `sku,locationCode,quantityOnHand,quantityAvailable,quantityReserved,source`
- `price_rules.sample.csv`: `sku,accountCode,minQuantity,uom,unitPrice,currency,activeFrom,priority`
- `product_substitutes.sample.csv`: `sourceSku,substituteSku,substituteType,riskLevel,requiresApproval,notes`
- `compatibility.sample.csv`: `sku,compatibleType,make,model,yearFrom,yearTo,configuration,notes,riskLevel`
- `discount_rules.sample.csv`: `code,name,accountCode,sku,maxDiscountPercent,requiresApprovalAbovePercent,activeFrom`
- `margin_rules.sample.csv`: `code,name,sku,category,minimumGrossMarginPercent,approvalRequiredBelowPercent`

## Verification

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn test

cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm.cmd run typecheck
npm.cmd run lint
npm.cmd run build

cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
.\.venv\Scripts\python.exe -m pytest

cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f infra/docker/docker-compose.yml config
```

## Known Limitations

- The importer is CSV-only for this stage.
- Duplicate rows are rejected or skipped by the seed script; existing source-of-truth data is not silently overwritten.
- The dashboard shows IDs for some inventory relationships until richer read models are added.
- No real AI/OCR, Telegram/WhatsApp, quote automation, or external ERP writes are enabled by this stage.

## Next Stage

After Stage 2 is stable, proceed to Stage 3 Omnichannel Intake stabilization: file upload, email webhook stub, API upload, and Telegram adapter only after the data foundation remains green.
