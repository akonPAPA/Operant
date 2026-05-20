# Security Review Runbook

## Run Tests

From `C:\OrderPilot\OrderPilot-Core\apps\core-api`:

```powershell
mvn clean test
```

Expected Stage 9 baseline: all tests pass, including bot safety, tenant isolation, reconciliation, audit, and documentation checks.

## Inspect Changed Files

From `C:\OrderPilot\OrderPilot-Core`:

```powershell
git status --short
git diff --stat
```

Review new files under:
- `apps/core-api/src/main/java/com/orderpilot/application/services`
- `apps/core-api/src/main/java/com/orderpilot/domain`
- `apps/core-api/src/main/java/com/orderpilot/api/rest`
- `apps/core-api/src/main/resources/db/migration`
- `docs/security`
- `docs/runbooks`
- `docs/investor`

## Check For Hardcoded Secrets

Run:

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\scripts\check-no-secrets.ps1
```

The script flags obvious non-placeholder patterns such as bot tokens, passwords, secrets, API keys, and private keys.

## Verify Migrations

Check migration order:

```powershell
Get-ChildItem src/main/resources/db/migration
```

Review new migrations for:
- tenant_id on tenant-owned tables;
- indexes on tenant and time/query dimensions;
- no destructive DDL without explicit approval;
- no direct ERP or external system writes.

## Review Audit-Critical Code Paths

Audit-critical paths currently include:
- Stage 7 bot message receipt and RFQ draft creation;
- Stage 7 human handoff creation;
- Stage 8 reconciliation case creation/update;
- Stage 8 reconciliation case status update;
- Stage 6 internal approval/workspace actions.

For each path, verify:
- controller delegates to service;
- service uses `TenantContext.requireTenantId()`;
- repository queries are tenant-scoped;
- important actions call `AuditEventService.record(...)`;
- no controller performs raw SQL or business mutation logic.

## Known Limitations

- Tenant resolution is still a header-based placeholder, not production authentication.
- Audit append-only is enforced by service convention and tests, not by DB trigger/policy yet.
- Webhook replay protection is deterministic for persisted Telegram chat/message IDs, but signed webhook verification and replay windows remain TODO.
- No production rate limiting exists yet.
- File upload hardening is documented but not production-complete.
- External ERP writes are intentionally absent.
