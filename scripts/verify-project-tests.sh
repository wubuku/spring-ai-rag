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

# Batch 816. Every controller was surveyed for overloads that forward a literal
# null into an HttpServletRequest position. Seventeen overloads forward a null
# somewhere; only two put it where the request itself goes, and both derivation
# helpers fail open on null — ChatPrincipal.from(null) is local() and
# ApiKeyCollectionAccess.isUnrestricted(null) is true — so reaching such an
# overload yields unscoped data rather than an error. The rule is deliberately
# narrow, because the other fifteen null a business parameter and flagging them
# would produce fifteen exemptions: a debt baseline wearing a gate's clothes.
node scripts/test-support/null-request-forwarding-self-test.mjs >/dev/null || {
  echo "Null-request forwarding self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/null-request-forwarding-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Null-request forwarding self-test"

node scripts/verify-null-request-forwarding.mjs
echo "PASS: Null-request forwarding"

# A collaborator injected with @Autowired(required = false) claims it may be
# absent. For all twelve of them the bean is an unconditional @Service, so the
# null branch is unreachable in a running application — while seven genuinely
# conditional beans exist, which is what stops this from rejecting every
# optional injection outright. Each claim must say why the guard exists.
node scripts/test-support/false-optional-wiring-self-test.mjs >/dev/null || {
  echo "False-optional-wiring self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/false-optional-wiring-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: False-optional-wiring self-test"

node scripts/verify-false-optional-wiring.mjs
echo "PASS: False-optional wiring"

# Spring injects a controller through its @Autowired constructor. A second
# constructor is reachable only from tests, and it chooses on the caller's behalf
# which collaborators end up null — nine of them did, and two of those selected a
# legacy retrieval mode the production wiring cannot reach. Visibility is not the
# test: a package-private constructor is as test-only as a public one.
node scripts/test-support/controller-constructor-count-self-test.mjs >/dev/null || {
  echo "Controller-constructor-count self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/controller-constructor-count-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Controller-constructor-count self-test"

node scripts/verify-controller-constructor-count.mjs
echo "PASS: Controller constructor count"

# Batch 873. ErrorCode calls itself the single source of truth, and six of the
# codes the API actually returns were never declared in it — so no title or
# problem-type URI could be derived, and the same exception grew two different
# body shapes depending on which branch caught it. The rule also pins that the
# status beside a code matches the one the enum declares, and that a hand-built
# ErrorResponse is actually a problem detail. Its known blind spot — the
# `ErrorResponse.of(...)` factories, which no source scan can see — is written
# into the gate's own header and pinned by a self-test, not left for the next
# reader to discover.
node scripts/test-support/error-code-catalog-self-test.mjs >/dev/null || {
  echo "Error-code-catalog self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/error-code-catalog-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Error-code-catalog self-test"

node scripts/verify-error-code-catalog.mjs
echo "PASS: Error code catalog"

# An eighth question, and the smallest surface of the lot: five acceptance gates
# read the same four counters off a surefire report, each with its own copy of
# the same `sed` pipeline, and none of them had a self-test — so the reading was
# never checked, only the thing being read. Surefire writes `tests=` before the
# cases an `@Nested` inner class contributes, so that pipeline could under-report
# a gated suite the moment somebody added a nested class to it, and the failure
# would name a number that is not the number of tests that ran. One reader in
# `scripts/lib/`, counted from the elements, with a self-test that runs the real
# shell function rather than a JavaScript restatement of it.
node scripts/test-support/surefire-report-self-test.mjs >/dev/null || {
  echo "Surefire-report self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/surefire-report-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Surefire-report self-test"

# Batch 895. `verify-managed-api-principals.sh` is a manual gate — it starts two
# backends and four containers, so it is in the standing CI gap and nothing ever
# ran its readers. Two of its sixty `jq -e` predicates read the active-alert list
# and both treated every non-zero exit as "not yet", which is right for a poll and
# wrong for a reader: on a renamed `metrics.principalId` the "no alert is firing"
# predicate returns true and the gate reports a firing alert as correctly absent.
# The two predicates now live in scripts/lib/ so that they can be run directly,
# and this self-test is what actually executes them — the gate itself still runs
# nowhere on its own.
node scripts/test-support/alert-payload-self-test.mjs >/dev/null || {
  echo "Alert-payload self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/alert-payload-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Alert-payload self-test"

# A jq predicate that says "nothing here is wrong" must be able to tell the
# difference between "nothing is wrong" and "I cannot see". Batch 895 found one
# such predicate by reading two of them by hand; Batch 896 found two more and
# closed all three, so the shape is a finding rather than something to be found
# by reading. No allowlist: an allowlist would be a list of predicates this gate
# does not check.
node scripts/test-support/json-assertions-self-test.mjs >/dev/null || {
  echo "JSON-assertions self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/json-assertions-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: JSON-assertions self-test"

node scripts/verify-json-assertions.mjs
echo "PASS: JSON negative assertions"

# Batch 905. Four gates read Java source and each had its own comment stripper,
# in three behaviours. Two of them were a naive regex with no notion of a string
# literal, so a URL in a string was read as a comment: 316 string literals in
# this tree are destroyed by that shape, and in nine files a `/*` inside a
# string ran the match to the next `*/` — one of them losing 118 lines. The
# stripper now lives in one place, and this self-test is the enforcement: its
# last case fails if a gate starts rolling its own again.
node scripts/test-support/java-source-self-test.mjs || {
  echo "Java-source self-test failed; the gates may be reading a mangled file." >&2
  exit 1
}
echo "PASS: Java-source self-test"

# Batch 901. Every automated gate in this repository is required to carry a
# self-test, and that requirement is discharged by importing the module and
# calling its functions — which means it cannot see a gate that never runs.
# Measured here first: 22 gate scripts each spelled "am I the entry point?" on
# their own, in four ways, and 18 of them printed nothing and exited 0 when
# named through a symlinked path. The spelling now lives in one helper, and
# this gate is what stops a fourth idiom from appearing next to it.
node scripts/test-support/gate-entry-points-self-test.mjs >/dev/null || {
  echo "Gate-entry-points self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/gate-entry-points-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Gate-entry-points self-test"

node scripts/verify-gate-entry-points.mjs
echo "PASS: Gate entry points"

# Batch 898. Batch 896 made "a negative assertion must not be satisfied by a
# value it cannot read" a gate over `scripts/**/*.sh`. This is the same defect
# in Python, in the one gate whose whole job is catching a regression:
# `run-retrieval-regression.sh` read `baseline_metrics.get(name, 0.0)`, so a
# metric the committed baseline happened not to carry was judged against zero
# and could never regress. A rule that looks at one language is a rule about one
# language — the three readers now live in scripts/lib and are run here.
node scripts/test-support/retrieval-baseline-self-test.mjs >/dev/null || {
  echo "Retrieval-baseline self-test failed; the readers may no longer reject anything." >&2
  node scripts/test-support/retrieval-baseline-self-test.mjs >&2 || true
  exit 1
}
echo "PASS: Retrieval-baseline self-test"
