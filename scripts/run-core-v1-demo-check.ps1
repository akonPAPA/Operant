param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [switch]$RequireRuntime
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "== $Message =="
}

function Test-Command([string]$Name) {
  if (Get-Command $Name -ErrorAction SilentlyContinue) {
    Write-Host "OK: Found $Name"
    return $true
  }
  Write-Host "WARN: $Name is unavailable on PATH."
  return $false
}

function Test-DockerComposeAvailable() {
  if (-not (Get-Command "docker" -ErrorAction SilentlyContinue)) {
    return $false
  }
  try {
    & docker compose version *> $null
    return $LASTEXITCODE -eq 0
  } catch {
    return $false
  }
}

$resolvedRoot = (Resolve-Path $RepoRoot).Path
$composePath = Join-Path $resolvedRoot "infra\docker\docker-compose.yml"
$checkScript = Join-Path $resolvedRoot "scripts\check-local-demo.ps1"
$runbook = Join-Path $resolvedRoot "docs\runbooks\CORE_V1_LOCAL_DEMO_RUNBOOK.md"

Write-Host "OrderPilot Core v1 local demo check"
Write-Host "Repository: $resolvedRoot"
Write-Host "This wrapper performs local preflight checks only. It does not call Telegram, LLMs, ERP/1C, external connector networks, production seeders, or production profiles."

Write-Step "Prerequisites"
Test-Command "docker" | Out-Null
$hasDockerCompose = Test-DockerComposeAvailable
if ($hasDockerCompose) {
  Write-Host "OK: Docker Compose is available."
} else {
  Write-Host "WARN: Docker Compose is unavailable or not accessible."
}
Test-Command "java" | Out-Null
Test-Command "mvn" | Out-Null
Test-Command "node" | Out-Null
Test-Command "npm.cmd" | Out-Null

Write-Step "Docker Compose"
if (Test-Path $composePath) {
  if ($hasDockerCompose) {
    & docker compose -f $composePath config
    if ($LASTEXITCODE -eq 0) {
      Write-Host "OK: Docker Compose config is valid."
    } elseif ($RequireRuntime) {
      throw "Docker Compose config validation failed for $composePath."
    } else {
      Write-Host "WARN: Docker Compose config validation failed for $composePath. Runtime check required before live demo."
    }
  } else {
    Write-Host "WARN: Docker Compose is unavailable or not accessible; skipping Compose config validation."
  }
} else {
  Write-Host "WARN: Compose file not found at $composePath."
}

Write-Step "Local demo health script"
if (-not (Test-Path $checkScript)) {
  throw "Missing $checkScript"
}

$checkArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $checkScript, "-RepoRoot", $resolvedRoot)
if ($RequireRuntime) { $checkArgs += "-RequireRuntime" }
& powershell @checkArgs
if ($LASTEXITCODE -ne 0) {
  throw "check-local-demo.ps1 exited with code $LASTEXITCODE."
}

Write-Step "Manual commands"
Write-Host "Start local services:"
Write-Host "  docker compose -f infra\docker\docker-compose.yml up -d postgres redis"
Write-Host "Seed local demo data:"
Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seed-local-demo.ps1 -UpdateFrontendEnv"
Write-Host "Start backend:"
Write-Host "  cd apps\core-api; mvn spring-boot:run"
Write-Host "Start frontend:"
Write-Host "  cd apps\web-dashboard; npm.cmd run dev"
Write-Host "Run worker tests:"
Write-Host "  cd apps\ai-worker; .\.venv\Scripts\python.exe -m pytest"
Write-Host "Open dashboard:"
Write-Host "  http://localhost:3000/demo"
Write-Host "Runbook:"
Write-Host "  $runbook"
