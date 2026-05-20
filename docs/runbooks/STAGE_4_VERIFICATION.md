# Stage 4 Verification

## Backend

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn test
```

## Frontend

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run lint
npm run build
```

## AI worker

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
pytest
```

## Docker

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f "infra/docker/docker-compose.yml" config
docker compose -f "infra/docker/docker-compose.yml" up --build
```

## Example API flow

```powershell
$tenantId = "00000000-0000-0000-0000-000000000001"
Invoke-RestMethod "http://localhost:8080/api/v1/health"

$msg = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/intake/messages" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"channel":"API","externalMessageId":"stage4-msg-1","textContent":"Need 10 EA SKU-001","rawPayload":"{}"}'

$run = Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/extractions/runs" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body (@{ sourceType = "CHANNEL_MESSAGE"; sourceId = $msg.id; providerType = "RULE_BASED" } | ConvertTo-Json)

Invoke-RestMethod "http://localhost:8080/api/v1/extractions/runs/$($run.id)/result" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/extractions/runs/$($run.id)/fields" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/extractions/runs/$($run.id)/line-items" -Headers @{ "X-Tenant-Id" = $tenantId }
```