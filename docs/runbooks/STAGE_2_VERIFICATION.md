# Stage 2 Verification

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

## Docker Compose

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f "infra/docker/docker-compose.yml" config
docker compose -f "infra/docker/docker-compose.yml" up --build
```

## Migration verification

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn spring-boot:run
Invoke-RestMethod "http://localhost:8080/api/v1/health"
```