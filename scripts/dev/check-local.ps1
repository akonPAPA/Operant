param(
  [switch]$CleanFrontendInstall
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeFile = Join-Path $repoRoot "infra\docker\docker-compose.yml"
$coreApiDir = Join-Path $repoRoot "apps\core-api"
$webDashboardDir = Join-Path $repoRoot "apps\web-dashboard"
$aiWorkerDir = Join-Path $repoRoot "apps\ai-worker"

function Write-Section([string]$Title) {
  Write-Host ""
  Write-Host "== $Title ==" -ForegroundColor Cyan
}

function Invoke-Native {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $true)][string[]]$Arguments,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory
  )

  Push-Location $WorkingDirectory
  try {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
      throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($Arguments -join ' ')"
    }
  }
  finally {
    Pop-Location
  }
}

function Resolve-RequiredCommand([string]$Name, [string]$InstallHint) {
  $command = Get-Command $Name -ErrorAction SilentlyContinue
  if (-not $command) {
    throw "$Name is not available on PATH. $InstallHint"
  }
  return $command.Source
}

function Resolve-NpmCommand {
  $npmCmd = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
  if ($npmCmd) { return $npmCmd.Source }

  $npm = Get-Command "npm" -ErrorAction SilentlyContinue
  if ($npm) { return $npm.Source }

  throw "npm is not available on PATH. Install Node.js, then run npm install in apps\web-dashboard if dependencies are missing."
}

Write-Host "OrderPilot local parity check"
Write-Host "Repo root: $repoRoot"
Write-Host "This script does not delete volumes, create .env files, or modify business data."
if ($CleanFrontendInstall) {
  Write-Host "Clean frontend install mode is enabled: npm ci will refresh apps\web-dashboard\node_modules."
}

Write-Section "Tooling"
$docker = Resolve-RequiredCommand "docker" "Install and start Docker Desktop."
$mvn = Resolve-RequiredCommand "mvn" "Install Maven 3.9+ and Java 21."
$npm = Resolve-NpmCommand
Invoke-Native $docker @("--version") $repoRoot
Invoke-Native $docker @("compose", "version") $repoRoot

Write-Section "Docker Compose Config"
Invoke-Native $docker @("compose", "-f", $composeFile, "config") $repoRoot

Write-Section "Start Local Infrastructure"
Invoke-Native $docker @("compose", "-f", $composeFile, "up", "-d", "postgres", "redis") $repoRoot

Write-Section "Compose Status"
Invoke-Native $docker @("compose", "-f", $composeFile, "ps") $repoRoot

Write-Section "Postgres Readiness"
Invoke-Native $docker @("exec", "orderpilot-postgres", "pg_isready", "-U", "orderpilot", "-d", "orderpilot") $repoRoot

Write-Section "Postgres Identity"
Invoke-Native $docker @("exec", "orderpilot-postgres", "psql", "-U", "orderpilot", "-d", "orderpilot", "-c", "select current_user, current_database();") $repoRoot

Write-Section "Backend Tests"
$previousSpringProfilesActive = [Environment]::GetEnvironmentVariable("SPRING_PROFILES_ACTIVE")
try {
  $env:SPRING_PROFILES_ACTIVE = "test"
  Invoke-Native $mvn @("test") $coreApiDir
}
finally {
  if ($null -eq $previousSpringProfilesActive) {
    Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
  }
  else {
    $env:SPRING_PROFILES_ACTIVE = $previousSpringProfilesActive
  }
}

if ($CleanFrontendInstall) {
  Write-Section "Frontend Clean Install"
  Invoke-Native $npm @("ci") $webDashboardDir
}

Write-Section "Frontend Lint"
Invoke-Native $npm @("run", "lint") $webDashboardDir

Write-Section "Frontend Build"
Invoke-Native $npm @("run", "build") $webDashboardDir

Write-Section "Frontend Tests"
Invoke-Native $npm @("test") $webDashboardDir

Write-Section "AI Worker Tests"
$venvPython = Join-Path $aiWorkerDir ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPython)) {
  throw @"
AI worker virtual environment is missing.
Create it first:
  cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
  py -3.12 -m venv .venv
  .\.venv\Scripts\Activate.ps1
  python -m pip install -e ".[dev]"
Then rerun:
  powershell -ExecutionPolicy Bypass -File ".\scripts\dev\check-local.ps1"
"@
}
Invoke-Native $venvPython @("-m", "pytest") $aiWorkerDir

Write-Section "Done"
Write-Host "Local parity check passed." -ForegroundColor Green
