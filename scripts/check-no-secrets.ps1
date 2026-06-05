$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$patterns = @(
  "TELEGRAM_BOT_TOKEN\s*=",
  "password\s*=",
  "secret\s*=",
  "api_key\s*=",
  "private_key",
  "BEGIN PRIVATE KEY"
)
$allowed = "(TODO|example|changeme|placeholder|non-production|forbidden pattern)"
$files = Get-ChildItem -Path $root -Recurse -File |
  Where-Object {
    $_.FullName -notmatch "\\target\\" -and
    $_.FullName -notmatch "\\.git\\" -and
    $_.FullName -notmatch "\\.next\\" -and
    $_.FullName -notmatch "\\node_modules\\" -and
    $_.Name -ne ".env.example" -and
    $_.FullName -notmatch "\\scripts\\check-no-secrets\.ps1$"
  }
$findings = @()
foreach ($file in $files) {
  foreach ($pattern in $patterns) {
    $matches = Select-String -Path $file.FullName -Pattern $pattern -CaseSensitive:$false -ErrorAction SilentlyContinue
    foreach ($match in $matches) {
      if ($match.Line -notmatch $allowed) {
        $findings += "$($file.FullName):$($match.LineNumber): $($match.Line.Trim())"
      }
    }
  }
}
if ($findings.Count -gt 0) {
  Write-Output "Potential hardcoded secrets found:"
  $findings | ForEach-Object { Write-Output $_ }
  exit 1
}
Write-Output "No obvious hardcoded secrets found."
