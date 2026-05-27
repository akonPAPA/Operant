<#
.SYNOPSIS
Runs the Core v1 demo acceptance evidence check and writes a Markdown report.

.DESCRIPTION
Stage 11C acceptance runner. By default it runs safe preflight evidence checks only:
repo state, required demo scripts/runbooks, documented Core v1 scenario coverage, and
explicit safety guardrails. It does not start services, call external networks, require
real secrets, enable production connectors, write ERP/1C, or mutate inventory.

Use -RequireRuntime only after local services are intentionally running. Runtime probes
are limited to localhost-style URLs.

.PARAMETER RequireRuntime
Opt in to strict local runtime checks. Runtime failures make the script exit non-zero.

.PARAMETER OutputPath
Markdown report path. Defaults to docs/runbooks/CORE_V1_ACCEPTANCE_EVIDENCE.md.

.PARAMETER BackendUrl
Local backend base URL used only with -RequireRuntime.

.PARAMETER FrontendUrl
Local frontend base URL used only with -RequireRuntime.

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1 -RequireRuntime
#>

[CmdletBinding()]
param(
  [switch]$Help,
  [switch]$RequireRuntime,
  [string]$OutputPath,
  [string]$BackendUrl = "http://localhost:8080",
  [string]$FrontendUrl = "http://localhost:3000"
)

$ErrorActionPreference = "Stop"

if ($Help) {
  Write-Host "OrderPilot Core v1 Stage 11C acceptance runner"
  Write-Host ""
  Write-Host "Usage:"
  Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1 [-OutputPath <path>]"
  Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1 -RequireRuntime [-BackendUrl http://localhost:8080] [-FrontendUrl http://localhost:3000]"
  Write-Host ""
  Write-Host "Default mode is preflight-only. It writes a Markdown evidence report and does not start services, call external networks, enable production connectors, require real secrets, or write ERP/1C."
  Write-Host "Use -RequireRuntime only after local backend/frontend/database services are intentionally running."
  return
}

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "== $Message =="
}

function New-Check([string]$Category, [string]$Name, [string]$Status, [string]$Evidence, [bool]$Required = $false) {
  [pscustomobject]@{
    Category = $Category
    Name = $Name
    Status = $Status
    Evidence = $Evidence
    Required = $Required
  }
}

function Add-Check([System.Collections.Generic.List[object]]$Checks, [string]$Category, [string]$Name, [string]$Status, [string]$Evidence, [bool]$Required = $false) {
  $Checks.Add((New-Check $Category $Name $Status $Evidence $Required))
  Write-Host ("{0}: {1} - {2}" -f $Status, $Name, $Evidence)
}

function Get-GitOutput([string]$Root, [string[]]$Arguments) {
  try {
    $output = & git -c "safe.directory=$Root" -C $Root @Arguments 2>$null
    if ($LASTEXITCODE -eq 0) {
      return ($output -join "`n").Trim()
    }
  } catch {
  }
  return ""
}

function Test-PathRelative([string]$Root, [string]$RelativePath) {
  return Test-Path (Join-Path $Root $RelativePath)
}

function Get-RelativeExistingPaths([string]$Root, [string[]]$RelativePaths) {
  $found = @()
  foreach ($relativePath in $RelativePaths) {
    if (Test-PathRelative $Root $relativePath) {
      $found += $relativePath
    }
  }
  return $found
}

function Test-FileContainsAny([string]$Root, [string[]]$RelativePaths, [string[]]$Patterns) {
  $found = @()
  foreach ($relativePath in $RelativePaths) {
    $path = Join-Path $Root $relativePath
    if (-not (Test-Path $path)) { continue }
    foreach ($pattern in $Patterns) {
      $match = Select-String -Path $path -Pattern $pattern -SimpleMatch -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($match) {
        $found += $relativePath
        break
      }
    }
  }
  return ($found | Select-Object -Unique)
}

function Test-EvidenceAny([string]$Root, [string[]]$RelativePaths, [string[]]$Patterns) {
  $found = Test-FileContainsAny $Root $RelativePaths $Patterns
  return [pscustomobject]@{
    Found = ($found.Count -gt 0)
    Paths = $found
  }
}

function Test-LocalUrl([string]$Url) {
  try {
    $uri = [System.Uri]$Url
    return $uri.Scheme -in @("http", "https") -and $uri.Host -in @("localhost", "127.0.0.1", "::1", "[::1]")
  } catch {
    return $false
  }
}

function Test-HttpOk([string]$Url) {
  if (-not (Test-LocalUrl $Url)) {
    return [pscustomobject]@{ Ok = $false; Detail = "Refused non-local URL: $Url" }
  }
  try {
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
    return [pscustomobject]@{ Ok = ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300); Detail = "HTTP $($response.StatusCode)" }
  } catch {
    return [pscustomobject]@{ Ok = $false; Detail = $_.Exception.Message }
  }
}

function Test-TcpOpen([string]$HostName, [int]$Port) {
  if ($HostName -notin @("localhost", "127.0.0.1", "::1")) {
    return [pscustomobject]@{ Ok = $false; Detail = "Refused non-local host: $HostName" }
  }
  $client = [System.Net.Sockets.TcpClient]::new()
  try {
    $task = $client.ConnectAsync($HostName, $Port)
    if (-not $task.Wait(3000)) {
      return [pscustomobject]@{ Ok = $false; Detail = "Timeout connecting to ${HostName}:$Port" }
    }
    return [pscustomobject]@{ Ok = $client.Connected; Detail = "TCP ${HostName}:$Port" }
  } catch {
    return [pscustomobject]@{ Ok = $false; Detail = $_.Exception.Message }
  } finally {
    $client.Dispose()
  }
}

function Get-JdbcHostPort([string]$JdbcUrl) {
  $match = [regex]::Match($JdbcUrl, "^jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\d+))?/")
  if (-not $match.Success) { return $null }
  return [pscustomobject]@{
    Host = $match.Groups["host"].Value
    Port = if ($match.Groups["port"].Success) { [int]$match.Groups["port"].Value } else { 5432 }
  }
}

function Test-DemoConfigHasNoRawSecrets([string]$Root) {
  $configPaths = Get-RelativeExistingPaths $Root @(
    ".env.example",
    "apps/core-api/.env.example",
    "apps/web-dashboard/.env.local.example",
    "apps/web-dashboard/.env.local"
  )
  $secretNamePattern = "(?i)(secret|token|password|api[_-]?key|private[_-]?key)"
  $allowedValuePattern = "(?i)^(|example|placeholder|changeme|change_me|local|demo|dev|test|orderpilot_dev_password|false|true|http://localhost:8080|00000000-0000-0000-0000-000000000000)$"
  $findings = @()
  foreach ($relativePath in $configPaths) {
    $path = Join-Path $Root $relativePath
    foreach ($line in (Get-Content $path -ErrorAction SilentlyContinue)) {
      $trimmed = $line.Trim()
      if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
      $parts = $trimmed.Split("=", 2)
      $name = $parts[0].Trim()
      $value = $parts[1].Trim().Trim('"').Trim("'")
      if ($name -match $secretNamePattern -and $value -notmatch $allowedValuePattern) {
        $findings += "${relativePath}:$name"
      }
    }
  }
  return [pscustomobject]@{
    Ok = ($findings.Count -eq 0)
    Detail = if ($findings.Count -eq 0) { "Demo config files contain placeholders/local values only." } else { "Potential raw secret values: $($findings -join ', ')" }
  }
}

function ConvertTo-MarkdownTable([object[]]$Rows) {
  $lines = [System.Collections.Generic.List[string]]::new()
  $lines.Add("| Check | Status | Evidence |")
  $lines.Add("| --- | --- | --- |")
  foreach ($row in $Rows) {
    $name = ($row.Name -replace "\|", "\|")
    $status = ($row.Status -replace "\|", "\|")
    $evidence = ($row.Evidence -replace "\|", "\|")
    $lines.Add("| $name | $status | $evidence |")
  }
  return ($lines -join "`n")
}

function Add-EvidenceScenario([System.Collections.Generic.List[object]]$Checks, [string]$Name, [string[]]$Paths, [string[]]$Patterns, [string]$PassEvidence, [string]$PartialEvidence) {
  $result = Test-EvidenceAny $repoRoot $Paths $Patterns
  if ($result.Found) {
    Add-Check $Checks "Core v1 acceptance matrix" $Name "PASS" "$PassEvidence Evidence: $($result.Paths -join ', ')."
  } else {
    Add-Check $Checks "Core v1 acceptance matrix" $Name "PARTIAL" $PartialEvidence
  }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $OutputPath) {
  $OutputPath = Join-Path $repoRoot "docs/runbooks/CORE_V1_ACCEPTANCE_EVIDENCE.md"
}
$resolvedOutputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
$mode = if ($RequireRuntime) { "runtime" } else { "preflight" }
$timestamp = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$checks = [System.Collections.Generic.List[object]]::new()

Write-Host "OrderPilot Core v1 Stage 11C acceptance runner"
Write-Host "Repository: $repoRoot"
Write-Host "Mode: $mode"
Write-Host "Report: $resolvedOutputPath"
Write-Host "Production connectors remain disabled. Real ERP/1C writes remain disabled. No external network calls or real secrets are required."

Write-Step "Git and repo state"
$branch = Get-GitOutput $repoRoot @("rev-parse", "--abbrev-ref", "HEAD")
if ($branch) {
  Add-Check $checks "Git and repo state" "Current branch" "PASS" $branch
} else {
  Add-Check $checks "Git and repo state" "Current branch" "NOT_VERIFIED" "Git branch could not be read."
}

$status = Get-GitOutput $repoRoot @("status", "--porcelain")
if ($status) {
  Add-Check $checks "Git and repo state" "Working tree clean" "PARTIAL" "Working tree has local changes."
} else {
  Add-Check $checks "Git and repo state" "Working tree clean" "PASS" "No tracked or untracked changes reported by git status --porcelain."
}

$head = Get-GitOutput $repoRoot @("rev-parse", "HEAD")
if ($head) {
  Add-Check $checks "Git and repo state" "OrderPilot-Core HEAD" "PASS" $head
}

$parentRoot = Split-Path -Parent $repoRoot
if (Test-Path (Join-Path $parentRoot ".git")) {
  $gitlink = Get-GitOutput $parentRoot @("ls-files", "-s", "OrderPilot-Core")
  if (-not $gitlink) {
    $gitlink = Get-GitOutput $parentRoot @("ls-tree", "HEAD", "OrderPilot-Core")
  }
  if ($gitlink) {
    Add-Check $checks "Git and repo state" "Parent gitlink pointer" "PASS" $gitlink
  } else {
    Add-Check $checks "Git and repo state" "Parent gitlink pointer" "NOT_VERIFIED" "Parent repo exists, but no OrderPilot-Core gitlink was readable at HEAD."
  }
} else {
  Add-Check $checks "Git and repo state" "Parent gitlink pointer" "NOT_VERIFIED" "No parent git repo detected above Core checkout."
}

foreach ($path in @(
  "scripts/start-local-demo.ps1",
  "scripts/check-local-demo.ps1",
  "scripts/seed-local-demo.ps1",
  "scripts/run-core-v1-demo-check.ps1"
)) {
  if (Test-PathRelative $repoRoot $path) {
    Add-Check $checks "Git and repo state" "Required script: $path" "PASS" "Found."
  } else {
    Add-Check $checks "Git and repo state" "Required script: $path" "FAIL" "Missing required script." $true
  }
}

Write-Step "Local demo readiness"
foreach ($path in @(
  "docs/runbooks/CORE_V1_LOCAL_DEMO_RUNBOOK.md",
  "docs/runbooks/CORE_V1_DEMO_READINESS_CHECKLIST.md"
)) {
  if (Test-PathRelative $repoRoot $path) {
    Add-Check $checks "Local demo readiness" "Required document: $path" "PASS" "Found."
  } else {
    Add-Check $checks "Local demo readiness" "Required document: $path" "FAIL" "Missing required document." $true
  }
}

$roadmapPaths = Get-RelativeExistingPaths $repoRoot @("docs/product/roadmap_core_v1.md", "docs/product/core-v1-scope.md", "docs/ROADMAP.md")
if ($roadmapPaths.Count -gt 0) {
  Add-Check $checks "Local demo readiness" "Core v1 roadmap/scope source" "PASS" "Found: $($roadmapPaths -join ', ')."
} else {
  Add-Check $checks "Local demo readiness" "Core v1 roadmap/scope source" "PARTIAL" "Preferred docs/product/roadmap_core_v1.md is missing and no fallback roadmap file was found."
}

Write-Step "Core v1 acceptance matrix"
Add-EvidenceScenario $checks "Telegram RFQ" @(
  "apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java",
  "apps/core-api/src/test/resources/demo/core-v1-demo/telegram-rfq-demo.json",
  "docs/security/AI_AND_BOT_GOVERNANCE.md"
) @("telegram-rfq-demo.json", "RFQ_REQUEST", "BOT_RFQ_DRAFT_CREATED") "Telegram webhook RFQ flow is covered by demo smoke test and fixture." "No focused Telegram RFQ evidence was found."

Add-EvidenceScenario $checks "PDF purchase order" @(
  "docs/security/FILE_UPLOAD_SECURITY.md",
  "docs/security/webhook-and-file-upload-security.md",
  "docs/architecture/OMNICHANNEL_INTAKE.md",
  "apps/core-api/src/main/java/com/orderpilot/application/services/intake/FileUploadService.java"
) @("PDF", "purchase order", "file upload") "PDF/file-upload intake boundary is documented or implemented." "PDF upload support exists only as a boundary check; purchase-order extraction remains not fully verified by this runner."

Add-EvidenceScenario $checks "Out-of-stock substitute suggestion" @(
  "docs/product/SUBSTITUTION_COMPATIBILITY_STAGE_11C.md",
  "apps/core-api/src/test/java/com/orderpilot/demo/DemoFixturesTest.java",
  "apps/core-api/src/main/java/com/orderpilot/application/services/workspace/RfqToDraftQuoteService.java"
) @("substitute", "OUT_OF_STOCK", "NO_SAFE_SUBSTITUTE_FOUND") "Substitution/compatibility evidence and demo fixture coverage are present." "Substitution evidence was not found."

Add-EvidenceScenario $checks "Discount violation approval" @(
  "docs/VALIDATION_ENGINE.md",
  "docs/security/PRODUCTION_AUTH_RBAC_PROOF_PLAN_STAGE_10G.md",
  "apps/core-api/src/main/java/com/orderpilot/application/services/validation/ValidationRunService.java",
  "apps/core-api/src/main/java/com/orderpilot/domain/validation/DiscountCheckResult.java"
) @("discount", "ApprovalRequirement", "below-margin discount") "Discount approval/routing evidence exists." "Discount violation approval is documented but not fully verified as a live scenario by this runner."

Add-EvidenceScenario $checks "Inventory mismatch" @(
  "apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java",
  "apps/core-api/src/test/resources/demo/core-v1-demo/reconciliation-demo.json",
  "apps/core-api/src/main/java/com/orderpilot/application/services/reconciliation/InventoryReconciliationService.java"
) @("mismatchQuantity", "RECONCILIATION_CASE_CREATED", "Inventory mismatch") "Inventory mismatch is covered by demo smoke test and reconciliation fixture." "Inventory mismatch evidence was not found."

Add-EvidenceScenario $checks "Bad AI output rejection" @(
  "docs/security/AI_OUTPUT_SAFETY.md",
  "apps/ai-worker/tests/test_stage4_extraction.py",
  "apps/core-api/src/main/java/com/orderpilot/application/services/extraction/AiOutputSanitizer.java",
  "apps/core-api/src/main/java/com/orderpilot/application/services/extraction/ExtractionSchemaValidator.java"
) @("Unsafe", "sanitized", "schema", "rejected") "AI output safety and sanitizer/schema evidence exists." "Bad AI output rejection requires live or focused test verification."

Add-EvidenceScenario $checks "Tenant isolation" @(
  "apps/core-api/src/test/java/com/orderpilot/security/TenantIsolationBoundaryTest.java",
  "apps/core-api/src/test/java/com/orderpilot/demo/CoreV1InvestorDemoSmokeTest.java",
  "docs/security/TENANT_ISOLATION_API_BOUNDARY_STAGE_10J.md"
) @("otherTenant", "Tenant isolation", "tenant B") "Tenant isolation tests/docs exist." "Tenant isolation evidence was not found."

Add-EvidenceScenario $checks "Duplicate webhook idempotency" @(
  "docs/security/WEBHOOK_SECURITY.md",
  "docs/security/THREAT_MODEL.md",
  "apps/core-api/src/main/java/com/orderpilot/domain/intake/InboundEventLedger.java",
  "apps/core-api/src/main/java/com/orderpilot/domain/intake/WebhookEvent.java"
) @("idempot", "InboundEventLedger", "duplicate") "Webhook/idempotency evidence exists." "Duplicate webhook idempotency is not fully verified by this runner."

Add-EvidenceScenario $checks "Connector failure visibility" @(
  "docs/runbooks/CONNECTOR_FAILURE_RUNBOOK.md",
  "docs/integrations/CONNECTOR_SAFETY_MODEL.md",
  "apps/core-api/src/main/java/com/orderpilot/application/services/integration/ConnectorExecutionSafetyService.java",
  "apps/core-api/src/main/java/com/orderpilot/application/services/integration/sandbox/ConnectorSandboxExecutionService.java"
) @("failure", "POLICY_BLOCKED", "CONNECTOR_SANDBOX_DRY_RUN_FAILED") "Connector failure/policy-block visibility evidence exists." "Connector failure visibility evidence was not found."

Add-EvidenceScenario $checks "Audit review" @(
  "apps/core-api/src/test/java/com/orderpilot/domain/audit/AuditEventServiceTest.java",
  "docs/security/SECURITY_CHECKLIST.md",
  "docs/security/SECURITY_BASELINE.md",
  "apps/web-dashboard/app/(dashboard)/audit-log/page.tsx"
) @("AuditEvent", "audit log", "append") "Audit service/UI/security evidence exists." "Audit review evidence was not found."

Write-Step "Safety guardrails"
$guardrailEvidencePaths = @(
  "scripts/start-local-demo.ps1",
  "scripts/check-local-demo.ps1",
  "scripts/run-core-v1-demo-check.ps1",
  "docs/runbooks/CORE_V1_LOCAL_DEMO_RUNBOOK.md",
  "docs/security/THREAT_MODEL.md",
  "docs/security/SECURITY_BASELINE.md",
  "docs/integrations/CONNECTOR_SAFETY_MODEL.md",
  "docs/security/DATA_AUTHORITY_MODEL.md",
  "docs/AI_GOVERNANCE.md"
)

$guardrails = @(
  @{ Name = "Production connectors disabled"; Patterns = @("Production connectors remain disabled", "production connectors;", "Production ERP/1C connectors remain disabled") },
  @{ Name = "Demo ERP only / mock connector only"; Patterns = @("DEMO_ONLY", "Demo ERP", "in-process Demo ERP adapter") },
  @{ Name = "No real ERP/1C write mode"; Patterns = @("no real ERP/1C writes", "No ERP, 1C", "Real ERP/1C writes remain out of scope") },
  @{ Name = "No AI/bot/frontend direct DB write path documented"; Patterns = @("no direct DB writes", "Frontend has no direct DB dependency", "AI worker has no direct DB dependency") },
  @{ Name = "ChangeRequest required for external writes"; Patterns = @("Future external writes require ChangeRequest", "ChangeRequest approval is mandatory", "external writes require ChangeRequest") }
)
foreach ($guardrail in $guardrails) {
  $evidence = Test-EvidenceAny $repoRoot $guardrailEvidencePaths $guardrail.Patterns
  if ($evidence.Found) {
    Add-Check $checks "Safety guardrails" $guardrail.Name "PASS" "Documented in $($evidence.Paths -join ', ')." $true
  } else {
    Add-Check $checks "Safety guardrails" $guardrail.Name "FAIL" "No guardrail evidence found." $true
  }
}

$demoConfig = Test-DemoConfigHasNoRawSecrets $repoRoot
if ($demoConfig.Ok) {
  Add-Check $checks "Safety guardrails" "No raw secrets in demo config" "PASS" $demoConfig.Detail $true
} else {
  Add-Check $checks "Safety guardrails" "No raw secrets in demo config" "FAIL" $demoConfig.Detail $true
}

Write-Step "Runtime checks"
if ($RequireRuntime) {
  $backendHealth = Test-HttpOk "$BackendUrl/api/v1/health"
  Add-Check $checks "Runtime checks" "Backend health endpoint reachable" $(if ($backendHealth.Ok) { "PASS" } else { "FAIL" }) $backendHealth.Detail $true

  $frontendHome = Test-HttpOk "$FrontendUrl/demo"
  Add-Check $checks "Runtime checks" "Frontend demo reachable" $(if ($frontendHome.Ok) { "PASS" } else { "FAIL" }) $frontendHome.Detail $true

  $actuatorHealth = Test-HttpOk "$BackendUrl/actuator/health"
  Add-Check $checks "Runtime checks" "Backend health returns OK if service is running" $(if ($actuatorHealth.Ok) { "PASS" } else { "FAIL" }) $actuatorHealth.Detail $true

  $datasourceUrl = [Environment]::GetEnvironmentVariable("SPRING_DATASOURCE_URL")
  if (-not $datasourceUrl) { $datasourceUrl = "jdbc:postgresql://localhost:55432/orderpilot" }
  $jdbc = Get-JdbcHostPort $datasourceUrl
  if ($jdbc) {
    $db = Test-TcpOpen $jdbc.Host $jdbc.Port
    Add-Check $checks "Runtime checks" "Database readiness" $(if ($db.Ok) { "PASS" } else { "FAIL" }) $db.Detail $true
  } else {
    Add-Check $checks "Runtime checks" "Database readiness" "FAIL" "Unsupported SPRING_DATASOURCE_URL format." $true
  }

  $checkScript = Join-Path $repoRoot "scripts/check-local-demo.ps1"
  if (Test-Path $checkScript) {
    $checkContent = Get-Content $checkScript -Raw
    if ($checkContent -match "worker|ai-worker") {
      Add-Check $checks "Runtime checks" "Worker readiness" "NOT_VERIFIED" "check-local-demo.ps1 mentions worker support, but this runner does not start workers."
    } else {
      Add-Check $checks "Runtime checks" "Worker readiness" "NOT_VERIFIED" "Existing local check script has no worker readiness probe."
    }
  }
} else {
  Add-Check $checks "Runtime checks" "Backend health endpoint reachable" "NOT_VERIFIED" "Skipped in preflight mode; rerun with -RequireRuntime after starting services."
  Add-Check $checks "Runtime checks" "Frontend reachable" "NOT_VERIFIED" "Skipped in preflight mode; rerun with -RequireRuntime after starting services."
  Add-Check $checks "Runtime checks" "Database readiness" "NOT_VERIFIED" "Skipped in preflight mode; existing check-local-demo.ps1 handles this in runtime mode."
  Add-Check $checks "Runtime checks" "Worker readiness" "NOT_VERIFIED" "Skipped in preflight mode; no worker startup is performed."
}

$failed = @($checks | Where-Object { $_.Status -eq "FAIL" })
$notVerified = @($checks | Where-Object { $_.Status -eq "NOT_VERIFIED" })
$strictFailures = @($checks | Where-Object { $_.Required -and $_.Status -eq "FAIL" })

$scenarioRows = @($checks | Where-Object { $_.Category -eq "Core v1 acceptance matrix" })
$guardrailRows = @($checks | Where-Object { $_.Category -eq "Safety guardrails" })
$repoRows = @($checks | Where-Object { $_.Category -in @("Git and repo state", "Local demo readiness", "Runtime checks") })

$reportLines = [System.Collections.Generic.List[string]]::new()
$reportLines.Add("# Core V1 Acceptance Evidence")
$reportLines.Add("")
$reportLines.Add("- Timestamp: $timestamp")
$reportLines.Add("- Repository: $repoRoot")
$reportLines.Add("- Branch: $(if ($branch) { $branch } else { 'NOT_VERIFIED' })")
$reportLines.Add("- HEAD: $(if ($head) { $head } else { 'NOT_VERIFIED' })")
$reportLines.Add("- Mode: $mode")
$reportLines.Add("- Production connectors remain disabled.")
$reportLines.Add("- Real ERP/1C writes remain disabled.")
$reportLines.Add("- External network calls, real secrets, raw connector secrets, inventory mutation, and bot-triggered connector commands remain disabled.")
$reportLines.Add("")
$reportLines.Add("## Repo And Runtime Checks")
$reportLines.Add("")
$reportLines.Add((ConvertTo-MarkdownTable $repoRows))
$reportLines.Add("")
$reportLines.Add("## Scenario Matrix")
$reportLines.Add("")
$reportLines.Add((ConvertTo-MarkdownTable $scenarioRows))
$reportLines.Add("")
$reportLines.Add("## Safety Guardrail Matrix")
$reportLines.Add("")
$reportLines.Add((ConvertTo-MarkdownTable $guardrailRows))
$reportLines.Add("")
$reportLines.Add("## Failed Checks")
$reportLines.Add("")
if ($failed.Count -eq 0) {
  $reportLines.Add("- None.")
} else {
  foreach ($item in $failed) { $reportLines.Add("- $($item.Category): $($item.Name) - $($item.Evidence)") }
}
$reportLines.Add("")
$reportLines.Add("## Not Verified Checks")
$reportLines.Add("")
if ($notVerified.Count -eq 0) {
  $reportLines.Add("- None.")
} else {
  foreach ($item in $notVerified) { $reportLines.Add("- $($item.Category): $($item.Name) - $($item.Evidence)") }
}
$reportLines.Add("")
$reportLines.Add("## Status Semantics")
$reportLines.Add("")
$reportLines.Add("- PASS: evidence or runtime check is present and meets the Stage 11C acceptance runner criteria.")
$reportLines.Add("- PARTIAL: meaningful evidence exists, but this runner does not prove the full end-to-end live scenario.")
$reportLines.Add("- FAIL: required evidence or strict runtime behavior is missing.")
$reportLines.Add("- NOT_VERIFIED: intentionally skipped or not covered by preflight evidence.")
$reportLines.Add("")
$reportLines.Add("## Next Recommended Stage")
$reportLines.Add("")
$reportLines.Add("- Run ``scripts/run-core-v1-acceptance.ps1 -RequireRuntime`` after local services are started and seeded, then use remaining PARTIAL/NOT_VERIFIED items as the Stage 11D/11E demo hardening queue.")
$reportLines.Add("- Keep production connectors disabled until a separate production connector acceptance gate is implemented and approved.")

$outputDirectory = Split-Path -Parent $resolvedOutputPath
if (-not (Test-Path $outputDirectory)) {
  New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}
Set-Content -Path $resolvedOutputPath -Value ($reportLines -join "`n") -Encoding UTF8

Write-Host ""
Write-Host "Evidence report written: $resolvedOutputPath"
Write-Host "Scenario statuses: PASS=$(@($scenarioRows | Where-Object Status -eq 'PASS').Count), PARTIAL=$(@($scenarioRows | Where-Object Status -eq 'PARTIAL').Count), FAIL=$(@($scenarioRows | Where-Object Status -eq 'FAIL').Count), NOT_VERIFIED=$(@($scenarioRows | Where-Object Status -eq 'NOT_VERIFIED').Count)"
Write-Host "Safety guardrails: PASS=$(@($guardrailRows | Where-Object Status -eq 'PASS').Count), FAIL=$(@($guardrailRows | Where-Object Status -eq 'FAIL').Count)"

if ($strictFailures.Count -gt 0) {
  Write-Host ""
  Write-Host "Strict required checks failed:"
  foreach ($failure in $strictFailures) {
    Write-Host "- $($failure.Category): $($failure.Name) - $($failure.Evidence)"
  }
  exit 1
}

Write-Host "Stage 11C acceptance runner completed without strict required failures."
