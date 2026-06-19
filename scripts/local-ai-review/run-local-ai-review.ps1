<#
.SYNOPSIS
  OP-CAP-38/COORD — Local AI Review Harness for OrderPilot Core.

.DESCRIPTION
  Runs a SEQUENTIAL, bounded, low-temperature local review of a scoped Stage A
  (OP-CAP-37) diff using locally installed Ollama models. Local models are
  REVIEWERS, NOT AUTHORITY: this script only reads a safe input package and saves
  advisory output + metrics. It NEVER modifies repository code, NEVER calls a real
  connector, and NEVER feeds secrets/.env/credentials/raw customer data to a model.

  Safety properties:
    * Input package is built ONLY from: git status --short, git diff --stat, and
      `git show <ref> --` over an explicit Stage A file allowlist, plus an embedded
      Stage A summary + test results. No .env / secrets / node_modules / build output.
    * Models run one at a time (never parallel) so two 30B/32B models are never
      co-resident.
    * Low temperature, bounded context (num_ctx) and bounded output (num_predict).
    * Captures duration, Ollama response metadata, and ollama/java/node process
      memory before/after each run.
    * Missing models are reported and skipped, never auto-pulled.

.PARAMETER DiffRef
  Git ref whose Stage A change set is reviewed. Default: the OP-CAP-37 commit.

.PARAMETER OutputDir
  Directory for raw model outputs + run metrics. Default: scripts/local-ai-review/output.

.PARAMETER OllamaHost
  Ollama base URL. Default http://localhost:11434.

.PARAMETER IncludeHeavyReviewer
  Opt in to deepseek-r1:32b. It is excluded from the default rota because OP-CAP-38/COORD
  observed two local Ollama crashes while running it.

.PARAMETER SelfTest
  Validate harness safety defaults without contacting Ollama.

.EXAMPLE
  pwsh ./scripts/local-ai-review/run-local-ai-review.ps1
#>
[CmdletBinding()]
param(
  [string]$DiffRef = "5103aa1",
  [string]$OutputDir,
  [string]$OllamaHost = "http://localhost:11434",
  [switch]$IncludeHeavyReviewer,
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
# Resolve script dir robustly (param defaults can see an empty $PSScriptRoot under some hosts).
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } elseif ($PSCommandPath) { Split-Path -Parent $PSCommandPath } else { (Get-Location).Path }
$RepoRoot = (git -C $ScriptDir rev-parse --show-toplevel).Trim()
if (-not $OutputDir) { $OutputDir = Join-Path $ScriptDir "output" }

# --- Safe Stage A file allowlist (no secrets, no env, no build output) ---------
$StageAFiles = @(
  "apps/core-api/src/main/java/com/orderpilot/api/dto/Stage12CDtos.java",
  "apps/core-api/src/main/java/com/orderpilot/application/services/integration/ChangeRequestService.java",
  "apps/core-api/src/main/java/com/orderpilot/application/services/workspace/QuoteReviewService.java",
  "apps/core-api/src/test/java/com/orderpilot/application/services/workspace/QuoteReviewServiceTest.java",
  "apps/core-api/src/test/java/com/orderpilot/api/rest/QuoteReviewControllerTest.java",
  "apps/core-api/src/test/java/com/orderpilot/application/services/workspace/QuoteDraftServiceStage12ATest.java"
)

$DeniedInputPathPatterns = @(
  '(^|/)\.env($|[./_-])',
  '(^|/)(secrets?|credentials?|private[-_]?keys?)(/|$)',
  '(^|/)(node_modules|target|\.next|dist|build|coverage|\.cache|local-data|private-data|customer-data|uploads|tmp|temp)(/|$)',
  '\.(pem|key|p12|pfx|crt|jks|keystore|dump|bak|sqlite|db)$'
)

function Test-SafeReviewPath {
  param([Parameter(Mandatory = $true)][string]$Path)
  $normalized = ($Path -replace '\\', '/').Trim()
  foreach ($pattern in $DeniedInputPathPatterns) {
    if ($normalized -match $pattern) { return $false }
  }
  return $true
}

function Assert-SafeReviewPath {
  param([Parameter(Mandatory = $true)][string]$Path)
  if (-not (Test-SafeReviewPath -Path $Path)) {
    throw "Denied path in local AI review input package: $Path"
  }
}

function Safe-GitLines {
  param([string[]]$Lines)
  $safe = @()
  foreach ($line in $Lines) {
    $blocked = $false
    foreach ($pattern in $DeniedInputPathPatterns) {
      if ($line -and (($line -replace '\\', '/') -match $pattern)) {
        $blocked = $true
        break
      }
    }
    if (-not $blocked) { $safe += $line }
  }
  return $safe
}

foreach ($f in $StageAFiles) { Assert-SafeReviewPath -Path $f }

# --- Embedded Stage A summary + verified test results (source of truth) --------
$StageASummary = @"
STAGE A — OP-CAP-37 IMPLEMENTATION SUMMARY (verified against committed code + tests)

Flow: assembled quote draft (status DRAFT_ASSEMBLED, no approval pending) ->
QuoteReviewService.assembleDraft calls ChangeRequestService.prepareQuoteExternalSyncCandidate
-> prepares EXACTLY ONE tenant-scoped, non-executed external-sync ChangeRequest candidate.

Candidate shape:
  targetSystem      = INTERNAL_SYNC_CANDIDATE   (neutral target; demo executor
                                                 executeStage9DemoChangeRequest requires
                                                 DEMO_ERP, so candidate can never execute)
  requestedAction   = QUOTE_EXTERNAL_SYNC_CANDIDATE
  sourceType        = QUOTE_REVIEW
  sourceId          = quoteId
  approvalStatus    = PENDING_APPROVAL          (ChangeRequest 10-arg ctor default)
  executionStatus   = EXECUTION_DISABLED        (ChangeRequest 10-arg ctor default)

Backend owns: tenantId (TenantContext), actorId (RequestActorResolver), approvalStatus,
executionStatus, idempotencyKey, audit metadata. Client sends business intent only
(reasonCode/note); body-supplied authority/state/total fields are ignored.

Dedup: deterministic per-quote idempotency key
  "opcap37:quote-external-sync-candidate:<tenant>:<quote>"
-> repeated assemble-draft never creates a duplicate active candidate. Audit:
QUOTE_DRAFT_EXTERNAL_SYNC_CANDIDATE_CREATED / _REUSED.

External-write safety: prepareQuoteExternalSyncCandidate calls only
changeRequestRepository.save(...) + auditEventService.record(...). It does NOT call emit()
(no OutboxEvent) and does NOT call any connector. externalExecution stays DISABLED.
When approval is still required, NO candidate is prepared this slice.

Response DTO Stage12CDtos.QuoteDraftSummary adds externalSyncCandidateStatus
(PREPARED | PENDING_INTERNAL_APPROVAL) only — no candidate id, target system, or connector data.

VERIFIED TEST RESULTS (mvn targeted, this run):
  QuoteReviewServiceTest          19/19 pass
  QuoteReviewControllerTest        6/6  pass
  QuoteDraftServiceStage12ATest   22/22 pass
  ChangeRequestServiceTest        10/10 pass
  ChangeRequestServiceStage9Test   6/6  pass
  TOTAL                           63/63 pass, 0 failures, 0 errors

Key proving tests:
  assembleDraftPreparesTenantScopedNonExecutedSyncCandidate (asserts target/action/status,
    executedAt null, externalReference null, audit CREATED, connectorSyncEvents EMPTY,
    outboxEvents EMPTY)
  repeatedAssembleDoesNotDuplicateActiveCandidate (dedup + _REUSED audit)
  assembleRequiringApprovalDoesNotPrepareCandidate (pending approval -> no candidate)
  assembleDraftCrossTenantAccessBlocked (cross-tenant denied)
  assembleDraftUsesTrustedActorIgnoresClientAuthorityAndReturnsSafeSummary (controller,
    malicious override ignored + response-leak assertions)
"@

# --- Reviewer definitions ------------------------------------------------------
$DefaultReviewers = @(
  @{
    Model = "qwen3-coder:30b"
    Role  = "Code-level review of changed files"
    Prompt = @"
You are a senior Java/Spring/TypeScript code reviewer for Operant / OrderPilot. Review ONLY the provided diff/report. Focus on:
1. ChangeRequest candidate safety
2. DTO/request/response contract
3. tenant isolation
4. malicious payload/mass assignment
5. idempotency/deduplication
6. audit event correctness
7. no connector execution
8. no external execution outbox
9. tests quality
10. parasite complexity
11. performance or memory risk introduced by the diff
Do not propose broad rewrites. Do not propose real ERP/1C execution. Do not invent new architecture.
Output sections:
- P0 blockers
- P1 risks
- Missing tests
- Complexity issues
- Performance/memory concerns
- Safe to proceed: yes/no
"@
  },
  @{
    Model = "qwen3:30b"
    Role  = "Product/security stage-gate sanity review"
    Prompt = @"
Review the OP-CAP-37 final report and provided diff as a product/security gate.
Question: Can this slice be accepted as a safe non-executed ChangeRequest candidate layer?
Check: no real external execution; tenant isolation; backend-owned authority; idempotency; audit; response safety; no overengineering; honest "not proven" section; whether OP-CAP-38/COORD has enough evidence.
Output:
- Accepted / Not accepted
- Reasons
- Required fixes before AI Model Runtime Foundation
- What is already strong
- What remains weak
"@
  }
)

$HeavyReviewer = @{
  Model = "deepseek-r1:32b"
  Role  = "Root-cause and business-logic review (optional/heavy)"
  Prompt = @"
You are a root-cause and business-logic reviewer for Operant / OrderPilot. Given this OP-CAP-37 implementation/report, verify the invariant:
Assembled quote draft -> non-executed ChangeRequest candidate -> no connector call -> no executable external outbox -> externalExecution disabled -> audit -> idempotent/deduped -> malicious client cannot override authority/status/execution fields.
Find hidden logical failure modes:
- lifecycle bypass
- approval bypass
- executionStatus spoofing
- duplicate candidate creation
- wrong tenant access
- unsafe response data
- misleading audit
- concurrency/idempotency edge cases
- business rule gaps
Output sections:
- P0 blockers
- P1 business risks
- Not proven
- Recommended next verification
- Safe to proceed: yes/no
Keep reasoning concise.
"@
}

$Reviewers = @()
$Reviewers += $DefaultReviewers
if ($IncludeHeavyReviewer) { $Reviewers += $HeavyReviewer }

if ($SelfTest) {
  if (-not (Test-SafeReviewPath -Path "apps/core-api/src/main/java/com/orderpilot/api/dto/Stage12CDtos.java")) {
    throw "SelfTest failed: safe allowlisted source path was rejected"
  }
  foreach ($unsafe in @(".env", "secrets/api.key", "node_modules/pkg/index.js", "target/classes/App.class", "customer-data/raw.json")) {
    if (Test-SafeReviewPath -Path $unsafe) {
      throw "SelfTest failed: unsafe path was allowed: $unsafe"
    }
  }
  if (-not $IncludeHeavyReviewer -and (($Reviewers | ForEach-Object { $_.Model }) -contains "deepseek-r1:32b")) {
    throw "SelfTest failed: heavy reviewer is in default rota"
  }
  $hasCoder = (@($Reviewers | Where-Object { $_.Model -eq "qwen3-coder:30b" }).Count -eq 1)
  $hasGate = (@($Reviewers | Where-Object { $_.Model -eq "qwen3:30b" }).Count -eq 1)
  if (-not $hasCoder -or -not $hasGate) {
    throw "SelfTest failed: default qwen reviewers are missing"
  }
  Write-Host "Local AI review harness SelfTest OK"
  return
}

# --- Build safe input package --------------------------------------------------
function Build-InputPackage {
  $sb = [System.Text.StringBuilder]::new()
  [void]$sb.AppendLine("# OP-CAP-37 LOCAL AI REVIEW INPUT PACKAGE")
  [void]$sb.AppendLine("# (safe: file names + scoped diff + summary only; no secrets/.env/credentials)")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## git status --short")
  [void]$sb.AppendLine((Safe-GitLines -Lines (git -C $RepoRoot -c core.excludesfile= status --short) | Out-String))
  [void]$sb.AppendLine("## git diff --stat (working tree)")
  [void]$sb.AppendLine((Safe-GitLines -Lines (git -C $RepoRoot diff --stat -- $StageAFiles) | Out-String))
  [void]$sb.AppendLine("## Stage A summary + verified test results")
  [void]$sb.AppendLine($StageASummary)
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## Stage A scoped diff (ref $DiffRef, allowlisted files only)")
  foreach ($f in $StageAFiles) {
    Assert-SafeReviewPath -Path $f
    [void]$sb.AppendLine("### $f")
    [void]$sb.AppendLine('```diff')
    [void]$sb.AppendLine((git -C $RepoRoot show $DiffRef -- $f | Out-String))
    [void]$sb.AppendLine('```')
  }
  return $sb.ToString()
}

function Get-ReviewProcMemory {
  $rows = Get-Process -Name ollama, java, node -ErrorAction SilentlyContinue |
    Select-Object ProcessName, Id, @{N = 'PM_MB'; E = { [math]::Round($_.PM / 1MB, 1) } }, @{N = 'WS_MB'; E = { [math]::Round($_.WS / 1MB, 1) } }
  return $rows
}

# --- Main ----------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runLog = Join-Path $OutputDir "run-$stamp.log"

Write-Host "== OP-CAP-38/COORD Local AI Review Harness =="
Write-Host "Repo:    $RepoRoot"
Write-Host "DiffRef: $DiffRef"
Write-Host "Output:  $OutputDir"
Write-Host "Heavy reviewer included: $IncludeHeavyReviewer"
Write-Host ""

# Ollama availability
$installed = @()
try {
  $listRaw = (& ollama list) 2>$null
  $installed = $listRaw | Select-Object -Skip 1 | ForEach-Object { ($_ -split '\s+')[0] } | Where-Object { $_ }
}
catch {
  Write-Warning "ollama CLI not available: $($_.Exception.Message)"
}
Write-Host "Installed models: $($installed -join ', ')"

$package = Build-InputPackage
$packagePath = Join-Path $OutputDir "input-package-$stamp.md"
$package | Out-File -FilePath $packagePath -Encoding utf8
Write-Host "Input package: $packagePath ($([math]::Round((Get-Item $packagePath).Length / 1KB, 1)) KB)"
Write-Host ""

$results = @()
foreach ($r in $Reviewers) {
  $model = $r.Model
  Write-Host "---- Reviewer: $model ($($r.Role)) ----"
  if ($installed -notcontains $model) {
    Write-Warning "MODEL MISSING: $model -- skipped (not auto-pulled)."
    $results += [pscustomobject]@{ Model = $model; Role = $r.Role; Status = "MISSING"; DurationSec = $null }
    continue
  }

  $memBefore = Get-ReviewProcMemory
  $prompt = "$($r.Prompt)`n`n===== INPUT PACKAGE BEGIN =====`n$package`n===== INPUT PACKAGE END ====="
  $isHeavy = $model -eq "deepseek-r1:32b"
  $numCtx = if ($isHeavy) { 6144 } else { 8192 }
  $numPredict = if ($isHeavy) { 1500 } else { 3000 }
  $timeoutSec = if ($isHeavy) { 900 } else { 1800 }
  $body = @{
    model   = $model
    prompt  = $prompt
    stream  = $false
    options = @{ temperature = 0.1; num_ctx = $numCtx; num_predict = $numPredict }
  } | ConvertTo-Json -Depth 6
  # Send as UTF-8 bytes: Windows PowerShell 5.1 mis-sets Content-Length for a string body
  # containing multi-byte chars, which truncates a large body and yields a 400 from Ollama.
  $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)

  $status = "OK"
  $resp = $null
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  try {
    $resp = Invoke-RestMethod -Uri "$OllamaHost/api/generate" -Method Post -Body $bodyBytes -ContentType "application/json" -TimeoutSec $timeoutSec
  }
  catch {
    $status = "FAILED: $($_.Exception.Message)"
    Write-Warning "Model run failed: $status"
  }
  $sw.Stop()
  $memAfter = Get-ReviewProcMemory

  $durSec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
  $outPath = Join-Path $OutputDir "review-$($model -replace '[:]','_')-$stamp.md"

  $meta = ""
  if ($resp) {
    $ns = 1e9
    $meta = @"
total_duration_s      : $([math]::Round(($resp.total_duration / $ns), 2))
load_duration_s       : $([math]::Round(($resp.load_duration / $ns), 2))
prompt_eval_count     : $($resp.prompt_eval_count)
prompt_eval_duration_s: $([math]::Round(($resp.prompt_eval_duration / $ns), 2))
eval_count            : $($resp.eval_count)
eval_duration_s       : $([math]::Round(($resp.eval_duration / $ns), 2))
"@
  }

  $header = @"
# Local AI Review — $model
Role: $($r.Role)
Run status: $status
Wall duration (s): $durSec

## Ollama response metadata
$meta

## Process memory BEFORE (MB)
$($memBefore | Format-Table -AutoSize | Out-String)
## Process memory AFTER (MB)
$($memAfter | Format-Table -AutoSize | Out-String)

## Model output
"@
  # Reasoning models (deepseek-r1, qwen3) place the review in `thinking`; capture both.
  $answer = if ($resp -and $resp.response) { $resp.response.Trim() } else { "" }
  $thinking = if ($resp -and $resp.thinking) { $resp.thinking.Trim() } else { "" }
  $modelOutput = ""
  if ($thinking) { $modelOutput += "### Reasoning (thinking)`n$thinking`n`n" }
  if ($answer) { $modelOutput += "### Answer`n$answer" }
  if (-not $modelOutput) { $modelOutput = "(no response)" }
  ($header + $modelOutput) | Out-File -FilePath $outPath -Encoding utf8
  Write-Host "  status=$status duration=${durSec}s eval_count=$($resp.eval_count) -> $outPath"

  $results += [pscustomobject]@{ Model = $model; Role = $r.Role; Status = $status; DurationSec = $durSec; OutputPath = $outPath; PromptEval = $resp.prompt_eval_count; EvalCount = $resp.eval_count }
}

# --- Run summary ---------------------------------------------------------------
Write-Host ""
Write-Host "== Run summary =="
$results | Format-Table Model, Status, DurationSec, PromptEval, EvalCount -AutoSize
$results | ConvertTo-Json -Depth 4 | Out-File -FilePath (Join-Path $OutputDir "summary-$stamp.json") -Encoding utf8
Write-Host "Outputs in: $OutputDir"
Write-Host "NOTE: model output is ADVISORY ONLY. Verify against repo + tests before acting."
