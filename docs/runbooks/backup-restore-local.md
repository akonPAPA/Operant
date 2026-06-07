# Local Backup & Restore Runbook (Core v1)

Scope: **local / dev / demo only.** This runbook covers backing up and restoring
the OrderPilot Core v1 local stack so a demo or development database can be
captured before a risky change and restored afterwards.

This is **not** a production cloud backup, HA, point-in-time-recovery, WORM audit
storage, or disaster-recovery design. Those remain future hardening tasks and are
explicitly out of scope here (see `docs/security/stage9-security-hardening.md` and
`docs/security/SECURITY_VERIFICATION_CHECKLIST.md`).

This runbook expands the short "Backup And Restore" checklist bullet in
`docs/runbooks/demo-readiness-checklist.md` into concrete, repeatable steps. For
the local stack itself (ports, services, conventions) see
`docs/runbooks/local-development.md` and `docs/runbooks/test-database.md`.

## What state actually needs backing up

| Component | Backed up here? | Why |
|---|---|---|
| PostgreSQL `orderpilot_local` | **Yes** | System of record: tenant/demo data, business mirror, draft quotes/orders, validation, bot/conversation, reconciliation, audit events, object-storage *pointers*, outbox/ChangeRequest rows. |
| Local object storage (raw files) | **Conditional** | Raw uploaded files live on the filesystem; only back up if your demo needs to re-open the original raw payloads (see below). |
| Redis | **No** | Used as cache / rate-limit / job coordination only. It holds no durable system-of-record state, so it is rebuilt on restart. Do not rely on a Redis dump for local restore. |
| `.env` / secrets / credentials | **Never** | See the secrets warning below. |

### Local connection facts (from `infra/docker/docker-compose.yml`)

- Container name: `orderpilot-postgres` (image `postgres:16-alpine`)
- App/demo database: `orderpilot_local`
- DB user: `orderpilot_local_user` (local-only password; not a production credential)
- Host port: published from `${ORDERPILOT_DB_HOST_PORT}` (commonly `55432`, compose
  default `15432`) → container `5432`. The `docker exec` commands below run **inside**
  the container and are therefore independent of the host port.
- The separate `orderpilot_test` database is for integration tests only. Do **not**
  back it up or restore it as if it were demo data, and never let a restore target it.

> The `docker exec` approach is preferred locally because it does not depend on
> having `pg_dump`/`pg_restore` installed on the Windows host or on the host port.

## Pre-backup checklist

Run from PowerShell at the repo root:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"

# Confirm the stack is up and the DB container is healthy.
docker ps --filter "name=orderpilot-postgres"

# Record the current migration level (so a restore can be verified against it).
docker exec orderpilot-postgres `
  psql -U orderpilot_local_user -d orderpilot_local `
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

Also record, in your demo notes: database name (`orderpilot_local`), the migration
version printed above, the seed script used (`scripts/seed-demo-data/seed-core-v1.ps1`),
and the fixture paths (`apps/core-api/src/test/resources/demo/core-v1-demo/`).

## Backup

### 1. PostgreSQL dump

Use the custom format (`-Fc`); it is compressed and restores cleanly with
`pg_restore`. Write dumps to a local, git-ignored folder.

```powershell
cd "C:\OrderPilot\OrderPilot-Core"

# Local-only backup folder. Keep this OUTSIDE version control (see secrets warning).
$backupDir = "C:\OrderPilot\local-backups"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpFile = "orderpilot_local-$stamp.dump"

# Dump from inside the container, then copy the artifact to the host.
docker exec orderpilot-postgres `
  pg_dump -U orderpilot_local_user -d orderpilot_local -Fc -f "/tmp/$dumpFile"
docker cp "orderpilot-postgres:/tmp/$dumpFile" "$backupDir\$dumpFile"
docker exec orderpilot-postgres rm -f "/tmp/$dumpFile"

Write-Host "Backup written to $backupDir\$dumpFile"
```

### 2. Local object storage (only if raw payloads matter for the demo)

Raw uploaded files are stored on the filesystem under the configured storage root
(`orderpilot.storage.local-root`, default `target/local-object-storage`, partitioned
by tenant). The database stores only the **pointer** (`ObjectStorageRecord`), not the
bytes. If your demo re-opens original raw files, snapshot that directory alongside the
dump:

```powershell
$storageRoot = "C:\OrderPilot\OrderPilot-Core\apps\core-api\target\local-object-storage"
if (Test-Path $storageRoot) {
  Compress-Archive -Path "$storageRoot\*" -DestinationPath "$backupDir\object-storage-$stamp.zip" -Force
}
```

If you do not need original raw payloads (most metric/flow demos), skip this — the
DB pointers remain consistent and the flows still render.

## Restore

> Restoring **drops and recreates** the `orderpilot_local` schema. Confirm you are
> targeting the local app database and never `orderpilot_test`.

### 1. Stop the backend (keep PostgreSQL running)

Stop the running `core-api` process / container so no connections hold the database
during restore. Leave the `postgres` container up.

### 2. Restore the PostgreSQL dump

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
$backupDir = "C:\OrderPilot\local-backups"
$dumpFile  = "orderpilot_local-YYYYMMDD-HHMMSS.dump"   # set to your chosen file

# Copy the dump into the container.
docker cp "$backupDir\$dumpFile" "orderpilot-postgres:/tmp/$dumpFile"

# Clean restore: --clean drops existing objects first; restore is scoped to orderpilot_local.
docker exec orderpilot-postgres `
  pg_restore -U orderpilot_local_user -d orderpilot_local --clean --if-exists "/tmp/$dumpFile"

docker exec orderpilot-postgres rm -f "/tmp/$dumpFile"
```

If you prefer a fully fresh volume instead of `--clean`, use
`scripts\dev\reset-postgres-volume.ps1` first, let the backend run Flyway to recreate
the schema, then restore data — but for a captured snapshot the `--clean` restore
above is simpler and preserves the exact migration state from the dump.

### 3. Restore object storage (only if you archived it)

```powershell
$storageRoot = "C:\OrderPilot\OrderPilot-Core\apps\core-api\target\local-object-storage"
New-Item -ItemType Directory -Force -Path $storageRoot | Out-Null
Expand-Archive -Path "$backupDir\object-storage-YYYYMMDD-HHMMSS.zip" -DestinationPath $storageRoot -Force
```

### 4. Redis

No restore step. Restart Redis if needed; cache/job state is rebuilt at runtime:

```powershell
docker restart orderpilot-redis
```

## Post-restore verification checklist

Run after every restore before trusting the environment for a demo or review:

- [ ] **Backend starts** cleanly and connects to `orderpilot_local` (no datasource or
      Flyway errors in the startup logs).
- [ ] **Migrations are not broken** — the migration level matches the pre-backup record:
      ```powershell
      docker exec orderpilot-postgres `
        psql -U orderpilot_local_user -d orderpilot_local `
        -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
      ```
      Every `success` must be `t`. A failed/pending row means the restore or schema is broken.
- [ ] **Tenant / demo data visible** — the demo tenant and customer load in the dashboard
      `/demo` route (see `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md`) and tenant-scoped
      list endpoints return rows.
- [ ] **Audit records preserved** (if the dump included them) — audit timeline shows
      who/what/when for the demo flows; the audit event count is non-zero and consistent
      with the pre-backup state.
- [ ] **No cross-tenant leakage introduced** — a request for one tenant via `X-Tenant-Id`
      never returns another tenant's rows. The restore must not weaken tenant isolation;
      this is already enforced in code and covered by `TenantIsolationBoundaryTest` and
      `TenantIsolationPostgresIntegrationTest`. Spot-check one list endpoint with two tenant
      ids to confirm the restored data still partitions correctly.
- [ ] **Object storage pointers resolve** (only if raw files matter) — a record's pointer
      maps to a file under the storage root; missing files mean the object-storage archive
      was not restored.

If verification passes, the local environment is restored and demo-safe.

## Failure & rollback notes

- **`pg_restore` reports errors but continues:** with `--clean --if-exists`, "does not
  exist, skipping" notices on a fresh schema are benign. Hard errors (constraint/role/
  encoding) are not — re-run against a freshly reset volume
  (`scripts\dev\reset-postgres-volume.ps1`) and restore again.
- **Migration mismatch after restore:** if `flyway_schema_history` does not match the
  application's expected version, do not "fix" rows by hand. Reset the volume, let the
  backend apply migrations to a clean schema, then restore data from a dump taken at a
  compatible migration level.
- **Restore made the environment worse:** the dump file is immutable, so re-running the
  restore from the original dump is the rollback. Keep at least the last known-good dump
  until the restored environment passes the checklist above.
- **Wrong database targeted:** if you accidentally pointed a restore at `orderpilot_test`,
  treat that test DB as disposable — reset it with the test-db tooling
  (`docs/runbooks/test-database.md`); never copy test data into `orderpilot_local`.

## Environment & secrets warning

- **Never commit** database dumps, object-storage archives, `.env` files, credentials, or
  any real customer data to the repository. Keep `local-backups/` outside the repo (or
  ensure it is git-ignored) and delete dumps when no longer needed.
- The local DB credentials shown here (`orderpilot_local_user` /
  `change-me-local-dev-only`) are **local placeholders only** and must never be reused for
  production, ERP, connector, customer, or payment databases.
- Local demo data is synthetic. If a dump ever contains anything resembling real customer
  data, treat it as sensitive: do not share it and do not store it in cloud sync folders.

## Related docs (canonical sources — not duplicated here)

- `docs/runbooks/local-development.md` — local stack, ports, DB conventions.
- `docs/runbooks/test-database.md` — integration-test DB harness (`orderpilot_test`).
- `docs/runbooks/demo-readiness-checklist.md` — backup/restore checklist bullet this runbook expands.
- `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md` — what the restored data should demonstrate.
- `docs/security/stage9-security-hardening.md`, `docs/security/SECURITY_VERIFICATION_CHECKLIST.md` — production backup/restore is future hardening.
