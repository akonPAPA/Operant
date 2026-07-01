$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$dockerfile = Join-Path $repoRoot "apps/core-api/Dockerfile"

if (-not (Test-Path $dockerfile)) {
  throw "Missing Dockerfile at $dockerfile"
}

$text = Get-Content -Raw -Path $dockerfile
if ([string]::IsNullOrWhiteSpace($text)) {
  throw "core-api Dockerfile is empty"
}

# Normalize BOM and line endings for Windows PowerShell / PowerShell Core compatibility.
$text = $text.TrimStart([char]0xFEFF)
$lines = $text -split "`r?`n"

$stages = New-Object System.Collections.Generic.List[object]
$currentStageLines = New-Object System.Collections.Generic.List[string]

foreach ($line in $lines) {
  $trimmed = $line.Trim()

  if ($trimmed -match '^(?i:FROM)\s+') {
    if ($currentStageLines.Count -gt 0) {
      $stages.Add(($currentStageLines -join "`n"))
      $currentStageLines.Clear()
    }
  }

  $currentStageLines.Add($line)
}

if ($currentStageLines.Count -gt 0) {
  $stages.Add(($currentStageLines -join "`n"))
}

if ($stages.Count -eq 0) {
  throw "core-api Dockerfile has no FROM stages"
}

$verifyStage = $null
$packageStage = $null

foreach ($stage in $stages) {
  $firstLine = (($stage -split "`n") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1).Trim()

  if ($firstLine -match '^(?i:FROM)\s+\S+.*\s+(?i:AS)\s+verify\s*$') {
    $verifyStage = $stage
  }

  if ($firstLine -match '^(?i:FROM)\s+verify\s+(?i:AS)\s+package\s*$') {
    $packageStage = $stage
  }
}

if ([string]::IsNullOrWhiteSpace($verifyStage)) {
  throw "core-api Dockerfile is missing a verify stage"
}

if ($verifyStage -notmatch '(?im)^\s*RUN\s+.*\bmvn\b.*\btest\b') {
  throw "core-api Dockerfile verify stage does not run Maven tests"
}

if ($verifyStage -match '(?i)-DskipTests\b|-Dmaven\.test\.skip(?:=true)?\b') {
  throw "core-api Dockerfile verify stage disables tests"
}

if ([string]::IsNullOrWhiteSpace($packageStage)) {
  throw "core-api Dockerfile package stage does not derive from verify"
}

$finalStage = $stages[$stages.Count - 1]

if ($finalStage -notmatch '(?im)^\s*COPY\s+.*--from=package\b') {
  throw "core-api Dockerfile final image does not copy the verified package artifact from package stage"
}

Write-Host "OK: core-api Dockerfile runs tests and chains verify -> package -> final image."