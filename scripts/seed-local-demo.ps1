param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$DatasourceUrl = $env:SPRING_DATASOURCE_URL,
  [string]$Username = $env:SPRING_DATASOURCE_USERNAME,
  [string]$Credential = $env:SPRING_DATASOURCE_PASSWORD,
  [switch]$UpdateFrontendEnv
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "== $Message =="
}

$DemoTenantId = "11111111-1111-4111-8111-111111111111"
$DemoCustomerId = "22222222-2222-4222-8222-222222222222"
$DemoLocationId = "33333333-3333-4333-8333-333333333333"
$DemoPrimaryProductId = "44444444-4444-4444-8444-444444444444"
$DemoSubstituteAId = "55555555-5555-4555-8555-555555555555"
$DemoSubstituteBId = "66666666-6666-4666-8666-666666666666"

function Convert-JdbcToPsql([string]$Url) {
  if (-not $Url) { $Url = "jdbc:postgresql://localhost:55432/orderpilot" }
  $match = [regex]::Match($Url, "^jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\d+))?/(?<db>[^?]+)")
  if (-not $match.Success) {
    throw "Unsupported PostgreSQL JDBC URL: $Url"
  }
  $port = if ($match.Groups["port"].Success) { $match.Groups["port"].Value } else { "5432" }
  return @{
    Host = $match.Groups["host"].Value
    Port = $port
    Database = $match.Groups["db"].Value
  }
}

function Write-FrontendEnv([string]$WebRoot) {
  $envPath = Join-Path $WebRoot ".env.local"
  $content = @"
# Local investor demo runtime values. Demo-safe; no real secrets.
NEXT_PUBLIC_CORE_API_URL=http://localhost:8080
NEXT_PUBLIC_DEMO_TENANT_ID=$DemoTenantId
NEXT_PUBLIC_DEMO_PRODUCT_ID=$DemoPrimaryProductId
NEXT_PUBLIC_DEMO_LOCATION_ID=$DemoLocationId
"@
  Set-Content -Path $envPath -Value $content -Encoding UTF8
  Write-Host "OK: Wrote demo-safe frontend env to $envPath"
}

function Get-ComposeFilePath([string]$Root) {
  $composePath = Join-Path $Root "infra\docker\docker-compose.yml"
  if (Test-Path $composePath) {
    return $composePath
  }
  return $null
}

function Test-DockerComposePostgres([string]$ComposePath) {
  if (-not $ComposePath) { return $false }
  if (-not (Get-Command "docker" -ErrorAction SilentlyContinue)) { return $false }

  try {
    & docker compose -f $ComposePath ps --status running postgres | Out-String | Out-Null
    if ($LASTEXITCODE -ne 0) { return $false }
    return $true
  } catch {
    return $false
  }
}

function Invoke-SeedWithLocalPsql([hashtable]$Connection, [string]$DbUsername, [string]$DbCredential, [string]$Sql) {
  $tempSql = Join-Path ([System.IO.Path]::GetTempPath()) "orderpilot-stage9f-seed-local-demo.sql"
  Set-Content -Path $tempSql -Value $Sql -Encoding UTF8

  try {
    if ($DbCredential) { $env:PGPASSWORD = $DbCredential } # non-production local database credential forwarding
    & psql -h $Connection.Host -p $Connection.Port -U $DbUsername -d $Connection.Database -v ON_ERROR_STOP=1 -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "psql exited with code $LASTEXITCODE" }
  } finally {
    Remove-Item -Path $tempSql -Force -ErrorAction SilentlyContinue
    if ($DbCredential) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
  }
}

function Invoke-SeedWithDockerComposePsql([string]$ComposePath, [hashtable]$Connection, [string]$DbUsername, [string]$Sql) {
  if ($Connection.Host -notin @("localhost", "127.0.0.1", "::1")) {
    throw "Local psql is unavailable and Docker Compose fallback is only safe for a localhost datasource. Current host: $($Connection.Host)."
  }

  Write-Host "WARN: psql is unavailable on PATH. Using repo-defined Docker Compose postgres service for local demo seeding."
  $Sql | & docker compose -f $ComposePath exec -T postgres psql -U $DbUsername -d $Connection.Database -v ON_ERROR_STOP=1
  if ($LASTEXITCODE -ne 0) {
    throw "docker compose postgres psql exited with code $LASTEXITCODE"
  }
}

$resolvedRoot = (Resolve-Path $RepoRoot).Path
$webRoot = Join-Path $resolvedRoot "apps\web-dashboard"
$connection = Convert-JdbcToPsql $DatasourceUrl
if (-not $Username) { $Username = "orderpilot" }

Write-Host "OrderPilot local demo seed"
Write-Host "Repository: $resolvedRoot"
Write-Host "This script is deterministic and local-only. It does not call Telegram, LLMs, ERP/1C, external connector networks, or production seeders."
Write-Host "It uses fixed demo UUIDs and SQL upserts / guarded inserts for repeatable local runs."

$sql = @"
BEGIN;

INSERT INTO tenant (id, slug, legal_name, status)
VALUES ('$DemoTenantId', 'orderpilot-demo-parts-distributor', 'OrderPilot Demo Parts Distributor', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
  slug = EXCLUDED.slug,
  legal_name = EXCLUDED.legal_name,
  status = EXCLUDED.status,
  updated_at = now();

INSERT INTO location (id, tenant_id, code, name, type, city, country, active)
VALUES ('$DemoLocationId', '$DemoTenantId', 'ALM-MAIN', 'Almaty Main Warehouse', 'WAREHOUSE', 'Almaty', 'KZ', true)
ON CONFLICT (id) DO UPDATE SET
  code = EXCLUDED.code,
  name = EXCLUDED.name,
  type = EXCLUDED.type,
  city = EXCLUDED.city,
  country = EXCLUDED.country,
  active = EXCLUDED.active,
  updated_at = now();

INSERT INTO customer_account (id, tenant_id, external_ref, account_code, legal_name, display_name, status, default_currency, default_location_id)
VALUES ('$DemoCustomerId', '$DemoTenantId', 'DEMO-CUST-001', 'ALMATY-AUTO', 'Almaty Auto Service LLP', 'Almaty Auto Service', 'ACTIVE', 'KZT', '$DemoLocationId')
ON CONFLICT (id) DO UPDATE SET
  external_ref = EXCLUDED.external_ref,
  account_code = EXCLUDED.account_code,
  legal_name = EXCLUDED.legal_name,
  display_name = EXCLUDED.display_name,
  status = EXCLUDED.status,
  default_currency = EXCLUDED.default_currency,
  default_location_id = EXCLUDED.default_location_id,
  updated_at = now();

INSERT INTO product (id, tenant_id, sku, name, description, category, brand, manufacturer, base_uom, status, cost, currency)
VALUES
  ('$DemoPrimaryProductId', '$DemoTenantId', 'TOY-CAM-2018-BPAD-OE', 'Original brake pads for Toyota Camry 2018', 'OEM-grade front brake pad set for Toyota Camry 2018', 'Brake System', 'Toyota Genuine', 'Toyota', 'PCS', 'ACTIVE', 18000.00, 'KZT'),
  ('$DemoSubstituteAId', '$DemoTenantId', 'AFT-CAM-2018-BPAD-A', 'Aftermarket compatible substitute A', 'Aftermarket brake pad set compatible with Toyota Camry 2018', 'Brake System', 'RoadMax', 'RoadMax Parts', 'PCS', 'ACTIVE', 12500.00, 'KZT'),
  ('$DemoSubstituteBId', '$DemoTenantId', 'AFT-CAM-2018-BPAD-B', 'Budget aftermarket compatible substitute B', 'Budget aftermarket brake pad set compatible with Toyota Camry 2018', 'Brake System', 'SteppeLine', 'SteppeLine Components', 'PCS', 'ACTIVE', 9800.00, 'KZT')
ON CONFLICT (id) DO UPDATE SET
  sku = EXCLUDED.sku,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  category = EXCLUDED.category,
  brand = EXCLUDED.brand,
  manufacturer = EXCLUDED.manufacturer,
  base_uom = EXCLUDED.base_uom,
  status = EXCLUDED.status,
  cost = EXCLUDED.cost,
  currency = EXCLUDED.currency,
  updated_at = now();

INSERT INTO product_alias (tenant_id, product_id, alias_type, raw_alias, normalized_alias, customer_account_id, confidence_default)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', 'CUSTOMER_TEXT', 'brake pads for Toyota Camry 2018', 'BRAKE PADS TOYOTA CAMRY 2018', '$DemoCustomerId', 0.9500
WHERE NOT EXISTS (
  SELECT 1 FROM product_alias
  WHERE tenant_id = '$DemoTenantId'
    AND product_id = '$DemoPrimaryProductId'
    AND normalized_alias = 'BRAKE PADS TOYOTA CAMRY 2018'
    AND active = true
);

INSERT INTO inventory_movement (tenant_id, product_id, location_id, movement_type, quantity, occurred_at, source_type, source_reference)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoLocationId', 'OPENING_STOCK', 150, '2026-05-01T08:00:00Z', 'DEMO_SEED', 'DEMO-OPENING-001'
WHERE NOT EXISTS (SELECT 1 FROM inventory_movement WHERE tenant_id = '$DemoTenantId' AND source_type = 'DEMO_SEED' AND source_reference = 'DEMO-OPENING-001');

INSERT INTO inventory_movement (tenant_id, product_id, location_id, movement_type, quantity, occurred_at, source_type, source_reference)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoLocationId', 'SALE', 34, '2026-05-03T10:00:00Z', 'DEMO_SEED', 'DEMO-SALE-001'
WHERE NOT EXISTS (SELECT 1 FROM inventory_movement WHERE tenant_id = '$DemoTenantId' AND source_type = 'DEMO_SEED' AND source_reference = 'DEMO-SALE-001');

INSERT INTO inventory_movement (tenant_id, product_id, location_id, movement_type, quantity, occurred_at, source_type, source_reference)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoLocationId', 'ACTUAL_STOCK_COUNT', 100, '2026-05-04T18:00:00Z', 'DEMO_SEED', 'DEMO-COUNT-001'
WHERE NOT EXISTS (SELECT 1 FROM inventory_movement WHERE tenant_id = '$DemoTenantId' AND source_type = 'DEMO_SEED' AND source_reference = 'DEMO-COUNT-001');

COMMIT;
"@

$psqlCommand = Get-Command "psql" -ErrorAction SilentlyContinue
Write-Step "Applying deterministic local seed"
if ($psqlCommand) {
  Invoke-SeedWithLocalPsql $connection $Username $Credential $sql
} else {
  $composePath = Get-ComposeFilePath $resolvedRoot
  if (-not (Test-DockerComposePostgres $composePath)) {
    throw "psql is unavailable on PATH and the repo-defined Docker Compose postgres service is not running. Start it with: docker compose -f $resolvedRoot\infra\docker\docker-compose.yml up -d postgres"
  }
  Invoke-SeedWithDockerComposePsql $composePath $connection $Username $sql
}

if ($UpdateFrontendEnv) {
  Write-FrontendEnv $webRoot
}

Write-Host "OK: Local demo seed is present."
Write-Host "Demo tenant id:   $DemoTenantId"
Write-Host "Demo product id:  $DemoPrimaryProductId"
Write-Host "Demo location id: $DemoLocationId"
