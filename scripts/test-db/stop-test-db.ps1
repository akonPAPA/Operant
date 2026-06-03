$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$composeFile = Join-Path $repoRoot "infra\docker\docker-compose.test.yml"

Write-Host "Stopping OrderPilot test database harness. Volumes are preserved."
docker compose -f $composeFile down
