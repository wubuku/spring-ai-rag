#!/usr/bin/env bash
# Test-visibility and integration-switch gates.
#
# Runs after the backend suite and fails when a test class neither executed
# nor reported itself as skipped. A class gated by `assumeTrue` inside
# `@BeforeAll` is aborted rather than skipped, so it produces
# `tests="0" skipped="0"` — the same pair of numbers an empty class produces,
# and therefore invisible in the run summary.
#
# Before this gate, 21 PostgreSQL/Testcontainers integration classes
# (~145 test methods, including the only coverage of the API-key rotation
# security guards) were invisible: the run reported "Skipped: 9" and read as
# though everything were accounted for.
#
# The second check reconciles the `*.it.enabled` switches those classes hide
# behind against the scripts and documents that are supposed to turn them on.
# Gating a suite is a promise that somebody can ungate it, and Batch 790 found
# `PdfImportPostgresIntegrationTest` — 2 test methods — whose switch appeared in
# no script and in no document outside an archived progress note.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v node >/dev/null 2>&1; then
  echo "Missing required command: node" >&2
  echo "This gate cannot run; treat it as failed, not as passed." >&2
  exit 1
fi

# Proves the checker can still reject. A gate that cannot fail is worse than
# no gate, and this repository has a documented history of producing one.
node scripts/test-support/test-visibility-self-test.mjs >/dev/null || {
  echo "Test-visibility self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/test-visibility-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Test visibility self-test"

node scripts/verify-test-visibility.mjs
echo "PASS: Test visibility"

node scripts/test-support/integration-switch-self-test.mjs >/dev/null || {
  echo "Integration-switch self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/integration-switch-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Integration-switch self-test"

node scripts/verify-integration-test-switches.mjs
echo "PASS: Integration-test switches"
