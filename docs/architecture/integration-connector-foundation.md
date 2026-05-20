# Integration Connector Foundation

Stage 12 separates business-system integrations from customer communication channels. Integrations represent ERP, accounting, inventory, file, API, and read-only database connections.

Supported provider catalog:
- 1C, Excel, CSV, Generic Database, Generic REST API
- NetSuite, Dynamics 365, Epicor, SAP, Odoo, QuickBooks
- Other ERP, Other Accounting, Other Inventory

Core rules:
- Every integration connection is tenant-scoped.
- Default mode is `READ_ONLY`.
- Stub sync actions record `connector_sync_event` rows and audit events.
- Connector adapters do not write product, customer, inventory, pricing, quote, or order tables.
- External writes require a later approval/change-request stage and must remain disabled by default.

Stage 12 records sync history and health checks. It does not perform real external API calls, database reads, or ERP writes.
