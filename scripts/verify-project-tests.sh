#!/usr/bin/env bash
# The repository's test-side gate chain.
#
# What it runs is every gate registered with `kind: "gate"` in
# `scripts/gate-registry.mjs`, each preceded by its own self-test, and the
# count it prints at the end is the number of checks that actually ran. This
# header used to name two of them — "Test-visibility and integration-switch
# gates" — and the documentation beside it said six, and the gate table said
# the two aggregate entry points covered "the nine above". None of those
# numbers was recomputed by anything, so all three rotted while the chain grew
# to thirty-two. The roster now lives in the registry, and the number lives in
# the run.
#
# Two of the checks are worth knowing about before you read a red one.
#
# `verify-test-visibility` fails when a test class neither executed nor
# reported itself as skipped. A class gated by `assumeTrue` inside
# `@BeforeAll` is aborted rather than skipped, so it produces
# `tests="0" skipped="0"` — the same pair of numbers an empty class produces,
# and therefore invisible in the run summary.
#
# `verify-integration-test-switches` reconciles the `*.it.enabled` switches the
# Testcontainers suites hide behind against the scripts and documents that are
# supposed to turn them on, in both directions: a switch nothing turns on, a
# run path no test class consumes, an empty gated suite, a runner entry that
# has drifted, and — since Batch 912 — a gated suite that only one feature's
# own script can run.
#
# Order matters, and the reason is the reports directory. Run this directly
# after a full `mvn test`: every check here reads
# `spring-ai-rag-core/target/surefire-reports`, and `scripts/verify-gated-it.sh`
# overwrites the same files. `docs/testing-guide.md` states the order.
set -euo pipefail

cd "$(dirname "$0")/.."

PASS_COUNT=0

# Every green line goes through here, so the count at the end cannot disagree
# with the lines above it. Batch 914 added this after finding that this chain
# printed thirty-two `PASS:` lines and no total, while the docs chain beside it
# printed `16 checks passed` — and that the descriptions of this chain's own
# scope had rotted in five places, in this file and in both languages of
# `docs/developer-reference*.md`, because each of them named a number that
# nothing recomputed. A printed count is derived; a commented one is a promise.
pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: $1"
}

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
pass "Test visibility self-test"

node scripts/verify-test-visibility.mjs
pass "Test visibility"

node scripts/test-support/integration-switch-self-test.mjs >/dev/null || {
  echo "Integration-switch self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/integration-switch-self-test.mjs >&2 || true
  exit 1
}
pass "Integration-switch self-test"

node scripts/verify-integration-test-switches.mjs
pass "Integration-test switches"

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
pass "External-database safety self-test"

node scripts/verify-external-db-safety.mjs
pass "External-database safety"

# A fourth question: can a Playwright spec be run at all? The switch reconciler
# says nothing about the frontend e2e suite, and Batch 804 found six of twenty
# specs that no verification script invoked — two of which contained tests that
# could never have passed.
node scripts/test-support/e2e-reachability-self-test.mjs >/dev/null || {
  echo "E2E reachability self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/e2e-reachability-self-test.mjs >&2 || true
  exit 1
}
pass "E2E reachability self-test"

node scripts/verify-e2e-run-paths.mjs
pass "E2E reachability"

# A fifth question, about observability rather than tests: a configured SLO
# threshold whose endpoint no longer exists reports 100% compliance forever, and
# an endpoint timed under a name the report never reads is measured and then
# dropped. Batch 806 found both halves live in the `/ask` + `/chat` alias pair.
node scripts/test-support/slo-endpoint-coverage-self-test.mjs >/dev/null || {
  echo "SLO endpoint coverage self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/slo-endpoint-coverage-self-test.mjs >&2 || true
  exit 1
}
pass "SLO endpoint coverage self-test"

node scripts/verify-slo-endpoint-coverage.mjs
pass "SLO endpoint coverage"

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
pass "Gate wiring self-test"

node scripts/verify-gate-wiring.mjs
pass "Gate wiring"

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
pass "Inert-test self-test"

node scripts/verify-test-expectations.mjs
pass "Inert-test census"

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
pass "Null-request forwarding self-test"

node scripts/verify-null-request-forwarding.mjs
pass "Null-request forwarding"

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
pass "False-optional-wiring self-test"

node scripts/verify-false-optional-wiring.mjs
pass "False-optional wiring"

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
pass "Controller-constructor-count self-test"

node scripts/verify-controller-constructor-count.mjs
pass "Controller constructor count"

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
pass "Error-code-catalog self-test"

node scripts/verify-error-code-catalog.mjs
pass "Error code catalog"

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
pass "Surefire-report self-test"

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
pass "Alert-payload self-test"

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
pass "JSON-assertions self-test"

node scripts/verify-json-assertions.mjs
pass "JSON negative assertions"

# Batch 908. A test assertion that cannot fail reads as coverage and is not.
# Five were removed, and the loudest one was hiding a wrong sentence: the test
# claimed the assistant role must not survive MiniMax normalisation, while the
# same file's javadoc and the production code both say the transformation is
# system → user. The rule stops at `assertEquals(x, x)` on purpose — JUnit
# resolves that through equals(), so it is a contract check rather than a
# tautology, and flagging it would have required an allowlist.
node scripts/test-support/tautological-assertions-self-test.mjs >/dev/null || {
  echo "Tautological-assertions self-test failed; the gate may no longer reject anything." >&2
  node scripts/test-support/tautological-assertions-self-test.mjs >&2 || true
  exit 1
}
pass "Tautological-assertions self-test"

node scripts/verify-tautological-assertions.mjs
pass "Tautological Java test assertions"

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
pass "Java-source self-test"

# Batch 915. The rule that a script running `mvn clean` must either put the
# surefire reports back or say it took them. Five scripts re-run an unscoped
# `mvn test` and need no declaration; seven declare it in their own header, so
# the reason travels with the command instead of living in a registry of known
# offenders. The self-test pins the second exit as hard as the first — most of
# its cases are scripts that must NOT be reported.
node scripts/test-support/report-destruction-self-test.mjs || {
  echo "Report-destruction self-test failed; the gate may be excusing the wrong scripts." >&2
  exit 1
}
pass "Report-destruction self-test"

# Batch 923. A script that asserts Flyway's latest version by writing the number
# down stops being true the moment someone adds a migration. One did exactly
# that for a month, and the run that caught it was the first since. The self-test
# feeds the rule the pre-fix text of that script, so the rule is never validated
# only against a clean tree.
node scripts/test-support/flyway-version-pinning-self-test.mjs || {
  echo "Flyway version-pinning self-test failed; the gate may be missing the shape it exists for." >&2
  exit 1
}
pass "Flyway version-pinning self-test"

node scripts/verify-flyway-version-pinning.mjs || {
  echo "Flyway version-pinning check failed." >&2
  exit 1
}
pass "Flyway version pinning"

# Batch 924. The capability protocol version had seven copies in shell scripts
# and two of them had already drifted to a value the server does not publish —
# in the worst case an assertion that no report could satisfy, which is why
# `verify-business-client-readiness.sh` had never completed in this work tree.
# The self-test feeds the rule the pre-fix text of all four scripts, because a
# rule that has only ever seen a clean tree is not evidence of anything.
node scripts/test-support/capability-protocol-pinning-self-test.mjs || {
  echo "Capability protocol-pinning self-test failed; the gate may be missing the shape it exists for." >&2
  exit 1
}
pass "Capability protocol-pinning self-test"

node scripts/verify-capability-protocol-pinning.mjs || {
  echo "Capability protocol-pinning check failed." >&2
  exit 1
}
pass "Capability protocol pinning"

# Batch 925. A surefire selector that names a method runs whichever names it
# finds, so a renamed test stops being verified in silence. The self-test feeds
# the rule the pre-fix text of the one script that does this, and also the six
# shell parameter expansions on the real tree that look like the same shape and
# are not.
node scripts/test-support/surefire-method-selection-self-test.mjs || {
  echo "Surefire method-selection self-test failed; the gate may be missing the shape it exists for." >&2
  exit 1
}
pass "Surefire method-selection self-test"

node scripts/verify-surefire-method-selection.mjs || {
  echo "Surefire method-selection check failed." >&2
  exit 1
}
pass "Surefire method selection"

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
pass "Gate-entry-points self-test"

node scripts/verify-gate-entry-points.mjs
pass "Gate entry points"

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
pass "Retrieval-baseline self-test"

echo "Repository gate chain: $PASS_COUNT checks passed."
