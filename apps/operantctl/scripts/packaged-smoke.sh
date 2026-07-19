#!/usr/bin/env bash
#
# Packaged behavioural smoke for the built operantctl shaded JAR.
#
# Scope:
# - prove that one executable shaded JAR is produced;
# - prove its executable Main-Class;
# - prove project test/source/fixture material is not packaged;
# - prove bounded process-level command behaviour;
# - prove fail-closed behaviour when no production credential exists;
# - prove no secret-shaped value appears in command output.
#
# Authenticated transport behaviour is covered by the Java tests executed
# in the same `mvn clean verify` run.
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_DIR="${MODULE_DIR}/target"
VERSION="0.1.0-SNAPSHOT"
EXPECTED_JAR="${TARGET_DIR}/operantctl-${VERSION}.jar"
MAIN_CLASS="com.operant.ctl.OperantCtl"
INVOKE_TIMEOUT="20s"

fail() {
  echo "SMOKE FAIL: $*" >&2
  exit 1
}

pass() {
  echo "SMOKE OK: $*"
}

resolve_jar_tool() {
  local candidate
  local java_home
  local java_command
  local java_bin_dir

  if command -v jar >/dev/null 2>&1; then
    command -v jar
    return 0
  fi

  if [[ -n "${JAVA_HOME:-}" ]]; then
    java_home="${JAVA_HOME}"

    if command -v cygpath >/dev/null 2>&1; then
      java_home="$(cygpath -u "${java_home}" 2>/dev/null || printf '%s' "${java_home}")"
    fi

    for candidate in \
      "${java_home}/bin/jar" \
      "${java_home}/bin/jar.exe"
    do
      if [[ -x "${candidate}" ]]; then
        printf '%s\n' "${candidate}"
        return 0
      fi
    done
  fi

  java_command="$(command -v java 2>/dev/null || true)"

  if [[ -n "${java_command}" ]]; then
    java_bin_dir="$(dirname "${java_command}")"

    for candidate in \
      "${java_bin_dir}/jar" \
      "${java_bin_dir}/jar.exe"
    do
      if [[ -x "${candidate}" ]]; then
        printf '%s\n' "${candidate}"
        return 0
      fi
    done
  fi

  return 1
}

JAR_TOOL="$(resolve_jar_tool)" ||
  fail "JDK jar tool not found; JAVA_HOME=${JAVA_HOME:-unset}"

read_jar_entry() {
  if [[ "$#" -ne 2 ]]; then
    echo "read_jar_entry requires: <archive> <entry>" >&2
    return 64
  fi

  local archive="$1"
  local entry="$2"
  local archive_abs
  local temp_dir
  local rc

  case "${archive}" in
    /*)
      archive_abs="${archive}"
      ;;
    *)
      archive_abs="$(pwd)/${archive}"
      ;;
  esac

  temp_dir="$(mktemp -d)"

  set +e
  (
    cd "${temp_dir}" || exit 1
    "${JAR_TOOL}" xf "${archive_abs}" "${entry}" || exit 1
    [[ -f "${entry}" ]] || exit 1
    cat "${entry}"
  )
  rc=$?
  set -e

  rm -rf "${temp_dir}"
  return "${rc}"
}

# ------------------------------------------------------------
# 1. Exactly one executable shaded JAR
# ------------------------------------------------------------

mapfile -t EXEC_JARS < <(
  find "${TARGET_DIR}" \
    -maxdepth 1 \
    -name 'operantctl-*.jar' \
    ! -name 'original-*.jar' \
    -printf '%f\n' |
    sort
)

[[ "${#EXEC_JARS[@]}" -eq 1 ]] ||
  fail "expected exactly one executable JAR, found: ${EXEC_JARS[*]:-none}"

[[ -f "${EXPECTED_JAR}" ]] ||
  fail "expected artifact missing: ${EXPECTED_JAR}"

pass "single executable artifact ${EXEC_JARS[0]}"

# ------------------------------------------------------------
# 2. Correct Main-Class
# ------------------------------------------------------------

MANIFEST="$(read_jar_entry "${EXPECTED_JAR}" META-INF/MANIFEST.MF | tr -d '\r')"

grep -q "^Main-Class: ${MAIN_CLASS}$" <<<"${MANIFEST}" ||
  fail "manifest Main-Class is not ${MAIN_CLASS}"

pass "manifest Main-Class ${MAIN_CLASS}"

# ------------------------------------------------------------
# 3. No Operant project tests, sources or fixtures
#
# Scope this invariant to Operant-owned package paths.
# A shaded dependency may legally contain an internal class or resource
# whose name contains 'test' or 'fixture'; that is not an Operant test
# artifact and must not create a false-positive release failure.
# ------------------------------------------------------------

ENTRIES="$("${JAR_TOOL}" tf "${EXPECTED_JAR}" | tr -d '\r')"

PROJECT_TEST_ENTRIES="$(
  grep -E \
    '^com/operant/ctl/.*(Test|Tests)\.class$|^com/operant/ctl/(test|tests|fixture|fixtures)(/|$)' \
    <<<"${ENTRIES}" ||
    true
)"

SOURCE_ENTRIES="$(
  grep -E '\.java$' <<<"${ENTRIES}" ||
    true
)"

if [[ -n "${PROJECT_TEST_ENTRIES}" || -n "${SOURCE_ENTRIES}" ]]; then
  {
    echo "Forbidden Operant-owned test/source/fixture entries:"
    printf '%s\n' "${PROJECT_TEST_ENTRIES}"
    printf '%s\n' "${SOURCE_ENTRIES}"
  } >&2

  fail "packaged JAR contains Operant test/source/fixture material"
fi

pass "no Operant test/source/fixture entries in artifact"

# ------------------------------------------------------------
# 4. Bounded process execution helper
# ------------------------------------------------------------

run_case() {
  local name="$1"
  shift

  local want_exit="$1"
  shift

  local out
  local rc

  set +e
  out="$(timeout "${INVOKE_TIMEOUT}" java -jar "${EXPECTED_JAR}" "$@" 2>&1)"
  rc=$?
  set -e

  [[ "${rc}" -ne 124 ]] ||
    fail "${name}: exceeded ${INVOKE_TIMEOUT} time bound"

  [[ "${rc}" -eq "${want_exit}" ]] ||
    fail "${name}: expected exit ${want_exit}, got ${rc}. output: ${out}"

  if grep -Eq '[0-9a-fA-F]{64}' <<<"${out}"; then
    fail "${name}: secret-shaped 64-hex value present in output"
  fi

  printf '%s' "${out}"
}

# ------------------------------------------------------------
# 5. Version command
# ------------------------------------------------------------

VERSION_OUT="$(run_case "version" 0 version)"

grep -q "operantctl 0.1.0" <<<"${VERSION_OUT}" ||
  fail "version banner missing"

grep -q "control protocol v" <<<"${VERSION_OUT}" ||
  fail "version protocol tag missing"

pass "version -> exit 0"

# ------------------------------------------------------------
# 6. No arguments
# ------------------------------------------------------------

run_case "no-args" 2 >/dev/null
pass "no-args -> exit 2"

# ------------------------------------------------------------
# 7. Unknown command
# ------------------------------------------------------------

run_case "unknown-command" 2 shutdown >/dev/null
pass "unknown-command -> exit 2"

# ------------------------------------------------------------
# 8. Remote command without credential
# ------------------------------------------------------------

DENIED_OUT="$(
  OPERANTCTL_CORE_BASE_URL="https://control.example.com" \
  OPERANTCTL_CREDENTIAL_ALIAS="ops-prod" \
    run_case "status-no-credential" 2 status
)"

grep -qi "config:" <<<"${DENIED_OUT}" ||
  fail "status-no-credential: missing bounded config error"

pass "status without credential -> exit 2"

# ------------------------------------------------------------
# 9. Invalid configuration
# ------------------------------------------------------------

OPERANTCTL_CORE_BASE_URL="ftp://nope" \
OPERANTCTL_CREDENTIAL_ALIAS="ops-prod" \
  run_case "config-validate-invalid" 2 config validate >/dev/null

pass "config validate invalid base URL -> exit 2"

echo "PACKAGED SMOKE PASSED"