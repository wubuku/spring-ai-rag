// The gate registry: one deliberate decision per gate script in this repository.
//
// Why this file exists
// --------------------
// `verify-project-docs.sh` already carries a check called "Gates can fail closed",
// and its comment records three separate instances of a gate that could not fail.
// Batch 809 found a fourth — `scripts/check-entity-migration-sync.sh`, which
// compared nothing at all, queried six table names that no longer exist, connected
// to a database this project does not use, and protected an invariant Hibernate
// already enforces at startup (`spring.jpa.hibernate.ddl-auto: validate`). That
// instance slipped through precisely because the existing check only inspects
// scripts that scan with `rg`, `jq` or `yq`; a `psql` + `grep` gate is invisible
// to it.
//
// So the response to "a gate that cannot fail is worse than no gate" cannot be a
// spot check. It has to be census. This file is the census's data, and
// `verify-gate-wiring.mjs` is the checker that keeps it true:
//
//   1. every gate script on disk is registered here, so a new one cannot appear
//      without somebody deciding what kind of thing it is;
//   2. an automated gate must prove it can reject (a self-test), or say why it
//      cannot;
//   3. an automated gate must actually be executed by something — a script
//      nobody runs is a gate in name only;
//   4. an automated gate that CI does not reach must carry a written reason, so
//      "this never runs in CI" is a recorded decision instead of a silent hole.
//
// Rules 3 and 4 are the ones this repository actually needed. Between Batch 768
// and Batch 809 not one `scripts/verify-*.sh` ran in CI, and 208 WebUI gate
// self-tests ran nowhere at all. Both were invisible.
//
// Two honest limitations, stated here rather than discovered later:
//
//   * "Executed by" is derived from how a runner textually invokes the script. A
//     runner may *mention* a gate without running it — `verify-project-docs.sh`
//     keeps a list of scripts that must merely exist, and the checker
//     deliberately does not count that as execution. It still cannot prove
//     arbitrary shell semantics, so rule 3 catches "wired to nothing", not
//     "wired to a lie".
//   * A `noCiReason` is a claim about the world that the checker re-verifies
//     against `.github/workflows/ci.yml` on every run. When the wiring lands, the
//     reason becomes stale and the gate fails with the list of lines to delete.
//     That is intentional: a stale exemption is how a debt baseline rots.
//   * Every path a `noCiReason` names must exist on disk. Batch 900 added this
//     because the registry already checked its machine-readable path fields —
//     `selfTest` at rule 2's neighbour — while exempting the one field whose
//     entire job is to tell a human where the artifact is. A standing gap is the
//     only hole in this repository with exactly one possible remover, so a dead
//     pointer in it does not fail loudly; it just quietly never gets fixed.

/**
 * Why the repository-level gate chain is not in CI, in the form a human can act
 * on. Everything reachable only through `verify-project-docs.sh` /
 * `verify-project-tests.sh` inherits this one blocker.
 *
 * Two things about this constant are load-bearing, and Batch 900 exists because
 * they were not.
 *
 * The blocker is a *tested* result, not an inherited assumption. Batch 806
 * recorded that editing `.github/workflows/` needs a token with the `workflow`
 * scope; Batch 899 pushed the change and the remote answered, verbatim,
 * "refusing to allow an OAuth App to create or update workflow
 * .github/workflows/ci.yml without workflow scope". The same batch measured
 * that `gh` is installed here (2.78.0) and merely not logged in — an earlier
 * version of this comment called the CLI unavailable, which was simply false,
 * and would have told the one person who could clear this to give up.
 *
 * The artifact this points at lives in the repository. It used to be
 * `/tmp/b806-ci-gates.patch`, and it no longer exists: a handoff parked in a
 * directory the operating system may empty is not a handoff. Every path a
 * `noCiReason` names is checked on disk by `verify-gate-wiring.mjs`, so a
 * pointer here cannot rot the way that one did.
 *
 * This block is the only place the claim is written down. It used to be
 * duplicated in the comment above, which is precisely how the two drifted into
 * disagreeing with the world.
 */
export const AWAITING_CI_WORKFLOW_SCOPE =
  '仓库级门禁链尚未接入 CI：编辑 .github/workflows/ 需要带 workflow scope 的凭据，' +
  '实测推送被远端拒绝；待人工应用 .github/pending/ci-repo-gates.patch，' +
  '应用后跑 verify-gate-wiring.mjs 并删掉它列出的过期 noCiReason。';

export const E2E_MOCK_NOT_IN_CI =
  '前端 e2e mock 套件（15 spec / 93 用例）单次约 2.4 分钟' +
  '（Batch 913 实测 93/93 全过，2 分 22 秒），刻意不挂在秒级门禁链上；' +
  '是否进 CI 属于尚未拍板的成本取舍。';

/**
 * A script a human runs on demand against a live system — a running server, real
 * credentials, or a real model. These are verification scripts, not gates: they
 * have no run path to be orphaned from, and a self-test would have to stand up
 * the very system they verify. They still must be registered, so that a new
 * script cannot be born unclassified.
 */
const manual = (gate) => ({ gate, kind: 'manual' });

export const GATES = [
  // ---------------------------------------------------------------- manual --
  // Require a running backend (scripts/start-server.sh) and, for most of them,
  // real LLM/embedding credentials. Listed in verify-project-docs.sh so their
  // existence is documented, but nothing executes them automatically.
  manual('scripts/verify-alert-notification-delivery.sh'),
  manual('scripts/verify-api-key-expiry-alerts.sh'),
  manual('scripts/verify-business-client-readiness.sh'),
  manual('scripts/verify-chat-capability.sh'),
  manual('scripts/verify-collection-provisioning.sh'),
  manual('scripts/verify-collection-purge.sh'),
  manual('scripts/verify-derivation-integrity.sh'),
  manual('scripts/verify-document-lifecycle.sh'),
  manual('scripts/verify-document-relocation.sh'),
  manual('scripts/verify-document-sync-runs.sh'),
  manual('scripts/verify-embedding-jobs.sh'),
  manual('scripts/verify-embedding-operations.sh'),
  manual('scripts/verify-jsonb-records.sh'),
  manual('scripts/verify-keyword-vector-decoupling.sh'),
  manual('scripts/verify-llm-usage-ledger.sh'),
  manual('scripts/verify-managed-api-principals.sh'),
  manual('scripts/verify-managed-quality.sh'),
  manual('scripts/verify-next-high-value-feature.sh'),
  manual('scripts/verify-next-high-value-features.sh'),
  manual('scripts/verify-openai-compatibility.sh'),
  manual('scripts/verify-quality-regression.sh'),
  manual('scripts/verify-release.sh'),
  manual('scripts/verify-rerank-document-diversity.sh'),
  manual('scripts/verify-retrieval-diagnostics.sh'),
  manual('scripts/verify-retrieval-filters.sh'),

  // ----------------------------------------------------- backend gate chain --
  // Run by scripts/verify-project-tests.sh, which must be the step right after a
  // full `mvn test`: verify-test-visibility.mjs reads the surefire reports that
  // run produces, and the gated IT overwrites them afterwards.
  {
    gate: 'scripts/verify-test-visibility.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/test-visibility-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-integration-test-switches.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/integration-switch-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-external-db-safety.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/external-db-safety-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-e2e-run-paths.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/e2e-reachability-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-slo-endpoint-coverage.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/slo-endpoint-coverage-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-test-expectations.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/inert-test-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-gate-wiring.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/gate-wiring-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-null-request-forwarding.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/null-request-forwarding-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 923. A script that selects Flyway's latest version and compares it
  // against a literal stops being true the moment someone adds a migration,
  // and then fails as if the product were broken.
  // `verify-alert-notification-delivery.sh` had been asserting `58` for a
  // month after V59 shipped, and the run that caught it was the first one since.
  // Two sibling scripts already compute the number from the migration
  // directory, so the correct shape existed and this one had not adopted it —
  // which is what lets the rule be zero-tolerance with no allowlist.
  {
    gate: 'scripts/verify-flyway-version-pinning.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/flyway-version-pinning-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 924. The same failure as the rule above, one layer up: the server
  // defines the integration capability protocol in exactly one place, six
  // scripts carried their own copy of its version, and two copies had already
  // drifted to a value the server does not publish. The worst one asserted
  // `1.0` on a report whose own producer refuses to write a success at anything
  // else — an assertion no report could satisfy, which is why
  // `verify-business-client-readiness.sh` had never completed in this work
  // tree. The value now lives in `scripts/lib/business-client-capability.sh`,
  // and because the correct shape already existed in that one place the rule
  // needs no allowlist: not for the shared library that defines it, not for the
  // envelope protocol that shares the field name, not for the self-test
  // fixtures that manufacture a mismatch, and not for this repository's habit
  // of quoting a broken line in a comment explaining it.
  {
    gate: 'scripts/verify-capability-protocol-pinning.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/capability-protocol-pinning-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 926. Seven scripts decided a port was free by *binding* a throwaway
  // `node:net` server, which answers a different question than the server they
  // are probing for: a Node bind to `127.0.0.1` succeeds against an IPv6
  // wildcard listener that `vite preview` then refuses to bind. Measured on this
  // machine — `lsof` sees `python3` on `*:4173`, the Node bind succeeds, and
  // `verify-release.sh` passed that verdict straight to `vite preview`, which
  // died on the port the probe had just certified free. Seven other scripts here
  // already ask with `lsof`, which is the shape the library now holds.
  {
    gate: 'scripts/verify-port-probe-authority.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/port-probe-authority-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 926. `playwright.config.ts` has no `testIgnore`; the two preview
  // configs declare `testIgnore: ['**/*-real.spec.ts']`. So the shape of the
  // command decides whether a run swallows the five specs that need a live
  // backend and real credentials. `verify-jsonb-records.sh` and
  // `verify-release.sh` each start their own `vite preview` — a static server
  // with no backend behind it — and ran a bare `npx playwright test`: 93 passed
  // and 5 failed on every run, neither script having ever completed in this
  // work tree. Seventeen of the nineteen invocations name their specs and are
  // not this rule's business; naming `alerts-real.spec.ts` on purpose is the
  // entire point of the alert acceptance run.
  {
    gate: 'scripts/verify-playwright-suite-selection.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/playwright-suite-selection-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 925. Surefire reads `-Dtest=Class#a+b` as "run whichever of a and b
  // exist", and twenty scripts here pass
  // `-Dsurefire.failIfNoSpecifiedTests=false` — correctly, because they select
  // whole classes, where a missing class leaves no report each of them already
  // checks for. Method names are the exception and produce silence instead.
  // `verify-next-high-value-feature.sh` named a method in both of its branches
  // that had been renamed; the run executed six of seven, and the only thing in
  // the repository that noticed was a hardcoded count, which reported a run in
  // which all six tests passed as a broken one without naming the method.
  // The check lives in a shared library for the reason Batch 894 moved the
  // report reader there, and the rule is the wiring — "does this script verify
  // the names" is not decidable by reading the script, but "does it source the
  // library" is.
  {
    gate: 'scripts/verify-surefire-method-selection.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/surefire-method-selection-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 928. `DerivationIntegrityRepository` requires `vector_generation > 0`
  // before it will call a vector fresh, and three production writers wrote
  // `rag_document_embedding_state` without naming `request_generation`, so a new
  // row took the column default — 0 until V60. A document whose vectors had just
  // been committed, with a matching hash, a matching chunker and no error
  // anywhere, was classified CORRUPT and surfaced as `embeddingStatus=FAILED`
  // with a null error: 69 of 82 rows on a live instance, and
  // `run-retrieval-regression.sh` aborting on its third fixture. The rule covers
  // the writers and the schema separately, because a correct writer and a schema
  // that cannot object are two different failures and only the second is
  // permanent. The test tree is out of scope on purpose: two fixtures there omit
  // the column to seed a pre-V42 shape and to assert the new default, and since
  // V60 the default is a value the integrity repository can read.
  {
    gate: 'scripts/verify-embedding-state-generation.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/embedding-state-generation-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 929. `DocumentLifecycleService` answers from
  // `DerivationIntegrityRepository` when it has one and falls back to a
  // hand-rolled SQL block otherwise, and the two are not equally strict: the
  // fallback compares status, hash, chunker and chunk count, while the
  // repository also wants a positive generation, contiguous indexes, matching
  // dimensions and one vector per local chunk. The only suite asserting the
  // lifecycle contract against a real database built both of its assertions
  // through the three-argument constructor, so it was checking the definition
  // production does not use — and a regression in the real one could not have
  // failed it. Both verdicts turned out identical once the repository was
  // attached, which is why nothing had noticed. The rule needs all three
  // conditions: construct the service, plant derivation rows, and never mention
  // `setIntegrityRepository`. A mocked unit test of the fallback satisfies
  // neither the second nor the third, and is left alone. The limit is stated in
  // the header and pinned by the self-test: the attachment is recognised by
  // name wherever it is aimed, so a file pointing it at a different collaborator
  // is not reported.
  {
    gate: 'scripts/verify-lifecycle-truth-source-wiring.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/lifecycle-truth-source-wiring-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 873. ErrorCode declares itself the single source of truth, and six of
  // the codes the API really returns were not in it, so no title or problem-type
  // URI could be derived from them. The rule also pins the status beside a code
  // against the enum's, and requires a hand-assembled ErrorResponse to be an
  // actual problem detail. Its blind spot — the `ErrorResponse.of(...)`
  // factories, invisible to any source scan — is documented in the gate header
  // and asserted by its self-test, so it is a known limit rather than a
  // surprise.
  {
    gate: 'scripts/verify-error-code-catalog.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/error-code-catalog-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 896. Inside `all(...)` or `any(...)`, a negative assertion — a field
  // compared with `!=`, or a `// ""`-guarded containment test negated with
  // `| not` — is satisfied by a field that reads as absent, so the predicate
  // reports "nothing is wrong" about something it cannot see. Batch 895 found
  // one by reading two predicates by hand and had to guard it from the outside;
  // the census that followed found two more in scripts nobody runs in CI.
  //
  // There is no allowlist here, and that is the point rather than a preference:
  // a fail-open predicate cannot be exempted without one, and an allowlist is a
  // list of things the gate does not check. The predicate Batch 895 guarded is
  // closed from inside instead, which is what keeps the finding count at zero.
  {
    gate: 'scripts/verify-gate-entry-points.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/gate-entry-points-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-json-assertions.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/json-assertions-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 908. A test assertion that cannot fail is worse than no assertion,
  // because it reads as coverage. Five existed; one of them carried a Chinese
  // failure message claiming the assistant role must not survive MiniMax
  // normalisation, while the file's own javadoc and the production code both
  // declare that the transformation is system → user. The tautology is what
  // kept the wrong sentence from being noticed.
  //
  // The rule deliberately stops short of `assertEquals(x, x)`, which the census
  // found eight times. JUnit resolves those through `equals`, so a broken
  // equals() or an unstable hashCode() fails them. Including them would have
  // meant an allowlist for a shape that is not the defect — and an allowlist is
  // a list of things the checker does not look at.
  {
    gate: 'scripts/verify-tautological-assertions.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/tautological-assertions-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 820. A collaborator injected with @Autowired(required = false) claims
  // it may be absent; this rejects that claim when the bean is an unconditional
  // @Service and the field has no `// optional-claim:` reason. Twelve such
  // claims existed; seven genuinely conditional beans are what keep the rule
  // from being a blanket objection to optional injection.
  {
    gate: 'scripts/verify-false-optional-wiring.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/false-optional-wiring-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 823. Spring injects a controller through its @Autowired constructor,
  // so a second one is reachable only from tests — and it decides on the
  // caller's behalf which collaborators end up null. Nine existed: seven public
  // convenience constructors that filled defaults in, and two package-private
  // ones on RagSearchController that selected a legacy retrieval mode the
  // production wiring can never reach. Visibility is not the test: a
  // package-private constructor is exactly as test-only as a public one.
  {
    gate: 'scripts/verify-controller-constructor-count.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/controller-constructor-count-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 935. `.env.deepspeed` sat in the index with real provider keys, a
  // database password and two absolute paths into a developer's home directory,
  // and it had reached the `main` of a **public** repository. `.gitignore` listed
  // `.env` and `.env.local` and nothing else, so it was a hole rather than an
  // accident. The repository's own added-line secret scan stayed green because the
  // file was older than any diff — not a flaw in that gate, a boundary it was
  // never meant to cover.
  //
  // The first version of this gate looked for credential-shaped values anywhere in
  // a tracked file and reported over a hundred findings, almost all normal code:
  // SQL columns named `lease_token`, a React state variable named
  // `confirmationToken`, a testcontainer password, fake keys in the tests that
  // exist to prove masking works. A gate that cries wolf gets switched off, which
  // is worse than never writing it. So the rule is the one shape that has no
  // false alarms and no allowlist: only `.env.example` is a dot-env file meant to
  // be tracked.
  //
  // It cannot speak for history — untracking is not removing, and removing is not
  // rotating. That limit is declared in the script's header and pinned by its
  // self-test, so nobody later believes the gate covers it.
  {
    gate: 'scripts/verify-tracked-env-files.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/tracked-env-files-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 935, second half. `.env.example` is what a new contributor copies to
  // `.env`, so a variable it declares is a promise that setting it does something.
  // `ad026765` broke that promise six times under the message "update
  // .env.example with all current configuration variables": `TRANSCRIPTION_*` and
  // `VISION_*`, whose own block headers promised audio transcription and multimodal
  // chat. No code has ever read them, the Spring AI dependency tree carries no
  // transcription or vision autoconfigure module, and
  // `docs/openai-compatibility-readiness.md` states that multimodal input is
  // unsupported. `TEST_IMAGE_PATH` and `TEST_AUDIO_PATH` were the same shape.
  //
  // The rule checks declared variables only, and that is measured rather than
  // assumed: after the six were removed, all 38 declared variables have a consumer
  // and the rule needs no allowlist. It cannot be extended to commented lines,
  // because `SPRING_DATASOURCE_DRIVER_CLASS_NAME` is one and is correct — nothing
  // here spells that name, while `spring.datasource.driver-class-name` is set in
  // four YAML files. "I did not find the name" is not "nothing reads it".
  //
  // The opposite direction is not checked, for the reason
  // `DocumentedRouteContract` gives: a template with forty commented-out switches
  // has already conceded it is not exhaustive, and completeness would mean nothing
  // but an allowlist.
  {
    gate: 'scripts/verify-env-example-consumers.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/env-example-consumers-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 936. `k8s/templates/secret.yaml` feeds the container through
  // `envFrom: secretRef`, so every key in `stringData` becomes an environment
  // variable — and two of the eighteen had no reader. `DEEPSEEK_API_KEY` came from
  // `docker-compose.yml`, where it is a **host-side** fallback that compose resolves
  // before the container exists; a substitution with no meaning inside a container.
  // `MINIMAX_API_KEY_ID` was read by nothing at all — Spring AI's own metadata
  // declares no `api-key-id` — and it was also the condition guarding the whole
  // MiniMax block, so an operator who configured MiniMax by the application's own
  // vocabulary silently received nothing. That one is worse than dead: it is a
  // correct configuration that silently does nothing.
  //
  // The corpus is the deployed application, stated positively: the main modules and
  // the chart's own ConfigMap. `demos/**` is another artifact and `docker/**` is
  // another deployment path, and each of them is why one of the two looked alive
  // during the census. `SPRING_*` is accepted as a **namespace**, because Spring Boot
  // binds the whole prefix — a mechanism rather than a list of names to remember.
  {
    gate: 'scripts/verify-helm-env-consumers.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/helm-env-consumers-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 937. `_helpers.tpl` defined three helpers that no template ever included,
// and they described a strategy that is **not the one in force**: `jvm-heap`
  // computed the heap from `jvm.heapPercent` while `deployment.yaml` reads
  // `jvm.maxHeap` directly, and `spring-profile` read a top-level
  // `.Values.springProfile` that `values.yaml` never declares while the profile
  // actually travels as `SPRING_PROFILES_ACTIVE` from `secret.yaml`.
  //
  // The cost of leaving them is not only dead code: `jvm.heapPercent` was a knob an
  // operator could `--set` and nothing would happen.
  //
  // This rule has no allowlist, which is the point worth recording. "A mention is a
  // use" needed three exclusions in Batch 935 because those are different things; a
  // Helm helper's visibility is decided by the template language itself, with nothing
  // in between. It deliberately does **not** check whether `.Values.*` paths exist:
  // measured, ten template references resolve nowhere, eight of them optional keys
  // under a `with` / `if` guard plus Helm's reserved `global` and the `postgresql`
  // subchart block. Checking it would cry wolf eight times, so it does not.
  {
    gate: 'scripts/verify-helm-helper-liveness.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/helm-helper-liveness-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Batch 938. Nine places in the WebUI each called `toLocaleString()` /
  // `toLocaleDateString()` / `toLocaleTimeString()` on a value they had just made a
  // `Date` out of, with no shared formatter in `src/` at all. `ApiKeys.tsx` wrapped its
  // call in a `try/catch` that reads as though it handles a value it cannot read — and
  // cannot, because `new Date('garbage')` returns an Invalid Date and
  // `toLocaleString()` on one returns the **string** "Invalid Date". The catch was
  // unreachable and the user read eleven characters of English on a Chinese page.
  //
  // The rule is "one formatter, imported", and it needs no allowlist because the one
  // legitimate owner is named by path. It does not judge what the formatter renders: a
  // date column and a time column must differ, and `ChatSidebar`'s relative ladder is
  // deliberate — what the gate stops is the second copy of it.
  //
  // It does not look at the Java side, where the four-way split starts
  // (`LocalDateTime` 73 / `OffsetDateTime` 31 / `ZonedDateTime` 15 / `Instant` 8 for
  // the same question). Making an offset travel on the wire is an API change with
  // external business clients, so it is a decision for a person; `docs/rest-api.md`
  // records the measurement and `src/utils/time.ts` states what it can and cannot
  // decide about a zoneless value.
  // No `noCiReason`: `npm run lint` runs in ci.yml's webui job, so this one is covered
  // by the gate layer CI has always reached.
  {
    gate: 'spring-ai-rag-webui/scripts/check-time-formatting.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/time-formatting.test.mjs',
  },

  // Batch 939. The rule is "`navigator.clipboard` is reached through one function", and
  // the one legitimate owner is named by path, so there is no allowlist to keep true.
  //
  // What it stops is a failure that review cannot see. `await
  // navigator.clipboard.writeText(rawKey); showToast(...copied)` reads correctly, and yet
  // on the two `ApiKeys` buttons — the ones that hand over a raw API key shown exactly
  // once — a denied write produced no toast at all, because the promise rejects and
  // nobody awaits it. `navigator.clipboard` is also `undefined` outside a secure context
  // (a self-hosted UI over plain HTTP on a LAN address), which throws a `TypeError`
  // rather than a rejection. Both failures looked identical to success.
  //
  // It says nothing about whether a successful copy is *reported*; that is
  // `ApiKeys.clipboard.test.tsx`, a rendering test on purpose.
  // No `noCiReason`: `npm run lint` runs in ci.yml's webui job.
  {
    gate: 'spring-ai-rag-webui/scripts/check-clipboard-calls.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/clipboard-calls.test.mjs',
  },

  // Batch 939. A census of `src/` for a hard-coded two-number substring found fifteen call
  // sites and four numbers behind them: 256 (query cap, 9×), 8 (short id, 3×), 50 (chat
  // title preview, 1×), 16 (`datetime-local` shape, 1×). The three display ones reached
  // the screen three different ways — a real U+2026 in one component, three ASCII dots in
  // another, three ASCII dots hand-written inside a ternary in a third, and nothing at
  // all in a fourth.
  //
  // It has **no allowlist**, which is the part worth reading twice. An earlier draft
  // exempted `utils/time.ts` because its `16` was a literal; naming the constant
  // `DATE_TIME_LOCAL_LENGTH` made the exemption unnecessary, and an exemption that exists
  // only because nobody finished the job is one more thing to keep true. `truncate` and
  // `capLength` cut at identifiers for the same reason.
  //
  // It decides by the shape of the arguments, not by intent: a display cut and a typed-in
  // cap are different decisions — an ellipsis in a search box becomes part of `q=` — but
  // no regex separates them. What it does guarantee is that any fixed cut is a named
  // decision somewhere. A computed bound is not caught; that limit is in the header and
  // pinned by the self-test.
  // No `noCiReason`: `npm run lint` runs in ci.yml's webui job.
  {
    gate: 'spring-ai-rag-webui/scripts/check-text-truncation.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/text-truncation.test.mjs',
  },

  // Batch 940. The rule is "`Intl` lives in `src/utils/number.ts`", and the cost it
  // protects is the kind nobody sees in review: `Metrics.tsx` had two
  // `Intl.NumberFormat` constructions, one called from inside a `.map()` over table
  // rows, so every cell of the usage table built a formatter on every render. Measured
  // on the census machine, 20 000 calls with identical output: 348.7 ms building per
  // call against 4.9 ms reusing one — 71x. Construction, not `format`, is the expensive
  // half of `Intl`.
  //
  // It also settles what a number looks like when the server did not send one, which
  // the rest of the app already answers as `—` and `Metrics` answered as `0` in one
  // direction and as the raw wire text in the other. The `0` branch was measured
  // unreachable: every `SUM(` in `LlmUsageQueryRepository` is wrapped in
  // `COALESCE(..., 0)`, so it was deleted rather than tested.
  //
  // The 71x figure is pinned by counting constructions in `number.test.ts`, not here —
  // a behaviour test cannot see it, because the output is byte-identical with and
  // without the cache. That distinction is recorded because an earlier version of that
  // test asserted only the output and stayed green when the cache was deleted.
  //
  // Declared limit: only `Intl.NumberFormat` is matched. `Number.prototype
  // .toLocaleString()` would do the same job and is not covered.
  // No `noCiReason`: `npm run lint` runs in ci.yml's webui job.
  {
    gate: 'spring-ai-rag-webui/scripts/check-number-formatting.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/number-formatting.test.mjs',
  },

  // Run by scripts/verify-project-docs.sh.
  {
    gate: 'scripts/verify-no-pessimistic-locks.sh',
    kind: 'gate',
    selfTest: 'scripts/test-support/pessimistic-locks-self-test.sh',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-zh-translation.mjs',
    kind: 'gate',
    selfTest: 'scripts/test-support/zh-translation-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // Entry points in their own right. Both aggregate the gates above, so the only
  // thing missing for them is a CI step — the same single blocker.
  {
    gate: 'scripts/verify-project-docs.sh',
    kind: 'entrypoint',
    selfTest: 'scripts/test-support/docs-integrity-self-test.mjs',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },
  {
    gate: 'scripts/verify-project-tests.sh',
    kind: 'entrypoint',
    // Its "self-test" is the five sibling self-tests it runs before each gate.
    // Batch 809 added no separate file because duplicating them would test the
    // copy rather than the gate.
    noSelfTestReason:
      '聚合入口：它逐个运行上面五个门禁的自测，自测已由被测门禁承担；' +
      '为它再写一份只会测试副本。',
    noCiReason: AWAITING_CI_WORKFLOW_SCOPE,
  },

  // The only gate CI does reach today, at ci.yml's "Gated PostgreSQL integration
  // tests" step. Batch 912 made the claim below true: until then this script
  // registered three of twenty-three gated suites while its own comment claimed
  // to run all of them, and the switch reconciliation stayed green, because
  // "some run path turns this switch on" and "one command runs the inventory"
  // were different questions and only the first was being asked.
  {
    gate: 'scripts/verify-gated-it.sh',
    kind: 'entrypoint',
    noSelfTestReason:
      '它的可失败性由 verify-integration-test-switches.mjs 对账保证：' +
      '若这里新增套件而开关没被登记，那道门禁的 unaggregated-gated-suite 规则会变红。',
  },

  {
    gate: 'scripts/verify-webui-e2e-mock.sh',
    kind: 'entrypoint',
    noSelfTestReason:
      '它跑的是 Playwright 套件本身（15 spec / 93 用例即其自测），' +
      '再套一层自测只会把 2.4 分钟变成 4.8 分钟。',
    noCiReason: E2E_MOCK_NOT_IN_CI,
  },

  // ------------------------------------------------------- WebUI gate chain --
  // All ten run from `npm run lint`, which ci.yml's webui job executes — the one
  // gate layer CI has always covered. Batch 809 appended the self-test suite to
  // that same command, so the self-test cases run in CI too.
  //
  // Batch 873. check-destructive-confirm was added by Batch 870 and *not*
  // listed here, so `verify-gate-wiring.mjs` was red on a clean main — this
  // registry did exactly the job it exists for, one batch late. Recorded rather
  // than quietly fixed, because a gate that can catch its own omission is worth
  // more than a gate nobody checks: the wiring check ran in `verify-project-tests.sh`
  // and in Batch 872's verification the WebUI lint chain was exercised, yet
  // neither surfaced it. What caught it was running the check on its own.
  {
    gate: 'spring-ai-rag-webui/scripts/check-destructive-confirm.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/destructive-confirm.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-a11y-forms.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/a11y-forms.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-alignment-policy.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/alignment-policy.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-design-system.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/design-tokens.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-double-submit.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/double-submit.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-hardcoded-copy.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/hardcoded-copy.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-i18n-keys.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/i18n-keys.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-mutation-errors.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/mutation-errors.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-page-shell.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/page-shell.test.mjs',
  },
  // Batch 933. The WebUI names a server route in more than one way, and the
  // three call sites that use `fetch` instead of `apiClient` — the SSE chat
  // stream, the multipart upload, the client-error report — each spelled
  // `/api/v1/rag` out in full, so the base path lived in four places. The
  // error-boundary one is the sharpest: its fetch is wrapped in a catch that
  // swallows everything on purpose, so a wrong base there does not announce
  // itself, it just stops reporting. The gate reads the base out of
  // `src/api/client.ts` rather than carrying its own copy, because a gate that
  // holds a second copy of the string it enforces is a machine number nothing
  // recomputes.
  {
    gate: 'spring-ai-rag-webui/scripts/check-single-api-base.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/single-api-base.test.mjs',
  },
  {
    gate: 'spring-ai-rag-webui/scripts/check-query-errors.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/query-errors.test.mjs',
  },

  // Batch 910. Continuous motion is what people with vestibular disorders turn
  // animations off for, and five stylesheets were running it with no way to stop
  // it: the skeleton shimmer on every page that loads, the streaming cursor's
  // blink, and two indeterminate spinners. Two other stylesheets already
  // carried the guard, so this enforces a shape the codebase had chosen rather
  // than inventing one.
  //
  // The rule is `infinite` animations only. A one-shot animation ends on its own
  // and is short by construction — flagging the dialog's 140ms fade would be
  // reporting a correct shape, and this repository does not build gates that
  // need an allowlist to go green.
  //
  // No `noCiReason`: `npm run lint` runs in ci.yml's webui job, so this one is
  // covered by the gate layer CI has always reached.
  {
    gate: 'spring-ai-rag-webui/scripts/check-reduced-motion.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/reduced-motion.test.mjs',
  },

  // Batch 911. A reader navigating by heading is told where they are by the
  // level, so `h1 → h3` claims a section that does not exist. Thirteen of
  // fourteen screens were already well-formed; Alerts was the only one that
  // jumped, twice, for the two form cards on a page with no h2 at all.
  //
  // The check is per page file, counting the h1 `PageHeader` renders, so a
  // heading a child component writes is not counted. That is a miss and never a
  // false alarm, which is the right way round — `check-page-shell`'s recursive
  // walk has the mirror limitation and documents it the same way. `Unlock.tsx`
  // is the one screen without `PageHeader` and carries its own h1, so the rule
  // needs no exception for it.
  {
    gate: 'spring-ai-rag-webui/scripts/check-heading-levels.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/heading-levels.test.mjs',
  },

  // Batch 911. An SVG with no role and no accessible name is telling a screen
  // reader nothing, and assistive technology still walks it. The whole tree
  // holds one inline <svg> — the clock glyph in Search's history toggle, whose
  // button already carried an aria-label, so the glyph was pure decoration and
  // was being announced anyway.
  //
  // Attributes are parsed by name rather than grepped, because a text search
  // cannot tell an attribute from a string that looks like one: reading
  // `data-note="aria-hidden='true'"` as a declaration would make the gate
  // report the opposite error from the one it exists to catch.
  {
    gate: 'spring-ai-rag-webui/scripts/check-decorative-graphics.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/decorative-graphics.test.mjs',
  },
];
