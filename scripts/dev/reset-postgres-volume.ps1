param(
  [switch]$Force
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$composeFile = Join-Path $repoRoot "infra\docker\docker-compose.yml"
$projectName = "docker"
$volumeName = "${projectName}_orderpilot_postgres"

Write-Host "DATA LOSS WARNING" -ForegroundColor Yellow
Write-Host "This deletes the local OrderPilot development PostgreSQL volume only:"
Write-Host "  $volumeName"
Write-Host "It is intended for fixing local role/database mismatches such as:"
Write-Host "  FATAL: role `"postgres`" does not exist"
Write-Host "Do not run this against production or shared data."

if (-not $Force) {
  $confirmation = Read-Host "Type RESET to delete the local dev Postgres volume"
  if ($confirmation -ne "RESET") {
    Write-Host "Aborted. No volumes were deleted."
    exit 0
  }
}

Push-Location $repoRoot
try {
  docker compose -f $composeFile down
  docker volume rm $volumeName
  docker compose -f $composeFile up -d postgres redis
  Write-Host "Local dev Postgres volume was recreated."
  Write-Host "Use: docker exec -it orderpilot-postgres psql -U orderpilot -d orderpilot"
}
finally {
  Pop-Location
}
