$ErrorActionPreference = "Stop"

$databaseName = if ($env:ORDERPILOT_TEST_DB_NAME) { $env:ORDERPILOT_TEST_DB_NAME } else { "orderpilot_test" }
if (-not $databaseName.EndsWith("_test")) {
  throw "Refusing to reset database '$databaseName'. Test database names must end with _test."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$composeFile = Join-Path $repoRoot "infra\docker\docker-compose.test.yml"

Write-Host "Resetting OrderPilot test database harness:"
Write-Host "  database: $databaseName"
Write-Host "  action: docker compose down -v, then up -d"

docker compose -f $composeFile down -v
docker compose -f $composeFile up -d
docker compose -f $composeFile ps
