# Test Database Harness

## Purpose

Stage 6.1 uses a reproducible PostgreSQL harness for integration tests that need real database behavior: Flyway migrations, PostgreSQL constraints, tenant-scoped data, audit rows, idempotency uniqueness, and quote-review read-model checks.

The existing `test` profile remains H2-backed for legacy unit and slice tests. PostgreSQL integration tests use the dedicated `integration-test` profile.

## Services

The test harness is defined in `infra/docker/docker-compose.test.yml`.

- PostgreSQL: `localhost:5433`
- Redis: `localhost:6380`
- Database: `orderpilot_test`
- Username: `orderpilot_test`
- Password: `orderpilot_test`

These are test-only credentials and must not be reused for production, demo, ERP, or connector databases.

## Commands

From the repository root:

```powershell
.\scripts\test-db\start-test-db.ps1
```

Equivalent Docker Compose command:

```powershell
docker compose -f infra\docker\docker-compose.test.yml up -d
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
jdbc:postgresql://localhost:5433/orderpilot_test
```

Environment overrides are available through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

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
