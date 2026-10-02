#!/usr/bin/env bash
# Negative tests for scripts/verify-no-pessimistic-locks.sh.
#
# This gate was one of only two the documentation gate could prove could fail,
# and until Batch 809 it was the only automated gate in the repository with no
# self-test at all. That combination is exactly the shape of the defect the
# registry census found next door: check-entity-migration-sync.sh shipped a
# promise ("entity fields vs database columns") that its body never attempted,
# and nothing in CI or in the test chain noticed, because nothing asked whether
# the gate could reject anything.
#
# The gate is exercised through a copy in a throwaway tree rather than against
# the real repository, for two reasons. It keeps the self-test from being a
# tautology (asserting that the current, lock-free source tree passes proves
# nothing), and it needs no test hook in the gate itself — `cd "$(dirname "$0")/.."`
# already resolves the root, so a copy under a temp dir scans that temp dir.
#
# Every negative case asserts the *reason* for the non-zero exit, not just the
# exit itself. Batch 809's first cut of this file did assert only the exit code,
# and running it without ripgrep on PATH made all seven "is rejected" cases pass
# for the wrong reason: the gate's preflight had already failed it closed. Only
# the clean-tree case noticed. A self-test that cannot tell "rejected the lock"
# from "could not run at all" is a gate in its own right.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Fail closed for the same reason the gate does. Without this the negative cases
# below would pass vacuously.
if ! command -v rg >/dev/null 2>&1; then
  echo "Missing required command: rg" >&2
  echo "This self-test cannot run; treat it as failed, not as passed." >&2
  exit 1
fi

ROOT_DIR="$(mktemp -d)"
trap 'rm -rf "$ROOT_DIR"' EXIT
umask 077

GATE_SOURCE="scripts/verify-no-pessimistic-locks.sh"
GATE_COPY="${ROOT_DIR}/scripts/verify-no-pessimistic-locks.sh"
mkdir -p "$(dirname "$GATE_COPY")"
cp "$GATE_SOURCE" "$GATE_COPY"
chmod +x "$GATE_COPY"

# Every source root the gate scans has to exist, otherwise ripgrep reports the
# missing path and the clean case would pass for the wrong reason.
SOURCE_DIR="${ROOT_DIR}/spring-ai-rag-core/src/main/java/com/example"
for module in spring-ai-rag-api spring-ai-rag-documents spring-ai-rag-starter; do
  mkdir -p "${ROOT_DIR}/${module}/src/main/java/com/example"
done
mkdir -p "$SOURCE_DIR"

OUTPUT="${ROOT_DIR}/gate.out"
CASES=0
FAILED=0

report() {
  if [[ "$1" -eq 0 ]]; then
    echo "ok   $2"
  else
    FAILED=$((FAILED + 1))
    echo "FAIL $2" >&2
  fi
  CASES=$((CASES + 1))
}

# Runs the gate, capturing both streams into $OUTPUT, and echoes its exit status.
# `set -e` is suspended because a non-zero exit is the behaviour under test.
run_gate() {
  set +e
  bash "$GATE_COPY" > "$OUTPUT" 2>&1
  local status=$?
  set -e
  echo "$status"
}

assert_rejects() {
  local name="$1" body="$2" status
  printf '%s\n' "$body" > "${SOURCE_DIR}/Fixture.java"
  status="$(run_gate)"
  if [[ "$status" -eq 0 ]]; then
    report 1 "$name is rejected (gate exited 0)"
  elif ! grep -q 'pessimistic coordination is forbidden' "$OUTPUT"; then
    report 1 "$name is rejected for the right reason (got: $(head -1 "$OUTPUT"))"
  else
    report 0 "$name is rejected"
  fi
  rm -f "${SOURCE_DIR}/Fixture.java"
}

# The clean tree must pass, or the negative cases below would prove nothing: a
# gate that rejects everything is not a gate, it is an outage.
status="$(run_gate)"
if [[ "$status" -eq 0 ]]; then
  report 0 "a lock-free source tree passes"
else
  report 1 "a lock-free source tree passes (gate exited $status: $(head -1 "$OUTPUT"))"
fi

# One case per alternative in the gate's pattern. A gate that matched only the
# first form would still pass the first case, so each alternative is pinned
# separately — this is the difference between "the gate can fail" and "the gate
# still cannot be fooled".
assert_rejects "FOR UPDATE" \
  'class Fixture { void f() { jdbc.queryForObject("SELECT id FROM rag_documents FOR UPDATE"); } }'
assert_rejects "FOR NO KEY UPDATE" \
  'class Fixture { void f() { jdbc.queryForObject("SELECT id FROM rag_documents FOR NO KEY UPDATE"); } }'
assert_rejects "FOR SHARE" \
  'class Fixture { void f() { jdbc.queryForObject("SELECT id FROM rag_documents FOR SHARE"); } }'
assert_rejects "SKIP LOCKED" \
  'class Fixture { void f() { jdbc.queryForObject("SELECT id FROM rag_documents FOR UPDATE SKIP LOCKED"); } }'
assert_rejects "PESSIMISTIC_WRITE" \
  'class Fixture { @Lock(LockModeType.PESSIMISTIC_WRITE) void f() {} }'
assert_rejects "pg_advisory_xact_lock" \
  'class Fixture { void f() { jdbc.execute("SELECT pg_advisory_xact_lock(42)"); } }'
assert_rejects "LOCK TABLE" \
  'class Fixture { void f() { jdbc.execute("LOCK TABLE rag_documents IN EXCLUSIVE MODE"); } }'

# A .sql file is scanned too. Without this the whole production schema — every
# Flyway migration, every native query — would sit outside the gate.
printf 'SELECT 1 FROM rag_documents FOR UPDATE;\n' > "${SOURCE_DIR}/native.sql"
status="$(run_gate)"
if [[ "$status" -ne 0 ]] && grep -q 'pessimistic coordination is forbidden' "$OUTPUT"; then
  report 0 "a .sql source is scanned as well as .java"
else
  report 1 "a .sql source is scanned as well as .java (gate exited $status)"
fi
rm -f "${SOURCE_DIR}/native.sql"

# The gate matches source text, so a maintainer cannot talk their way past it by
# commenting the statement out. This also guards against the gate being "fixed"
# into something that strips comments, which would silently change its verdict on
# the very documentation that quotes these statements.
printf '// SELECT 1 FROM rag_documents FOR UPDATE\nclass Fixture {}\n' > "${SOURCE_DIR}/Fixture.java"
status="$(run_gate)"
if [[ "$status" -ne 0 ]] && grep -q 'pessimistic coordination is forbidden' "$OUTPUT"; then
  report 0 "a commented-out lock is still reported"
else
  report 1 "a commented-out lock is still reported (gate exited $status)"
fi
rm -f "${SOURCE_DIR}/Fixture.java"

# The gate must name the file it objected to, or a maintainer cannot act on it.
printf 'class Fixture { void f() { jdbc.execute("SELECT pg_advisory_xact_lock(1)"); } }\n' \
  > "${SOURCE_DIR}/Fixture.java"
run_gate > /dev/null
if grep -q 'Fixture.java' "$OUTPUT"; then
  report 0 "the report names the offending file"
else
  report 1 "the report names the offending file"
fi
rm -f "${SOURCE_DIR}/Fixture.java"

if [[ "$FAILED" -gt 0 ]]; then
  echo >&2
  echo "${FAILED}/${CASES} pessimistic-lock self-test case(s) failed." >&2
  exit 1
fi

echo
echo "All ${CASES} pessimistic-lock self-test cases passed."
