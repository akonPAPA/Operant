param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$BackendUrl = "http://localhost:8080",
  [string]$FrontendUrl = "http://localhost:3000"
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "== $Message =="
}

function Test-Endpoint([string]$Url) {
  try {
    Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 | Out-Null
    return $true
  } catch {
    return $false
  }
}

function Require-Command([string]$Name) {
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "$Name is unavailable on PATH. Open the correct developer shell or install $Name before starting the demo."
  }
  Write-Host "OK: Found $Name"
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

function Get-ListeningPids([int]$Port) {
  $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  if (-not $listeners) { return "" }
  return ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ", "
}

function Show-PortState([string]$Name, [int]$Port) {
  $pids = Get-ListeningPids $Port
  if ($pids) {
    Write-Host "WARN: $Name port $Port is already listening (PID $pids). The script will verify the expected endpoint before starting a new process."
    return
  }
  Write-Host "OK: $Name port $Port is free."
}

function Read-DotEnv([string]$Path) {
  $values = @{}
  if (-not (Test-Path $Path)) {
    return $values
  }
  Get-Content $Path | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $parts = $line.Split("=", 2)
      $values[$parts[0]] = $parts[1]
    }
  }
  return $values
}

function Require-DemoEnv([string]$WebRoot) {
  $envPath = Join-Path $WebRoot ".env.local"
  $examplePath = Join-Path $WebRoot ".env.local.example"
  if (-not (Test-Path $envPath)) {
    throw "Missing $envPath. Copy $examplePath to .env.local and fill local demo values before starting the investor demo."
  }

  $values = Read-DotEnv $envPath
  foreach ($name in @("NEXT_PUBLIC_CORE_API_URL", "NEXT_PUBLIC_DEMO_TENANT_ID", "NEXT_PUBLIC_DEMO_PRODUCT_ID", "NEXT_PUBLIC_DEMO_LOCATION_ID")) {
    if (-not $values[$name]) {
      throw "$name is missing from $envPath."
    }
  }
  if ($values["NEXT_PUBLIC_CORE_API_URL"] -ne "http://localhost:8080") {
    throw "NEXT_PUBLIC_CORE_API_URL must be http://localhost:8080 for the standard local investor demo."
  }
  foreach ($name in @("NEXT_PUBLIC_DEMO_TENANT_ID", "NEXT_PUBLIC_DEMO_PRODUCT_ID", "NEXT_PUBLIC_DEMO_LOCATION_ID")) {
    if ($values[$name] -eq "00000000-0000-0000-0000-000000000000") {
      throw "$name is still set to the placeholder UUID in $envPath."
    }
  }
  Write-Host "OK: Frontend .env.local exists and contains required demo keys."
}

function Test-Postgres([string]$DatasourceUrl) {
  $match = [regex]::Match($DatasourceUrl, "^jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\d+))?/")
  if (-not $match.Success) {
    throw "SPRING_DATASOURCE_URL is not a supported PostgreSQL JDBC URL: $DatasourceUrl"
  }

  $hostName = $match.Groups["host"].Value
  $port = if ($match.Groups["port"].Success) { [int]$match.Groups["port"].Value } else { 5432 }
  $reachable = Test-NetConnection -ComputerName $hostName -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
  if (-not $reachable) {
    throw "Postgres is unreachable at ${hostName}:$port. Start local Postgres or fix SPRING_DATASOURCE_URL before starting core-api."
  }
  Write-Host "OK: Postgres TCP endpoint is reachable at ${hostName}:$port."
}

function Get-EnvOrDefault([string]$Name, [string]$DefaultValue) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ($value) { return $value }
  return $DefaultValue
}

function Get-DefaultDatasourceUrl() {
  $hostPort = Get-EnvOrDefault "ORDERPILOT_DB_HOST_PORT" "55432"
  $databaseName = Get-EnvOrDefault "ORDERPILOT_DB_NAME" "orderpilot_local"
  return "jdbc:postgresql://localhost:${hostPort}/${databaseName}"
}

$resolvedRoot = (Resolve-Path $RepoRoot).Path
$coreRoot = Join-Path $resolvedRoot "apps\core-api"
$webRoot = Join-Path $resolvedRoot "apps\web-dashboard"

Write-Host "OrderPilot local demo startup"
Write-Host "Repository: $resolvedRoot"
Write-Host "This script does not seed production data, call Telegram, call an LLM, write to ERP/1C, execute external connector networks, install dependencies, or use raw secrets."
Write-Host "It opens local backend/frontend processes only after prerequisite checks."
Write-Host "For seeded local demo data, run scripts\seed-local-demo.ps1 first against a local Postgres database."

Write-Step "Checking required tools"
Require-Command "java"
Require-Command "mvn"
Require-Command "node"
Require-Command "npm.cmd"

Write-Step "Checking Docker Compose file"
$composePath = Join-Path $resolvedRoot "infra\docker\docker-compose.yml"
if (Test-Path $composePath) {
  if (Test-DockerComposeAvailable) {
    & docker compose -f $composePath config
    if ($LASTEXITCODE -eq 0) {
      Write-Host "OK: Docker Compose config is valid."
    } else {
      Write-Host "WARN: Docker Compose config validation failed for $composePath. Continue only if PostgreSQL/Redis are available another way."
    }
  } else {
    Write-Host "WARN: Docker Compose is unavailable or not accessible. Start PostgreSQL/Redis another way, or install Docker Desktop with Compose."
  }
} else {
  Write-Host "WARN: No Docker Compose file found at $composePath."
}

Write-Step "Checking frontend dependencies and demo env"
if (-not (Test-Path (Join-Path $webRoot "node_modules"))) {
  throw "npm dependencies are missing. Run: cd $webRoot; npm install"
}
Write-Host "OK: npm dependencies are installed."
Require-DemoEnv $webRoot

Write-Step "Checking ports"
Show-PortState "Backend" 8080
Show-PortState "Frontend" 3000

Write-Step "Checking backend runtime datastore"
$datasourceUrl = [Environment]::GetEnvironmentVariable("SPRING_DATASOURCE_URL")
if (-not $datasourceUrl) { $datasourceUrl = Get-DefaultDatasourceUrl }
Test-Postgres $datasourceUrl

Write-Host ""
Write-Host "Backend:  $BackendUrl"
Write-Host "Frontend: $FrontendUrl/demo"

$backendReady = Test-Endpoint "$BackendUrl/api/v1/health"
$backendPids = Get-ListeningPids 8080
if ($backendReady) {
  Write-Host "Core API already responds at $BackendUrl/api/v1/health"
} elseif ($backendPids) {
  throw "Port 8080 is busy (PID $backendPids), but $BackendUrl/api/v1/health is not responding. Stop the conflicting process or fix the backend before starting a new one."
} else {
  Write-Host "Starting Core API in a hidden PowerShell process..."
  Start-Process powershell -WindowStyle Hidden -ArgumentList @(
    "-ExecutionPolicy", "Bypass",
    "-Command",
    "cd '$coreRoot'; mvn spring-boot:run"
  )
}

$frontendReady = Test-Endpoint "$FrontendUrl/demo"
$frontendPids = Get-ListeningPids 3000
if ($frontendReady) {
  Write-Host "Web dashboard already responds at $FrontendUrl/demo"
} elseif ($frontendPids) {
  throw "Port 3000 is busy (PID $frontendPids), but $FrontendUrl/demo is not responding. Stop the conflicting process or use a different frontend port."
} else {
  Write-Host "Starting web dashboard in a hidden PowerShell process..."
  Start-Process powershell -WindowStyle Hidden -ArgumentList @(
    "-ExecutionPolicy", "Bypass",
    "-Command",
    "cd '$webRoot'; npm.cmd run dev"
  )
}

Write-Host ""
Write-Host "When both services are ready, run:"
Write-Host "powershell -ExecutionPolicy Bypass -File $resolvedRoot\scripts\check-local-demo.ps1"
Write-Host ""
Write-Host "Then open:"
Write-Host "$FrontendUrl/demo"
