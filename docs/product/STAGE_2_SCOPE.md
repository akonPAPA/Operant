# Stage 2 Scope

## Goal

Build the data foundation and import mirror that future intake, AI extraction, validation, substitution, quote/order, analytics, and integration stages need.

## Included

- Tenant-owned customer account and customer segment tables.
- Tenant-owned location and department tables.
- Tenant-owned product catalog tables: product, aliases, OEM references, compatibility, substitutes, and customer substitution preferences.
- Inventory snapshot table.
- Price, discount, and margin rule tables.
- Data source, import job, import staging row, and validation report tables.
- REST endpoints for customers, products, aliases, inventory snapshots, price rules, and import jobs.
- Deterministic import row validation examples for products, customers, inventory, and prices.
- Audit events for important create/update/import operations where implemented.

## Excluded

- AI extraction pipeline.
- Telegram, WhatsApp, and email intake.
- PDF or Excel parsing engine.
- Quote/order workspace.
- Real ERP/1C connector.
- Substitution ranking.
- Analytics dashboards.
- External writes.
- Production auth/SSO.