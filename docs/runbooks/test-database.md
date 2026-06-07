# Test Database Harness

## Purpose

Stage 6.1 uses a reproducible PostgreSQL harness for integration tests that need real database behavior: Flyway migrations, PostgreSQL constraints, tenant-scoped data, audit rows, idempotency uniqueness, and quote-review read-model checks.

The existing `test` profile remains H2-backed for legacy unit and slice tests. PostgreSQL integration tests use the dedicated `integration-test` profile.

## Services

The default local test database is created by the main Compose `postgres` service in `infra/docker/docker-compose.yml`.

- PostgreSQL: `localhost:55432`
- Redis: `localhost:6380`
- Database: `orderpilot_test`
- Username: `orderpilot_local_user`
- Password: `change-me-local-dev-only`

These are local-only credentials and must not be reused for production, ERP, connector, customer, or payment databases. The test database is separate from `orderpilot_local`; test cleanup truncates test tables and must not target the app/demo database.

## Commands

From the Compose directory:

```powershell
cd "C:\OrderPilot\OrderPilot-Core\infra\docker"
docker compose up -d postgres redis
Test-NetConnection localhost -Port 55432
```

The optional `infra/docker/docker-compose.test.yml` file is only for starting an isolated test database stack when the main Compose stack is not being used. If you use it, keep `ORDERPILOT_TEST_DB_HOST_PORT` aligned with the Maven environment for that shell.

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
.\scripts\test-db\start-test-db.ps1
```

Reset the test database volume:

```powershell
.\scripts\test-db\reset-test-db.ps1
```

The reset script refuses database names that do not end with `_test`.

Stop the harness while preserving the test volume:

```powershell
.\scripts\test-db\stop-test-db.ps1
```

Equivalent Docker Compose command:

```powershell
docker compose -f infra\docker\docker-compose.test.yml down
```

## Running Integration Tests

From `apps/core-api`:

```powershell
mvn test "-Dspring.profiles.active=integration-test" "-Dtest=*IntegrationTest"
```

The `integration-test` profile reads:

```text
jdbc:postgresql://localhost:55432/orderpilot_test
```

Environment overrides are available through `ORDERPILOT_TEST_DB_NAME`, `ORDERPILOT_TEST_DB_USER`, `ORDERPILOT_TEST_DB_PASSWORD`, `ORDERPILOT_TEST_DB_HOST_PORT`, or through explicit `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

Do not debug Maven integration-test failures until the expected PostgreSQL port is reachable:

```powershell
Test-NetConnection localhost -Port 55432
```

## Fixtures

Deterministic SQL fixtures live under:

```text
apps/core-api/src/test/resources/db/testdata
```

The fixture set includes:

- tenant A and tenant B
- users and roles
- customers and locations
- products, aliases, inventory, pricing, discounts, and margins
- quote conversion attempts covering pre-draft review, linked draft quote, and tenant isolation

`clean.sql` truncates all public tables except `flyway_schema_history` so migrations stay intact.

## Scope Boundaries

This harness does not change production datasource behavior. It does not make H2 the main integration database, add Testcontainers, write to ERP/1C, execute connector commands, add AI worker behavior, or mutate operator review workflows.

Known limitations:

- Docker Compose must be available before PostgreSQL integration tests can run.
- Existing `@ActiveProfiles("test")` tests continue using H2 unless they are explicitly moved to `integration-test`.
- The Redis service is present for future integration parity, but the Stage 6.1 verification tests focus on PostgreSQL.
- Existing local Docker volumes initialized before this contract may need the documented local reset path before `orderpilot_test` exists in the main `postgres` container.
