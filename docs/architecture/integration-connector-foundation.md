# Integration Connector Foundation

Stage 13 separates business-system integrations from customer communication channels and prepares the first safe read-only pilot.

Supported provider catalog:
- 1C, Excel, CSV, Generic Database, Generic REST API
- NetSuite, Dynamics 365, Epicor, SAP, Odoo, QuickBooks
- Other ERP, Other Accounting, Other Inventory

Core rules:
- Every integration connection is tenant-scoped.
- Connections default to `DRAFT` and `READ_ONLY`.
- Secret metadata is stored as a vault reference and timestamp only.
- Health checks return structured diagnostics and do not perform external writes.
- Sync actions record `connector_sync_event` rows and audit events.
- Connector adapters do not write product, customer, inventory, pricing, quote, order, warehouse, ERP, accounting, or master-data tables.
- External writes require a later explicit approval/change-request stage and remain disabled by default.

## Read-Only Pilot

`DemoErpIntegrationAdapter` is the Stage 13 read-only pilot. It fetches product, customer, inventory, and price summaries as counts and records sync events. The pilot intentionally stores only sync metadata and keeps `recordsWritten = 0`.

## Health Checks

Run health checks through:
- `POST /api/v1/integrations/connections/{id}/health-check`
- `POST /api/v1/channels/connections/{id}/health-check`

The response includes `diagnostics` with severity, code, and safe UI message fields.
