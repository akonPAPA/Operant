#!/usr/bin/env bash
#
# Packaged behavioural smoke for the built operantctl shaded JAR (CI-Linux).
#
# Scope: this script validates PACKAGING and PROCESS-LEVEL behaviour of the real executable JAR
# produced by `mvn clean verify`. It never re-implements the Java unit/integration behaviour tests.
#
# Authenticated remote behaviour (HTTP 200 status/health, 401/403 denial, malformed JSON, semantic
# contradiction, oversized response, partial-body stall / total-deadline bound) is proven inside the
# same `mvn verify` run by ControlApiClientResponseBoundTest and OperantCtlCommandTest, which drive a
# real local HTTP server with an in-memory credential store. Those cases cannot be re-driven through
# the production JAR here because the production credential store is OS-native (Windows DPAPI) and
# secret import is interactive-only by design; on Linux CI the store is deliberately unsupported, so
# any command needing a credential fails closed at config resolution before any network call. This
# script proves that fail-closed process behaviour, the packaging invariants, and secret absence.
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_DIR="${MODULE_DIR}/target"
VERSION="0.1.0-SNAPSHOT"
EXPECTED_JAR="${TARGET_DIR}/operantctl-${VERSION}.jar"
MAIN_CLASS="com.operant.ctl.OperantCtl"
# Bound every packaged invocation so a hang fails the job instead of blocking CI.
INVOKE_TIMEOUT="20s"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
pass() { echo "SMOKE OK: $*"; }

# ---- 1. Exactly one executable JAR (the shade byproduct original-*.jar is not executable) ----
mapfile -t EXEC_JARS < <(find "${TARGET_DIR}" -maxdepth 1 -name 'operantctl-*.jar' ! -name 'original-*.jar' -printf '%f\n' | sort)
[[ "${#EXEC_JARS[@]}" -eq 1 ]] || fail "expected exactly one executable JAR, found: ${EXEC_JARS[*]:-none}"
[[ -f "${EXPECTED_JAR}" ]] || fail "expected artifact missing: ${EXPECTED_JAR}"
pass "single executable artifact ${EXEC_JARS[0]}"

# ---- 2. Correct Main-Class in the manifest ----
MANIFEST="$(unzip -p "${EXPECTED_JAR}" META-INF/MANIFEST.MF | tr -d '\r')"
grep -q "^Main-Class: ${MAIN_CLASS}$" <<<"${MANIFEST}" || fail "manifest Main-Class is not ${MAIN_CLASS}"
pass "manifest Main-Class ${MAIN_CLASS}"

# ---- 3. No test classes, no Java sources, no fixture material packaged ----
ENTRIES="$(unzip -Z1 "${EXPECTED_JAR}")"
if grep -Eq '(Test\.class$|Tests\.class$|\.java$|fixture|/test/)' <<<"${ENTRIES}"; then
  fail "packaged JAR contains test/source/fixture entries"
fi
pass "no test/source/fixture entries in artifact"

# ---- 4. version command: exit 0, expected banner ----
run_case() {
  local name="$1"; shift
  local want_exit="$1"; shift
  local out rc
  set +e
  out="$(timeout "${INVOKE_TIMEOUT}" java -jar "${EXPECTED_JAR}" "$@" 2>&1)"
  rc=$?
  set -e
  [[ ${rc} -ne 124 ]] || fail "${name}: exceeded ${INVOKE_TIMEOUT} time bound (hang)"
  [[ ${rc} -eq ${want_exit} ]] || fail "${name}: expected exit ${want_exit}, got ${rc}. output: ${out}"
  # Fail closed on any 64-hex secret-shaped run leaking into output.
  if grep -Eq '[0-9a-fA-F]{64}' <<<"${out}"; then
    fail "${name}: secret-shaped 64-hex value present in output"
  fi
  printf '%s' "${out}"
}

VERSION_OUT="$(run_case "version" 0 version)"
grep -q "operantctl 0.1.0" <<<"${VERSION_OUT}" || fail "version banner missing"
grep -q "control protocol v" <<<"${VERSION_OUT}" || fail "version protocol tag missing"
pass "version -> exit 0"

# ---- 5. no arguments: usage / config exit code 2 ----
run_case "no-args" 2 >/dev/null
pass "no-args -> exit 2 (usage)"

# ---- 6. unknown command: usage / config exit code 2, no passthrough ----
run_case "unknown-command" 2 shutdown >/dev/null
pass "unknown-command -> exit 2 (usage)"

# ---- 7. remote command with no credential store: fail closed at config (exit 2), no secret ----
DENIED_OUT="$(OPERANTCTL_CORE_BASE_URL="https://control.example.com" \
  OPERANTCTL_CREDENTIAL_ALIAS="ops-prod" \
  run_case "status-no-credential" 2 status)"
grep -qi "config:" <<<"${DENIED_OUT}" || fail "status-no-credential: missing bounded config error"
pass "status without credential -> exit 2 (fail closed)"

# ---- 8. config validate with invalid base url: exit 2 ----
OPERANTCTL_CORE_BASE_URL="ftp://nope" OPERANTCTL_CREDENTIAL_ALIAS="ops-prod" \
  run_case "config-validate-invalid" 2 config validate >/dev/null
pass "config validate (invalid base url) -> exit 2"

echo "PACKAGED SMOKE PASSED"
