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

# A third question the switch reconciler cannot answer: it checks that a gated
# suite has a run path, but not what happens to the database that run path
# points at. Batch 802 found a suite that accepted a caller-named database and
# ran `flyway.clean()` on it with no acknowledgement, while ten siblings
# required one. That suite was also ungated, so the reconciler never saw it.
node scripts/test-support/external-db-safety-self-test.mjs >/dev/null || {
  echo "External-database safety self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/external-db-safety-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: External-database safety self-test"

node scripts/verify-external-db-safety.mjs
echo "PASS: External-database safety"

# A fourth question: can a Playwright spec be run at all? The switch reconciler
# says nothing about the frontend e2e suite, and Batch 804 found six of twenty
# specs that no verification script invoked — two of which contained tests that
# could never have passed.
node scripts/test-support/e2e-reachability-self-test.mjs >/dev/null || {
  echo "E2E reachability self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/e2e-reachability-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: E2E reachability self-test"

node scripts/verify-e2e-run-paths.mjs
echo "PASS: E2E reachability"

# A fifth question, about observability rather than tests: a configured SLO
# threshold whose endpoint no longer exists reports 100% compliance forever, and
# an endpoint timed under a name the report never reads is measured and then
# dropped. Batch 806 found both halves live in the `/ask` + `/chat` alias pair.
node scripts/test-support/slo-endpoint-coverage-self-test.mjs >/dev/null || {
  echo "SLO endpoint coverage self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/slo-endpoint-coverage-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: SLO endpoint coverage self-test"

node scripts/verify-slo-endpoint-coverage.mjs
echo "PASS: SLO endpoint coverage"

# A sixth question, and the one that keeps the other five honest: are these
# questions being asked at all? Batch 809 deleted `check-entity-migration-sync.sh`
# after finding it compared nothing, queried six table names that no longer exist,
# and protected an invariant Hibernate already enforces at startup — and found
# that between Batch 768 and then, not one of the gates below ran in CI while 208
# WebUI gate self-tests ran nowhere. The census below cannot fix the CI gap (that
# needs a workflow change this token cannot make), but it can make the gap a
# recorded decision instead of a silence, and it stops the next gate from being
# born unclassified, untested and unwired.
node scripts/test-support/gate-wiring-self-test.mjs >/dev/null || {
  echo "Gate-wiring self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/gate-wiring-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Gate wiring self-test"

node scripts/verify-gate-wiring.mjs
echo "PASS: Gate wiring"

# A seventh question, and the one closest to the point of this whole script: of
# the tests that ran, how many would have failed had the code under test been
# deleted? Batch 810 found three @Test methods in PgTrgmFulltextProviderTest whose
# bodies held nothing but a comment claiming the behaviour was "covered via
# HybridRetrieverService integration tests" — a claim nobody had checked, and the
# only place the Java-side minScore and excludeIds filtering was ever exercised.
# They reported as passes in every run.
node scripts/test-support/inert-test-self-test.mjs >/dev/null || {
  echo "Inert-test self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/inert-test-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Inert-test self-test"

node scripts/verify-test-expectations.mjs
echo "PASS: Inert-test census"
