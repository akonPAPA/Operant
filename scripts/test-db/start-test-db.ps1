$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$composeFile = Join-Path $repoRoot "infra\docker\docker-compose.test.yml"
$databaseName = if ($env:ORDERPILOT_TEST_DB_NAME) { $env:ORDERPILOT_TEST_DB_NAME } else { "orderpilot_test" }
$hostPort = if ($env:ORDERPILOT_TEST_DB_HOST_PORT) { $env:ORDERPILOT_TEST_DB_HOST_PORT } else { "55432" }
$redisPort = if ($env:REDIS_TEST_PORT) { $env:REDIS_TEST_PORT } else { "6380" }

Write-Host "Starting OrderPilot test database harness:"
Write-Host "  database: $databaseName"
Write-Host "  postgres: localhost:$hostPort"
Write-Host "  redis: localhost:$redisPort"

docker compose -f $composeFile up -d
docker compose -f $composeFile ps
