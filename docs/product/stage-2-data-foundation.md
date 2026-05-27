# Stage 2 Data Foundation

Stage 2 makes OrderPilot usable with realistic demo business data while preserving the product authority model.

## Scope

- Tenant-owned customer, product, location, inventory, pricing, alias, OEM, substitute, compatibility, discount, margin, import job, staging, validation, and audit foundations.
- CSV import into staging before activation.
- Demo data for an auto/industrial parts distributor.
- Toyota Camry 2018 brake pads scenario with an original product out of stock and two in-stock substitutes.
- API-backed Windows seed script.

## Out of Scope

- Real AI/OCR.
- Telegram or WhatsApp integration.
- Quote/order automation.
- External ERP, accounting, or warehouse writes.
- Direct database seed scripts.

## Data Authority

Core-api owns trusted business mutations. Imported data is mirrored from client/source systems, staged, validated, and activated only through backend services. OrderPilot does not silently overwrite external source-of-truth data.

## Demo Data

Fixtures live under `packages/test-fixtures/stage2-demo` and include:

- 1 demo tenant, created by API.
- 3 customers.
- 8 products.
- 2 warehouses.
- Toyota Camry 2018 brake pads, including OEM reference `04465-33450`.
- Original brake pad product out of stock.
- Two substitute products in stock.
- Customer-specific price rules.
- Product aliases and old SKU codes.
- Discount and margin rules.

## Acceptance

- Import jobs stage rows first.
- Validation reports block bad required headers and bad numeric values.
- Activation writes only validated rows.
- Duplicate imports return validation errors or are skipped by the script.
- Tenant A data is not visible to Tenant B.
- Import activation emits audit events.
