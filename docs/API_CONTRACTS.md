# API Contracts

## AI Understanding APIs

Phase 4 extraction APIs expose advisory read and run operations for tenant-scoped inbound documents and channel messages.

Read-only inspection:

```text
GET /api/v1/extractions/results
GET /api/v1/extractions/results/{id}
GET /api/v1/extractions/runs/{id}/result
GET /api/v1/extractions/runs/{id}/fields
GET /api/v1/extractions/runs/{id}/line-items
GET /api/v1/extractions/runs/{id}/evidence
GET /api/v1/extractions/runs/{id}/suggestions
GET /api/v1/extractions/sources/{sourceType}/{sourceId}/results
```

Run operation:

```text
POST /api/v1/extractions/runs/execute
```

Supported source types:

- `INBOUND_DOCUMENT`
- `CHANNEL_MESSAGE`

Supported document intents:

- `PURCHASE_ORDER`
- `RFQ`
- `AVAILABILITY_REQUEST`
- `PRICE_REQUEST`
- `ORDER_STATUS_REQUEST`
- `UNKNOWN`

## Business Authority Boundary

AI extraction APIs must not expose endpoints that directly mutate quotes, orders, inventory, customer accounts, products, price rules, discount rules, margin rules, users, roles, tenants, integration connections, or ERP state.

Deterministic validation and typed backend services own final business decisions. Human approval is required for risky actions and external writes.

## Security Contract

Every request must preserve authentication, tenant scoping, and permission checks. AI output remains advisory and tenant-owned. Prompt-injection text from customers is ordinary untrusted content and cannot alter system behavior.
