# Fail if the core-api production Dockerfile builds a release JAR without a prior test stage.
$ErrorActionPreference = "Stop"
$dockerfile = Join-Path $PSScriptRoot "..\apps\core-api\Dockerfile"
if (-not (Test-Path $dockerfile)) {
  throw "Missing Dockerfile at $dockerfile"
}
$text = Get-Content -Raw $dockerfile

$verifyStage = [regex]::Match(
  $text,
  '(?ms)^FROM[^\r\n]+\s+AS\s+verify\s*$.*?(?=^FROM|\z)')
if (-not $verifyStage.Success) {
  throw "core-api Dockerfile is missing a verify stage"
}

if ($verifyStage.Value -notmatch '(?m)^RUN\s+mvn[^\r\n]*\btest\b') {
  throw "core-api Dockerfile verify stage does not run Maven tests"
}

if ($verifyStage.Value -match 'skipTests|maven\.test\.skip') {
  throw "core-api Dockerfile verify stage disables tests"
}

if ($text -notmatch '(?m)^FROM\s+verify\s+AS\s+package\s*$') {
  throw "core-api Dockerfile package stage does not derive from verify"
}

if ($text -notmatch '(?m)^COPY\s+--from=package\b') {
  throw "core-api Dockerfile final image does not copy the verified package artifact"
}

Write-Host "OK: core-api Dockerfile runs tests and chains verify -> package -> final image."
