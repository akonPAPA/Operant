# Local Development Runbook

## Stage 9I Local Demo Baseline

For the local investor demo on Windows, launch Docker Desktop first and start only the repo-defined infrastructure:

```powershell
cd "C:\OrderPilot\OrderPilot-Core\infra\docker"
docker compose up -d postgres redis
docker compose ps
```

Then seed, start, and verify from the repo root:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1
```

Native `psql` is optional for localhost demo seeding. If it is unavailable, the seed script uses the repo-defined Docker Compose `postgres` service as a safe fallback. Do not delete Docker volumes during normal local demo recovery.

## Start all services with Docker Compose

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
Copy-Item ".env.example" ".env"
docker compose -f "infra/docker/docker-compose.yml" up --build
```

Core API health:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/health"
```

Web dashboard:

```powershell
Start-Process "http://localhost:3000"
```

## Backend only

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn spring-boot:run
```

## Frontend only

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run dev
```

## AI worker only

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
python -m orderpilot_ai_worker.main
```
