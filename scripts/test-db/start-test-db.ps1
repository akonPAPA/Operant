$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$composeFile = Join-Path $repoRoot "infra\docker\docker-compose.test.yml"

Write-Host "Starting OrderPilot test database harness:"
Write-Host "  database: orderpilot_test"
Write-Host "  postgres: localhost:5433"
Write-Host "  redis: localhost:6380"

docker compose -f $composeFile up -d
docker compose -f $composeFile ps
