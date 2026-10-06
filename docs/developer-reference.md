# Developer Reference

> [English](developer-reference.md) | [中文](developer-reference-zh-CN.md)

> **Purpose**: Provide copyable build, startup, database, model, WebUI, E2E, and release-verification commands.
> **Maintenance**: Commands must match repository scripts. Local Agent state may link here, but this document never depends on local state.

Documentation hub: [index.md](index.md). Stable project context: [project-context.md](project-context.md).

## 1. Fixed Conventions

| Item | Value |
|------|-------|
| Java | 21+ |
| Maven | 3.9+ |
| Service / backend-only default port | `8081` |
| `dev.sh` backend port | `18082` |
| Local profile | `postgresql` |
| Real-LLM E2E port | `18081` |
| Embedding | SiliconFlow `BAAI/bge-m3` |
| Vector dimension | `1024` |
| Flyway | V1–V60 |

Do **not** append `/v1` to an OpenAI or Embedding `base-url`. Spring AI appends `/v1/chat/completions` or `/v1/embeddings`.

## 2. Build And Test

```bash
mvn clean compile
mvn clean test
mvn clean package -DskipTests
```

`clean` is not optional on the test run. Without it Maven keeps
`target/test-classes` entries for test classes that were deleted from source,
and Surefire goes on executing them: the reported suite size and every coverage
figure derived from it are then inflated. `scripts/verify-project-tests.sh`
reconciles the source tree against the reports both ways and fails on either
mismatch, so a ghost cannot survive a gate.

One module or test (a focused run is fine for debugging, but it does **not**
satisfy the visibility gate, which expects the module's full suite):

```bash
mvn test -pl spring-ai-rag-core
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest
```

Coverage:

```bash
mvn clean test jacoco:report
open spring-ai-rag-core/target/site/jacoco/index.html
```

See [testing-guide.md](testing-guide.md) for the test strategy.

### Test Visibility

Run after the backend suite, once reports exist:

```bash
./scripts/verify-project-tests.sh
```

A test class that neither executes nor reports itself as skipped writes
`tests="0" skipped="0"` to its surefire report — the same pair of numbers a
genuinely empty class produces, so it vanishes from the run summary entirely.
Gating a class with `assumeTrue` inside `@BeforeAll` causes exactly this:
JUnit aborts the container rather than skipping it.

Twenty-one PostgreSQL/Testcontainers integration classes were in that state,
hiding about 145 test methods — including the only coverage of the API-key
rotation security guards. They now carry a class-level
`@EnabledIfSystemProperty`, so a disabled container reports its real test
count as skipped, and this gate fails if any class goes quiet again.

Because a gate that cannot fail is worse than no gate, the script first runs
`scripts/test-support/test-visibility-self-test.mjs`, which asserts the checker
rejects the `tests="0" skipped="0"` shape rather than merely that it runs.

#### Integration-Test Switches

The same script then reconciles the `*.it.enabled` switches that gate those
PostgreSQL suites against the scripts and documents meant to turn them on:

```bash
./scripts/verify-project-tests.sh   # the whole test-side chain; it prints how many checks ran
```

Gating a suite is a promise that somebody can ungate it. `PdfImportPostgresIntegrationTest`
— 2 test methods — had a switch that appeared in no script and in no document
outside an archived progress note, so nothing could run it. The gate fails when
a gated switch has no run path in either direction, when a run path names a
switch no test class consumes, when a gated class declares no `@Test`, when
a `verify-gated-it.sh` suite entry points at a class that is gone or no longer
gated by the flag the runner passes, and when a gated class that no aggregate
runner runs is reachable only through one feature's own script. That last one
answered a question the first four could not: every gated switch in this
repository had a run path, and twenty of the twenty-three suites still had no
single command that ran them. Adding a gated suite means adding its run path
*and* its `ALL_SUITES` line; `scripts/test-support/integration-switch-self-test.mjs`
proves each of those five checks can still reject.

### Gate Inventory and the Gate Census

`scripts/gate-registry.mjs` records every gate script in this repository and
`scripts/verify-gate-wiring.mjs` censuses it. The registry is not bookkeeping:
before it existed, Batch 809 measured that **13 of the 21 gates and entry points
appeared nowhere under `docs/`** — including seven of the nine WebUI checks, so
`check:mutation-errors` and `check:query-errors` were undiscoverable to anyone who
did not already know they existed.

| Gate | Rejects | Self-test | Runs in |
|------|---------|-----------|----------|
| `verify-flyway-version-pinning.mjs` | A verification script that selects Flyway's latest version and compares it against a literal integer. `verify-alert-notification-delivery.sh` asserted `58` in an expected-facts string for a month after V59 shipped, so an acceptance run that had been correct started failing on a fact naming nothing the reader could act on — and the `database_facts migration=…` line it printed reported the stale number as if it were what the database said. **Two sibling scripts already computed it** from `db/migration` (`verify-collection-provisioning.sh`, `verify-managed-api-principals.sh`), so the correct shape existed and this one script had not adopted it; that is what lets the rule be zero-tolerance with no allowlist. Naming a version in a comment or a filename is not a violation — only *asserting against* one is. The self-test feeds the rule the **pre-fix text of the script it was written for**, because a scanner never shown the instance it exists to catch is not evidence of anything | `test-support/flyway-version-pinning-self-test.mjs` | tests chain |
| `verify-capability-protocol-pinning.mjs` | A script that compares the capability protocol version against a written-down number. The server defines it in exactly one place, `IntegrationCapabilityCatalog.CONTRACT_VERSION`, and six shell scripts each kept a copy — **two of which had already drifted to a value the server does not publish**. `business-client-contract-e2e.sh` required `1.0` from a report whose own producer (`business-client-binding-preflight.sh`) refuses to write a success at anything else, so the assertion was **unsatisfiable for any report** (a failure report is `null == "1.0"`, a success report is `"1.1" == "1.0"`) and `verify-business-client-readiness.sh` had therefore **never completed in this work tree**; its first rerun since (2026-10-06) stopped on it with 16 of 17 steps green. `verify-document-sync-runs.sh` read the same endpoint and required the same `"1.0"`, last run 2026-08-20. **A drifted copy is not a stale number**: correcting the two would leave five correct copies free to drift the same way, and the six scripts share no execution that would notice. The literal therefore lives in `scripts/lib/business-client-capability.sh`, and `verify-capability-protocol-pinning.mjs` keeps it the only place it appears. **The rule carries no allowlist**: the envelope protocol that shares the field name (`generic-client-record-mutation-v1` — a name, not a version), the library that defines the value, the `test-support` fixtures that manufacture a mismatch on purpose, and the comments that quote a broken line while explaining it are each excluded by *what they are* rather than *where they live* | `test-support/capability-protocol-pinning-self-test.mjs` | tests chain |
| `verify-port-probe-authority.mjs` | A script that decides a port is free by **binding a throwaway socket** and does not source `scripts/lib/port-probe.sh`. That is not the question the server it is probing for asks, and on this machine the two answers collided: `lsof` sees `python3` listening on `*:4173` (an IPv6 wildcard), a Node bind to `127.0.0.1:4173` **succeeds**, and `verify-release.sh` handed that verdict straight to `vite preview --strictPort`, which died on the very port the probe had just certified free — in a script that has never completed in this work tree. Six siblings carried the same probe and the same latent hole. **Seven scripts here already** ask the authoritative way, with `lsof -nP -iTCP:<port> -sTCP:LISTEN`, and that is the shape the library now holds. The rule looks for a socket being bound, not for the word `node`, so the scripts that use node to parse JSON or write evidence files are untouched | `test-support/port-probe-authority-self-test.mjs` | tests chain |
| `verify-playwright-suite-selection.mjs` | A bare `npx playwright test` that names no spec and passes no `--config`. `playwright.config.ts` has **no** `testIgnore`; the two preview configs declare `testIgnore: ['**/*-real.spec.ts']` — so the **shape of the command** decides whether the run swallows the five specs that need a live backend and real credentials. `verify-jsonb-records.sh` and `verify-release.sh` each start their own `vite preview` (a static server with no backend behind it) and then ran a bare invocation: **93 passed, 5 failed**, and neither script had ever completed in this work tree, so nothing had recorded that the step is red for a reason no reader can act on. Seventeen of the nineteen invocations name their specs and are none of this rule's business — naming `alerts-real.spec.ts` on purpose is the entire point of the alert acceptance run | `test-support/playwright-suite-selection-self-test.mjs` | tests chain |
| `verify-surefire-method-selection.mjs` | A script that selects surefire tests **by method name** without first checking that those methods still exist. Surefire reads `-Dtest=Class#a+b` as "run whichever of a and b exist", and twenty scripts here pass `-Dsurefire.failIfNoSpecifiedTests=false` — right for **class** selection, since a missing class leaves no report and every one of them already checks for the report. Method names are the exception, and they are **silent**. `verify-next-high-value-feature.sh` named a renamed method in **both** of its branches (`migrationsCreate…` → `latestMigrationsCreate…`): the first run since 2026-08-21 executed six of the seven it asked for, and the only thing in the repository that noticed was the count hardcoded beside the list, which reported a run in which all six tests passed as "must run 7 tests" without naming the method or the rename. The check lives in `scripts/lib/surefire-method-selection.sh` (for the reason Batch 894 moved the report reader there), and the rule is the **wiring** — "does this script verify the names" is not decidable by reading the script, but "does it source the library" is. The rule requires an identifier immediately followed by `#` at the start of a quote or after `-Dtest=`, so the six shell parameter expansions on the real tree (`${version#1.}`, `${suite##*:}` and the rest) are excluded because there `#` is an operator rather than a separator — not because of which file they are in | `test-support/surefire-method-selection-self-test.mjs` | tests chain |
| `verify-embedding-state-generation.mjs` | A production writer of `rag_document_embedding_state` that omits `request_generation` from its INSERT, or that assigns the column a bare literal. `DerivationIntegrityRepository` requires `vector_generation > 0` before it will call a vector fresh, so a row written without the column took the default of 0 and a correctly embedded document — status `COMPLETED`, hash matching, chunker matching, vector present — was classified `CORRUPT` and surfaced as `embeddingStatus=FAILED` with a null error. Measured on a live instance: 69 of 82 rows, with `run-retrieval-regression.sh` aborting on its third fixture with `status=FAILED error=None`. The rule reads Java with comments stripped and string literals intact, because the SQL is assembled across lines by `+` — the gap between the table name and its column list contains a closing quote, a newline, a plus and an opening quote, and a pattern allowing only whitespace there matches nothing in this repository, which looks identical to a clean tree. The migration half requires some migration to declare `CHECK (request_generation > 0)`, because a correct writer and a schema that cannot object are two different failures and only the second is permanent. The test tree is out of scope on purpose: two fixtures there omit the column to seed a pre-V42 shape and to assert the new default, and since V60 the default is a value the integrity repository can read | `test-support/embedding-state-generation-self-test.mjs` | tests chain |
| `verify-test-visibility.mjs` | A test class that neither ran nor reported a skip (`tests="0" skipped="0"`). The check is **bidirectional**: every non-abstract test source matching surefire's four default includes (`Test*`, `*Test`, `*Tests`, `*TestCase` — Batch 885 added the missing `Test*`) must have produced a report, and every report must map back to a source, so a class deleted from source while its `TEST-*.xml` survived cannot inflate the totals. A source that declares no JUnit test method is not collected, because surefire reports nothing for one. **One module per run**: the source root is derived from the reports directory, and the aggregate entry point points the gate at `spring-ai-rag-core`; the success line names the module it covered. The test count itself is **counted from the `<testcase>` elements, not read off the `tests=` attribute** (Batch 893). Surefire writes that attribute before the cases an `@Nested` inner class contributes, so it can be smaller than the children it summarises: measured over all four modules' 1006 reports, three disagreed — `RagCollectionServiceTest` by 7 (17 declared against 24 real cases, every one of them from a nested class), `DocumentMapperTest` by 1, `GeneralRagAutoConfigurationBeanTest` by 1 — and the attribute total came to 8320 where 8329 cases ran. This gate is the thing that prints that number, so it was under-reporting its own tree by nine. `skipped`, `failures` and `errors` are still read from the attribute because none of the 1006 reports disagrees on them; a counter changed without a measurement behind it is its own kind of defect | `test-support/test-visibility-self-test.mjs` | tests chain |
| `verify-integration-test-switches.mjs` | A gated switch missing a run path, in either direction; and — since Batch 912 — a gated suite that only one feature's own script can run. That second direction is the one the first four could not express: every gated switch in this repository had a run path, and twenty of twenty-three suites still had no single command that runs them, while the gate stayed green | `test-support/integration-switch-self-test.mjs` | tests chain |
| `verify-external-db-safety.mjs` | A suite that takes a caller-named database and runs `flyway.clean()` on it | `test-support/external-db-safety-self-test.mjs` | tests chain |
| `verify-e2e-run-paths.mjs` | A Playwright spec no script can run | `test-support/e2e-reachability-self-test.mjs` | tests chain |
| `verify-slo-endpoint-coverage.mjs` | A threshold whose endpoint is gone; a timer name shared across controllers | `test-support/slo-endpoint-coverage-self-test.mjs` | tests chain |
| `verify-gate-wiring.mjs` | An automated gate that is unregistered, untested, unexecuted or unreachable from CI, and — since Batch 900 — a standing-gap reason that sends the reader to a path which does not exist. That last one is the shape nothing else catches: the registry already checked its machine-readable path fields, and a standing gap is the only hole here with exactly one person who can close it, so a dead pointer in it does not fail loudly, it just means nobody ever can. All sixteen of them named `/tmp/b806-ci-gates.patch`, which is gone. A token counts as a path only if it contains a slash, so a bare script name is not resolved against a guessed directory, and only if it contains a dot or ends in a slash, so `text/plain` and `2026/10/05` are prose. The self-test pins both limits, and pins that the repository's own reasons resolve. **Since Batch 913**: an `entrypoint` committed without the executable bit. An entry point is the one kind a person types as a path, so that is the one kind whose mode decides whether the documented command works — and `verify-webui-e2e-mock.sh` shipped as 100644 while its three siblings shipped as 100755, with every other property this gate checks about it correct. `kind: 'gate'` and `kind: 'manual'` are exempt on purpose: every automated gate is run as `node scripts/verify-*.mjs` and is 100644 for that reason, and two *sourced* libraries under `scripts/lib/` carry shebangs at 100644, so "has a shebang" would have been the wrong test | `test-support/gate-wiring-self-test.mjs` | tests chain |
| `scripts/lib/python-assertion-check.mjs` | The Python half of `verify-json-assertions.mjs`, which until Batch 899 read shell only. Batch 898 had already found the identical defect in Python — in the one gate whose whole job is catching a regression — and the only reason it was found was that somebody read that script, which is what "reads one language" means in practice. Reports a line where a falsy default feeds a comparison whose other side is falsy too: the one shape where "the reader could not obtain it" and "it is correct" are the same answer. Measured before the gate existed: 97 sources, 4147 lines, 88 falsy defaults, **0 of the dangerous shape** — which is why it is a gate and not a baseline. `not in (field or "")` is deliberately not counted: it fails closed | `test-support/json-assertions-self-test.mjs` | tests chain |
| `verify-gate-entry-points.mjs` | A gate script that decides "am I the entry point?" by hand. Twenty-two of them did, in four spellings, and **eighteen printed nothing and exited 0 when named through a symlinked path** — the ESM loader resolves symlinks when it computes `import.meta.url` and leaves `process.argv[1]` as typed, so the two disagree and `main()` is never called. `resolve()` does not help: it makes a path absolute, it does not follow links, which is the distinction the second idiom got wrong. The registry's own rule that every gate carries a self-test is discharged by *importing* the module, so it is structurally blind to a gate that never runs. Also requires the two copies of the helper to be byte-identical, because the webui package cannot import the repository's and a mirror nobody compares is a second implementation waiting to happen. **Stated limit**: a gate with no guard at all — `verify-json-assertions.mjs` is one — passes this gate and is a different question. **Blast radius, stated because it is easy to overstate**: Node resolves `process.cwd()` physically, so relative invocation from a symlinked directory was always safe and the aggregate was never affected; the exposure is naming a script through a symlink | `test-support/gate-entry-points-self-test.mjs` | tests chain |
| `scripts/lib/is-main-module.mjs` | The single spelling of "is this the program being run", and the fix the self-test proves behaviourally: it reduces both sides to real paths, so a symlink anywhere in the typed path still matches. Exists because four files already had a correct answer and the duplication is what produced the other eighteen | `test-support/gate-entry-points-self-test.mjs` | tests chain |
| `scripts/lib/report-destruction-check.mjs` | Decides whether a `scripts/*.sh` that runs a Maven `clean` takes the surefire reports with it, and whether it says so. `verify-test-visibility.mjs` reconciles the test source tree against `target/surefire-reports`, so the chain is only as correct as what the last thing to touch `target/` left there. Measured before the rule existed: twelve scripts run `mvn clean`, **five put the reports back** by re-running an unscoped `mvn test`, and **seven did not and said nothing** — a reader who ran one and then the chain got `No surefire reports at …`, which reads like their own mistake. Both exits are legitimate, so the rule accepts either, and seven scripts now carry a line in their own header rather than a registry of known offenders. **The declaration criterion is deliberately two-part**: a mention of `surefire-report.sh` is three scripts talking about sharing a report *reader*, and one of them could have moved that line into its header and been excused without changing anything. Three goal-token shapes cost a rewrite each: a comment that lists `mvn clean` is not an invocation; `mvn clean test` is one command that both destroys and restores, so the two tests cannot be `else if`; and shell writes `mvn test;` and `{ mvn test; }`, so recognising only whitespace-terminated goals misses exactly the shape that makes a script self-healing | `test-support/report-destruction-self-test.mjs` | tests chain |
| `scripts/lib/java-source.mjs` | The single way four gates strip Java comments. They had four copies in three behaviours; two were a regex with no notion of a string literal, so a URL in a string read as a comment. Measured, with no code changed: **316 string literals in this repository are destroyed by that shape**, and in nine files a `/*` inside a string runs the match to the next `*/` — one of them losing 118 of 369 lines. A gate that cannot see a third of a file is not reading that file. The scan is a character walk that tracks quote state, and it preserves length and every newline because `verify-error-code-catalog` maps findings back by counting lines. Its self-test's last case fails if a gate starts rolling its own again | `test-support/java-source-self-test.mjs` | tests chain |
| `scripts/lib/reason-pointer-check.mjs` | Extracts the path-shaped tokens from a `noCiReason` so `verify-gate-wiring.mjs` can check each one against the disk. Split out because the shape of a path is a judgement — a first draft resolved every path-shaped token in the registry and reported six that do not exist, of which four were regex constants and one was a script Batch 809 deleted on purpose — and that judgement wants cases of its own rather than living inline in a 350-line checker | `test-support/gate-wiring-self-test.mjs` | tests chain |
| `verify-test-expectations.mjs` | A `@Test` that asserts nothing, for any of the ways a test can end up a no-op. **Empty body** (or a body holding only comments) reports as a *pass* every run, so it inflates the passing count while proving nothing. **`private` or `static`** is not run by JUnit 5 at all, and is the quieter of the two: it does not appear in the report as a pass or a skip, it just does not appear, so the build stays green while the test count shrinks (Batch 887). The second rule reads the annotation off the lines **directly above** the declaration rather than off the window between declarations — that window is bounded by the previous declaration's *parameter list*, so a private helper inherits whatever `@Test` sits above whichever test preceded it (743 such helpers on the real tree, measured). Both rules are only as good as the parser underneath them, and the parser has been wrong twice. It read `new ClientFixture(...)` and then every control-flow statement as a method declaration — an `if` block has no return type to consume, so whatever landed in the name position was a Java keyword, and **579 of 12150 reported "declarations" were statements or anonymous classes** (Batch 890), each contributing a block that was read as a method body. Before that, body extraction skipped string literals but not comments: an apostrophe in a `//` note was read as a string delimiter, the scan ran off looking for a partner quote, and **eighteen real `@Test` methods across fourteen files came back with no body at all** — invisible to both rules, so an empty-bodied one among them would have passed unnoticed (Batch 888). Comments are now stepped over before quotes, and a name that is a statement keyword — or a return-type slot reading `return`/`new`, which is how an anonymous class is spelled — is not a declaration. The rejection is surgical on purpose: the match still starts where it started, because moving it would shift the gap window the empty-body rule builds, which is how Batch 887 broke that rule once already. Three quotes in a row are a text block, and three quotes are also a string delimiter the ordinary scan consumes two at a time — so the payload came back as code and **nine method bodies in `MultiModelConfigLoaderTest` were truncated inside their own JSON sample**, with two more text blocks in `EvaluationSuiteDefinitionCaseValidationTest` that do not even balance. Batch 886 had concluded this was harmless, and the reasoning was sound: the body only feeds an emptiness check, and a body read too long reads as non-empty. The data was still wrong, and a conclusion about what a wrong value is used for is not a reason to leave it wrong. A text block is now one unit, and the parser recognises it before the ordinary string case so the braces inside stay data. The known miss is a wrapped annotation whose closing line is not preceded by a rebalanced paren count, and the direction is only-miss | `test-support/inert-test-self-test.mjs` | tests chain |
| `verify-null-request-forwarding.mjs` | An overload that forwards a `null` into an `HttpServletRequest` parameter — either a literal, or a local declared as a possibly-null `HttpServletRequest`, where `ChatPrincipal.from(null)` and `ApiKeyCollectionAccess.isUnrestricted(null)` both fail open. Batch 883 closed three scanner blind spots: signatures ending in a `throws` clause, declarations indented deeper than four spaces (inner classes), and a glob inside a line comment dragging the block-comment rule into real code — `// … assets/**` had made `WebUiConfig`'s entire `webuiCatchAll` method invisible to the gate. **Known miss**: a Java text block (`"""`) is not modelled separately; the direction is only-miss, never a false finding | `test-support/null-request-forwarding-self-test.mjs` | tests chain |
| `verify-false-optional-wiring.mjs` | A collaborator injected with `@Autowired(required = false)` and guarded by a null check, whose bean is an unconditional `@Service`/`@Component` — so the guarded branch is unreachable in a running application — unless the field records `// optional-claim: <reason>`. The scanned surface is `*Controller.java` plus `*Service.java` (Batch 829); both throwing and skipping guards are claims, and they differ only in disposition: delete a throwing one, record a reason for a skipping one. A reason under eight characters is itself a finding (`weak-optional-claim`, Batch 882) — the same house rule as `weak-allow-reason` in the two frontend gates, because this valve waives a claim about the deployment shape rather than a style choice | `test-support/false-optional-wiring-self-test.mjs` | tests chain |
| `verify-controller-constructor-count.mjs` | A controller declaring more than one constructor, of any visibility — Spring injects through the `@Autowired` one, so the rest are reachable only from tests and decide on the caller's behalf which collaborators end up null. **Scope, stated because the old success line overstated it (Batch 884)**: this counts constructors only. Twelve `@Autowired` methods on six controllers inject thirteen collaborators a constructor count cannot see, and a test can leave those unset — twelve through `@Autowired(required = false)`, which `verify-false-optional-wiring` judges, and one through a required setter, which no gate judges. The uncovered surface is measured and printed on every run | `test-support/controller-constructor-count-self-test.mjs` | tests chain |
| `verify-error-code-catalog.mjs` | A code that reaches an error response without being declared in `ErrorCode` (which calls itself the single source of truth; six were missing); an HTTP status beside a code that contradicts the one the enum declares; a hand-assembled `ErrorResponse` that is not a problem detail. This is the one gate in the repository that both discards a comment's newlines and reports line numbers, so the two had to be reconciled: `stripComments` used to replace each comment with nothing at all, and `lineOf` counted lines in the shortened text, which put **32 findings on the real tree at the wrong line — up to 54 lines off**, and a gate whose job includes saying where to look has to be able to say it correctly. Comments now keep their newlines, which leaves the matching byte-for-byte unchanged (**0 files** changed their finding set) | `test-support/error-code-catalog-self-test.mjs` | tests chain |
| `verify-json-assertions.mjs` | Inside `all(...)` / `any(...)`, a negative assertion — a field compared with `!=`, or a `// ""`-guarded containment test negated with `\| not` — is satisfied by a field that reads as absent, so the predicate reports "nothing is wrong" about something it cannot see. Batch 895 found one such predicate by reading two of them by hand and had to guard it from outside; the census that followed found two more, in scripts that run nowhere in CI. **No allowlist**: a fail-open predicate cannot be exempted without one, and an allowlist is a list of things the gate does not check | `test-support/json-assertions-self-test.mjs` | tests chain |
| `scripts/lib/retrieval_baseline.py` | The readers `run-retrieval-regression.sh` uses to judge a run against a committed baseline. It replaced three readers that each substituted `0.0` for a value they could not obtain: `metric_at_k` turned a metric the run never supplied into a zero **before any comparison saw it**, `check_minimum` let a floor of `0.0` be satisfied by a metric that was never measured, and the baseline comparison judged a metric against zero when the committed baseline did not carry it — so the regression check for that metric stopped existing without a word. Measured: with a complete baseline an ndcg drop from 1.0 to 0.70 is reported; with the `ndcg` key missing it silently passes, and the total collapse that `aggregateMinimum` still catches is exactly the drop this hides | `test-support/retrieval-baseline-self-test.mjs` | tests chain |
| `verify-tautological-assertions.mjs` | A Java test assertion that cannot fail reads as coverage and is not. Two rules, both with no correct instance so neither needs an allowlist: a self-satisfying literal (`assertTrue(true)`, `assertFalse(false)`, `assertNull(null)`, `assertNotNull(<non-null literal>)`) and a first argument whose **top-level** connective is `\|\| true` or `&& false`. Top-level is the whole rule — `a == false \|\| true` is constant even though its `\|\|` is not the first operator you read, while `x == false` is an ordinary assertion. Batch 908 removed five; the loudest one carried a Chinese failure message claiming the assistant role must not survive MiniMax normalisation, while the same file's javadoc and the production code both declare the transformation is `system → user`. **Deliberately not a rule**: `assertEquals(x, x)`, of which the census found eight. JUnit resolves those through `equals()`, so a broken `equals()` or an unstable `hashCode()` fails them — they are contract checks, and flagging them is what would have forced an allowlist | `test-support/tautological-assertions-self-test.mjs` | tests chain |
| `verify-no-pessimistic-locks.sh` | Pessimistic locks, `SKIP LOCKED` and advisory locks in production code | `test-support/pessimistic-locks-self-test.sh` | docs chain |
| `verify-zh-translation.mjs` | An untranslated English passage in a Chinese document | `test-support/zh-translation-self-test.mjs` | docs chain |
| `verify-project-tests.sh` / `verify-project-docs.sh` | The two aggregate entry points: one runs every gate registered with `kind: "gate"` in the registry, each preceded by its own self-test, the other runs the documentation chain. Each prints the number of checks that actually ran, so neither file needs to be edited when the chain grows — this row used to say "the nine above", which was a number nothing recomputed | borne by each gate | by hand / not yet in CI |
| `verify-gated-it.sh` | The gated PostgreSQL inventory — 23 classes / 153 test methods, all of them pure Testcontainers-plus-Flyway as of Batch 912, when the last twenty were registered and the suite's own "runs every database-only suite" comment stopped being a claim about three of them. Takes about 9 minutes locally. CI calls it with no arguments, so this is where the whole inventory runs | borne by switch reconciliation | **wired into CI** |
| `verify-webui-e2e-mock.sh` | The 15 spec / 93 case WebUI mock regression. All 93 passed in 2m22s when Batch 913 first ran it in this working copy, which is the measurement behind the cost half of the open question about wiring it into CI: the only cost is wall-clock, not failures. Batch 913 also found it committed **without the executable bit**, so the invocation this table documents failed with `Permission denied` and exit 126 | the suite is its own self-test | standalone (2.4 min measured) |
| `check-alignment-policy.mjs` | Physical `text-align`, inline `textAlign`, the global-stylesheet contract; test files are skipped, `--text-align` is a token rather than a declaration, and an `allow-center` comment no centre claims is a failure (Batch 881) | `__tests__/alignment-policy.test.mjs` | `npm run lint` |
| `check-design-system.mjs` | Hard-coded values that bypass a design token | `__tests__/design-tokens.test.mjs` | `npm run lint` |
| `check-a11y-forms.mjs` | A control with no accessible name, a label bound to nothing | `__tests__/a11y-forms.test.mjs` | `npm run lint` |
| `check-mutation-errors.mjs` | A write action that does not report its failure | `__tests__/mutation-errors.test.mjs` | `npm run lint` |
| `check-query-errors.mjs` | A read whose failure looks like an empty result | `__tests__/query-errors.test.mjs` | `npm run lint` |
| `check-double-submit.mjs` | A write left unguarded while its request is in flight | `__tests__/double-submit.test.mjs` | `npm run lint` |
| `check-destructive-confirm.mjs` | A destructive action with no confirmation — the list is derived from the methods in `src/api/*.ts` that actually issue a DELETE; a POST never counts | `__tests__/destructive-confirm.test.mjs` | `npm run lint` |
| `check-i18n-keys.mjs` | Asymmetric key sets between the two locales; a `t()` naming a key that does not exist; a `t('x') \|\| fallback` guard that can never fire; **a key every locale carries that no source reaches**, where a reference may be a template prefix, a lookup table, a data array, an aliased translator or an i18next plural family. An `i18n-allow` comment is a note, not an exemption — the finding still fails (Batch 879) | `__tests__/i18n-keys.test.mjs` | `npm run lint` |
| `check-hardcoded-copy.mjs` | A component that never calls i18n; a hard-coded user string in a file that does — including inside a JSX expression container, while leaving ARIA/machine attribute values and `t()` fallback strings alone. Its seven-entry `ALLOWED` list is keyed `path:copy` and an entry nothing uses any more is a failure (Batch 880) | `__tests__/hardcoded-copy.test.mjs` | `npm run lint` |
| `check-page-shell.mjs` | A protected page that bypasses `PageHeader`, renders it with no `description`, or passes a `description` that is provably empty — the walk is recursive, so a page in a subdirectory is a page like any other | `__tests__/page-shell.test.mjs` | `npm run lint` |
| `check-reduced-motion.mjs` | A CSS rule that declares an **`infinite`** animation and whose selector is never neutralized (`animation: none`) under `@media (prefers-reduced-motion: reduce)`. Continuous motion is what people with vestibular disorders turn animations off for, and Batch 910 measured five stylesheets running it with no way to stop: the skeleton shimmer shown by every page that loads, the same shimmer inside `ReembedAllButton`, the streaming cursor's blink, and two indeterminate spinners in `Files`. **Only `infinite` is reported** — a one-shot animation ends on its own and is short by construction, so requiring a guard for the dialog's 140ms fade would be reporting correct code. The rule matches on selectors rather than positions, because the house pattern declares the animation by default and overrides it; a first draft that asked "is this declaration outside a reduced-motion block?" flagged all six files, five of which had just been fixed correctly. Comments are stripped first, and that is not a formality: the guards this batch added each name the media query in prose, so a text-counting version counts its own explanation as the guard | `__tests__/reduced-motion.test.mjs` | `npm run lint` |
| `check-heading-levels.mjs` | A page whose headings skip a level on the way down — `h1 → h3` claims a section that does not exist, so the outline a screen-reader user builds from the page is wrong before they read a word. `PageHeader` counts as the page's `h1`, and `Unlock.tsx`, the one screen that does not use it, carries its own. Thirteen of fourteen screens were already well-formed; Batch 911 found `Alerts` the only one that jumped, twice, for the two form cards on a page with no `h2` at all. The check is **per page file**, so a heading a child component writes is not counted — a miss, never a false alarm, which is the same limitation `check-page-shell`'s recursive walk documents from the other side. Reading the tree without stripping comments reports `Documents.tsx` as a heading site: a Chinese comment there spells out `<h2>` while explaining an `aria-labelledby` that points at an empty one | `__tests__/heading-levels.test.mjs` | `npm run lint` |
| `check-decorative-graphics.mjs` | An inline `<svg>` that has no `role`, no accessible name and no `<title>` is telling a screen reader nothing, and assistive technology still walks it. The whole tree holds one inline `<svg>` — the clock glyph in `Search`'s history toggle, whose `<button>` already carried an `aria-label`, so the glyph was pure decoration and was being announced anyway. The rule also refuses to report a graphic that **does** name itself: hiding meaningful content is the opposite error, and WCAG's own logic puts it out of bounds. Attributes are parsed by name rather than grepped, because a text search cannot tell an attribute from a string that looks like one — reading `data-note="aria-hidden='true'"` as a declaration would make the gate report the opposite error from the one it exists to catch | `__tests__/decorative-graphics.test.mjs` | `npm run lint` |
| `scripts/lib/tsx-source.mjs` | The one way to strip comments and string literals from TSX, for gates that read it. Batch 910 and Batch 911 each added gates that read source text, and all of them wanted something that did not exist. The Java equivalent arrived in Batch 905 after four gates destroyed 316 string literals between them, so the third dialect of the same mistake is the one worth not writing. **Two entry points, because the two consumers need opposite treatments**: `stripTsxNoise` blanks string contents (a gate matching markup must not match a heading inside a string) and `stripTsxComments` leaves them (this file's decorative-graphic gate asks whether `aria-hidden="true"` is present, and blanking the value erases the very token it is looking for). Keeps length and newlines so a finding still maps to the line it is on, and tracks template-literal interpolations as real code | `__tests__/tsx-source.test.mjs` | `npm run lint` |

### Reading a Surefire report

Five acceptance gates — `verify-collection-purge.sh`, `verify-api-key-expiry-alerts.sh`,
`verify-next-high-value-feature.sh`, `verify-collection-provisioning.sh` and
`verify-document-lifecycle.sh` — assert that a gated PostgreSQL suite ran to
completion, and all of them do it by reading four counters off a `TEST-*.xml`.
Before Batch 894 each carried its own copy of the same `sed` pipeline and none had
a self-test, so the reading itself was never checked, only the thing being read.

They now share `scripts/lib/surefire-report.sh`, whose self-test runs the real shell
function rather than a JavaScript restatement of it. Two things about it are worth
knowing before changing it:

- **`tests` is counted from the `<testcase>` elements, not read off the `tests=`
  attribute.** Surefire writes that attribute before the cases an `@Nested` inner
  class contributes, so it can be smaller than the children it summarises: across
  the four modules' 1006 reports, three disagreed, by 9 cases in total, and the
  attribute total came to 8320 where 8329 ran. The expected counts in those gates
  are hard-coded, so the first person to add a nested class to a gated suite would
  have met a failure naming a number that is not the number of tests that ran.
- **`skipped`, `failures` and `errors` are still read off the attribute**, because
  none of those 1006 reports disagrees on them. Changing a counter without a
  measurement behind it is its own kind of defect.

The four counters come back comma-separated rather than space-separated. Three
empty counters in a row are one separator to any whitespace split, so a
space-separated version cannot distinguish a report carrying no `failures`
attribute from one carrying `failures=""` — every consumer of it, shell and test
alike, silently received two fields instead of four. Consumers read it as
`IFS=, read -r tests failures errors skipped < <(surefire_counts "$report")`.

### Reading the active-alert list

`verify-managed-api-principals.sh` is a manual gate — it starts two backends and
four containers — so it is one of the sixteen gates in the standing CI gap and
none of its readers ran anywhere. It holds sixty `jq -e` predicates over API
responses. Two of them read the active-alert list, and both were written as
`any(...)` / `all(...)` one-liners inside a poll loop that treated every non-zero
exit as "not yet". That is the right reading for a poll and the wrong one for a
reader:

- `all(.[]; .alertType != "API_PRINCIPAL_EXPIRY" or .metrics.principalId != $p)`
  returns **true** when `principalId` is renamed, because the missing field is
  `null` and `null != $p`. This predicate exists to prove an alert is *not*
  firing, so it is the one direction that must never fail open: measured, an
  alert that was present, firing and attributed to the principal under test was
  reported as correctly absent.
- A body that is no longer an array makes `jq` exit 5, not 1, and the loop could
  not tell the two apart. It burned the whole 30-second budget and then reported
  "the alert did not reach ACTIVE" — naming the alert instead of the reader.

The predicates now live in `scripts/lib/alert-payload.sh`, and each poll calls
`alerts_response_judgable` **before** it reads: a payload that is not an array,
or an `API_PRINCIPAL_EXPIRY` alert with no readable `metrics.principalId`, is
refused on the first poll with a reason that names the reader. The guard is scoped
to that one alert type on purpose — other kinds legitimately carry no principal,
and demanding one of everything would fail on correct data. An empty list is
still legitimately "no alert"; what changed is that a payload the reader cannot
interpret is no longer silently agreed with.

The other three expiry readers in that gate were left alone, and the self-test
pins why: all three demand `($alerts | length) == 1`, so a renamed field yields
zero matches and they fail. The shape that fails open is the one that asks
"is anything here" rather than "is this exactly one thing".

`scripts/test-support/alert-payload-self-test.mjs` runs the real shell functions,
because the thing under test is a shell function — a JavaScript restatement would
pass while the shell one rotted. One of its cases asserted that
`alerts_lack_expiry` **still** returned 0 for an alert it cannot attribute, pinning
the hazard at the predicate level so the guard in the poll loop was known to be
load-bearing.

**Batch 896 inverted that case, because Batch 896 closed the predicate.** Requiring
the fields inside the same conjunction makes the assertion range over "the field is
readable and says something else" instead of over "I cannot look":

```jq
all(.[];
  (.alertType != null)
  and ((.alertType != "API_PRINCIPAL_EXPIRY")
    or ((.metrics.principalId != null)
      and (.metrics.principalId != $principal))))
```

The principal requirement is scoped to expiry alerts on purpose — other kinds
legitimately carry no principal, and demanding one of everything would fail on
correct data. `alerts_response_judgable` stays, but as a **message** rather than a
safety net: it names the reader in the first second instead of leaving a poll to
time out on a question it can never answer. A case that pins a defect is a nail,
and once the defect is gone the nail has to come out or it puts the defect back.

The census has five hard rules: every gate script is registered; an automated gate
carries a self-test or says why it cannot; an automated gate is executed bysomething; **an automated gate CI cannot reach carries a written reason**; and every
gate is mentioned in a document. Adding a gate without registering it fails the
next run of `verify-project-tests.sh`.

That last rule is not decoration. `check-entity-migration-sync.sh`, deleted in
Batch 809, was this repository's **fourth** gate that could not fail: it claimed to
reconcile entity fields against Flyway migrations and its body compared nothing,
6 of its 11 hard-coded table names had been renamed out from under it
(`rag_retrieval_log` → `rag_retrieval_logs` and five others), and it connected to a
`postgres` database this project does not use — while the invariant it claimed to
protect is already enforced at every startup by `ddl-auto: validate` in
`application.yml`. It survived that long because "Gates can fail closed" in
`verify-project-docs.sh` only inspects scripts that scan with `rg`, `jq` or `yq`,
and a `psql` + `grep` gate is invisible to it.

CI status: the two repository entry points (`verify-project-docs.sh` and
`verify-project-tests.sh`) are **not wired into CI** — editing `.github/workflows/`
needs a token with the `workflow` scope, which Batch 899 measured by pushing the
change and being refused, and the handoff is waiting on a human at
`.github/pending/ci-repo-gates.patch`. Every path a standing-gap reason names is
checked on disk by `verify-gate-wiring.mjs`, so that pointer cannot rot the way
its predecessor in `/tmp` did. The nine WebUI checks
and their self-tests do run in CI, because ci.yml's webui job executes
`npm run lint`. The census prints the standing gap together with its reason on
every run; once the patch lands those entries turn *stale* and the gate fails,
naming the lines to delete. That is deliberate: a stale exemption is how a debt
baseline rots.

### Documentation System

Run the project-documentation boundary, link, bilingual-structure, invariant, command, whitespace, and secret checks with:

```bash
./scripts/verify-project-docs.sh
```

For the document CRUD, external synchronization, version restore, disposable
PostgreSQL, reference client, and WebUI acceptance flow:

```bash
./scripts/verify-document-lifecycle.sh
```

For the focused V42/V51 Sync Run HTTP acceptance against disposable PostgreSQL,
including authenticated authorization and durable item receipts:

```bash
./scripts/verify-document-sync-runs.sh
```

The gate creates temporary restricted read/write and read-only principals and
verifies ACLs, cursor pagination, terminal-rescan semantics, failed-receipt
recovery, `no-store`, and sensitive-data protection. Evidence excludes
credentials, cursors, external IDs, and business payloads.

For caller-scoped, durable Collection-create idempotency across PostgreSQL,
two backend instances, and process restart:

```bash
./scripts/verify-collection-provisioning.sh
```

The gate covers V52 migration and constraints, exact replay, semantic key
reuse, owner isolation, restricted ACLs, concurrent first create, current
soft-deleted state, one create audit, ledger failure closure, and secret-safe
database facts. Use `COLLECTION_PROVISIONING_VERIFY_PHASE=http` to rerun only
the disposable dual-instance HTTP phase.

For V56 Collection content cleanup, permanent-key tombstones, reference
cascades, and the WebUI preview/apply flow:

```bash
./scripts/verify-collection-purge.sh
```

The default run uses Testcontainers for five real PostgreSQL scenarios. A
caller-provided disposable database can be selected with
`COLLECTION_PURGE_IT_JDBC_URL`, `COLLECTION_PURGE_IT_USERNAME`,
`COLLECTION_PURGE_IT_PASSWORD`, and
`COLLECTION_PURGE_IT_CLEAN_CONFIRM=YES`. The script also runs focused backend
tests, the Maven clean compile gate, the complete WebUI gates, no-screenshot
Collection Mock Playwright, lock/document/shell-syntax/whitespace checks, and
writes evidence under `.verification/collection-purge/<run-id>/`.

For the durable model-invocation usage ledger and its principal-scoped
aggregate API:

```bash
LLM_USAGE_LEDGER_VERIFY_RUN_ID=usage-ledger-gate \
./scripts/verify-llm-usage-ledger.sh
```

The gate runs focused attribution/recorder/API tests, migrates an isolated
PostgreSQL database through V53, runs the full Maven and WebUI gates, verifies
the no-pessimistic-lock and project-documentation rules, and executes the
no-screenshot Mock Playwright Metrics checks. It writes secret-safe evidence
under `.verification/llm-usage-ledger/<run-id>/`. The script does not perform
real provider calls; after this gate passes, use the real-LLM lifecycle
procedure in [testing-guide.md](testing-guide.md) with an isolated service and
disposable database.

For the V43 local-keyword/vector derivation boundary:

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-4 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4191 \
./scripts/verify-keyword-vector-decoupling.sh
```

This gate requires real PostgreSQL lifecycle/full-text integration tests,
`mvn clean compile test-compile`, and the WebUI TypeScript, Vitest,
production-build, alignment, and no-screenshot Mock Playwright checks.

## 3. Start And Health Check

One-command backend and frontend development:

```bash
./scripts/dev.sh
```

Before starting Maven, the launcher validates the complete embedding contract:
`RAG_EMBEDDING_API_KEY`, `RAG_EMBEDDING_BASE_URL`, `RAG_EMBEDDING_MODEL`, and
`RAG_EMBEDDING_DIMENSIONS`. The default is fail-fast, so a missing or malformed
embedding setting is reported before any backend or frontend process is
created. Set `RAG_EMBEDDING_STARTUP_CHECK=warn` only for an explicit diagnostic
startup; this mode emits a prominent warning and never injects a mock model or a
placeholder credential.

When validation passes, the launcher exports the complete repository-root `.env`
to Maven / Spring Boot. It also allows the exact Vite origin on the backend and
verifies a root-authenticated management POST before reporting ready:

```text
Backend: http://127.0.0.1:18082
WebUI:   http://127.0.0.1:15173/webui/unlock
```

If neither `.env` nor the caller environment defines `RAG_ROOT_API_KEY`, the
launcher generates an ephemeral root credential for the current backend process.
On macOS it is copied to the clipboard and is never written to files or logs.
Status, stop, and port overrides:

```bash
./scripts/dev.sh --status
./scripts/dev.sh --stop
./scripts/dev.sh --force-kill
BACKEND_PORT=19082 FRONTEND_PORT=15174 ./scripts/dev.sh
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
```

By default, an unmanaged listener on either target port makes startup fail
conservatively. Use `--force-kill` only after confirming that stale processes on
the configured `BACKEND_PORT` / `FRONTEND_PORT` may be terminated. It targets
only listeners on those two ports and their descendants, sends `TERM` first,
uses `KILL` only after a timeout, and then continues normal startup.

The launcher never performs automatic Flyway repair. If startup detects a
migration checksum mismatch, it prints the relevant cause. Restore the applied
migration and place later changes in a new migration instead of rewriting
schema history.

Backend only:

```bash
bash scripts/start-server.sh
```

Manual start:

```bash
set -a
source .env
set +a
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

Port cleanup and health for the backend-only `8081` process:

```bash
lsof -ti :8081 | xargs kill -9 2>/dev/null
curl -fsS http://127.0.0.1:8081/actuator/health
```

Swagger: `http://127.0.0.1:8081/swagger-ui.html`

For the full-stack launcher, use `18082` instead:

```bash
curl -fsS http://127.0.0.1:18082/actuator/health
```

## 4. Database

- PostgreSQL connection values come from `.env`.
- Required extension: `vector`.
- Recommended extension: `pg_trgm`; `pg_jieba` is optional.
- Migrations: `spring-ai-rag-core/src/main/resources/db/migration/`.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Prefer a Docker PostgreSQL image with the extensions installed. See [postgresql-extensions.md](postgresql-extensions.md).

## 5. Model Configuration

### Embedding

```text
Provider: SiliconFlow
Model: BAAI/bge-m3
Dimensions: 1024
Base URL: https://api.siliconflow.cn
```

### Chat Providers

| Provider | Configuration |
|----------|---------------|
| OpenAI-compatible | `spring.ai.openai.*` |
| Anthropic | `spring.ai.anthropic.*` |
| MiniMax | `spring.ai.minimax.*` |

Select the default provider with `LLM_PROVIDER` / `app.llm.provider`. See [multi-model-external-config.md](multi-model-external-config.md) for model instances and external configuration.

Store real credentials only in `.env`; never put them in shell examples, Markdown, or Git.

## 6. WebUI

```bash
cd spring-ai-rag-webui
npm ci
npm run tokens:check
npm run lint
npm run test:run
npm run test:design-system
npm run build
```

### 6.1 Design tokens and the design-system gate

Design tokens have exactly one canonical source: `design-tokens/tokens.json`.
`tokens.css` and `tokens.generated.ts` are generated outputs — **never edit them by
hand**:

```bash
npm run tokens:build          # regenerate from tokens.json
npm run tokens:check          # compare only, never writes; use this in CI
npm run check:design-system   # design-debt gate
npm run check:a11y-forms      # form-accessibility gate
npm run test:design-system    # focused tests for the generator and the gates
```

- `check:design-system` scans CSS, TS, TSX and SVG and rejects ten classes of
  violation: undefined `var(--*)`, numeric z-index, literal colors (including CSS
  named colors), broad `transition` shorthands, non-zero `letter-spacing`,
  unreasoned `!important`, cross-page `*.module.css` imports, compatibility-alias
  usage, `emoji-glyph` (an emoji or dingbat used as an interface icon), and
  `css-syntax` (a stylesheet that does not parse).
- `emoji-glyph` covers pictographs plus the dingbat blocks browsers still render as
  standalone glyphs (arrows, carets, geometric shapes, check/cross marks, stars)
  and the `×` that reads as a close affordance. **Comments are masked before the
  rule runs and string literals are not**, so documentation may explain a flow
  with `→` while a glyph selected inside an expression is still caught. Box
  drawing and CJK punctuation are layout characters, not icons, and are out of
  scope. Prefer a tree-shaken `lucide-react` component; if a glyph is genuinely
  unavoidable, take the same inline `design-token-allow` route as any other rule.
- Existing debt is recorded in `design-tokens/design-debt-baseline.json` with the
  fingerprint `file|kind|value`. **New violations, increased counts and stale
  over-sized entries all fail**, so debt can only shrink. A missing baseline file
  means "no debt"; an unreadable or malformed one is an error, because a gate
  that cannot read its own records cannot be trusted to enforce them.
- `css-syntax` is the one class that **cannot** be waived with
  `design-token-allow`. A stylesheet that does not load is not a style
  preference. Note that `npm run build` is no longer the only thing that catches
  it: Vitest stubs CSS modules, so a stray brace used to pass typecheck, lint and
  the entire test suite.
- A genuinely necessary exception uses an inline
  `/* design-token-allow: <concrete reason> */` on the same or the previous line; a
  too-thin reason is separately rejected as `weak-allow-reason`, and reported once
  per comment rather than once per line it governs. Do not buy a green gate with
  blanket exemptions. (Batch 878: this exemption existed but did nothing — the
  counts the gate fails on were built from every violation, waived or not, so a
  justified exemption still failed while the error message told the reader to
  write one.)
- `check:design-tokens` is kept as a compatibility entry point for
  `check:design-system`.
- `check:a11y-forms` scans every `.tsx` under `src/` and rejects a form control
  with no accessible name, a `<label>` that labels nothing, and an `onClick` on an
  element the keyboard cannot reach. A `placeholder` is not a name, and a `role`
  is not enough on its own — the element must also declare `tabIndex` and handle a
  key. It has no debt baseline, because every violation it was written against was
  fixable. See [webui-design-language.md](webui-design-language.md#5-form-accessibility).
- `lint` chains ESLint, `check:alignment`, `check:design-system` and
  `check:a11y-forms`.
  `test:coverage` additionally enforces the global coverage thresholds from
  `vitest.config.ts`.

### 6.2 Development and integration

Development:

```bash
npm run dev
```

Direct use listens on `http://127.0.0.1:15173/webui/` and proxies `/api` to
`http://127.0.0.1:8081` by default. For normal full-stack development, run
`./scripts/dev.sh` from the repository root so the launcher keeps ports and the
proxy target aligned.

The release build embeds assets under:

```text
spring-ai-rag-core/src/main/resources/static/webui/
```

### IME-safe input behavior

All WebUI inputs that search, submit, save, update the URL, start a debounced
request, or otherwise trigger an action on Enter must treat an active
input-method-editor (IME) composition as an intermediate state. This applies
to Chinese, Japanese, Korean, and other IMEs; it is not a Chat-only or
Documents-only rule.

The shared `ImeSafeForm` boundary blocks accidental form submission during
composition, while `Chat`, `Documents`, `Files`, `Search`, collection scope
selection, and Embeddings filters protect their action-triggering inputs
directly. The implementation recognizes both the
standard `nativeEvent.isComposing` signal and the legacy `keyCode === 229`
signal. After `compositionend`, the final value is retained and the next
ordinary Enter performs the expected action.

When adding or changing an action-triggering input:

1. Keep `compositionstart`/`compositionend` state local to the input or use
   `useImeComposition`.
2. On Enter, return without submitting, searching, saving, or navigating when
   either IME signal is active.
3. If the input commits on change, updates the URL, or starts a debounced
   request, defer the commit until `compositionend` and process the final
   value once.
4. Add a DOM interaction test for both the blocked composition event and the
   normal post-composition action. Screenshots are not acceptance evidence.

## 7. E2E

### HTTP E2E

```bash
bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 bash scripts/e2e-test.sh
```

### WebUI Playwright

```bash
cd spring-ai-rag-webui
npm run build
npx vite preview --host 127.0.0.1 --port 4173
BASE_URL=http://127.0.0.1:4173 npx playwright test
```

### Real LLM

```bash
./scripts/start-real-e2e-server.sh
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/real-llm-e2e-smoke.sh
```

The flow performs provider preflight, unique-document creation, embedding, search, ask, and
stream. When `RAG_ROOT_API_KEY` is configured, pass it through `RAG_API_KEY` (or the
equivalent `X-API-Key` header); the script also loads the root key from `.env`. Mock
Playwright is not a substitute for real-LLM validation. The four
`RAG_EMBEDDING_*` variables are required; retired `RAG_EMBEDDING_URL` and
`SILICONFLOW_*` names are rejected rather than mapped. If `.env` sets
`MODELS_CONFIG_FILE`, remember that the external file fully overrides the YAML
model registry. For a direct provider smoke, point it to a nonexistent file:

```bash
MODELS_CONFIG_FILE=/tmp/spring-ai-rag-no-external-models.json \
RAG_EMBEDDING_BASE_URL=https://api.siliconflow.cn \
SERVER_PORT=18181 \
./scripts/start-real-e2e-server.sh
```

The guarded Collection purge real-provider lifecycle uses
`scripts/real-collection-purge-e2e-smoke.sh`. It requires a running isolated
service and disposable database, then verifies event-first embedding, real
retrieval/Chat, purge/replay, retired rejection, and the tombstone. See the
[testing guide](testing-guide.md#guarded-collection-purge-and-retirement-acceptance-gate)
for the full command and evidence-safety boundary.

This Chat-turn idempotency delivery has a separate PLAIN smoke that does not require an
Embedding provider:

```bash
BASE_URL=http://127.0.0.1:18081 \
./scripts/real-llm-chat-idempotency-smoke.sh
```

The script forces the OpenAI-compatible Chat provider and verifies native JSON/SSE first
requests, same-key replay, key conflict, turn status lookup, and the before/after
`/actuator/metrics/rag.chat.provider.calls` counter to prove replay did not invoke the
provider again. Start the server with an isolated PostgreSQL database and dedicated ports;
never put API keys in shell history or documentation.

### Chat Capability Verification

Run the Chat redesign gate serially because it includes Maven clean output:

```bash
./scripts/verify-chat-capability.sh
```

The script verifies `KNOWLEDGE`, `AGENT`, and `PLAIN` mode execution, Spring AI
Tool Calling boundaries, principal-scoped memory/history, V32 session leases,
V46 durable summary CAS, V47 durable Chat-turn idempotency/replay, V48 stable managed principals and shared quota, bounded execution metadata, structured SSE, WebUI
mode/capability/source rendering, and Chat export snapshots. It also runs the
`NextHighValueFeaturesPostgresIntegrationTest` matrix and the independent
domain-extension and read-only SQL tool demo tests, then records every step under
`.verification/chat-capability/<run-id>/summary.md`.

PostgreSQL/Testcontainers defaults:

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

If Docker is unavailable, the script records the PostgreSQL gate as `SKIP`
instead of claiming it passed. `--skip-postgres` is an explicit equivalent and
must remain visible in the recorded summary. The known Docker API `1.32`
versus daemon minimum `1.40` problem is documented in
[china-network-guide.md](china-network-guide.md).

The Chat Mock Playwright gate runs against a strict, overridable Vite preview
port:

```bash
CHAT_PLAYWRIGHT_PORT=4199 ./scripts/verify-chat-capability.sh
```

It uses DOM, network, URL, and test assertions only; screenshots are not
correctness evidence. Real provider calls are opt-in:

```bash
./scripts/verify-chat-capability.sh --with-real-llm
```

With `--with-real-llm`, the gate creates a disposable PostgreSQL database,
starts an isolated `scripts/dev.sh` stack (backend `18083`, WebUI `15175` by
default), runs the real WebUI `chat-real.spec.ts` and provider smoke, then
cleans up the stack, overlay environment, and database. The `.env` or caller
environment must provide `RAG_ROOT_API_KEY`; override the ports with
`CHAT_REAL_BACKEND_PORT` and `CHAT_REAL_FRONTEND_PORT` when needed. Without
that option the real LLM step is recorded as `SKIP`.

### OpenAI Compatibility Verification

```bash
./scripts/verify-openai-compatibility.sh
```

The gate covers model aliases, request-scoped Collection scope/ACL, complete
text-only messages, non-streaming OpenAI JSON, compatible error envelopes, SSE
chunk ordering, and `[DONE]`, followed by relevant `test-compile`, shell
syntax, and whitespace checks. Evidence is written under
`.verification/openai-compatibility/<run-id>/`. The runtime controller remains
disabled unless `RAG_OPENAI_COMPATIBILITY_ENABLED=true`.

This focused gate is primarily MockMvc and unit-level contract coverage. It does not
start an isolated real Spring Boot HTTP service or invoke an official OpenAI SDK. By
itself, it does not prove automatic `/v1` registration in a Starter-only consumer or
compatibility with every default parameter emitted by third-party Agents and IDEs. See
[OpenAI compatibility readiness](openai-compatibility-readiness.md#44-current-evidence-and-evidence-gaps)
for the evidence boundary and P0 improvements.

### Durable Embedding Jobs Verification

```bash
./scripts/verify-embedding-jobs.sh
```

The gate covers the service, worker, HTTP API, V33 migration, active-job
coalescing, and two-worker atomic conditional claims. It starts an isolated
`pgvector/pgvector:pg16` container by default. Reuse a caller-provided isolated
database with `EMBEDDING_JOBS_IT_JDBC_URL`, `EMBEDDING_JOBS_IT_USERNAME`, and
`EMBEDDING_JOBS_IT_PASSWORD`. Evidence is written under
`.verification/embedding-jobs/<run-id>/`.

<a id="document-lifecycle-verification"></a>

### Document Lifecycle Verification

```bash
./scripts/verify-document-lifecycle.sh
```

This verifies local create/PATCH/disable/restore/permanent-delete, external
TEXT/JSON `collectionKey + sourceNamespace + externalId`, revision CAS,
complete snapshots, generation-aware re-embedding after content changes,
no re-embedding for non-text changes, WebUI CRUD, and the reference client.

The script prefers a disposable database created from current-shell or `.env`
`POSTGRES_*`, avoiding Testcontainers/new-Docker protocol negotiation issues.
Alternatively provide `DOCUMENT_LIFECYCLE_IT_JDBC_URL`,
`DOCUMENT_LIFECYCLE_IT_USERNAME`, and `DOCUMENT_LIFECYCLE_IT_PASSWORD`. Never
point these at development or production. Evidence is stored under
`.verification/document-data-plane/<run-id>/`.

### Document Relocation And Derivation Integrity Verification

```bash
./scripts/verify-document-relocation.sh
./scripts/verify-derivation-integrity.sh
```

Both focused gates run the no-pessimistic-lock check, focused HTTP tests,
disposable-PostgreSQL integration tests, `mvn clean compile test-compile`,
WebUI typecheck/Vitest/production build/alignment, no-screenshot Mock
Playwright, bilingual documentation checks, and the whitespace gate. Evidence
is written under `.verification/relocation/<run-id>/` and
`.verification/derivation-integrity/<run-id>/`.

Testcontainers is the default. To use a caller-created disposable database,
set `NEXT_HIGH_VALUE_IT_JDBC_URL`, `NEXT_HIGH_VALUE_IT_USERNAME`, and
`NEXT_HIGH_VALUE_IT_PASSWORD`, plus the explicit safety acknowledgement
`NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES`. The test cleans the database, so these
variables must never point at development or production. Override the Mock
Playwright preview's initial port with `NEXT_HIGH_VALUE_PLAYWRIGHT_PORT`.

### Managed API Principal Verification

```bash
MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=minimax \
./scripts/verify-managed-api-principals.sh --with-real-llm
```

After the Mock and build gates pass, this command uses two backends (default
`18181` and `18182`), one Vite frontend (default `15181`), and disposable
PostgreSQL for real full-stack plus bounded real-LLM acceptance. The V55
matrix verifies idempotent provisioning across two instances, runtime
capability discovery, read-only/read-write capabilities for NORMAL principals,
policy CAS, staged prepare/replay/complete/cancel/deadline/family revoke,
shared quota during overlap, and write rejection before quota accounting.
Real-LLM mode requires nine successful provider calls across immediate data
access and staged complete/cancel/revoke lifecycles, while replayed or rejected
requests must not increase the provider counter. Override port
conflicts with `MANAGED_API_BACKEND_A_PORT`, `MANAGED_API_BACKEND_B_PORT`, and
`MANAGED_API_FRONTEND_PORT`. `MANAGED_API_REAL_LLM_PROVIDER` accepts `openai`,
`minimax`, or `anthropic`; the script validates and loads only the selected
provider. Evidence is written under
`.verification/managed-api-principals/<run-id>/`.

### Managed API Principal Expiry Alert Verification

```bash
# Focused backend, V1-V60 PostgreSQL, and frontend Mock gates
API_KEY_EXPIRY_ALERT_VERIFY_PHASE=focused \
./scripts/verify-api-key-expiry-alerts.sh

# Add Maven clean, lock, documentation, shell, diff, and added-line secret gates
./scripts/verify-api-key-expiry-alerts.sh
```

The script covers expiry-property validation, after-commit Spring Events, the
asynchronous proxy contract, create/update/revoke lifecycle integration,
operator-only Alerts APIs, notification channels, V57 multi-instance
deduplication/CAS, phase transitions, automatic resolution, fair fallback
scans, the WebUI `firedAt` contract, and no-screenshot Alerts Mock Playwright.
It uses `pgvector/pgvector:pg16` Testcontainers by default.
`API_PRINCIPAL_EXPIRY_ALERT_IT_JDBC_URL` and related variables may instead
select an explicitly disposable database. Evidence is written under
`.verification/api-key-expiry-alerts/<run-id>/`.

### Durable Alert Notification Outbox Verification

```bash
./scripts/verify-alert-notification-delivery.sh

MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=openai \
./scripts/verify-managed-api-principals.sh \
  --with-real-llm \
  --with-durable-notifications
```

The first command uses isolated PostgreSQL, a real local HTTP provider, two
backend instances, and the real WebUI. It covers event-driven first delivery,
transient retry, one HTTP call per ledger attempt, process-exit/expired-lease
recovery, low-sensitivity receipts, and no-screenshot DOM/network Playwright.
The second command actually calls the Chat and Embedding services from `.env`
only after all local gates pass, while routing managed-principal WARNING and
CRITICAL alerts through V58 durable delivery. Evidence is written under
`.verification/alert-notification-delivery/<run-id>/` and
`.verification/managed-api-principals/<run-id>/`.

<a id="business-service-integration-readiness-verification"></a>

### Business Service Integration Readiness Verification

```bash
./scripts/verify-business-client-readiness.sh
```

The gate starts with focused API/core tests, creates disposable PostgreSQL
integration databases serially, runs `mvn clean compile test-compile`, WebUI
typecheck/Vitest/production build, core Mock Playwright, and the
documentation/lock/secret/diff gates. It then starts disposable PostgreSQL, a
deterministic embedding stub, real Spring Boot, and a real Vite frontend for
the generic business-credential HTTP contract and real API-key Playwright.

Rerun only the real-service phase:

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

Require a clean Git tree for a final candidate commit:

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

Default ports are backend `18084`, embedding stub `18085`, Mock frontend
`15184`, and real frontend `15185`. Override them with
`BUSINESS_CLIENT_BACKEND_PORT`, `BUSINESS_CLIENT_EMBEDDING_PORT`,
`BUSINESS_CLIENT_MOCK_FRONTEND_PORT`, and
`BUSINESS_CLIENT_REAL_FRONTEND_PORT`. Override the PostgreSQL image with
`BUSINESS_CLIENT_POSTGRES_IMAGE`. Evidence is written under
`.verification/business-client-readiness/<run-id>/`; exit traps clean private
credential files, containers, ports, and processes. The real HTTP contract
includes read-only/canary binding preflight, runtime-limit enforcement,
principal/Collection-scoped operation observability, restart persistence, and
Record preservation after a provider `503`. `release-manifest.json` pins the
full Git SHA, initial tree state, project/OpenAPI versions, API base path,
latest Flyway migration, passed steps, PostgreSQL image, HTTP-check count,
verified credential profiles, and observed JSON batch item/payload limits plus
operation-observability state. Runtime facts not reached are JSON `null`; it
stores no credential, URL, payload, external ID, or private path.

The deployed binding runner can also be executed independently against an
already running instance:

```bash
./scripts/business-client-binding-preflight.sh
```

It is read-only by default. Set the `RAG_BINDING_*` inputs documented in the
[Business Service Integration Guide](business-client-integration.md).
`RAG_BINDING_MIN_JSON_BATCH_ITEMS`,
`RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES`, and
`RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY` add fail-closed runtime
requirements. Mutation mode is opt-in and must use a dedicated canary
Collection; its report is machine-readable and contains no credential, URL,
Collection key, external ID, or payload.

This gate verifies the real Spring AI embedding HTTP path. The capability does
not change Chat, so it does not call a Chat LLM. See the
[Business Service Integration Guide](business-client-integration.md) for the
integration contract and deployment binding.

### Retrieval diagnostics / metadata filters / embedding operations / managed quality

```bash
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-no-pessimistic-locks.sh
# or run A–D together:
./scripts/verify-next-high-value-features.sh
```

These gates cover V35 diagnostics, V36 metadata `@>` pushdown, V37 embedding
operations pagination/readiness, V38 managed suites plus citation validation,
and the post-V39 data-access concurrency rule. The lock gate statically rejects
`FOR UPDATE`, `SKIP LOCKED`, JPA `PESSIMISTIC_*`, and PostgreSQL advisory
locks. The other gates start isolated PostgreSQL by default; override with the
matching `*_IT_JDBC_URL` variables.

### JSONB Structured-Record Verification

Run the focused, repeatable gate for the JSONB implementation and its
surrounding API, database, WebUI, documentation, and whitespace checks:

```bash
./scripts/verify-jsonb-records.sh
```

Use `--skip-playwright` only when browser binaries are unavailable and record
the skipped gate. The script starts isolated PostgreSQL itself, avoiding the
Testcontainers 1.20.4 / newer Docker daemon API negotiation issue. Reuse a
caller-provided isolated database with `JSONB_IT_JDBC_URL`,
`JSONB_IT_USERNAME`, and `JSONB_IT_PASSWORD`; override the image with
`TESTCONTAINERS_PG_IMAGE`. Logs and a Markdown summary are written to
`.verification/jsonb-verification/<run-id>/`.
The Mock Playwright preview uses `JSONB_PLAYWRIGHT_PORT` (default `4174`) with
strict port binding and never reuses an unrelated process. If that port is
occupied, choose an unused one, for example:

```bash
JSONB_PLAYWRIGHT_PORT=4199 ./scripts/verify-jsonb-records.sh
```

Run this gate serially: its `mvn clean` step must not overlap another Maven
test process that uses the same module `target/` directories.

### JSONB Live HTTP E2E

Run the JSON structured-record HTTP flow against an already running PostgreSQL
profile service:

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/jsonb-records-e2e.sh
```

The script verifies JSON-record upsert, collection-scoped search, detail,
payload-only updates, `retrievalText` updates, clone/export/import, and
allow/deny behavior using a temporary restricted API key created by the root.
`embed=true` calls the real embedding provider but does not call a Chat LLM.
Use `--skip-acl` only when the server intentionally has no usable root
credential, and record that skip. The script never prints API keys or complete
payloads; temporary responses are removed from ignored
`.verification/jsonb-e2e/` storage on exit.

<a id="external-document-synchronization-http-e2e"></a>

### External Document Synchronization HTTP E2E

Run the ordinary external-document synchronization flow against an already
running PostgreSQL-profile service:

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/external-documents-e2e.sh
```

The script verifies create with `embed=false`, exact replay, update with
`expectedSourceRevision`, CAS conflict, same-revision conflict, batch upsert,
lookup by external identity, tombstone deletion/replay, and restoration with a
distinct subsequent `sourceRevision`. By default it also verifies successful re-embedding after content
change. Set `EXTERNAL_DOCUMENT_E2E_EMBED=false` only when the embedding provider
is intentionally unavailable; the run then records the embedding check as
skipped rather than claiming a completed vector path. Logs are written under
ignored `.verification/external-documents-e2e/` and the script never prints API
keys or complete document content.

## 8. Goldenset And Release Gates

Retrieval goldenset:

```bash
BASE_URL=http://127.0.0.1:8081 ./scripts/run-retrieval-goldenset.sh
```

Versioned live retrieval regression:

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-quality-regression.sh
```

Rerank document-diversity acceptance, including focused backend tests,
PostgreSQL/pgvector, WebUI gates, isolated `dev.sh`, real Search/Playwright,
goldenset, versioned regression, and real LLM checks:

```bash
./scripts/verify-rerank-document-diversity.sh
```

The runner refuses to replace an existing `.dev` stack, defaults to isolated
ports `18083`/`15175`, creates a disposable PostgreSQL database (local first,
Docker fallback), keeps the generated root key in the shell only, and writes
evidence under `.verification/rerank-document-diversity/`. After the real
provider baseline passes, it restarts the service with cap=`0` and cap=`2`
against the same database and fixture, collecting 20 Search and 5 Chat samples
per variant by default. It correlates trace IDs through read-only
`rag_retrieval_logs` queries and writes retrieval/rerank p95, HTTP response
payload, and final document-coverage observations to `runtime-comparison.json`
and `runtime-comparison.md`. Wall-clock and payload values are evidence, not
unstable threshold gates.

The correlated database result count is the latest retrieval-outcome count.
Search requires it to match the final HTTP count. KNOWLEDGE Chat records the
relationship separately because its HTTP sources are produced after advisor
query joining, reranking, and prompt-budget processing.

Chat samples retry explicit transient HTTP `429/502/503/504` responses within
the positive `RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS` bound (default `2`). All
retries are logged; Search and non-retryable failures remain fail-fast.

The dataset and committed baseline live under `testdata/regression/`. The
runner creates fixtures by stable
`collectionKey + sourceNamespace(default) + externalId`, checks Hit
Rate, MRR, Recall@K, nDCG, metric floors, baseline regression, Collection-decoy
leakage, and an explicit-empty JSONB case, then writes JSON artifacts and a
Markdown summary under `.verification/quality-regression/<run-id>/`. When
`RAG_API_KEY` is not set explicitly, it safely reads `RAG_API_KEY` /
`RAG_ROOT_API_KEY` from `.env` without printing the credential.
Run `./scripts/run-retrieval-regression.sh --self-test` without a service to
check recognition of the current `READY` and compatible `COMPLETED/CACHED`
successful embedding statuses.

One-command release verification:

```bash
./scripts/verify-release.sh
./scripts/verify-release.sh --with-quality-regression
./scripts/verify-release.sh --with-local-runtime
```

`--with-quality-regression` adds the versioned gate against the running
`BASE_URL`. `--with-local-runtime` includes HTTP E2E, goldenset, quality
regression, and real-LLM smoke.

Logs and summaries are written to `target/release-verification/<run-id>/`. See [release-checklist.md](release-checklist.md).

## 9. Docker And Mainland-China Networking

Preferred local build:

```bash
./scripts/docker-build-local.sh
```

Keep Dockerfile base images overridable instead of hard-coding regional mirrors. See [china-network-guide.md](china-network-guide.md) for DaoCloud, Aliyun Maven, npm, Playwright, and Git proxy guidance.

## 10. Key Paths

| Path | Purpose |
|------|---------|
| `spring-ai-rag-api/` | DTOs and SPIs |
| `spring-ai-rag-core/` | Core implementation and runnable app |
| `spring-ai-rag-starter/` | Auto-configuration |
| `spring-ai-rag-documents/` | Document processing |
| `spring-ai-rag-webui/` | React admin UI |
| `scripts/` | Startup, E2E, goldenset, documentation and release verification |
| `docker/` | Dockerfile and Compose |
| `k8s/` | Helm chart |

## 11. Troubleshooting

- General: [troubleshooting.md](troubleshooting.md)
- Configuration: [configuration.md](configuration.md)
- Mainland-China networking: [china-network-guide.md](china-network-guide.md)
- Claude Code + grok: [claude-grok-proxy.md](claude-grok-proxy.md)
