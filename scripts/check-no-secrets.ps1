param(
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

# F11: labelled detectors. Each entry is a category + a single-quoted regex (so PowerShell does not
# reinterpret quotes/backticks). Detection is intentionally broad; false positives on genuine synthetic
# fixtures are handled by an EXACT fingerprint allowlist (below), never by broad word suppression.
$detectors = @(
  @{ Label = "named-env-secret"; Pattern = '(?i)(TELEGRAM_BOT_TOKEN|OPENAI_API_KEY|ORDERPILOT_[A-Z0-9_]*(PASSWORD|SECRET|API_KEY)|SPRING_DATASOURCE_PASSWORD|AWS_SECRET_ACCESS_KEY|GITHUB_TOKEN|SLACK_TOKEN)\s*[:=]\s*["'']?(?<secret>[^\s"''<>]{8,})' },
  @{ Label = "assigned-secret";  Pattern = '(?i)(?<![A-Za-z0-9_])(password|passwd|secret|api[_-]?key|private[_-]?key|access[_-]?token|auth[_-]?token)(?![A-Za-z0-9_])\s*[:=]\s*["''](?<secret>[^"'']{16,})["'']' },
  @{ Label = "private-key-pem";  Pattern = 'BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY' },
  @{ Label = "github-token";     Pattern = '(?<![A-Za-z0-9])(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}' },
  @{ Label = "aws-access-key";   Pattern = '(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![A-Za-z0-9])' },
  @{ Label = "slack-token";      Pattern = '(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{10,}' },
  @{ Label = "jwt";              Pattern = '(?<![A-Za-z0-9])eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}' },
  @{ Label = "db-url-cred";      Pattern = '(?i)(?<secret>(postgres(ql)?|mysql|mongodb|redis|amqp)://[^\s:/@]+:[^\s:/@]{4,}@)' }
)

# F11: EXACT fixture allowlist keyed by the SHA-256 fingerprint of the extracted secret-shaped token.
# Changing a fixture value changes its fingerprint and re-triggers detection. Each entry documents why
# the synthetic value is safe, who owns it, and (where applicable) a review date. NO broad word-based
# suppression (example / fake / test-only / fixture / synthetic / null) is used anywhere.
$fingerprintAllowlist = @{
    '60da56ce41d1631d4066bc9fe6b0b6011293e4b56ab934093f368b5501668d89' = 'synthetic non-production fixture; owner=security; review=2027-01-01'
    '80aa1802235690ff9ff20a6da599e1cce0dd1717b884cd9de9b6d7fa4db3d61a' = 'synthetic ai-worker test fixture value; owner=security; review=2027-01-01'
    '4f0f455d3eb8d4f8475997d37bbfc4eaa05fca07fae9b8a89c8fc12ba0fd512b' = 'synthetic ai-worker test fixture value; owner=security; review=2027-01-01'
    '72adc6987ba2e6e610f1811e1928a9d78ce13d9b0cce07187dcb30094d318103' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    'bc792684fbe4aee7838bffb66b7b4d2c635df9e2d800e02ada4d2d2e94db1036' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    'c28b19f37528a15e988a5ca0e0cf5f3bf4f9380b72ca5bd2f61b71ee2caefced' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    'b72ef6aa26d5d795a23d6a5db15f0ce6ce8f00ad19d368ced894ac4b5c9e4fca' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    'f65df5d658780d5d5b56c40362678de24c826e05f3c4d4d8c9db35943a67bfed' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    '6a34e9cf66e854e6e1b79ceebaac12897fd6845a57d2cf367ca33a74fdbc1afb' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    '746b4ad1ca9129e1caf080bf9406d43531b8bbf97f42cc1597ee4f3d4663938e' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    '210dd275cdec1e38b96633087edfd69601a5bd1af45f01d9cf38a1e1c6d94f89' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    'a06cafb12819ffc9b4009e17fc97e5d659026079ab64fd9f5bb1bc9b12e3af18' = 'synthetic backend test fixture secret; owner=security; review=2027-01-01'
    '82210ead4c45332429ecc82ac983f84ce76c0b058a03fa0dadca30a730d96ca1' = 'synthetic test PEM key fixture; owner=security; review=2027-01-01'
    'a451469ecf7603734a2991da90c324caf225068deb11e973c3bd49d04185ca9a' = 'synthetic Playwright E2E test config value; owner=security; review=2027-01-01'
    '2cae604e16ede2b50159e4f1139607e47097617425a23916ffe8a66f6837d5e4' = 'synthetic Playwright E2E test config value; owner=security; review=2027-01-01'
    '4b84b016fce10416c07ba3f61d0799a5c9da10d201997fddd16b1f644b4ac7b4' = 'synthetic Playwright E2E test config value; owner=security; review=2027-01-01'
    '960653edf7593af1930baf6b66bc0c6a913ed02f84565986d9282aeb3f7733b4' = 'synthetic negative-test legacy secret (must-not-enable); owner=security; review=2027-01-01'
    '2666aa4398198cc8a673136ef8f4eaa6d623cdd614fe8ae27dc597ea96bb1c55' = 'synthetic negative-test legacy secret (must-not-enable); owner=security; review=2027-01-01'
    'f0c0a65f78d06ddb8c26ce92907410f491a26718ddc95460dfbf34b133ac469b' = 'synthetic frontend BFF test gateway secret; owner=security; review=2027-01-01'
    'fb969449cd1f49b568e7c86ddc6448e13f771ccfae74c848b80dd7ac43a24a20' = 'local-dev runbook example password (non-production); owner=security; review=2027-01-01'
    'd43658ac5d1e1840b7370fa7dd78c88d0b2c1ca3a19416c00c3385c6f6595f0c' = 'synthetic processing-job redaction regression fixture; owner=security; review=2027-01-01'
}

# Generated / vendor / build / cache trees only — via exact path rules. Test, e2e and fixture trees are
# NOT excluded (F11): they are scanned like every other tracked file.
$generatedPath = '(^|[/\\])(target|\.git|\.next|node_modules|graphify-out|playwright-report|test-results|coverage|dist)([/\\]|$)'

function Get-MatchedSecretValue([System.Text.RegularExpressions.Match]$regexMatch) {
  $secretGroup = $regexMatch.Groups["secret"]
  if ($secretGroup -and $secretGroup.Success) {
    return $secretGroup.Value
  }
  return $regexMatch.Value
}

function Get-CandidateSecretValue([string]$line) {
  # Prefer a quoted value, then an unquoted assignment value, else the whole trimmed line.
  if ($line -match '["'']([^"''\s]{8,})["'']') { return $Matches[1] }
  if ($line -match '[:=]\s*([^\s"''<>]{8,})') { return $Matches[1] }
  return $line.Trim()
}

function Get-Sha256Hex([string]$text) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    return (([System.BitConverter]::ToString($sha.ComputeHash($bytes))) -replace '-', '').ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

function Test-IsPrivateKeyMarkerDocumentation([string]$line) {
  # Real PEM blobs include key material or an END marker; denylist/docs cite the header in quotes.
  if ($line -match '(?i)END PRIVATE KEY') { return $false }
  if ($line -match '[A-Za-z0-9+/]{40,}={0,2}') { return $false }
  if ($line -match '(?i)(["''`]).{0,12}-----?begin (rsa )?private key-----?.{0,12}\1') { return $true }
  if ($line -match '(?i)(forbidden|denylist|deny-list|marker|must never|never appear|sanitiz|rejects?:|doesNotContain|doesNotMatch)') { return $true }
  return $false
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
    foreach ($detector in $detectors) {
      try {
        $matches = Select-String -LiteralPath $file.FullName -Pattern $detector.Pattern -CaseSensitive:$false -AllMatches -ErrorAction Stop
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
        foreach ($regexMatch in $match.Matches) {
          if ($detector.Label -eq "private-key-pem" -and (Test-IsPrivateKeyMarkerDocumentation $line)) { continue }
          $value = Get-MatchedSecretValue $regexMatch
          if ($value -match '^\$[\{(]') { continue }
          if ($line -match '\$\{' -and $value.EndsWith('}')) { continue }
          if ($value -cmatch '^[A-Z][A-Z0-9_]{2,}$') { continue }
          $fingerprint = Get-Sha256Hex $value
          if ($fingerprintAllowlist.ContainsKey($fingerprint)) { continue }
          $findings += "$($file.RelativePath):$($match.LineNumber): [REDACTED:$($detector.Label)] [fp:$($fingerprint.Substring(0,12))]"
        }
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
    $e2eDir  = Join-Path $tmp "apps/web-dashboard/e2e"
    New-Item -ItemType Directory -Force -Path $prodDir, $testDir, $e2eDir | Out-Null

    $prodSecret   = Join-Path $prodDir "Leaked.java"
    $testSecret   = Join-Path $testDir "Fixture.java"
    $e2eSecret    = Join-Path $e2eDir "leak.spec.ts"
    $exampleLine  = Join-Path $tmp "example-comment.txt"
    $envExample   = Join-Path $tmp ".env.example"
    $wrongLiteral = Join-Path $tmp "wrong-allowlist-literal.txt"
    $testOnlyLine = Join-Path $tmp "test-only-comment.txt"
    $approvedFix  = Join-Path $tmp "approved-fixture.txt"
    $changedFix   = Join-Path $tmp "changed-fixture.txt"
    $pemFile      = Join-Path $tmp "key.pem"
    $markerFile   = Join-Path $prodDir "Markers.java"

    $realSecret = "s3cr3t-Value-With-Entropy-01234567"
    Set-Content -LiteralPath $prodSecret   -Value ('password="' + $realSecret + '"') -NoNewline
    Set-Content -LiteralPath $testSecret   -Value ('secret = "another-real-secret-abcdef-123456"') -NoNewline
    Set-Content -LiteralPath $e2eSecret    -Value ('const apiKey = "e2e-real-secret-value-7788990011"') -NoNewline
    # A secret on a line that merely CONTAINS the word "example" / "test-only" must still be detected.
    Set-Content -LiteralPath $exampleLine  -Value ('# example config: password="leaked-despite-example-9911"') -NoNewline
    Set-Content -LiteralPath $envExample   -Value ('OPENAI_API_KEY="env-example-real-secret-5566778899"') -NoNewline
    Set-Content -LiteralPath $testOnlyLine -Value ('// test-only note: api_key="leaked-despite-testonly-2277"') -NoNewline
    # An approved synthetic fixture (allowlisted by fingerprint) passes; a changed value does not.
    $approvedValue = "approved-synthetic-fixture-value-0001"
    Set-Content -LiteralPath $approvedFix  -Value ('secret="' + $approvedValue + '"') -NoNewline
    Set-Content -LiteralPath $wrongLiteral -Value ('password="' + $approvedValue + '" api_key="second-real-secret-must-be-detected-0001"') -NoNewline
    Set-Content -LiteralPath $changedFix   -Value ('secret="approved-synthetic-fixture-value-0002"') -NoNewline
    Set-Content -LiteralPath $pemFile      -Value "-----BEGIN PRIVATE KEY-----`nMIIabc`n-----END PRIVATE KEY-----" -NoNewline
    Set-Content -LiteralPath $markerFile   -Value 'private static final List<String> FORBIDDEN = List.of("-----begin private key-----");' -NoNewline

    # Temporarily allowlist the approved fixture by fingerprint of its extracted value.
    $approvedFp = Get-Sha256Hex $approvedValue
    $script:fingerprintAllowlist[$approvedFp] = "self-test approved synthetic fixture; owner=security; review=never (test-only)"

    $candidates = @(
      [pscustomobject]@{ RelativePath = "apps/core-api/src/main/java/Leaked.java"; FullName = $prodSecret; Tracked = $true },
      [pscustomobject]@{ RelativePath = "apps/core-api/src/test/java/Fixture.java"; FullName = $testSecret; Tracked = $true },
      [pscustomobject]@{ RelativePath = "apps/web-dashboard/e2e/leak.spec.ts"; FullName = $e2eSecret; Tracked = $true },
      [pscustomobject]@{ RelativePath = "example-comment.txt"; FullName = $exampleLine; Tracked = $true },
      [pscustomobject]@{ RelativePath = ".env.example"; FullName = $envExample; Tracked = $true },
      [pscustomobject]@{ RelativePath = "wrong-allowlist-literal.txt"; FullName = $wrongLiteral; Tracked = $true },
      [pscustomobject]@{ RelativePath = "test-only-comment.txt"; FullName = $testOnlyLine; Tracked = $true },
      [pscustomobject]@{ RelativePath = "approved-fixture.txt"; FullName = $approvedFix; Tracked = $true },
      [pscustomobject]@{ RelativePath = "changed-fixture.txt"; FullName = $changedFix; Tracked = $true },
      [pscustomobject]@{ RelativePath = "key.pem"; FullName = $pemFile; Tracked = $true },
      [pscustomobject]@{ RelativePath = "apps/core-api/src/main/java/Markers.java"; FullName = $markerFile; Tracked = $true },
      [pscustomobject]@{ RelativePath = "gone.txt"; FullName = (Join-Path $tmp "gone.txt"); Tracked = $true }
    )
    $findings = @(Find-SecretFindings $candidates)
    $joined = $findings -join " | "

    function Assert-Detected([string]$needle, [string]$what) {
      if (-not ($findings | Where-Object { $_ -match [regex]::Escape($needle) })) {
        throw "self-test did not detect $what ($needle). Findings: $joined"
      }
    }
    Assert-Detected "Leaked.java" "production main-source secret"
    Assert-Detected "src/test/java/Fixture.java" "test-source secret"
    Assert-Detected "e2e/leak.spec.ts" "e2e-source secret"
    Assert-Detected "example-comment.txt" "secret on an 'example' line"
    Assert-Detected ".env.example" "secret in .env.example"
    Assert-Detected "wrong-allowlist-literal.txt" "second secret when an allowlisted literal appears earlier on the same line"
    Assert-Detected "test-only-comment.txt" "secret on a 'test-only' line"
    Assert-Detected "changed-fixture.txt" "changed fixture value"
    Assert-Detected "key.pem" "PEM private key"
    Assert-Detected "gone.txt" "unreadable tracked file (fail closed)"

    # The approved fixture (exact fingerprint) must NOT be reported.
    if ($findings | Where-Object { $_ -match "approved-fixture.txt" }) {
      throw "self-test incorrectly flagged an allowlisted fixture. Findings: $joined"
    }
    # The quoted denylist marker must NOT be reported.
    if ($findings | Where-Object { $_ -match "Markers\.java" }) {
      throw "self-test incorrectly flagged a quoted denylist marker. Findings: $joined"
    }
    # Output must be redacted and must never contain original secret bytes.
    foreach ($finding in ($findings | Where-Object { $_ -notmatch "unreadable" })) {
      if ($finding -notmatch "\[REDACTED:") {
        throw "self-test finding is missing redaction marker: $finding"
      }
    }
    if ($joined -match [regex]::Escape($realSecret) -or $joined -match "another-real-secret" -or $joined -match "e2e-real-secret" -or $joined -match "leaked-despite" -or $joined -match "MIIabc") {
      throw "self-test output leaked secret bytes: $joined"
    }
    # Path exclusion must still skip generated trees but not production names containing 'test'.
    if ("apps/core-api/src/main/java/ContestHelper.java" -match $generatedPath) {
      throw "generatedPath incorrectly excludes production ContestHelper path"
    }
    if ("apps/core-api/src/test/java/Fixture.java" -match $generatedPath) {
      throw "generatedPath must NOT exclude src/test trees (F11: scan every tracked file)"
    }
    if (".env.example" -match $generatedPath) {
      throw "generatedPath must NOT exclude .env.example"
    }
    if ("node_modules/x/leak.js" -notmatch $generatedPath) {
      throw "generatedPath must exclude node_modules"
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
