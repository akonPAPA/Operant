# OrderPilot Core V1 10-Minute Demo Script

## 1. Intake And Evidence

Start with the inbox or document/message views. Explain that inbound requests are tenant-scoped and normalized before they become operator work. Customer text and bot messages are untrusted input; they do not become authority.

## 2. Validation And Review

Open a validation-backed review. Show product matching, pricing, margin, inventory, substitution, and approval evidence. Emphasize that draft quote/order preparation remains behind backend readiness gates.

## 3. Analytics And Reconciliation

Open Command Center and Inventory/Reconciliation. Show request volume, bot handoffs, validation-backed reviews, blocked unsafe draft attempts, drafts prepared, exception rate, inventory mismatch, stale inventory, low stock, and business-value metrics.

## 4. Demo ERP ChangeRequest

Open Integrations. Show the Demo ERP connection, execution mode `DEMO_ONLY`, capability list, placeholder credential status, disabled production warning, ChangeRequest queue, sync runs, and audit timeline.

Create or open an approved validation-backed ChangeRequest. Execute it through the Demo ERP adapter. The result is deterministic and local, with a demo external reference.

## 5. Idempotent Replay

Execute the same ChangeRequest again. Show that the same external reference and `sha256:*` idempotency hash are returned. The audit timeline shows replay/idempotent reuse with `replay:true` and `networkCall:false`. There is no second external execution and no `ConnectorCommand`.

## 6. Failure And Policy Evidence

Show retryable and terminal failure states if seeded data is available. Explain attempt count, max attempts, last attempt, next retry, failure type, and retryable label.

Show a non-demo target policy block. The audit event is `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`. The blocked request does not execute and does not mutate inventory.

## 7. Safety Close

Stage 9B is demo-only. No real ERP/1C writes are performed. No external connector network calls are performed. No raw connector credentials or raw idempotency seeds are stored, returned, displayed, or logged. Production connector activation is future work requiring separate security and runbook acceptance.
