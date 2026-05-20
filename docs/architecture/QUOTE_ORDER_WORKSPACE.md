# Quote/Order Workspace

Stage 6 creates internal `DraftQuote` and `DraftOrder` workflow records from `ValidationRun` output.

```text
ValidationRun
  -> DraftQuote / DraftOrder
  -> DraftQuoteLine / DraftOrderLine
  -> internal review
  -> internal approve / reject / cancel
  -> AuditEvent and OperatorAction
```

Draft quotes and draft orders are OrderPilot review records only. They are not ERP records, accounting records, warehouse orders, or final customer-facing documents.

Approval in Stage 6 is internal workflow approval. It does not send email, create an external order, reserve inventory, decrement inventory, or call a connector.
