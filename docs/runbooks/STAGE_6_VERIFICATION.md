# Stage 6 Verification

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

Example local API checks:

```powershell
$tenantId = "00000000-0000-0000-0000-000000000001"
Invoke-RestMethod "http://localhost:8080/api/v1/health"
$case = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/exception-cases/from-validation/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/suggested-fixes/generate/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
$quote = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/draft-quotes/from-validation/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
$order = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/draft-orders/from-validation/REPLACE_WITH_VALIDATION_RUN_ID" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/workspace/draft-quotes/$($quote.id)/approve-internal" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/workspace/timeline?targetType=DRAFT_QUOTE&targetId=$($quote.id)" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/workspace/summary" -Headers @{ "X-Tenant-Id" = $tenantId }
```
