# Investor Demo Scenarios

## A. Request Intake

Show inbound RFQ, document, or message intake if seeded data is present. Explain tenant scoping and audit visibility.

## B. Validation And Risk Evidence

Open validation review and show validation issues, readiness state, suggested fixes, approval requirements, and draft preparation boundaries.

## C. ChangeRequest Demo Execution

Open Integrations and execute an approved validation-backed `DRAFT_QUOTE` or `DRAFT_ORDER` ChangeRequest through Demo ERP. Confirm execution mode is `DEMO_ONLY`.

## D. Idempotent Replay

Run execute again on the same ChangeRequest. Expected evidence:

- same external reference;
- same `connectorIdempotencyKeyHash`;
- hash starts with `sha256:*`;
- no second sync event;
- no `ConnectorCommand`;
- audit contains replay/idempotent reuse.

## E. Non-Demo Connector Target Policy Block

Attempt or show a non-demo target execution. Expected evidence:

- execution denied before adapter execution;
- audit event `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`;
- no external network call;
- no inventory mutation.

## F. Retryable And Terminal Failure

Show retryable failure metadata when available: failure type, safe failure message, attempt count, last attempt, next retry, retryable label. Show terminal failure when max attempts are reached or failure is non-retryable.

## G. Audit Timeline

Show audit entries for attempt, success, failure, replay, cancellation, and policy block.

## H. Safety Statement

No real ERP/1C writes are performed. No external network connector calls are performed. Stage 9B is demo-only. Production connectors remain disabled until a separate security/runbook acceptance phase.
