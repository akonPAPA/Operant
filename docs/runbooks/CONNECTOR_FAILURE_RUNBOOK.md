# Connector Failure Runbook

Stage 9B connector failures are local Demo ERP adapter failures only. No real ERP/1C writes or external connector network calls are performed.

## Status Interpretation

- `EXECUTION_PENDING`: a demo execution attempt is being recorded.
- `EXECUTED`: demo execution succeeded and stored an external reference.
- `FAILED`: demo execution failed. Check failure type, message, attempt count, and retryable flag.
- `CANCELLED`: operator/admin stopped the ChangeRequest before successful execution.
- `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`: execution was denied before adapter execution.

## Retryable Failure

Safe to retry only when:

- approval status is `APPROVED`;
- execution status is `FAILED`;
- retryable is true;
- attempt count is below max attempts;
- target remains `DEMO_ERP`.

Manual retry must be audited. Retry must not mutate inventory or create `ConnectorCommand`.

## Terminal Failure

Do not retry when:

- retryable is false;
- max attempts reached;
- request is rejected or cancelled;
- target is non-demo;
- source is not an approved validation-backed draft quote/order.

Terminal failures require operator review or engineering review before a new ChangeRequest is created.

## Idempotent Replay

If an already executed ChangeRequest is executed again:

- return the existing external reference;
- keep the same `sha256:*` idempotency hash;
- do not create a second sync event;
- do not create a `ConnectorCommand`;
- audit `DEMO_ERP_IDEMPOTENT_REPLAY` with `replay:true` and `networkCall:false`.

## Policy-Blocked Execution

Non-demo targets, unapproved requests, cancelled requests, unsupported sources, and failed requests without explicit retry must be blocked before adapter execution. Review the audit timeline for `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`.

## Sync Run Failure

Inspect sync run status, error code, safe error message, ChangeRequest ID, and tenant. Sync run failures are evidence only; do not manually edit external references or inventory records.
