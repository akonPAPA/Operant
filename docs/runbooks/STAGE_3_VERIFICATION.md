# Stage 3 Verification

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

## Example API requests

```powershell
$tenantId = "00000000-0000-0000-0000-000000000001"
Invoke-RestMethod "http://localhost:8080/api/v1/health"
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/intake/messages" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"channel":"API","externalMessageId":"msg-1","textContent":"Need filters","rawPayload":"{\"source\":\"local\"}"}'
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/webhooks/telegram/demo" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"externalEventId":"tg-1","rawPayload":"{\"message\":{\"text\":\"Need brake pads\"}}"}'
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/intake/documents/api-upload" -Headers @{ "X-Tenant-Id" = $tenantId } -ContentType "application/json" -Body '{"sourceChannel":"API_UPLOAD","documentType":"RFQ","originalFilename":"rfq.txt","contentType":"text/plain","contentBase64":"TmVlZCAxMCBmaWx0ZXJz","receivedFrom":"local-test"}'
```