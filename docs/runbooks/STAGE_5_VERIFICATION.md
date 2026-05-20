# Stage 5 Verification

Use these commands from Windows PowerShell:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f "infra/docker/docker-compose.yml" config
docker compose -f "infra/docker/docker-compose.yml" up --build
```

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn test
```

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run lint
npm run build
```

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
pytest
```

Example API checks:

```powershell
$tenantId = "00000000-0000-0000-0000-000000000001"
Invoke-RestMethod "http://localhost:8080/api/v1/health"
$run = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/validations/runs" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"extractionResultId":"REPLACE_WITH_EXTRACTION_RESULT_ID","mode":"FULL"}'
Invoke-RestMethod "http://localhost:8080/api/v1/validations/runs/$($run.id)/summary" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/validations/runs/$($run.id)/issues" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/validations/runs/$($run.id)/substitute-candidates" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/validations/runs/$($run.id)/approval-requirements" -Headers @{ "X-Tenant-Id" = $tenantId }
```
