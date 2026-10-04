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

/**
 * Batch 806 could not add a CI step itself: editing `.github/workflows/` needs an
 * OAuth token with the `workflow` scope, and the `gh` CLI is unavailable here, so
 * the patch at `/tmp/b806-ci-gates.patch` is waiting on a human. Everything
 * reachable only through `verify-project-docs.sh` / `verify-project-tests.sh`
 * inherits that single blocker. Delete the `noCiReason` lines below once the
 * patch is applied — the checker will tell you which ones went stale.
 */
export const AWAITING_CI_WORKFLOW_SCOPE =
  '仓库级门禁链尚未接入 CI：Batch 806 因 OAuth workflow scope 限制摘出，' +
  '补丁 /tmp/b806-ci-gates.patch 待人工应用到 .github/workflows/ci.yml。';

export const E2E_MOCK_NOT_IN_CI =
  '前端 e2e mock 套件（15 spec / 93 用例）单次约 2.6 分钟，刻意不挂在秒级门禁链上；' +
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
  // tests" step.
  {
    gate: 'scripts/verify-gated-it.sh',
    kind: 'entrypoint',
    noSelfTestReason:
      '它的可失败性由 verify-integration-test-switches.mjs 对账保证：' +
      '若这里新增套件而开关没被登记，那道门禁会变红。',
  },

  {
    gate: 'scripts/verify-webui-e2e-mock.sh',
    kind: 'entrypoint',
    noSelfTestReason:
      '它跑的是 Playwright 套件本身（15 spec / 93 用例即其自测），' +
      '再套一层自测只会把 2.6 分钟变 5.2 分钟。',
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
  {
    gate: 'spring-ai-rag-webui/scripts/check-query-errors.mjs',
    kind: 'gate',
    selfTest: 'spring-ai-rag-webui/scripts/__tests__/query-errors.test.mjs',
  },
];
