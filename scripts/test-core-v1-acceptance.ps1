param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$OutputPath = (Join-Path ([System.IO.Path]::GetTempPath()) "orderpilot-core-v1-acceptance-test.md")
)

$ErrorActionPreference = "Stop"

$resolvedRoot = (Resolve-Path $RepoRoot).Path
$runner = Join-Path $resolvedRoot "scripts/run-core-v1-acceptance.ps1"

Write-Host "Stage 11C acceptance runner validation"
Write-Host "Repository: $resolvedRoot"
Write-Host "Runner: $runner"
Write-Host "Temp report: $OutputPath"

if (-not (Test-Path $runner)) {
  throw "Missing acceptance runner: $runner"
}

$tokens = $null
$parseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile($runner, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
  $parseErrors | ForEach-Object { Write-Host $_ }
  throw "Acceptance runner failed PowerShell parser validation."
}
Write-Host "OK: script parses successfully."

& $runner -Help
Write-Host "OK: help output works."

if (Test-Path $OutputPath) {
  Remove-Item -Path $OutputPath -Force
}

& $runner -OutputPath $OutputPath
if ($LASTEXITCODE -ne 0) {
  throw "Acceptance runner preflight exited with code $LASTEXITCODE."
}

if (-not (Test-Path $OutputPath)) {
  throw "Acceptance runner did not create report: $OutputPath"
}

$report = Get-Content $OutputPath -Raw
foreach ($requiredText in @(
  "Core V1 Acceptance Evidence",
  "Scenario Matrix",
  "Safety Guardrail Matrix",
  "Production connectors remain disabled",
  "Real ERP/1C writes remain disabled"
)) {
  if ($report -notmatch [regex]::Escape($requiredText)) {
    throw "Acceptance report is missing required text: $requiredText"
  }
}

Write-Host "OK: preflight mode created a Markdown evidence report."
Write-Host "Stage 11C acceptance runner validation passed."
