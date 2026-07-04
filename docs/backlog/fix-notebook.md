# Fix Notebook

## P2 Product Decision — unify the legacy demo RFQ entrypoint with the channel handoff workspace

- Status: PARTIAL — implementation and bounded H2/frontend proof pass; live browser + PostgreSQL
  proof remains pending because the local PostgreSQL listener was unavailable.
- Severity: P2 / Product Decision
- Files:
  - `apps/web-dashboard/lib/demo-api.ts`
  - `apps/web-dashboard/app/api/demo/rfq-handoff/route.ts`
  - `apps/core-api/src/main/java/com/orderpilot/api/rest/DemoRfqHandoffController.java`
  - `apps/core-api/src/main/java/com/orderpilot/application/services/channel/LocalDemoRfqIntakeService.java`
- Root cause: the `/demo` button posts to `/api/v1/bot/telegram/webhook`, which creates the legacy
  bot RFQ/review records. The RFQ handoff workspace reads `channel_rfq_handoff`, which is created by
  the managed channel/bot bridge path. The two visible surfaces are not connected.
- Risk: a user can receive a successful “RFQ requires human review” response on `/demo`, then see no
  pending item in `/channels/rfq-handoffs`.
- Resolution: the browser now posts an empty request to a same-origin demo BFF. The BFF resolves the
  local demo tenant from server-only configuration; core resolves the operator, fixed demo Telegram
  connection, stable provider event identity, handoff state, deduplication, and audit, then delegates
  to the managed channel/bot bridge. The production Telegram webhook controller was not changed.
- Required proof/tests:
  - browser click on **Send demo Telegram RFQ** creates exactly one tenant-scoped handoff;
  - replay creates no duplicate event, bot RFQ, or handoff;
  - cross-tenant and spoofed source/connection attempts are denied;
  - no raw provider payload or internal connection ID is returned;
  - no quote approval, connector command, ChangeRequest, or outbox event is created.
- Proof:
  - `LocalDemoRfqIntakeServiceTest` proves one event, one bot RFQ, one `PENDING_REVIEW` handoff,
    stable replay, cross-tenant denial, and zero connector command/sandbox execution/ChangeRequest/
    outbox rows.
  - `DemoRfqHandoffControllerAuthorityBoundaryTest` proves `ADMIN_SETTINGS_MANAGE`, backend-owned
    actor resolution, spoofed body authority ignored, safe response fields, `STAFF_SUPPORT_READ`
    denial, and no service invocation on denial.
  - `DemoRfqHandoffControllerProductionGateTest.productionLikeRuntimeDeniesBeforeIntakeAndLeavesRfqTablesUntouched`
    proves a production-like profile is denied even when the endpoint flag is enabled, with no
    intake-service or inbound-event/bot-RFQ/channel-handoff repository interaction.
  - `apps/web-dashboard/tests/rfq-handoffs.test.mjs` proves the empty browser request, server-side
    tenant/permission injection, managed core path, bounded response contract, and redacted errors.
  - `RfqHandoffRealDemoPostgresIntegrationTest` contains the connection-only PostgreSQL creation and
    replay case; execution remains pending (`localhost:15432` refused the integration connection).
- Residual proof to close: start the documented disposable PostgreSQL instance, pass the two-test
  integration command, then perform the documented double-click browser walkthrough and confirm
  exactly one `PENDING_REVIEW` row.
- Owner/target week: Product/API owner; target week not assigned.
- docs/api/quote-transactions.md:13
- commit b16b993db1c944555a356c7c77725292d6608dd6
- RuleID: generic-api-key
## P2 — Historical gitleaks finding in docs/api/quote-transactions.md

Severity:
P2 / Security Hygiene

Status:
OPEN / TRIAGE REQUIRED

Found by:
gitleaks git-history scan during proof/post-pr239-real-demo-proof.

Location:
docs/api/quote-transactions.md:13

Historical commit:
b16b993db1c944555a356c7c77725292d6608dd6

Finding:
generic-api-key pattern detected by gitleaks. Secret value is redacted in scan output.

Root cause:
Historical documentation example or committed token-like value requires classification.

Risk:
If this was a real API key or token, deleting it from current files is insufficient; the credential must be revoked/rotated. If it was a fake example, it should be rewritten to an obvious placeholder and optionally allowlisted only after review.

Suggested fix:

1. Inspect the historical/current line without exposing the value in chat.
2. Classify as real secret or fake/example.
3. If real: revoke/rotate immediately.
4. If fake: rewrite as `<example-api-key>` / `<redacted>` and document false-positive classification.
5. Consider a narrow `.gitleaksignore` only after confirming it is non-secret.

Required proof/tests:

- gitleaks current-tree scan clean.
- gitleaks history finding classified.
- No real secret present in current branch diff.
