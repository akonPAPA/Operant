# Stage 11E - Quote Approval Handoff Readiness

Stage 11E prepares approved internal quotes for a future external-write handoff without executing any ERP, 1C, accounting, or warehouse write.

Internal quote approval is not external mutation. The controlled path is:

Approved quote -> handoff snapshot -> ChangeRequest draft -> future explicit connector execution.

## Scope

- Check whether a draft quote is ready for handoff.
- Prepare an immutable quote handoff snapshot.
- Create a controlled ChangeRequest draft from the snapshot.
- Allow internal ChangeRequest approval or cancellation while execution remains disabled.
- Emit audit events for readiness, snapshot preparation, draft creation, internal approval, cancellation, and Stage 11E execution blocking.

## Readiness Rules

A quote is ready only when:

- the quote status is internally approved;
- open blocking quote validation issues are resolved;
- substitute decisions are approved or not required;
- customer/account, product, quantity, UOM, and price references are present;
- selected substitute products belong to the current tenant and are approved;
- tenant-scoped lookup succeeds.

If any rule fails, the handoff status is `HANDOFF_BLOCKED`.

## Snapshot Contract

`quote_handoff_snapshot` stores the immutable payload that a future connector would use. It includes tenant id, quote id, customer reference, quote status, line items, original product references, selected approved substitutes, quantities, UOM, price fields, validation summary, approval summary, generated timestamp, actor, payload version, idempotency key, and payload hash.

Snapshots are not sent externally. If quote data changes later, a new snapshot version is created instead of mutating an old snapshot.

## ChangeRequest Boundary

Stage 11E ChangeRequests use `sourceType=QUOTE`, `sourceId=<quote id>`, a snapshot reference, payload hash, and idempotency key. They may be internally approved, rejected, blocked, or cancelled.

Execution remains `NOT_EXECUTED` or `EXECUTION_DISABLED`. The `EXECUTED` state is forbidden by database constraints and Stage 11E services do not create connector commands.

## Future Connector Requirements

Future real connector execution must require explicit tenant policy, scoped connector credentials, idempotency, audit, approval, and a separate execution command. Stage 11E intentionally does not implement that execution path.
