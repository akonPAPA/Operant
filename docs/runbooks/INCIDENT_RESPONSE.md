# Incident Response Runbook

## First Response

1. Identify tenant, user, endpoint, request time, and affected record IDs.
2. Preserve audit events, connector sync runs, outbox events, and relevant application logs.
3. Freeze risky action paths if the incident involves connector execution, bot abuse, or tenant isolation.
4. Communicate internally with engineering, product, and customer owner using factual status only.

## Suspected Tenant Data Leak

- Disable affected tenant access if exposure is ongoing.
- Query audit events for cross-tenant reads or writes.
- Review controllers/services for missing tenant-scoped repository calls.
- Do not delete evidence.

## Suspected Secret Exposure

- Revoke affected token/credential immediately.
- Rotate any dependent webhook or connector references.
- Search audit metadata, logs, docs, fixtures, and API responses for raw secret values.
- Confirm only placeholder credential refs are exposed in Stage 9B.

## Connector Policy Bypass

- Freeze connector execution routes.
- Search audit for `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`, `CHANGE_REQUEST_EXECUTION_PENDING`, `DEMO_ERP_COMMAND_EXECUTED`, and `DEMO_ERP_IDEMPOTENT_REPLAY`.
- Confirm target system is `DEMO_ERP` and execution mode is `DEMO_ONLY`.
- Engineering review is required before any retry.

## Repeated Connector Failures

- Inspect ChangeRequest attempt count, max attempts, failure type, failure message, last attempt, and next retry.
- Retry only if retryable and under max attempts.
- Terminal failures require new review or engineering assessment.

## Bot Abuse

- Review bot conversation, handoff, response draft, and policy decision audit events.
- Block or rate-limit abusive source if available.
- Do not trust identity claims inside message text.

## Prompt Injection Incident

- Treat source text as hostile evidence.
- Confirm AI output did not approve or mutate business state.
- Review validation, review, and audit records for attempted bypass.

## Communication Notes

- State whether customer data, credentials, connector execution, or external systems were affected.
- Do not speculate about root cause until evidence is preserved.
- Record final remediation, tests added, and prevention follow-up.
