# Fail if the core-api production Dockerfile builds a release JAR without a prior test stage.
$ErrorActionPreference = "Stop"
$dockerfile = Join-Path $PSScriptRoot "..\apps\core-api\Dockerfile"
if (-not (Test-Path $dockerfile)) {
  throw "Missing Dockerfile at $dockerfile"
}
$text = Get-Content -Raw $dockerfile
if ($text -match 'FROM[^\r\n]+AS\s+verify' -and $text -match 'FROM\s+verify\s+AS\s+package') {
  Write-Host "OK: core-api Dockerfile chains verify -> package."
  exit 0
}
if ($text -match 'mvn[^\r\n]*-DskipTests[^\r\n]*package' -and $text -notmatch 'AS\s+verify') {
  throw "core-api Dockerfile runs skipTests package without a verify stage"
}
throw "core-api Dockerfile release path is not test-gated (expected verify stage before package)"
