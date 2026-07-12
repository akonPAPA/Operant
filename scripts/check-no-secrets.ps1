param(
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
# Use single-quoted regexes so PowerShell does not reinterpret quotes/backticks.
$patterns = @(
  '(?i)(TELEGRAM_BOT_TOKEN|OPENAI_API_KEY|ORDERPILOT_[A-Z0-9_]*(PASSWORD|SECRET|API_KEY)|SPRING_DATASOURCE_PASSWORD)\s*=\s*[^\s"''<>]+',
  '(?i)(?<![A-Za-z0-9_])(password|secret|api_key|private_key)(?![A-Za-z0-9_])\s*=\s*["''][^"'']{16,}["'']',
  'BEGIN PRIVATE KEY'
)
# Placeholder / synthetic allowlist only. Never allow the PEM marker itself as a blanket exception.
$allowed = '(?i)(TODO|example|changeme|change-me|placeholder|non-production|forbidden pattern|test-only|local-test|local-dev-only|fake|redacted|null|test_password|e2e|fixture|synthetic)'
# Inventory exclusions: generated/build/cache/vendor, plus synthetic test-fixture trees that intentionally
# embed secret-shaped values. Production/main and docs remain scanned. Covered by -SelfTest.
$generatedPath = '(^|[/\\])(target|\.git|\.next|node_modules|graphify-out|playwright-report|test-results|coverage)([/\\]|$)|(^|[/\\])src[/\\]test([/\\]|$)|(^|[/\\])(tests|e2e|__tests__)([/\\]|$)|\.env\.example$'

function Test-IsPrivateKeyMarkerDocumentation([string]$line) {
  # Real PEM blobs include key material or an END marker; denylist/docs cite the header in quotes.
  if ($line -match '(?i)END PRIVATE KEY') { return $false }
  if ($line -match '[A-Za-z0-9+/]{40,}={0,2}') { return $false }
  if ($line -match '(?i)(["''`]).{0,12}-----?begin (rsa )?private key-----?.{0,12}\1') { return $true }
  if ($line -match '(?i)(forbidden|denylist|deny-list|marker|must never|never appear|sanitiz|rejects?:)') { return $true }
  return $false
}

function Redact-SecretLine([string]$line) {
  $redacted = $line -replace '(?i)(TELEGRAM_BOT_TOKEN\s*=\s*)\S+', '${1}[REDACTED]'
  $redacted = $redacted -replace '(?i)(OPENAI_API_KEY\s*=\s*)\S+', '${1}[REDACTED]'
  $redacted = $redacted -replace '(?i)(ORDERPILOT_[A-Z0-9_]*(?:PASSWORD|SECRET|API_KEY)\s*=\s*)\S+', '${1}[REDACTED]'
  $redacted = $redacted -replace '(?i)(SPRING_DATASOURCE_PASSWORD\s*=\s*)\S+', '${1}[REDACTED]'
  $redacted = $redacted -replace '(?i)(?<![A-Za-z0-9_])(password|secret|api_key|private_key)(\s*=\s*)\S+', '${1}${2}[REDACTED]'
  if ($redacted -match 'BEGIN PRIVATE KEY') {
    $redacted = 'BEGIN PRIVATE KEY [REDACTED]'
  }
  return $redacted
}

function New-Candidate([string]$relativePath, [bool]$tracked) {
  [pscustomobject]@{
    RelativePath = $relativePath
    FullName = Join-Path $root $relativePath
    Tracked = $tracked
  }
}

function Get-GitCandidateFiles {
  $tracked = @(git -C $root ls-files --cached)
  if ($LASTEXITCODE -ne 0) {
    throw "git ls-files --cached failed; refusing to report a clean secret scan"
  }
  $untracked = @(git -C $root ls-files --others --exclude-standard)
  if ($LASTEXITCODE -ne 0) {
    throw "git ls-files --others failed; refusing to report a clean secret scan"
  }
  # Index entries already deleted in the worktree are pending removal — skip them
  # (same race class as an untracked file disappearing after inventory).
  $deleted = @{}
  foreach ($relative in @(git -C $root ls-files --deleted)) {
    if ($relative) {
      $deleted[($relative -replace '\\', '/')] = $true
    }
  }
  if ($LASTEXITCODE -ne 0) {
    throw "git ls-files --deleted failed; refusing to report a clean secret scan"
  }
  $seen = @{}
  foreach ($relative in ($tracked + $untracked)) {
    if (-not $relative) { continue }
    $normalized = $relative -replace '\\', '/'
    if ($seen.ContainsKey($normalized)) { continue }
    $seen[$normalized] = $true
    if ($deleted.ContainsKey($normalized)) { continue }
    if ($normalized -match $generatedPath) { continue }
    if ($normalized -eq 'scripts/check-no-secrets.ps1') { continue }
    $isTracked = ($tracked -contains $relative) -or (($tracked | ForEach-Object { $_ -replace '\\', '/' }) -contains $normalized)
    New-Candidate $normalized $isTracked
  }
}

function Find-SecretFindings($candidates) {
  $findings = @()
  foreach ($file in $candidates) {
    if (-not (Test-Path -LiteralPath $file.FullName -PathType Leaf)) {
      if ($file.Tracked) {
        $findings += "$($file.RelativePath): unreadable tracked file"
      }
      continue
    }
    foreach ($pattern in $patterns) {
      try {
        $matches = Select-String -LiteralPath $file.FullName -Pattern $pattern -CaseSensitive:$false -ErrorAction Stop
      } catch {
        if (-not (Test-Path -LiteralPath $file.FullName -PathType Leaf)) {
          if ($file.Tracked) {
            $findings += "$($file.RelativePath): unreadable tracked file"
          }
          continue
        }
        $findings += "$($file.RelativePath): unreadable file"
        break
      }
      foreach ($match in $matches) {
        $line = $match.Line
        if ($line -match $allowed) { continue }
        # Production denylist / docs citations of the PEM header are not key material.
        if ($pattern -eq 'BEGIN PRIVATE KEY' -and (Test-IsPrivateKeyMarkerDocumentation $line)) { continue }
        $findings += "$($file.RelativePath):$($match.LineNumber): $(Redact-SecretLine $line.Trim())"
      }
    }
  }
  return $findings
}

if ($SelfTest) {
  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("op-secret-scan-" + [System.Guid]::NewGuid())
  New-Item -ItemType Directory -Path $tmp | Out-Null
  try {
    $prodDir = Join-Path $tmp "apps/core-api/src/main/java"
    $testDir = Join-Path $tmp "apps/core-api/src/test/java"
    New-Item -ItemType Directory -Force -Path $prodDir, $testDir | Out-Null
    $secretFile = Join-Path $tmp "plain secret.txt"
    $allowedFile = Join-Path $tmp "allowed.txt"
    $pemFile = Join-Path $tmp "key.pem"
    $envNamed = Join-Path $tmp "named.env"
    $markerFile = Join-Path $prodDir "Markers.java"
    $prodSecret = Join-Path $prodDir "Leaked.java"
    $testSecret = Join-Path $testDir "Fixture.java"
    Set-Content -LiteralPath $secretFile -Value 'password="super-secret-value-123"' -NoNewline
    Set-Content -LiteralPath $allowedFile -Value "password=placeholder" -NoNewline
    Set-Content -LiteralPath $pemFile -Value "-----BEGIN PRIVATE KEY-----`nabc`n-----END PRIVATE KEY-----" -NoNewline
    Set-Content -LiteralPath $envNamed -Value "TELEGRAM_BOT_TOKEN=123456:ABC-DEF_real_token_value" -NoNewline
    Set-Content -LiteralPath $markerFile -Value 'private static final List<String> FORBIDDEN = List.of("-----begin private key-----");' -NoNewline
    Set-Content -LiteralPath $prodSecret -Value 'password="production-leak-value-99"' -NoNewline
    Set-Content -LiteralPath $testSecret -Value 'password="test-fixture-secret-99"' -NoNewline

    $candidates = @(
      [pscustomobject]@{ RelativePath = "plain secret.txt"; FullName = $secretFile; Tracked = $false },
      [pscustomobject]@{ RelativePath = "allowed.txt"; FullName = $allowedFile; Tracked = $false },
      [pscustomobject]@{ RelativePath = "key.pem"; FullName = $pemFile; Tracked = $false },
      [pscustomobject]@{ RelativePath = "named.env"; FullName = $envNamed; Tracked = $false },
      [pscustomobject]@{ RelativePath = "apps/core-api/src/main/java/Markers.java"; FullName = $markerFile; Tracked = $true },
      [pscustomobject]@{ RelativePath = "apps/core-api/src/main/java/Leaked.java"; FullName = $prodSecret; Tracked = $true },
      [pscustomobject]@{ RelativePath = "generated-gone.md"; FullName = (Join-Path $tmp "generated-gone.md"); Tracked = $false }
    )
    $findings = @(Find-SecretFindings $candidates)
    if ($findings.Count -ne 4) { throw "expected exactly four self-test findings, got $($findings.Count): $($findings -join ' | ')" }
    foreach ($finding in $findings) {
      if ($finding -match "super-secret-value|ABC-DEF_real_token|production-leak-value|-----BEGIN PRIVATE KEY-----\s*\nabc") {
        throw "self-test finding leaked secret material: $finding"
      }
      if ($finding -notmatch "\[REDACTED\]") {
        throw "self-test finding is missing redaction marker: $finding"
      }
    }
    if (-not ($findings | Where-Object { $_ -match "BEGIN PRIVATE KEY" })) {
      throw "self-test did not detect PEM private key marker"
    }
    if (-not ($findings | Where-Object { $_ -match "Leaked\.java" })) {
      throw "self-test did not detect production main-source secret"
    }
    if ($findings | Where-Object { $_ -match "Markers\.java" }) {
      throw "self-test incorrectly flagged quoted denylist marker"
    }
    # Exclusion policy must skip src/test trees but not production paths that merely contain 'test' in a name.
    if ("apps/core-api/src/main/java/ContestHelper.java" -match $generatedPath) {
      throw "generatedPath incorrectly excludes production ContestHelper path"
    }
    if ("apps/core-api/src/test/java/Fixture.java" -notmatch $generatedPath) {
      throw "generatedPath must exclude src/test fixture trees"
    }
    if ("apps/web-dashboard/tests/foo.test.mjs" -notmatch $generatedPath) {
      throw "generatedPath must exclude tests/ trees"
    }
    Write-Output "Secret scanner self-test passed."
    exit 0
  } finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
  }
}

try {
  $candidates = @(Get-GitCandidateFiles)
  $findings = @(Find-SecretFindings $candidates)
} catch {
  Write-Output "Secret scanner failed closed: $($_.Exception.Message)"
  exit 2
}
if ($findings.Count -gt 0) {
  Write-Output "Potential hardcoded secrets found:"
  $findings | ForEach-Object { Write-Output $_ }
  exit 1
}
Write-Output "No obvious hardcoded secrets found."
