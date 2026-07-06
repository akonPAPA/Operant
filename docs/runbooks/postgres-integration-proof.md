# PostgreSQL Integration Proof (PR #248)

> **H2 green is NOT PostgreSQL proof.** The default backend test profile
> (`apps/core-api/src/test/resources/application-test.yml`) runs on H2 with
> `spring.flyway.enabled: false` and `jpa.hibernate.ddl-auto: create-drop`, so the schema is generated
> from JPA entities — Flyway migrations, real constraints, real foreign keys, `TIMESTAMPTZ`, and
> Postgres-only locking semantics are **not** exercised. Several production-critical behaviors only hold
> under real PostgreSQL. This runbook is the command of record for proving them.

## What this proof covers

The suite lives in `apps/core-api/src/test/java/com/orderpilot/integration/testdb/` and is gated by the
`@RequiresPostgresIntegration` marker (opt-in system property `orderpilot.postgres.integration.enabled=true`
+ the `integration-test` Spring profile in `application-integration-test.yml`, which keeps external
execution / connectors / bot outbound disabled). Mapping to the PR #248 required proof areas:

| Area | Proof | Class |
| --- | --- | --- |
| A. Migrations apply cleanly | 66 Flyway migrations apply to an empty Postgres DB, expected tables exist | `PostgresMigrationSmokeIntegrationTest` |
| B. Tenant-scoped lookups | product/customer lookups correct per tenant; wrong tenant returns empty | `TenantIsolationPostgresIntegrationTest` |
| C. Idempotency uniqueness | `idempotency_key` unique per-tenant only; duplicate same-tenant key rejected | `AuditIdempotencyPostgresIntegrationTest`, `FulfillmentSignalIdempotencyPostgresIntegrationTest` |
| D. Lock / retry / reaper | `FOR UPDATE SKIP LOCKED` worker claim + result drain concurrency | `WorkerClaimConcurrencyPostgresIntegrationTest`, `WorkerResultDrainConcurrencyPostgresIntegrationTest` (**Testcontainers → Docker required; skipped when absent**) |
| E. Bounded Pageable | tenant-scoped, bounded, deterministically ordered tracking-link / quote-review reads | `OrderJourneyTrackingLinkPostgresIntegrationTest`, `QuoteReviewPostgresIntegrationTest` |
| F. Audit / outbox persistence | tenant-scoped audit rows; denied path creates no external-write/outbox state | `AuditIdempotencyPostgresIntegrationTest`, `RfqHandoffRealDemoPostgresIntegrationTest` |
| G. Support grant persistence + support audit | staff + grant rows persist (UUID/`TIMESTAMPTZ`/enum-as-`VARCHAR`); tenant/scope-scoped queries; expiry + approval gating; support-plane **allow AND deny audit persists with a `staff_user` actor** (post-V66); `audit_event_actor_id_fkey` proven dropped | `SupportGrantPersistencePostgresIntegrationTest` (**added in #248; fixes PG-248-01**) |
| H. RFQ / AI / runtime path | safe terminal demo state persists; `externalExecution=DISABLED`, connector `NOT_INVOKED`; runtime-plan single-active invariant | `RfqHandoffRealDemoPostgresIntegrationTest`, `RuntimePlanInvariantPostgresIntegrationTest` |

## Prerequisites

A real PostgreSQL (16+; validated on 18.4) reachable at the datasource in `application-integration-test.yml`.
Defaults: host port `15432`, db `orderpilot_test`, user `orderpilot_local_user`, and the `pgcrypto`
extension (for `gen_random_uuid()`). Override with `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` or the
`ORDERPILOT_TEST_DB_*` env vars.

### Option 1 — Docker Compose (preferred when Docker is available)

```bash
docker compose -f infra/docker/docker-compose.test.yml up -d postgres-test
# postgres:16-alpine on localhost:15432, db/user/pwd from the compose defaults, pgcrypto seeded by init-test-db.sql
```

### Option 2 — Local PostgreSQL binaries (used for the #248 proof; Docker was unavailable)

```bash
PGBIN="/c/Program Files/PostgreSQL/18/bin"
PGDATA=/path/to/throwaway/pgdata            # e.g. a scratch dir, NOT a real cluster
"$PGBIN/initdb.exe" -D "$PGDATA" -U postgres --auth=trust -E UTF8 --no-locale
"$PGBIN/pg_ctl.exe" -D "$PGDATA" -o "-p 15432 -c listen_addresses=localhost" -w start
"$PGBIN/psql.exe" -h localhost -p 15432 -U postgres -d postgres \
  -c "CREATE ROLE orderpilot_local_user LOGIN PASSWORD 'change-me-local-dev-only';" \
  -c "CREATE DATABASE orderpilot_test OWNER orderpilot_local_user;"
"$PGBIN/psql.exe" -h localhost -p 15432 -U postgres -d orderpilot_test \
  -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" -c "GRANT ALL ON SCHEMA public TO orderpilot_local_user;"
# teardown when done:  "$PGBIN/pg_ctl.exe" -D "$PGDATA" -m immediate stop  &&  rm -rf "$PGDATA"
```

Flyway migrates the empty DB on first run; the tests use `spring.test.database.replace=none` and reset
data between tests with `@Sql(/db/testdata/clean.sql, ...)`.

## Exact command

From `apps/core-api`:

```bash
# Full Postgres integration suite
mvn -Dorderpilot.postgres.integration.enabled=true -Dtest="com.orderpilot.integration.testdb.*" test

# A single area (example: the #248 support-grant persistence proof)
mvn -Dorderpilot.postgres.integration.enabled=true -Dtest=SupportGrantPersistencePostgresIntegrationTest test
```

Never pass `-DskipTests` / `-Dmaven.test.skip`. Without `orderpilot.postgres.integration.enabled=true`
the classes are disabled (they do not silently pass — JUnit reports them as skipped by condition).

## Expected successful output shape

Per-class `Tests run: N, Failures: 0, Errors: 0`. The last verified run (PostgreSQL 18.4, local cluster):

```
PostgresMigrationSmokeIntegrationTest           Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0
TenantIsolationPostgresIntegrationTest          Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0
AuditIdempotencyPostgresIntegrationTest         Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0
FulfillmentSignalIdempotencyPostgresIntegrationTest  Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
OrderJourneyTrackingLinkPostgresIntegrationTest Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0
QuoteReviewPostgresIntegrationTest              Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0
RfqHandoffRealDemoPostgresIntegrationTest       Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0
RuntimePlanInvariantPostgresIntegrationTest     Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0
SupportGrantPersistencePostgresIntegrationTest  Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0
WorkerClaimConcurrencyPostgresIntegrationTest   Tests run: 5,  Failures: 0, Errors: 0, Skipped: 5   (Testcontainers/Docker absent)
WorkerResultDrainConcurrencyPostgresIntegrationTest  Tests run: 5, Failures: 0, Errors: 0, Skipped: 5   (Testcontainers/Docker absent)
```

Totals: **35 passed, 0 failures, 0 errors, 10 skipped.** The migration log prints
`Successfully applied 66 migrations to schema "public", now at version v66` (or `applied 1 migration … now
at version v66` when V66 lands incrementally on an existing v65 schema).

## PG-248-01 — found and fixed in PR #248

The proof surfaced a real Postgres-only defect: `audit_event.actor_id` carried a Flyway FK
`REFERENCES user_account(id)` (H2 never creates it, because the H2 test profile builds the schema from JPA
entities). The Operant support plane records audits with the acting **`staff_user`** id (a separate identity
domain), so every support-plane audit write — **allow AND deny** — violated that FK on real Postgres and
aborted the action (denials were unauditable).

**Fix:** `V66__audit_event_actor_id_polymorphic_principal.sql` drops the FK and documents `actor_id` as an
opaque/polymorphic principal id spanning multiple identity domains (tenant user, staff user, service account,
connector/bot/worker, system/runtime). The column, its data, and the audit indexes are preserved. This is
now positively proven on Postgres by `SupportGrantPersistencePostgresIntegrationTest`
(`supportServiceAuditWritesPersistForStaffActorsUnderPostgres`,
`auditActorIdFkToUserAccountIsDroppedAndAcceptsStaffAndTenantActors`).

## What is intentionally NOT proven here

- **Lock/retry/reaper (area D)** requires Docker (Testcontainers). When Docker is down those two classes
  **skip** (they do not fail). Run them with Docker available to complete area D.
- **First-class audit actor provenance:** `actor_id` is now correctly polymorphic but does NOT encode the
  actor domain as a column. A stronger forensic model (`actor_principal_type`/`actor_source`) is deferred —
  see `docs/backlog/fix-notebook.md` **PG-248-02** (P2, non-blocking).
- Full application boot against Postgres, browser/E2E, full CI, and production readiness are out of scope.

## Troubleshooting

- **`Could not find a valid Docker environment`** (Worker*Concurrency): Docker Desktop / daemon is not
  running. Start it, or accept those two classes as skipped.
- **`Connection refused` / `password authentication failed`**: Postgres is not on `15432`, or the
  role/db/password do not match `application-integration-test.yml`. Re-check the prerequisites, or set
  `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`.
- **`function gen_random_uuid() does not exist`**: `CREATE EXTENSION pgcrypto;` was not run on the test DB.
- **Tests report "skipped" with no DB activity**: you forgot
  `-Dorderpilot.postgres.integration.enabled=true`.
- **`audit_event_actor_id_fkey` violation**: should no longer occur after V66. If it reappears, a migration
  re-introduced the FK — do not "fix" it by seeding fake `user_account` rows; `actor_id` is polymorphic
  (see PG-248-01 and the V66 migration comment).
- **`staff_user` handle uniqueness on repeat runs**: `clean.sql` now truncates `staff_user` explicitly (the
  OP-CAP-51+ support/incident tables that carry a `tenant` FK are already cleared via `tenant … CASCADE`).
  The #248 test additionally uses random handles for defense-in-depth.
