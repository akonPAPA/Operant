param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$BackendUrl = "http://localhost:8080",
  [string]$FrontendUrl = "http://localhost:3000",
  [switch]$AllowFixtureMode,
  [switch]$RequireRuntime
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "== $Message =="
}

function Add-Failure([System.Collections.Generic.List[string]]$Failures, [string]$Message) {
  $Failures.Add($Message)
  Write-Host "FAIL: $Message"
}

function Add-Warning([string]$Message) {
  Write-Host "WARN: $Message"
}

function Add-RuntimeIssue([System.Collections.Generic.List[string]]$Failures, [string]$Message) {
  if ($RequireRuntime) {
    Add-Failure $Failures $Message
  } else {
    Add-Warning "$Message Manual check required when runtime services are not started."
  }
}

function Test-CommandAvailable([string]$Name, [System.Collections.Generic.List[string]]$Failures) {
  if (Get-Command $Name -ErrorAction SilentlyContinue) {
    Write-Host "OK: Found $Name"
    return
  }
  Add-Failure $Failures "$Name is unavailable on PATH. Install it or open a shell with the correct developer environment."
}

function Test-HttpEndpoint([string]$Name, [string]$Url, [System.Collections.Generic.List[string]]$Failures) {
  Write-Host "Checking $Name at $Url"
  try {
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
    if ($response.StatusCode -ne 200) {
      Add-Failure $Failures "$Name returned HTTP $($response.StatusCode), expected 200."
      return
    }
    Write-Host "OK: $Name returned HTTP $($response.StatusCode)"
  } catch {
    Add-RuntimeIssue $Failures "$Name is not reachable at $Url. $($_.Exception.Message)"
  }
}

function Invoke-DockerComposeConfig([string]$ComposePath) {
  & docker compose -f $ComposePath config
  return $LASTEXITCODE
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

function Test-ApiEndpoint([string]$Name, [string]$Url, [string]$Method, [hashtable]$Headers, [string]$Body, [System.Collections.Generic.List[string]]$Failures) {
  Write-Host "Checking $Name at $Method $Url"
  try {
    $parameters = @{
      Uri = $Url
      Method = $Method
      Headers = $Headers
      UseBasicParsing = $true
      TimeoutSec = 5
    }
    if ($Body) {
      $parameters["Body"] = $Body
      $parameters["ContentType"] = "application/json"
    }
    $response = Invoke-WebRequest @parameters
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
      Add-Failure $Failures "$Name returned HTTP $($response.StatusCode), expected 2xx."
      return
    }
    Write-Host "OK: $Name returned HTTP $($response.StatusCode)"
  } catch {
    Add-RuntimeIssue $Failures "$Name is not reachable at $Url. $($_.Exception.Message)"
  }
}

function Test-Port([string]$Name, [int]$Port, [bool]$ShouldListen, [System.Collections.Generic.List[string]]$Failures) {
  $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  if ($ShouldListen) {
    if ($listeners) {
      $pids = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ", "
      Write-Host "OK: $Name port $Port is listening (PID $pids)."
    } else {
      Add-RuntimeIssue $Failures "$Name is not listening on localhost:$Port."
    }
    return
  }

  if ($listeners) {
    $pids = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ", "
    Add-Warning "$Name port $Port is already in use (PID $pids). This is fine only if it is the intended OrderPilot service."
  } else {
    Write-Host "OK: $Name port $Port is free."
  }
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

function Get-EnvValue([hashtable]$Values, [string]$Name) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if (-not $value) { $value = $Values[$Name] }
  return $value
}

function Test-DemoUuid([string]$Name, [string]$Value, [System.Collections.Generic.List[string]]$Failures, [bool]$AllowFixtureMode) {
  $placeholder = "00000000-0000-0000-0000-000000000000"
  if ($Value -and $Value -match "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$" -and $Value -ne $placeholder) {
    Write-Host "OK: $Name is configured."
    return
  }

  $message = "$Name is missing or still set to the placeholder UUID. Set it in apps\web-dashboard\.env.local for full button-driven demo readiness."
  if ($AllowFixtureMode) {
    Add-Warning "$message Fixture-only visual mode is allowed for this run."
  } else {
    Add-Failure $Failures $message
  }
}

function Get-JdbcHostPort([string]$JdbcUrl) {
  $match = [regex]::Match($JdbcUrl, "^jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\d+))?/")
  if (-not $match.Success) {
    return $null
  }
  return @{
    Host = $match.Groups["host"].Value
    Port = if ($match.Groups["port"].Success) { [int]$match.Groups["port"].Value } else { 5432 }
  }
}

$failures = [System.Collections.Generic.List[string]]::new()
$resolvedRoot = (Resolve-Path $RepoRoot).Path
$webRoot = Join-Path $resolvedRoot "apps\web-dashboard"
$coreRoot = Join-Path $resolvedRoot "apps\core-api"
$envPath = Join-Path $webRoot ".env.local"
$envExamplePath = Join-Path $webRoot ".env.local.example"
$buildPath = Join-Path $webRoot ".next"

Write-Host "OrderPilot local demo runtime check"
Write-Host "Repository: $resolvedRoot"
Write-Host "No production seeding, Telegram outbound calls, LLM calls, ERP/1C writes, external connector network calls, payment integrations, or dependency upgrades are performed."
Write-Host "Runtime checks are warnings unless -RequireRuntime is supplied."

Write-Step "Required tools"
Test-CommandAvailable "java" $failures
Test-CommandAvailable "mvn" $failures
Test-CommandAvailable "node" $failures
Test-CommandAvailable "npm.cmd" $failures

Write-Step "Repository layout"
foreach ($path in @($webRoot, $coreRoot, (Join-Path $resolvedRoot "docs\runbooks\LOCAL_DEMO_RUNBOOK.md"))) {
  if (Test-Path $path) {
    Write-Host "OK: Found $path"
  } else {
    Add-Failure $failures "Missing required path: $path"
  }
}

Write-Step "Docker Compose"
$composePath = Join-Path $resolvedRoot "infra\docker\docker-compose.yml"
if (Test-Path $composePath) {
  if (Test-DockerComposeAvailable) {
    $composeExitCode = Invoke-DockerComposeConfig $composePath
    if ($LASTEXITCODE -eq 0) {
      Write-Host "OK: Docker Compose config is valid."
    } else {
      Add-RuntimeIssue $failures "Docker Compose config validation failed for $composePath."
    }
  } else {
    Add-Warning "Docker Compose is unavailable or not accessible. Compose config validation skipped."
  }
} else {
  Add-Failure $failures "Missing Docker Compose file: $composePath"
}

Write-Step "Frontend dependencies and environment"
if (Test-Path (Join-Path $webRoot "node_modules")) {
  Write-Host "OK: npm dependencies are installed in apps\web-dashboard\node_modules."
} else {
  Add-Failure $failures "npm dependencies are missing. Run: cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm install"
}

if (Test-Path $envPath) {
  Write-Host "OK: Found $envPath"
  $envValues = Read-DotEnv $envPath
} else {
  $envValues = @{}
  Add-RuntimeIssue $failures "Missing $envPath. Copy $envExamplePath to .env.local and fill seeded demo UUIDs."
}

$coreApiUrl = Get-EnvValue $envValues "NEXT_PUBLIC_CORE_API_URL"
if ($coreApiUrl -eq "http://localhost:8080") {
  Write-Host "OK: NEXT_PUBLIC_CORE_API_URL points to http://localhost:8080."
} elseif ($coreApiUrl) {
  Add-Warning "NEXT_PUBLIC_CORE_API_URL is '$coreApiUrl'. That may be intentional, but the standard local demo uses http://localhost:8080."
} else {
  Add-RuntimeIssue $failures "NEXT_PUBLIC_CORE_API_URL is missing. Set it to http://localhost:8080 in apps\web-dashboard\.env.local."
}

foreach ($name in @("NEXT_PUBLIC_DEMO_TENANT_ID", "NEXT_PUBLIC_DEMO_PRODUCT_ID", "NEXT_PUBLIC_DEMO_LOCATION_ID")) {
  Test-DemoUuid $name (Get-EnvValue $envValues $name) $failures ([bool]$AllowFixtureMode)
}

$demoTenantId = Get-EnvValue $envValues "NEXT_PUBLIC_DEMO_TENANT_ID"
$demoProductId = Get-EnvValue $envValues "NEXT_PUBLIC_DEMO_PRODUCT_ID"
$demoLocationId = Get-EnvValue $envValues "NEXT_PUBLIC_DEMO_LOCATION_ID"
$placeholderUuid = "00000000-0000-0000-0000-000000000000"
$validUuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
$fullDemoIdsConfigured =
  $demoTenantId -match $validUuidPattern -and $demoTenantId -ne $placeholderUuid -and
  $demoProductId -match $validUuidPattern -and $demoProductId -ne $placeholderUuid -and
  $demoLocationId -match $validUuidPattern -and $demoLocationId -ne $placeholderUuid

Write-Step "Backend runtime prerequisites"
$datasourceUrl = [Environment]::GetEnvironmentVariable("SPRING_DATASOURCE_URL")
if (-not $datasourceUrl) { $datasourceUrl = "jdbc:postgresql://localhost:55432/orderpilot" }
$datasourceUsername = [Environment]::GetEnvironmentVariable("SPRING_DATASOURCE_USERNAME")
if (-not $datasourceUsername) { $datasourceUsername = "orderpilot" }
$datasourceCredential = [Environment]::GetEnvironmentVariable("SPRING_DATASOURCE_PASSWORD")

Write-Host "Runtime datasource URL: $datasourceUrl"
Write-Host "Runtime datasource username: $datasourceUsername"
if (-not $datasourceCredential) {
  Add-Warning "SPRING_DATASOURCE_PASSWORD is not set in this shell. The backend will use its local default from application.yml unless overridden."
}

$jdbc = Get-JdbcHostPort $datasourceUrl
if ($jdbc) {
  $postgresReachable = Test-NetConnection -ComputerName $jdbc.Host -Port $jdbc.Port -InformationLevel Quiet -WarningAction SilentlyContinue
  if ($postgresReachable) {
    Write-Host "OK: Postgres TCP endpoint is reachable at $($jdbc.Host):$($jdbc.Port)."
  } else {
    Add-RuntimeIssue $failures "Postgres is unreachable at $($jdbc.Host):$($jdbc.Port). Start local Postgres or fix SPRING_DATASOURCE_URL before backend runtime."
  }
} else {
  Add-Failure $failures "SPRING_DATASOURCE_URL is not a supported PostgreSQL JDBC URL: $datasourceUrl"
}

Write-Step "Build output"
if (Test-Path $buildPath) {
  Write-Host "OK: Found Next.js build output at $buildPath"
} else {
  Add-Warning "No .next build output found. Run npm run build before a formal investor demo."
}

Write-Step "Ports"
Test-Port "Backend" 8080 $true $failures
Test-Port "Frontend" 3000 $true $failures

Write-Step "HTTP probes"
Test-HttpEndpoint "Core API health" "$BackendUrl/api/v1/health" $failures

Write-Step "Demo API probes"
if ($fullDemoIdsConfigured) {
  $headers = @{ "X-Tenant-Id" = $demoTenantId }
  $messageSuffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $rfqPayload = @{
    update_id = [int](910000 + ($messageSuffix % 100000))
    message = @{
      message_id = [int](700000 + ($messageSuffix % 100000))
      chat = @{ id = 450001 }
      text = "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty."
    }
  } | ConvertTo-Json -Depth 5 -Compress
  Test-ApiEndpoint "Demo Telegram RFQ webhook" "$BackendUrl/api/v1/bot/telegram/webhook" "POST" $headers $rfqPayload $failures
  $reconciliationPayload = @{ productId = $demoProductId; locationId = $demoLocationId } | ConvertTo-Json -Compress
  Test-ApiEndpoint "Demo inventory reconciliation run" "$BackendUrl/api/v1/reconciliation/inventory/run" "POST" $headers $reconciliationPayload $failures
  Test-ApiEndpoint "Demo reconciliation cases" "$BackendUrl/api/v1/reconciliation/cases?page=0&size=50" "GET" $headers $null $failures
  Test-ApiEndpoint "Demo commerce analytics summary" "$BackendUrl/api/v1/analytics/commerce/summary" "GET" $headers $null $failures
} elseif ($AllowFixtureMode) {
  Add-Warning "Demo API probes that require seeded tenant/product/location IDs were skipped because fixture-only visual mode is allowed."
} else {
  Add-RuntimeIssue $failures "Demo API probes were skipped because seeded tenant/product/location IDs are not configured."
}

Write-Step "Key dashboard routes"
$routes = @(
  "/demo",
  "/command-center",
  "/inbox",
  "/bot-conversations",
  "/bot/conversations",
  "/reconciliation",
  "/analytics",
  "/audit-log",
  "/integrations"
)
foreach ($route in $routes) {
  Test-HttpEndpoint "Frontend route $route" "$FrontendUrl$route" $failures
}

if ($failures.Count -gt 0) {
  Write-Host ""
  Write-Host "Local demo check failed with $($failures.Count) issue(s):"
  foreach ($failure in $failures) {
    Write-Host "- $failure"
  }
  Write-Host ""
  Write-Host "See docs\runbooks\LOCAL_DEMO_RUNBOOK.md for startup and troubleshooting."
  exit 1
}

Write-Host ""
if ($RequireRuntime) {
  Write-Host "Local demo check passed. Backend runtime, frontend runtime, env config, and key routes are ready."
} else {
  Write-Host "Local demo preflight passed. Runtime-only checks may have been reported as warnings; rerun with -RequireRuntime before a live demo."
}
