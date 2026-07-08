Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Require-Pattern {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $true)][string]$Pattern,
    [Parameter(Mandatory = $true)][string]$Label
  )

  if (-not (Test-Path -LiteralPath $FilePath)) {
    throw "Missing required file: $FilePath"
  }

  $content = Get-Content -LiteralPath $FilePath -Raw -Encoding UTF8
  if ($content -notmatch $Pattern) {
    throw "Missing required marker [$Label] in $FilePath (pattern: $Pattern)"
  }
}

function Require-Substring {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $true)][string]$Needle,
    [Parameter(Mandatory = $true)][string]$Label
  )

  if (-not (Test-Path -LiteralPath $FilePath)) {
    throw "Missing required file: $FilePath"
  }

  $content = Get-Content -LiteralPath $FilePath -Raw -Encoding UTF8
  if (-not $content.Contains($Needle)) {
    throw "Missing required marker [$Label] in $FilePath (substring: $Needle)"
  }
}

Write-Host "Checking required skip-safe gate markers in workflows..."

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$wfRoot = Join-Path $repoRoot ".github\workflows"

$frontend = Join-Path $wfRoot "frontend.yml"
$aiWorker = Join-Path $wfRoot "ai-worker.yml"
$semgrep  = Join-Path $wfRoot "semgrep.yml"
$snyk     = Join-Path $wfRoot "snyk.yml"

# Frontend Gate
Require-Pattern -FilePath $frontend -Pattern "(?m)^[ \\t]*name:[ \\t]*Frontend Gate[ \\t]*\r?$" -Label "Frontend Gate job name"
Require-Substring -FilePath $frontend -Needle "needs.changes.result" -Label "Frontend Gate checks needs.changes.result"

# AI Worker Gate
Require-Pattern -FilePath $aiWorker -Pattern "(?m)^[ \\t]*name:[ \\t]*AI Worker Gate[ \\t]*\r?$" -Label "AI Worker Gate job name"
Require-Substring -FilePath $aiWorker -Needle "needs.changes.result" -Label "AI Worker Gate checks needs.changes.result"

# Semgrep Gate
Require-Pattern -FilePath $semgrep -Pattern "(?m)^[ \\t]*name:[ \\t]*Semgrep Gate[ \\t]*\r?$" -Label "Semgrep Gate job name"
Require-Substring -FilePath $semgrep -Needle "needs.changes.result" -Label "Semgrep Gate checks needs.changes.result"

# Snyk Gate
Require-Pattern -FilePath $snyk -Pattern "(?m)^[ \\t]*name:[ \\t]*Snyk Gate[ \\t]*\r?$" -Label "Snyk Gate job name"
Require-Pattern -FilePath $snyk -Pattern "(?m)snyk_dependency_relevant" -Label "Snyk changes output snyk_dependency_relevant"
Require-Pattern -FilePath $snyk -Pattern "(?m)snyk_workflow_relevant" -Label "Snyk changes output snyk_workflow_relevant"
Require-Substring -FilePath $snyk -Needle "needs.changes.result" -Label "Snyk Gate checks needs.changes.result"

Write-Host "OK: release gate markers present."

