param(
  [string]$BaseUrl = "http://localhost:8080",
  [string]$TenantSlug = "demo-auto-industrial",
  [string]$TenantName = "Demo Auto Industrial Parts LLC"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fixtureRoot = Join-Path $repoRoot "packages\test-fixtures\stage2-demo"

function Invoke-CoreApi {
  param(
    [Parameter(Mandatory=$true)][string]$Method,
    [Parameter(Mandatory=$true)][string]$Path,
    [object]$Body = $null,
    [hashtable]$Headers = @{}
  )

  $uri = "$BaseUrl$Path"
  $request = @{
    Method = $Method
    Uri = $uri
    Headers = $Headers
  }
  if ($null -ne $Body) {
    $request.ContentType = "application/json"
    $request.Body = ([pscustomobject]$Body | ConvertTo-Json -Depth 20 -Compress)
  }
  Invoke-RestMethod @request
}

function Import-Fixture {
  param(
    [Parameter(Mandatory=$true)][string]$Type,
    [Parameter(Mandatory=$true)][string]$FileName,
    [Parameter(Mandatory=$true)][hashtable]$Headers
  )

  $path = Join-Path $fixtureRoot $FileName
  if (!(Test-Path $path)) {
    throw "Missing fixture: $path"
  }

  $csvContent = [System.IO.File]::ReadAllText($path)
  $job = Invoke-CoreApi -Method "POST" -Path "/api/v1/imports/$Type" -Headers $Headers -Body @{
    originalFilename = $FileName
    csvContent = $csvContent
  }
  $report = Invoke-CoreApi -Method "POST" -Path "/api/v1/imports/$($job.id)/validate" -Headers $Headers

  if ($report.invalidRows -gt 0) {
    $messages = @($report.validationErrors | ForEach-Object { $_.errors } | ForEach-Object { $_ })
    $duplicateOnly = ($messages.Count -gt 0) -and (($messages | Where-Object { $_ -notmatch "duplicate|already exists" }).Count -eq 0)
    if ($duplicateOnly) {
      return [pscustomobject]@{
        Type = $Type
        File = $FileName
        Status = "SKIPPED_DUPLICATE"
        Rows = $report.totalRows
        Warnings = ($messages -join "; ")
      }
    }
    throw "Import $Type failed validation: $($messages -join '; ')"
  }

  $activated = Invoke-CoreApi -Method "POST" -Path "/api/v1/imports/$($job.id)/activate" -Headers $Headers
  [pscustomobject]@{
    Type = $Type
    File = $FileName
    Status = $activated.status
    Rows = $activated.validRows
    Warnings = ""
  }
}

try {
  Invoke-CoreApi -Method "GET" -Path "/api/v1/health" | Out-Null
} catch {
  throw "Core API is not reachable at $BaseUrl. Start apps/core-api or Docker Compose before running this script."
}

if (!(Test-Path $fixtureRoot)) {
  throw "Fixture folder not found: $fixtureRoot"
}

$tenant = Invoke-CoreApi -Method "POST" -Path "/api/v1/demo/tenant" -Body @{
  slug = $TenantSlug
  legalName = $TenantName
}
$headers = @{ "X-Tenant-Id" = [string]$tenant.id }

$results = @()
$results += Import-Fixture -Type "LOCATIONS" -FileName "locations.sample.csv" -Headers $headers
$results += Import-Fixture -Type "CUSTOMERS" -FileName "customers.sample.csv" -Headers $headers
$results += Import-Fixture -Type "PRODUCTS" -FileName "products.sample.csv" -Headers $headers
$results += Import-Fixture -Type "PRODUCT_ALIASES" -FileName "product_aliases.sample.csv" -Headers $headers
$results += Import-Fixture -Type "OEM_REFERENCES" -FileName "oem_references.sample.csv" -Headers $headers
$existingInventory = @(Invoke-CoreApi -Method "GET" -Path "/api/v1/inventory" -Headers $headers)
if ($existingInventory.Count -gt 0) {
  $results += [pscustomobject]@{
    Type = "INVENTORY"
    File = "inventory.sample.csv"
    Status = "SKIPPED_EXISTING_SNAPSHOTS"
    Rows = $existingInventory.Count
    Warnings = "Inventory snapshots are append-only; existing tenant snapshots were left unchanged."
  }
} else {
  $results += Import-Fixture -Type "INVENTORY" -FileName "inventory.sample.csv" -Headers $headers
}
$results += Import-Fixture -Type "PRICE_RULES" -FileName "price_rules.sample.csv" -Headers $headers
$results += Import-Fixture -Type "PRODUCT_SUBSTITUTES" -FileName "product_substitutes.sample.csv" -Headers $headers
$results += Import-Fixture -Type "COMPATIBILITY" -FileName "compatibility.sample.csv" -Headers $headers
$results += Import-Fixture -Type "DISCOUNT_RULES" -FileName "discount_rules.sample.csv" -Headers $headers
$results += Import-Fixture -Type "MARGIN_RULES" -FileName "margin_rules.sample.csv" -Headers $headers

$products = Invoke-CoreApi -Method "GET" -Path "/api/v1/products" -Headers $headers
$customers = Invoke-CoreApi -Method "GET" -Path "/api/v1/customers" -Headers $headers
$inventory = Invoke-CoreApi -Method "GET" -Path "/api/v1/inventory" -Headers $headers
$prices = Invoke-CoreApi -Method "GET" -Path "/api/v1/pricing/rules" -Headers $headers

Write-Host "OrderPilot Stage 2 demo seed summary"
Write-Host "tenant_id: $($tenant.id)"
Write-Host "tenant_created: $($tenant.created)"
Write-Host "customers imported: $($customers.Count)"
Write-Host "products imported: $($products.Count)"
Write-Host "aliases imported: see PRODUCT_ALIASES import row count"
Write-Host "inventory snapshots imported: $($inventory.Count)"
Write-Host "price rules imported: $($prices.Count)"
Write-Host ""
$results | Format-Table Type, Status, Rows, Warnings -AutoSize
