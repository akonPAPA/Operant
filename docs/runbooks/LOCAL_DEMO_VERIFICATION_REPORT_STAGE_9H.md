# Local Demo Verification Report - Stage 9H

## Metadata

- Date/time: 2026-05-19T15:10:58.4475527+05:00
- Verifier: Codex
- Active repository root: `C:\OrderPilot\OrderPilot-Core`
- Scope: Backend startup unblock after Stage 9G Docker/Postgres recovery.

## Problem Summary

After Docker Desktop recovery in Stage 9G, the Core API could reach PostgreSQL and Flyway could validate/apply migrations, but Spring Boot exited during Hibernate schema validation.

Initial blocker:

```text
Schema-validation: wrong column type encountered in column [default_currency] in table [customer_account];
found [bpchar (Types#CHAR)], but expecting [varchar(255) (Types#VARCHAR)]
```

## Root Cause

Flyway migrations intentionally define ISO-style currency code columns as fixed-width `CHAR(3)`, but several Java entity fields were plain `String` mappings. Hibernate therefore expected `VARCHAR(255)` for those fields during `spring.jpa.hibernate.ddl-auto=validate`.

Confirmed applied/live schema:

- Flyway `V2` is already applied in the local database.
- `customer_account.default_currency` is live as `character(3)` / `bpchar`.
- Other applied fixed-width currency columns are:
  - `product.currency`
  - `price_rule.currency`
  - `extracted_line_item.currency`

## Files Inspected

- `apps\core-api\src\main\java\com\orderpilot\domain\customer\CustomerAccount.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\product\Product.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\pricing\PriceRule.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\extraction\ExtractedLineItem.java`
- `apps\core-api\src\main\resources\db\migration\V2__data_foundation_import_mirror.sql`
- `apps\core-api\src\main\resources\db\migration\V4__ai_assisted_understanding.sql`
- Later Flyway migrations touching `customer_account`: `V3`, `V5`, `V6`; none changed `customer_account.default_currency`.
- `apps\core-api\src\main\resources\application.yml`
- `scripts\seed-local-demo.ps1`
- `scripts\start-local-demo.ps1`
- `scripts\check-local-demo.ps1`
- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9G.md`

## Exact Code Change

Aligned entity mappings to the already-applied Flyway schema for the fixed-width currency code columns:

- Added `@Size(min = 3, max = 3)` for three-character currency-code validation.
- Added `@JdbcTypeCode(SqlTypes.CHAR)` so Hibernate validates against PostgreSQL `bpchar` / `CHAR`.
- Added `@Column(... length = 3, columnDefinition = "char(3)")` on the fixed-width currency fields.

Changed fields:

- `CustomerAccount.defaultCurrency`
- `Product.currency`
- `PriceRule.currency`
- `ExtractedLineItem.currency`

No Flyway migration was added because the live database schema already matched the applied migrations. No applied migration was edited.

## Why This Is Safe

- Preserves existing data.
- Does not drop, truncate, or reset any table.
- Does not run `docker compose down -v`.
- Does not weaken Hibernate validation.
- Does not disable Flyway.
- Does not change business services, AI logic, frontend routes, investor demo UI, security checks, tenant isolation, audit behavior, or write-path rules.
- Matches the existing schema intent: ISO-style currency codes are three characters.

## Commands Run and Results

```powershell
git status --short --branch
rg -n "class CustomerAccount|defaultCurrency|default_currency|customer_account" apps\core-api\src\main\java apps\core-api\src\main\resources\db\migration apps\core-api\src\test scripts docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9G.md scripts\start-local-demo.ps1 scripts\check-local-demo.ps1
rg -n "ddl-auto|hibernate|flyway|datasource" apps\core-api\src\main\resources apps\core-api\pom.xml
Get-Content apps\core-api\src\main\java\com\orderpilot\domain\customer\CustomerAccount.java
Get-Content apps\core-api\src\main\resources\db\migration\V2__data_foundation_import_mirror.sql
Get-Content apps\core-api\src\main\resources\application.yml
rg -n "CHAR\(3\)|currency" apps\core-api\src\main\resources\db\migration
rg -n "private String currency|currency;|defaultCurrency" apps\core-api\src\main\java\com\orderpilot\domain
docker compose exec -T postgres psql -U orderpilot_app -d orderpilot -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank; select column_name, data_type, character_maximum_length, udt_name from information_schema.columns where table_name='customer_account' and column_name='default_currency';"
```

DB inspection result:

- Flyway versions `1` through `8` applied successfully.
- `customer_account.default_currency`: `data_type=character`, `character_maximum_length=3`, `udt_name=bpchar`.

Test command:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn test
```

Result:

- Initial sandboxed attempt failed because Maven could not reach/cache dependencies from Maven Central.
- Rerun with approved access passed.
- Final result: `Tests run: 48, Failures: 0, Errors: 0, Skipped: 0`.

Runtime command:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

Result:

- First fix attempt with `columnDefinition` only still failed because Hibernate expected `Types#VARCHAR`.
- Final fix with explicit `@JdbcTypeCode(SqlTypes.CHAR)` stayed running until the command timeout, with no schema validation failure.

Startup command:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
```

Result:

- PASS.
- Core API already responded at `http://localhost:8080/api/v1/health`.
- Web dashboard already responded at `http://localhost:3000/demo`.

Full demo check:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
```

Result:

- PASS.
- Backend port `8080` listening.
- Frontend port `3000` listening.
- Core API health returned HTTP 200.
- Demo Telegram RFQ webhook returned HTTP 200.
- Demo inventory reconciliation run returned HTTP 200.
- Demo reconciliation cases returned HTTP 200.
- Demo commerce analytics summary returned HTTP 200.
- Dashboard routes returned HTTP 200:
  - `/demo`
  - `/command-center`
  - `/inbox`
  - `/bot-conversations`
  - `/bot/conversations`
  - `/reconciliation`
  - `/analytics`
  - `/audit-log`
  - `/integrations`

Secret scan:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1
```

Result:

- PASS: `No obvious hardcoded secrets found.`

## Backend Startup Result

PASS. The Core API now stays running and responds at:

```text
http://localhost:8080/api/v1/health
```

## Full Local Demo Verification Result

PASS. `check-local-demo.ps1 -AllowFixtureMode` passed all required backend, frontend, route, and demo API probes.

## Changed Files

- `apps\core-api\src\main\java\com\orderpilot\domain\customer\CustomerAccount.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\product\Product.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\pricing\PriceRule.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\extraction\ExtractedLineItem.java`
- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9H.md`

## Final Status

`PASS`

The backend schema/entity mismatch is fixed without destructive database actions, and the full local demo verification now passes.

## Remaining Blockers

No blocker remains for the local demo flow covered by Stage 9H.

Known non-blocking condition from Stage 9G remains true: native `psql` is not on PATH, so `seed-local-demo.ps1` uses the safe Docker Compose psql fallback when seeding locally.
