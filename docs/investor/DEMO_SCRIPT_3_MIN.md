# OrderPilot Core V1 3-Minute Demo Script

## Opening

OrderPilot turns messy customer requests, documents, bot conversations, validation issues, inventory discrepancies, and integration intent into one controlled operator workflow. The point of the demo is measurable operating leverage with safety gates.

## Flow

1. Show intake: inbound RFQ/document/message records enter the system with tenant-scoped audit evidence.
2. Show validation: the operator sees product, pricing, margin, inventory, substitution, and approval risks before draft preparation.
3. Show Command Center: automation, exception, handoff, reconciliation, and business-value metrics summarize where work was saved and where risk was found.
4. Show Integrations: an approved validation-backed draft creates a ChangeRequest and executes through the local Demo ERP adapter.
5. Replay the same ChangeRequest: the same external reference and `sha256:*` idempotency hash are reused, with no second execution.
6. Show policy block: non-demo connector targets are denied and audited.
7. Close on safety: no real ERP/1C writes, no external connector network calls, no raw secrets, no inventory mutation, and no bot-triggered connector commands.

## Investor Close

OrderPilot demonstrates the value path and the control path together: fewer manual reviews, clearer risk evidence, and a future-ready integration boundary that stays disabled until security acceptance.
