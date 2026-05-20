# Verification Checklist

## Backend

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn test
```

Expected:

- Health endpoint test passes.
- Tenant context placeholder test passes.
- AuditEvent service test passes.

## Frontend

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run lint
npm run build
```

Expected:

- TypeScript/Next.js build passes.
- Lint passes if dependencies are installed.

## AI worker

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
pytest
```

Expected:

- Mock processing returns advisory-only extraction.
- Schema validation rejects invalid confidence.
- Forbidden mutation task is rejected.

## Docker

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f "infra/docker/docker-compose.yml" config
docker compose -f "infra/docker/docker-compose.yml" up --build
```