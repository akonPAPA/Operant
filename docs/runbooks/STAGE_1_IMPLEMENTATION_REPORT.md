# Stage 1 Implementation Report

## 1. STATUS

PASS_WITH_MANUAL_STEPS

Reason: the Stage 1 repository, code, migrations, docs, Docker Compose file, and workflow definitions exist. Direct AI worker smoke verification passed with the bundled Python runtime. Full Maven, frontend npm, Docker Compose, and Java 21 verification could not be executed in this shell because the required local tools are unavailable or not on PATH.

## 2. Summary

Built the OrderPilot Core Stage 1 foundation:

- Java 21 Spring Boot 3.x core-api with `GET /api/v1/health`.
- PostgreSQL and Flyway configuration.
- Initial platform migration for `tenant`, `user_account`, `role`, `permission`, `user_role`, `role_permission`, `audit_event`, and `idempotency_key`.
- Tenant context placeholder using `X-Tenant-Id`.
- AuditEvent service and repository.
- Structured API error response model.
- Next.js TypeScript dashboard shell with required B2B SaaS navigation pages.
- Python 3.12 AI worker skeleton with advisory-only inbound document processing.
- Windows connector placeholder with outbound-only and read-only default constraints.
- Docker Compose for postgres, redis, core-api, web-dashboard, and ai-worker.
- GitHub Actions workflow definitions.
- Architecture, security, product, and runbook docs derived from the markdown source-of-truth.

## 3. File tree

```text
OrderPilot-Core/
  apps/
    core-api/
    web-dashboard/
    ai-worker/
    windows-connector-agent-placeholder/
  packages/
    domain-contracts/
    integration-sdk/
    test-fixtures/
  infra/
    docker/
    terraform-placeholder/
    github-actions/
    observability-placeholder/
  docs/
    architecture/
    security/
    product/
    runbooks/
    adr/
  scripts/
    local-dev/
    seed-demo-data/
  .github/
    workflows/
```

## 4. Files changed or created

Stage 1 created the implementation repository at:

`C:\OrderPilot\OrderPilot-Core`

Important created files:

- `README.md`
- `.env.example`
- `apps/core-api/pom.xml`
- `apps/core-api/src/main/java/com/orderpilot/OrderPilotApplication.java`
- `apps/core-api/src/main/java/com/orderpilot/api/rest/HealthController.java`
- `apps/core-api/src/main/java/com/orderpilot/common/tenant/TenantContext.java`
- `apps/core-api/src/main/java/com/orderpilot/common/tenant/TenantContextFilter.java`
- `apps/core-api/src/main/java/com/orderpilot/application/services/AuditEventService.java`
- `apps/core-api/src/main/resources/db/migration/V1__platform_foundation.sql`
- `apps/web-dashboard/app/(dashboard)/*/page.tsx`
- `apps/web-dashboard/components/dashboard-shell.tsx`
- `apps/web-dashboard/public/.gitkeep`
- `apps/ai-worker/orderpilot_ai_worker/tasks/process_inbound_document.py`
- `apps/windows-connector-agent-placeholder/README.md`
- `infra/docker/docker-compose.yml`
- `.github/workflows/backend.yml`
- `.github/workflows/frontend.yml`
- `.github/workflows/ai-worker.yml`
- `.github/workflows/verify.yml`
- `docs/architecture/FOUNDATION_DECISIONS.md`
- `docs/architecture/ADR-0001-core-architecture.md`
- `docs/security/SECURITY_BASELINE.md`
- `docs/security/security-principles.md`
- `docs/product/STAGE_1_SCOPE.md`
- `docs/product/core-v1-scope.md`
- `docs/runbooks/local-development.md`
- `docs/runbooks/verification-checklist.md`
- `docs/runbooks/source-markdown-inventory.md`
- `docs/runbooks/STAGE_1_IMPLEMENTATION_REPORT.md`

## 5. How to run locally

### Docker Compose

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
Copy-Item ".env.example" ".env"
docker compose -f "infra/docker/docker-compose.yml" up --build
```

### Backend

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn spring-boot:run
```

Health check:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/health"
```

### Frontend

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run dev
```

Open:

```powershell
Start-Process "http://localhost:3000"
```

### Python worker

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
python -m orderpilot_ai_worker.main
```

## 6. How to test

### Backend tests

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn test
```

### Frontend lint and build

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run lint
npm run build
```

### Python worker tests

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
pytest
```

### Docker Compose validation

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f "infra/docker/docker-compose.yml" config
```

## 7. Verification performed in this shell

Passed:

- Repository tree exists at the requested path.
- Required docs exist.
- `pom.xml` parses as XML.
- `package.json` and `tsconfig.json` parse as JSON.
- Frontend `@/*` TypeScript alias is present.
- Docker Compose file contains required service definitions.
- AI worker direct smoke verification passed with bundled Python 3.12 and Pydantic.

Blocked by local environment:

- `mvn test`: Maven is not available in PATH.
- Backend Java 21 execution: system `java` is Java 8, not Java 21.
- `npm install`, `npm run lint`, `npm run build`: npm is not available in PATH.
- `pytest`: pytest is not installed in the bundled Python runtime.
- `docker compose`: Docker is not available in PATH.

## 8. Security verification

- AI worker has no direct business DB write path.
- AI worker marks extraction output as advisory only.
- AI worker rejects known forbidden business mutation task names.
- Frontend has no DB access or database dependency.
- Core API owns the future mutation path.
- Tenant isolation placeholder exists through `TenantContext` and `TenantContextFilter`.
- Audit service exists and records tenant-scoped audit events.
- External writes are not implemented and are documented as requiring future ChangeRequest and approval.

## 9. Known limitations

- Full authentication is not implemented in Stage 1.
- RBAC/ABAC tables exist, but enforcement is not yet implemented.
- Tenant isolation is a placeholder, not full policy enforcement.
- Audit append-only is enforced at application convention level, not yet by database trigger or restricted DB role.
- OpenAPI generation is not yet wired.
- No product/customer/inventory/import mirror data model exists yet.
- No real OCR/LLM provider is integrated.
- No Telegram, WhatsApp, email, ERP, 1C, or Excel connector is implemented.
- Frontend pages are empty states by design.

## 10. Next recommended stage

Stage 2 - Data Foundation and Import Mirror.