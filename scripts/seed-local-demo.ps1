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
$DemoOperatorUserId = "00000000-0000-4000-8000-000000000002"
$DemoChannelConnectionId = "99999999-9999-4999-8999-999999999901"
$DemoInboundChannelEventId = "99999999-9999-4999-8999-999999999902"
$DemoRfqHandoffId = "99999999-9999-4999-8999-999999999903"
$DemoCustomerId = "22222222-2222-4222-8222-222222222222"
$DemoCustomerBId = "22222222-2222-4222-8222-222222222223"
$DemoLocationId = "33333333-3333-4333-8333-333333333333"
$DemoLocationBId = "33333333-3333-4333-8333-333333333334"
$DemoPrimaryProductId = "44444444-4444-4444-8444-444444444444"
$DemoSubstituteAId = "55555555-5555-4555-8555-555555555555"
$DemoSubstituteBId = "66666666-6666-4666-8666-666666666666"
$DemoFilterProductId = "77777777-7777-4777-8777-777777777777"
$DemoOilProductId = "88888888-8888-4888-8888-888888888888"

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

function Convert-JdbcToPsql([string]$Url) {
  if (-not $Url) { $Url = Get-DefaultDatasourceUrl }
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
NEXT_PUBLIC_ORDERPILOT_DEMO_MODE=true
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
if (-not $Username) { $Username = Get-EnvOrDefault "ORDERPILOT_DB_USER" "orderpilot_local_user" }
$activeProfile = if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { "(default)" }

Write-Host "OrderPilot local demo seed"
Write-Host "Repository: $resolvedRoot"
Write-Host "This script is deterministic and local-only. It does not call Telegram, LLMs, ERP/1C, external connector networks, or production seeders."
Write-Host "It uses fixed demo UUIDs and SQL upserts / guarded inserts for repeatable local runs."
Write-Host "Target datasource: jdbc:postgresql://$($connection.Host):$($connection.Port)/$($connection.Database)"
Write-Host "Target database:   $($connection.Database)"
Write-Host "Target user:       $Username"
Write-Host "Spring profile:    $activeProfile"

$sql = @"
BEGIN;

INSERT INTO tenant (id, slug, legal_name, status)
VALUES ('$DemoTenantId', 'orderpilot-demo-parts-distributor', 'OrderPilot Demo Parts Distributor', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
  slug = EXCLUDED.slug,
  legal_name = EXCLUDED.legal_name,
  status = EXCLUDED.status,
  updated_at = now();

INSERT INTO user_account (id, tenant_id, email, display_name, status)
VALUES (
  '$DemoOperatorUserId',
  '$DemoTenantId',
  'local-demo-operator@operant.invalid',
  'Local Demo Operator',
  'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO channel_connection (
  id,
  tenant_id,
  provider_type,
  display_name,
  status,
  mode,
  external_account_id,
  webhook_verification_mode
)
VALUES (
  '$DemoChannelConnectionId',
  '$DemoTenantId',
  'TELEGRAM',
  'Deterministic local demo RFQ source',
  'ACTIVE',
  'READ_ONLY',
  'operant-local-demo',
  'DISABLED_FOR_LOCAL_DEV'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO inbound_channel_event (
  id,
  tenant_id,
  channel_connection_id,
  provider_type,
  external_event_id,
  source_actor_type,
  source_actor_external_id,
  normalized_text,
  payload_hash,
  status,
  received_at,
  processed_at,
  verification_status,
  verification_reason
)
VALUES (
  '$DemoInboundChannelEventId',
  '$DemoTenantId',
  '$DemoChannelConnectionId',
  'TELEGRAM',
  'operant-local-demo-rfq-1',
  'CUSTOMER',
  'steppe-logistics-demo',
  'Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.',
  'operant-local-demo-rfq-1',
  'NORMALIZED',
  '2026-07-03T00:00:00Z',
  '2026-07-03T00:00:00Z',
  'SKIPPED_LOCAL_DEV',
  'Deterministic local demo seed'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO channel_rfq_handoff (
  id,
  tenant_id,
  inbound_channel_event_id,
  channel_connection_id,
  source_channel,
  source_external_event_id,
  source_actor_external_id,
  request_text,
  detected_intent,
  status,
  created_at,
  updated_at
)
VALUES (
  '$DemoRfqHandoffId',
  '$DemoTenantId',
  '$DemoInboundChannelEventId',
  '$DemoChannelConnectionId',
  'TELEGRAM',
  'operant-local-demo-rfq-1',
  'steppe-logistics-demo',
  'Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.',
  'RFQ_REQUEST',
  'PENDING_REVIEW',
  '2026-07-03T00:00:00Z',
  '2026-07-03T00:00:00Z'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO location (id, tenant_id, code, name, type, city, country, active)
VALUES ('$DemoLocationId', '$DemoTenantId', 'WH-ALM', 'Almaty Main Warehouse', 'WAREHOUSE', 'Almaty', 'KZ', true)
ON CONFLICT (id) DO UPDATE SET
  code = EXCLUDED.code,
  name = EXCLUDED.name,
  type = EXCLUDED.type,
  city = EXCLUDED.city,
  country = EXCLUDED.country,
  active = EXCLUDED.active,
  updated_at = now();

INSERT INTO location (id, tenant_id, code, name, type, city, country, active)
VALUES ('$DemoLocationBId', '$DemoTenantId', 'AST-BRANCH', 'Astana Branch Warehouse', 'WAREHOUSE', 'Astana', 'KZ', true)
ON CONFLICT (id) DO UPDATE SET
  code = EXCLUDED.code,
  name = EXCLUDED.name,
  type = EXCLUDED.type,
  city = EXCLUDED.city,
  country = EXCLUDED.country,
  active = EXCLUDED.active,
  updated_at = now();

INSERT INTO customer_account (id, tenant_id, external_ref, account_code, legal_name, display_name, status, default_currency, default_location_id)
VALUES ('$DemoCustomerId', '$DemoTenantId', 'CUST-001', 'CUST-001', 'Steppe Logistics LLP', 'Steppe Logistics', 'ACTIVE', 'USD', '$DemoLocationId')
ON CONFLICT (id) DO UPDATE SET
  external_ref = EXCLUDED.external_ref,
  account_code = EXCLUDED.account_code,
  legal_name = EXCLUDED.legal_name,
  display_name = EXCLUDED.display_name,
  status = EXCLUDED.status,
  default_currency = EXCLUDED.default_currency,
  default_location_id = EXCLUDED.default_location_id,
  updated_at = now();

INSERT INTO customer_account (id, tenant_id, external_ref, account_code, legal_name, display_name, status, default_currency, default_location_id)
VALUES ('$DemoCustomerBId', '$DemoTenantId', 'DEMO-CUST-002', 'ASTANA-FLEET', 'Astana Fleet Service LLP', 'Astana Fleet Service', 'ACTIVE', 'KZT', '$DemoLocationBId')
ON CONFLICT (id) DO UPDATE SET
  external_ref = EXCLUDED.external_ref,
  account_code = EXCLUDED.account_code,
  legal_name = EXCLUDED.legal_name,
  display_name = EXCLUDED.display_name,
  status = EXCLUDED.status,
  default_currency = EXCLUDED.default_currency,
  default_location_id = EXCLUDED.default_location_id,
  updated_at = now();

INSERT INTO product (id, tenant_id, sku, normalized_sku, name, description, category, brand, manufacturer, base_uom, status, cost, currency)
VALUES
  ('$DemoPrimaryProductId', '$DemoTenantId', 'PAD-OE-04465', 'PADOE04465', 'Toyota Camry 2018 OEM Front Brake Pad Set', 'Original-equivalent front brake pad set for Toyota Camry 2018', 'Brake Pads', 'Toyota', 'Toyota', 'EA', 'ACTIVE', 42.00, 'USD'),
  ('$DemoSubstituteAId', '$DemoTenantId', 'PAD-SUB-ADV', 'PADSUBADV', 'Advantage Ceramic Brake Pad Set', 'Aftermarket brake pad set compatible with Toyota Camry 2018', 'Brake Pads', 'RoadMax', 'RoadMax Parts', 'EA', 'ACTIVE', 25.00, 'USD'),
  ('$DemoSubstituteBId', '$DemoTenantId', 'PAD-SUB-ECON', 'PADSUBECON', 'Economy Brake Pad Set', 'Economy aftermarket brake pad set compatible with Toyota Camry 2018', 'Brake Pads', 'SteppeLine', 'SteppeLine Components', 'EA', 'ACTIVE', 19.00, 'USD'),
  ('$DemoFilterProductId', '$DemoTenantId', 'TOY-CAM-2018-AIR-FILTER', 'TOYCAM2018AIRFILTER', 'Air filter for Toyota Camry 2018', 'OEM-compatible air filter for Toyota Camry 2018', 'Filters', 'Toyota Genuine', 'Toyota', 'PCS', 'ACTIVE', 4500.00, 'KZT'),
  ('$DemoOilProductId', '$DemoTenantId', 'OIL-5W30-4L', 'OIL5W304L', 'Synthetic engine oil 5W30 4L', 'Synthetic 5W30 engine oil, four liter canister', 'Fluids', 'SteppeOil', 'SteppeOil', 'PCS', 'ACTIVE', 7200.00, 'KZT')
ON CONFLICT (id) DO UPDATE SET
  sku = EXCLUDED.sku,
  normalized_sku = EXCLUDED.normalized_sku,
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
SELECT '$DemoTenantId', '$DemoPrimaryProductId', 'CUSTOMER_TEXT', 'brake pads for Toyota Camry 2018', 'BRAKEPADSFORTOYOTACAMRY2018', '$DemoCustomerId', 0.9500
WHERE NOT EXISTS (
  SELECT 1 FROM product_alias
  WHERE tenant_id = '$DemoTenantId'
    AND product_id = '$DemoPrimaryProductId'
    AND normalized_alias = 'BRAKEPADSFORTOYOTACAMRY2018'
    AND active = true
);

INSERT INTO product_alias (tenant_id, product_id, alias_type, raw_alias, normalized_alias, customer_account_id, confidence_default)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', 'CUSTOMER_SKU', 'PAD-OE-04465', 'PADOE04465', NULL, 0.9900
WHERE NOT EXISTS (
  SELECT 1 FROM product_alias
  WHERE tenant_id = '$DemoTenantId'
    AND product_id = '$DemoPrimaryProductId'
    AND normalized_alias = 'PADOE04465'
    AND active = true
);

INSERT INTO product_alias (tenant_id, product_id, alias_type, raw_alias, normalized_alias, customer_account_id, confidence_default)
SELECT '$DemoTenantId', '$DemoFilterProductId', 'CUSTOMER_SKU', 'camry air filter', 'CAMRYAIRFILTER', NULL, 0.9500
WHERE NOT EXISTS (
  SELECT 1 FROM product_alias
  WHERE tenant_id = '$DemoTenantId'
    AND product_id = '$DemoFilterProductId'
    AND normalized_alias = 'CAMRYAIRFILTER'
    AND active = true
);

INSERT INTO oem_reference (tenant_id, product_id, oem_code, normalized_oem_code, manufacturer)
SELECT '$DemoTenantId', '$DemoFilterProductId', '17801-0H050', '178010H050', 'Toyota'
WHERE NOT EXISTS (
  SELECT 1 FROM oem_reference
  WHERE tenant_id = '$DemoTenantId'
    AND product_id = '$DemoFilterProductId'
    AND normalized_oem_code = '178010H050'
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

INSERT INTO inventory_snapshot (tenant_id, product_id, location_id, quantity_on_hand, quantity_reserved, quantity_available, captured_at, source)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoLocationId', 100, 0, 100, '2026-05-04T18:00:00Z', 'DEMO_SEED'
WHERE NOT EXISTS (SELECT 1 FROM inventory_snapshot WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoPrimaryProductId' AND location_id = '$DemoLocationId' AND captured_at = '2026-05-04T18:00:00Z' AND source = 'DEMO_SEED');

INSERT INTO inventory_snapshot (tenant_id, product_id, location_id, quantity_on_hand, quantity_reserved, quantity_available, captured_at, source)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoLocationBId', 0, 0, 0, '2026-05-04T18:00:00Z', 'DEMO_SEED'
WHERE NOT EXISTS (SELECT 1 FROM inventory_snapshot WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoPrimaryProductId' AND location_id = '$DemoLocationBId' AND captured_at = '2026-05-04T18:00:00Z' AND source = 'DEMO_SEED');

INSERT INTO inventory_snapshot (tenant_id, product_id, location_id, quantity_on_hand, quantity_reserved, quantity_available, captured_at, source)
SELECT '$DemoTenantId', '$DemoSubstituteAId', '$DemoLocationBId', 75, 0, 75, '2026-05-04T18:00:00Z', 'DEMO_SEED'
WHERE NOT EXISTS (SELECT 1 FROM inventory_snapshot WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoSubstituteAId' AND location_id = '$DemoLocationBId' AND captured_at = '2026-05-04T18:00:00Z' AND source = 'DEMO_SEED');

INSERT INTO inventory_snapshot (tenant_id, product_id, location_id, quantity_on_hand, quantity_reserved, quantity_available, captured_at, source)
SELECT '$DemoTenantId', '$DemoSubstituteBId', '$DemoLocationBId', 50, 0, 50, '2026-05-04T18:00:00Z', 'DEMO_SEED'
WHERE NOT EXISTS (SELECT 1 FROM inventory_snapshot WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoSubstituteBId' AND location_id = '$DemoLocationBId' AND captured_at = '2026-05-04T18:00:00Z' AND source = 'DEMO_SEED');

INSERT INTO inventory_snapshot (tenant_id, product_id, location_id, quantity_on_hand, quantity_reserved, quantity_available, captured_at, source)
SELECT '$DemoTenantId', '$DemoFilterProductId', '$DemoLocationId', 30, 0, 30, '2026-05-04T18:00:00Z', 'DEMO_SEED'
WHERE NOT EXISTS (SELECT 1 FROM inventory_snapshot WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoFilterProductId' AND location_id = '$DemoLocationId' AND captured_at = '2026-05-04T18:00:00Z' AND source = 'DEMO_SEED');

INSERT INTO inventory_snapshot (tenant_id, product_id, location_id, quantity_on_hand, quantity_reserved, quantity_available, captured_at, source)
SELECT '$DemoTenantId', '$DemoOilProductId', '$DemoLocationId', 40, 0, 40, '2026-05-04T18:00:00Z', 'DEMO_SEED'
WHERE NOT EXISTS (SELECT 1 FROM inventory_snapshot WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoOilProductId' AND location_id = '$DemoLocationId' AND captured_at = '2026-05-04T18:00:00Z' AND source = 'DEMO_SEED');

INSERT INTO price_rule (tenant_id, product_id, customer_account_id, min_quantity, uom, unit_price, currency, active_from, priority)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoCustomerId', 1, 'EA', 65.00, 'USD', '2026-01-01T00:00:00Z', 10
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoPrimaryProductId' AND customer_account_id = '$DemoCustomerId' AND min_quantity = 1 AND uom = 'EA' AND active = true);

INSERT INTO price_rule (tenant_id, product_id, customer_account_id, min_quantity, uom, unit_price, currency, active_from, priority)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoCustomerBId', 1, 'EA', 62.00, 'USD', '2026-01-01T00:00:00Z', 10
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoPrimaryProductId' AND customer_account_id = '$DemoCustomerBId' AND min_quantity = 1 AND uom = 'EA' AND active = true);

INSERT INTO price_rule (tenant_id, product_id, customer_account_id, min_quantity, uom, unit_price, currency, active_from, priority)
SELECT '$DemoTenantId', '$DemoSubstituteAId', NULL, 1, 'EA', 39.00, 'USD', '2026-01-01T00:00:00Z', 20
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoSubstituteAId' AND customer_account_id IS NULL AND min_quantity = 1 AND uom = 'EA' AND active = true);

INSERT INTO price_rule (tenant_id, product_id, customer_account_id, min_quantity, uom, unit_price, currency, active_from, priority)
SELECT '$DemoTenantId', '$DemoSubstituteBId', NULL, 1, 'EA', 29.50, 'USD', '2026-01-01T00:00:00Z', 20
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoSubstituteBId' AND customer_account_id IS NULL AND min_quantity = 1 AND uom = 'EA' AND active = true);

INSERT INTO price_rule (tenant_id, product_id, customer_account_id, min_quantity, uom, unit_price, currency, active_from, priority)
SELECT '$DemoTenantId', '$DemoFilterProductId', NULL, 1, 'EA', 9000.00, 'KZT', '2026-01-01T00:00:00Z', 20
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoFilterProductId' AND customer_account_id IS NULL AND min_quantity = 1 AND uom = 'EA' AND active = true);

INSERT INTO price_rule (tenant_id, product_id, customer_account_id, min_quantity, uom, unit_price, currency, active_from, priority)
SELECT '$DemoTenantId', '$DemoOilProductId', NULL, 1, 'EA', 12000.00, 'KZT', '2026-01-01T00:00:00Z', 20
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE tenant_id = '$DemoTenantId' AND product_id = '$DemoOilProductId' AND customer_account_id IS NULL AND min_quantity = 1 AND uom = 'EA' AND active = true);

INSERT INTO discount_rule (tenant_id, code, name, customer_account_id, product_id, max_discount_percent, requires_approval_above_percent, active_from)
VALUES
  ('$DemoTenantId', 'DEMO-BRAKE-DISCOUNT', 'Demo brake discount guardrail', '$DemoCustomerId', '$DemoPrimaryProductId', 20.00, 10.00, '2026-01-01T00:00:00Z'),
  ('$DemoTenantId', 'DEMO-FILTER-DISCOUNT', 'Demo filter discount guardrail', NULL, '$DemoFilterProductId', 15.00, 8.00, '2026-01-01T00:00:00Z')
ON CONFLICT DO NOTHING;

INSERT INTO margin_rule (tenant_id, code, name, product_id, category, minimum_gross_margin_percent, approval_required_below_percent)
VALUES
  ('$DemoTenantId', 'DEMO-BRAKE-MARGIN', 'Demo brake margin guardrail', '$DemoPrimaryProductId', NULL, 20.00, 25.00),
  ('$DemoTenantId', 'DEMO-FILTER-MARGIN', 'Demo filter margin guardrail', '$DemoFilterProductId', NULL, 20.00, 25.00),
  ('$DemoTenantId', 'DEMO-GENERAL-MARGIN', 'Demo general margin guardrail', NULL, NULL, 15.00, 20.00)
ON CONFLICT DO NOTHING;

INSERT INTO product_substitute (tenant_id, source_product_id, substitute_product_id, substitute_type, risk_level, requires_approval, notes)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoSubstituteAId', 'COMPATIBLE_ALTERNATIVE', 'LOW', false, 'Safe aftermarket substitute'
WHERE NOT EXISTS (
  SELECT 1 FROM product_substitute
  WHERE tenant_id = '$DemoTenantId'
    AND source_product_id = '$DemoPrimaryProductId'
    AND substitute_product_id = '$DemoSubstituteAId'
    AND active = true
);

INSERT INTO product_substitute (tenant_id, source_product_id, substitute_product_id, substitute_type, risk_level, requires_approval, notes)
SELECT '$DemoTenantId', '$DemoPrimaryProductId', '$DemoSubstituteBId', 'COMPATIBLE_ALTERNATIVE', 'HIGH', true, 'Risky substitute requiring approval'
WHERE NOT EXISTS (
  SELECT 1 FROM product_substitute
  WHERE tenant_id = '$DemoTenantId'
    AND source_product_id = '$DemoPrimaryProductId'
    AND substitute_product_id = '$DemoSubstituteBId'
    AND active = true
);

INSERT INTO customer_substitution_preference (tenant_id, customer_account_id, product_id, allow_aftermarket, blocked_substitute_product_id, notes)
SELECT '$DemoTenantId', '$DemoCustomerId', '$DemoPrimaryProductId', true, '$DemoSubstituteBId', 'Safe substitute A allowed; substitute B blocked for customer'
WHERE NOT EXISTS (
  SELECT 1 FROM customer_substitution_preference
  WHERE tenant_id = '$DemoTenantId'
    AND customer_account_id = '$DemoCustomerId'
    AND product_id = '$DemoPrimaryProductId'
    AND blocked_substitute_product_id = '$DemoSubstituteBId'
);

INSERT INTO product_compatibility (tenant_id, product_id, compatible_type, make, model, year_from, year_to, notes, risk_level)
SELECT '$DemoTenantId', '$DemoSubstituteAId', 'VEHICLE', 'Toyota', 'Camry', 2018, 2018, 'Verified Camry 2018 fitment', 'LOW'
WHERE NOT EXISTS (
  SELECT 1 FROM product_compatibility
  WHERE tenant_id = '$DemoTenantId'
    AND product_id = '$DemoSubstituteAId'
    AND make = 'Toyota'
    AND model = 'Camry'
    AND active = true
);

COMMIT;
"@

$verifySql = @"
DO `$`$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM tenant WHERE id = '$DemoTenantId') THEN
    RAISE EXCEPTION 'Demo seed verification failed: tenant % is missing', '$DemoTenantId';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM product
    WHERE tenant_id = '$DemoTenantId'
      AND id = '$DemoPrimaryProductId'
      AND sku = 'PAD-OE-04465'
      AND normalized_sku = 'PADOE04465'
      AND deleted_at IS NULL
  ) THEN
    RAISE EXCEPTION 'Demo seed verification failed: product PAD-OE-04465 / normalized_sku PADOE04465 is missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM location
    WHERE tenant_id = '$DemoTenantId'
      AND id = '$DemoLocationId'
      AND code = 'WH-ALM'
      AND active = true
  ) THEN
    RAISE EXCEPTION 'Demo seed verification failed: warehouse WH-ALM is missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM user_account
    WHERE tenant_id = '$DemoTenantId'
      AND id = '$DemoOperatorUserId'
      AND status = 'ACTIVE'
  ) THEN
    RAISE EXCEPTION 'Demo seed verification failed: backend-owned local demo operator is missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM channel_rfq_handoff
    WHERE tenant_id = '$DemoTenantId'
      AND id = '$DemoRfqHandoffId'
      AND source_channel = 'TELEGRAM'
  ) THEN
    RAISE EXCEPTION 'Demo seed verification failed: deterministic RFQ handoff is missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM channel_connection
    WHERE tenant_id = '$DemoTenantId'
      AND id = '$DemoChannelConnectionId'
      AND provider_type = 'TELEGRAM'
      AND status = 'ACTIVE'
  ) THEN
    RAISE EXCEPTION 'Demo seed verification failed: deterministic Telegram connection is missing';
  END IF;
END
`$`$;

SELECT
  current_database() AS database,
  current_user AS username,
  inet_server_addr() AS server_addr,
  inet_server_port() AS server_port;

SELECT id, slug, status
FROM tenant
WHERE id = '$DemoTenantId';

SELECT id, sku, normalized_sku, status
FROM product
WHERE tenant_id = '$DemoTenantId'
  AND sku = 'PAD-OE-04465';

SELECT id, code, active
FROM location
WHERE tenant_id = '$DemoTenantId'
  AND code = 'WH-ALM';
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

Write-Step "Verifying deterministic local seed"
if ($psqlCommand) {
  Invoke-SeedWithLocalPsql $connection $Username $Credential $verifySql
} else {
  Invoke-SeedWithDockerComposePsql $composePath $connection $Username $verifySql
}

if ($UpdateFrontendEnv) {
  Write-FrontendEnv $webRoot
}

Write-Host "OK: Local demo seed is present."
Write-Host "Demo tenant id:   $DemoTenantId"
Write-Host "Demo product id:  $DemoPrimaryProductId"
Write-Host "Demo location id: $DemoLocationId"
Write-Host "Demo RFQ handoff: $DemoRfqHandoffId"
