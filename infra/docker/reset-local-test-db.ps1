# OrderPilot — safe, scoped local test-DB reset + integration-test bring-up (Windows / PowerShell).
#
# Why this exists: host port 55432 falls inside a Windows-reserved TCP range
# (e.g. 55363-55462, reserved by Hyper-V/WSL/Docker Desktop), so Docker cannot bind it.
# Local dev/test now defaults to host port 15432 -> container 5432.
#
# This script ONLY touches the OrderPilot Compose project. It does NOT run a global
# Docker prune and does NOT remove unrelated containers/images/networks/volumes.
#
# Usage:
#   cd C:\OrderPilot\OrderPilot-Core\infra\docker
#   ./reset-local-test-db.ps1                 # reset orderpilot_test + start postgres + run one targeted test
#   ./reset-local-test-db.ps1 -SkipTest       # just reset DB + start postgres
#   ./reset-local-test-db.ps1 -DestroyVolume  # ALSO destroy the local OrderPilot DB volume (local data loss)

param(
  [int]    $HostPort      = 15432,
  [string] $Container     = "orderpilot-postgres",
  [string] $DbAdminUser   = "orderpilot",
  [string] $DbUser        = "orderpilot_local_user",
  [string] $TestDbName    = "orderpilot_test",
  [string] $TestClass     = "AuditIdempotencyPostgresIntegrationTest",
  [switch] $SkipTest,
  [switch] $DestroyVolume
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path $PSScriptRoot "docker-compose.yml"
$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$coreApi     = Join-Path $repoRoot "apps\core-api"

# Pin the host port for both the published mapping and the Spring test datasource fallback.
$env:ORDERPILOT_DB_HOST_PORT      = "$HostPort"
$env:ORDERPILOT_TEST_DB_HOST_PORT = "$HostPort"

Write-Host "== 1. List OrderPilot Compose resources (scoped, read-only) ==" -ForegroundColor Cyan
docker compose -f $composeFile ps
docker volume ls --filter "name=orderpilot" --format "  volume: {{.Name}}"

Write-Host "`n== 2. Stop ONLY OrderPilot Compose services + remove ITS orphans ==" -ForegroundColor Cyan
if ($DestroyVolume) {
  Write-Warning "DestroyVolume set: this removes the local OrderPilot DB volume (LOCAL DB DATA LOSS, OrderPilot only)."
  docker compose -f $composeFile down --remove-orphans --volumes
} else {
  docker compose -f $composeFile down --remove-orphans
}

Write-Host "`n== 3. Start ONLY Postgres on host port $HostPort ==" -ForegroundColor Cyan
docker compose -f $composeFile up -d postgres
docker ps --format "table {{.Names}}`t{{.Ports}}" | Select-String "orderpilot-postgres"

Write-Host "`n== 4. Wait for Postgres health ==" -ForegroundColor Cyan
for ($i = 0; $i -lt 30; $i++) {
  $health = (docker inspect --format "{{.State.Health.Status}}" $Container) 2>$null
  if ($health -eq "healthy") { Write-Host "  postgres healthy"; break }
  Start-Sleep -Seconds 2
}

Write-Host "`n== 5. Verify host port $HostPort is reachable ==" -ForegroundColor Cyan
$tc = Test-NetConnection localhost -Port $HostPort
"  TcpTestSucceeded : $($tc.TcpTestSucceeded)"

Write-Host "`n== 6. Reset ONLY $TestDbName (drop + recreate; no other DB touched) ==" -ForegroundColor Cyan
docker exec -i $Container psql -U $DbAdminUser -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$TestDbName';" | Out-Null
docker exec -i $Container psql -U $DbAdminUser -d postgres -c "DROP DATABASE IF EXISTS $TestDbName;"
docker exec -i $Container psql -U $DbAdminUser -d postgres -c "CREATE DATABASE $TestDbName OWNER $DbUser;"
docker exec -i $Container psql -U $DbAdminUser -d $TestDbName -c "ALTER SCHEMA public OWNER TO $DbUser; GRANT USAGE, CREATE ON SCHEMA public TO $DbUser;"
docker exec -i $Container psql -U $DbUser -d $TestDbName -c "CREATE TABLE __permission_probe(id int); DROP TABLE __permission_probe;"

if ($SkipTest) { Write-Host "`nDone (test skipped)."; return }

Write-Host "`n== 7. Run one targeted integration test ($TestClass) ==" -ForegroundColor Cyan
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$HostPort/$TestDbName"
Push-Location $coreApi
try {
  mvn "-Dspring.profiles.active=integration-test" "-Dtest=$TestClass" test
} finally {
  Pop-Location
}
