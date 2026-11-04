# Verification Baseline Overview

> One line: which gate proves each conclusion, which layer it runs in, roughly how
> long it takes, and where to look first when it turns red. The data comes from
> [gate-registry.mjs](../scripts/gate-registry.mjs),
> [ci.yml](../.github/workflows/ci.yml), [nightly.yml](../.github/workflows/nightly.yml),
> and local measurements, not from memory.

## 1. How To Read This And The Evidence Bar

- **Measurement environment**: macOS arm64, JDK 21, Node 24.21.0, npm 11,
  Vite 8.3.5, Playwright Chromium. Times are one real run including process startup.
- **CI is slower**: on GitHub runners the same command is usually 1.5–3x the
  local value. Use the tables to judge order of magnitude, not as an SLA.
- **Time labels**: `measured` means one local run; `not measured` means the gate
  needs Docker, a real model, or external secrets that are unavailable locally —
  no number is invented.
- **Gate registry**: `scripts/gate-registry.mjs` is the machine-readable source of
  truth. A new script must be registered, wired into CI or marked manual, and
  `verify-gate-wiring.mjs` enforces the three-way consistency.
- **Failure artifacts**: CI logs land in `.verification/ci/` and
  `.verification/nightly/`. For local runs, read the last 40 lines and the
  diagnostic path printed by the script.

## 2. Layer Overview

| Layer | Trigger | Entry | Main proof | Local measurement |
|-------|---------|-------|------------|-------------------|
| PR fast | push `main`/`develop`, PR | `.github/workflows/ci.yml` | compile, unit/contract tests, static gates, docs gates, WebUI type/lint/unit/build/mock E2E | see details below; Mock E2E about 9.6 minutes |
| Long run | daily 18:00 UTC and manual | `.github/workflows/nightly.yml` | gated PostgreSQL, failure modes, crash consistency, real model, soak, security regressions, container delivery, real WebUI | see details below; the longest failure mode is about 8 minutes |
| Supply chain | push `main` and Monday 03:00 UTC | `.github/workflows/codeql.yml` | Java/JS static security scanning | not measured (CI only) |
| Manual release | before a release | `./scripts/verify-release.sh` | artifact integrity, embedded WebUI, inventory and signatures | not measured (needs a full release environment) |
| Local optional | on demand | `scripts/verify-*.py`, `scripts/verify-*.sh` | deep acceptance with real processes, browsers, and fault injection | see section 6 |

## 3. PR Fast Layer

| Gate/entry | Proposition | Measured | Dependencies | First place to look |
|------------|------------|----------|--------------|---------------------|
| `mvn test` | all module unit and contract tests pass; OpenAPI shape and WebUI route contracts do not drift | 111.2s | JDK 21; no database (contract tests disable Flyway) | `.verification/ci/mvn-test.log`, `target/surefire-reports/` |
| `mvn -f demos/demo-basic-rag/pom.xml test` | demo configuration binding does not shadow core settings; env vars do not silently stop working | 2.6s | JDK 21 | demo `application.yml` and `RagProperties` binding |
| `./scripts/verify-project-tests.sh` | 76 repository gates pass (visibility, wiring, static contracts, WebUI policy, self-tests) | 45.6s | Node, `rg`, Maven surefire reports | the first `FAIL` in the log; section 4 for each gate |
| `./scripts/verify-project-docs.sh` | 17 documentation gates pass (links, bilingual structure, translation, docs-code truth, script registration, secrets, whitespace) | 4.2s | Node, `rg`, git | the failing `verify-*` documentation item and file name |
| Flyway migration inventory | migration file names, version continuity, and no duplicates | <1s | shell, `find` | `spring-ai-rag-core/src/main/resources/db/migration/` |
| OpenAPI breaking change | starts the demo and compares the real `/v3/api-docs`; new optional fields are not breaking | not measured | JDK 21, PostgreSQL, demo jar | `/tmp/openapi-app.log`, baseline diff under `target/openapi-baseline` |
| `npx tsc -b` | WebUI type checking passes | not measured separately | Node, installed dependencies | the reported `tsc` file and line |
| `npm run lint` | ESLint, horizontal alignment, design tokens, shared primitives pass | 28.3s | Node, `npm ci` | the matching `spring-ai-rag-webui/scripts/check-*.mjs` |
| `npm run test:run` | all WebUI unit tests pass | 13.8s | Node, jsdom | the failing Vitest case and component diff |
| `npm run test:coverage` | coverage thresholds do not regress | not measured separately | Node | `coverage/` and threshold config |
| `npm run build` | production build succeeds and static assets are produced | 2.5s | Node, Vite 8 | build log and `dist/` |
| Static build matches repository | a fresh rebuild is byte-identical to `spring-ai-rag-core/src/main/resources/static/webui/` | 2.5s rebuild plus compare | Node, Vite 8 | the files listed by `diff -r`, then check whether a rebuilt asset was not committed |
| `npm run check:performance-budget` | bundle size budget is not exceeded | 18.2s | Node, `dist/` | the over-budget chunk in the report |
| `npm run test:e2e:mock` | 312 mock E2E tests pass (Chromium/Firefox/WebKit, including responsive) | 579.4s | Playwright browsers, no backend | `playwright-report/` and failing traces |
| `verify-visual-regression.sh` | visual regression baselines are not changed accidentally | not measured | Docker, Playwright | visual diffs and the baseline commit |

## 4. Static Gate Details (inside `verify-project-tests.sh`)

The 56 automated gates below (57 are registered; `verify-container-image.sh` needs a real Docker image, has no self-test, and appears in §5) all carry self-tests; times are one local run in seconds.

### 4.1 Backend And Repository Contracts

| Script | Proposition | Measured | First place to look |
|--------|-------------|----------|---------------------|
| `verify-architecture-boundaries.mjs` | module dependency direction and layering boundaries are intact | 0.52 | the reported cross-layer import |
| `verify-capability-protocol-pinning.mjs` | capability protocol constants match between client and server | 0.21 | the drifted constant name |
| `verify-controller-constructor-count.mjs` | controller constructor injection stays within the testability limit | 0.28 | the over-limit controller |
| `verify-dependency-versions.mjs` | third-party versions are declared only in the root `pom.xml`; modules carry no manual versions | 2.79 | the reported module and version |
| `verify-docs-code-truth.mjs` | migration range, ports, and configuration in docs match the code | 0.40 | the mismatched documentation item |
| `verify-e2e-run-paths.mjs` | E2E script paths and real entry points exist | 0.23 | the missing path |
| `verify-embedding-state-generation.mjs` | embedding state machine generation fields are consistent | 0.23 | the mismatched state/generation |
| `verify-endpoint-behavior-matrix.mjs` | the endpoint behavior matrix covers every route | 0.23 | the uncovered route |
| `verify-env-example-consumers.mjs` | every variable in `.env.example` has a consumer | 2.30 | the variable without a consumer |
| `verify-error-code-catalog.mjs` | the error-code catalog is complete, unique, and fully used | 0.30 | catalog versus enum differences |
| `verify-error-response-contract.mjs` | error response envelopes match the contract | 0.29 | the controller that violates the envelope |
| `verify-external-db-safety.mjs` | external database safety switches have correct defaults | 0.37 | the unsafe default |
| `verify-false-optional-wiring.mjs` | beans marked optional are actually wired | 0.35 | the falsely optional bean |
| `verify-flyway-version-pinning.mjs` | migration versions are continuous, unique, and published scripts are not rewritten | 0.24 | the version and file |
| `verify-gate-entry-points.mjs` | gate entry scripts are reachable and executable | 0.19 | the missing entry point |
| `verify-gate-wiring.mjs` | registry, docs, and CI wiring are consistent | 0.32 | `undocumented-gate`/`orphan-gate`/`stale-ci-reason` |
| `verify-helm-env-consumers.mjs` | every env var injected by Helm has an application consumer | 2.76 | the variable without a consumer |
| `verify-helm-helper-liveness.mjs` | Helm helper templates are referenced and renderable | 0.20 | the unreferenced helper |
| `verify-integration-test-switches.mjs` | integration test switches default off and can be explicitly enabled | 0.40 | the switch default |
| `verify-interrupt-handling.mjs` | thread interrupts are propagated, not swallowed | 0.51 | the code location that swallows interrupts |
| `verify-json-assertions.mjs` | JSON assertions in tests are not vacuous | 0.22 | the vacuous assertion file |
| `verify-lifecycle-truth-source-wiring.mjs` | lifecycle truth has a single wiring source | 0.44 | the multiple truth-source paths |
| `verify-list-endpoint-pagination.mjs` | list endpoints declare pagination and stable ordering | 0.19 | the endpoint missing the contract |
| `verify-null-request-forwarding.mjs` | null request fields are forwarded, not silently dropped | 6.45 | the controller/DTO that drops fields |
| `verify-playwright-suite-selection.mjs` | Playwright suite selection and naming match | 0.22 | the suite mapping |
| `verify-port-probe-authority.mjs` | port probing has a single authoritative source | 0.23 | the duplicate probing logic |
| `verify-slo-endpoint-coverage.mjs` | SLOs cover every public endpoint | 0.37 | the uncovered endpoint |
| `verify-surefire-method-selection.mjs` | surefire method selection syntax is valid | 0.20 | the invalid selector |
| `verify-tautological-assertions.mjs` | no tautological, always-true assertions | 0.53 | the always-true assertion |
| `verify-test-expectations.mjs` | test expectations and coverage baseline are not lost | 1.36 | the item where the expectation count dropped |
| `verify-test-visibility.mjs` | every test class is visible and executed, with no orphans | 1.07 | the unexecuted test class |
| `verify-tracked-env-files.mjs` | only safe env files are tracked; secrets are not committed | 0.28 | the tracked sensitive file |
| `verify-write-endpoint-idempotency.mjs` | write endpoints declare and implement idempotency contracts | 0.32 | the endpoint missing idempotency |
| `verify-zh-translation.mjs` | Chinese docs contain no untranslated English prose; bilingual heading structure matches | 0.19 | the reported document and line |
| `verify-client-consumer-contract.sh` | external consumer contract samples compile | 0.41 | the contract sample diff |
| `verify-no-pessimistic-locks.sh` | no explicit pessimistic locks, `SKIP LOCKED`, or advisory locks | 0.19 | the reported SQL/code location |

### 4.2 WebUI Policy

| Script | Proposition | Measured | First place to look |
|--------|-------------|----------|---------------------|
| `check-a11y-forms.mjs` | form labels are correctly associated with inputs | 0.30 | the control missing a label |
| `check-alignment-policy.mjs` | body content defaults to `text-align: start`; centering is only for explicit semantics | 0.19 | the violating selector |
| `check-clipboard-calls.mjs` | clipboard behavior always goes through the shared wrapper | 0.20 | the direct clipboard call |
| `check-decorative-graphics.mjs` | decorative graphics are marked `aria-hidden` | 0.20 | the unmarked graphic |
| `check-design-system.mjs` | design tokens, icons, and shared primitive rules hold | 0.27 | the violation category and file |
| `check-destructive-confirm.mjs` | destructive actions require confirmation | 0.21 | the entry without confirmation |
| `check-double-submit.mjs` | submit buttons prevent double submission | 0.21 | the unguarded submit |
| `check-hardcoded-copy.mjs` | no hard-coded user-facing copy | 2.57 | the hard-coded string |
| `check-heading-levels.mjs` | heading levels do not skip | 0.20 | the page with a skipped level |
| `check-i18n-keys.mjs` | i18n keys are complete with no missing entries | 0.24 | the missing key |
| `check-mutation-errors.mjs` | mutation error handling is consistent | 0.21 | the unhandled mutation error |
| `check-number-formatting.mjs` | number formatting is consistent | 0.19 | the direct formatting call |
| `check-page-shell.mjs` | page shell structure is consistent | 0.20 | the page that deviates from the shell |
| `check-performance-budget.mjs` | the production bundle stays inside the size budget | 9.17 | the over-budget chunk |
| `check-query-errors.mjs` | query error handling is consistent | 0.26 | the unhandled query error |
| `check-reduced-motion.mjs` | `prefers-reduced-motion` is respected | 0.18 | the animation without a fallback |
| `check-single-api-base.mjs` | the API base has exactly one source | 0.20 | the hard-coded base |
| `check-text-truncation.mjs` | text truncation policy is consistent | 0.20 | the direct truncation |
| `check-time-formatting.mjs` | time formatting is consistent and timezone-explicit | 0.31 | the direct `toLocaleString` call |
| `check-timing-constants.mjs` | timing constants are centralized | 0.21 | the scattered magic number |

## 5. Long Run Layer (nightly)

| Entry | Proposition | Measured | Dependencies | First place to look |
|-------|-------------|----------|--------------|---------------------|
| `verify-gated-it.sh` | 31 gated PostgreSQL integration suites pass (real migrations, transactions, leases) | not measured | Docker/Testcontainers, JDK 21 | `.verification/nightly/gated-it.log` |
| `verify-upstream-recovery-e2e.py` | upstream retries, `Retry-After`, and post-recovery availability | 83.4s | isolated PostgreSQL, mock provider | the three-stage failure point and recovery assertions |
| `verify-embedding-failure-e2e.py` | embedding failure recovers to `SYNC` and becomes searchable | 490.0s | isolated PostgreSQL, mock provider | the failure state and recovery polling |
| `verify-crash-consistency-e2e.py` | real `SIGKILL` leaves consistent data with no half-writes | not measured | isolated PostgreSQL, demo jar | the crash point and post-recovery SQL assertions |
| `verify-endpoint-behavior-e2e.py` | the runtime endpoint behavior matrix matches the contract | not measured | isolated PostgreSQL, mock provider | the differing matrix row |
| `real-llm-e2e-smoke.sh` plus real-model checks | real BGE-M3/LLM, goldenset, and retrieval regressions | not measured | real model secrets, external network | model call logs and goldenset diff |
| Bounded soak | sustained load has no leaks, lock contention, or unbounded latency | not measured | isolated PostgreSQL, demo jar | soak summary and memory/latency curves |
| `verify-security-boundary.py` | a restricted key cannot cross Collections or principals; path traversal is rejected | 14.0s | isolated PostgreSQL, mock provider | the specific privilege-escalation assertion |
| `verify-security-upgrade-path.py` | V70→V71 upgrade fails closed and ownership backfill is correct | 34.7s | isolated PostgreSQL, V70 data construction | the pre/post visibility assertions |
| `verify-security-deployment-posture.py` | deployment security switch defaults are correct | 32.2s | isolated PostgreSQL, demo jar | the unsafe default |
| `verify-log-secrecy-e2e.py` | sensitive canaries stay out of logs/metrics/error bodies/exports; diagnostic actuator endpoints are closed | 24.0s | isolated PostgreSQL, mock provider | leaked canary name and scanned location |
| `verify-container-image.sh` | the image builds, starts, and passes health checks | not measured | Docker, Buildx | build log and container health output |
| Real WebUI specs | page flows work against a real backend and real model | not measured | dev stack, real model, Playwright | Playwright traces and backend logs |
| CodeQL | Java/JS static security scanning has no new high-confidence alerts | not measured | GitHub Actions | the CodeQL alert location |

## 6. Manual Release And Local Optional

| Script | Proposition | Dependencies |
|--------|-------------|--------------|
| `verify-release.sh` | 1.0 release gate: artifacts, embedded WebUI, resource existence, Git trackability | full build environment |
| `verify-clean-clone.sh` | a clean clone builds and tests without local leftovers | network, Maven |
| `verify-baseline.sh` | baseline capability set and default configuration do not drift | local service |
| `verify-api-compatibility.sh` | external API compatibility | service process |
| `verify-business-client-readiness.sh` | external business-client onboarding readiness | service process, sample client |
| `verify-client-api-e2e.sh` | client API end to end | service process |
| `verify-client-contract-governance.sh` | client contract governance flow | samples and contracts |
| `verify-client-sdk-quickstart.sh` | SDK quickstart runs | service process, SDK |
| `verify-openai-compatibility.sh` | OpenAI-compatible endpoint behavior | service process |
| `verify-openai-sdk-e2e.sh` | OpenAI SDK end to end | service process, SDK |
| `verify-chat-capability.sh` | chat capability switches and routing | service process |
| `verify-chunking-quality.sh` | chunking quality thresholds | isolated database |
| `verify-collection-provisioning.sh` | Collection creation/version/alias | isolated database |
| `verify-collection-coverage.sh` | Collection coverage and ownership | isolated database |
| `verify-collection-purge.sh` | purge semantics and idempotency | isolated database |
| `verify-derivation-integrity.sh` | derived data integrity | isolated database |
| `verify-document-lifecycle.sh` | document lifecycle state machine | isolated database |
| `verify-document-relocation.sh` | document relocation/migration | isolated database |
| `verify-document-sync-runs.sh` | external document idempotent synchronization | isolated database |
| `verify-embedding-jobs.sh` | embedding job queue and retries | isolated database |
| `verify-embedding-operations.sh` | embedding operational actions | isolated database |
| `verify-jsonb-records.sh` | JSONB record read/write consistency | isolated database |
| `verify-keyword-vector-decoupling.sh` | keyword and vector retrieval are decoupled | isolated database |
| `verify-llm-usage-ledger.sh` | LLM usage ledger accuracy | service process |
| `verify-managed-api-principals.sh` | managed API principal lifecycle | isolated database |
| `verify-managed-quality.sh` | managed quality evaluation flow | isolated database |
| `verify-retrieval-diagnostics.sh` | retrieval diagnostic fields and output | isolated database |
| `verify-retrieval-filters.sh` | retrieval filter conditions are correct | isolated database |
| `verify-rerank-document-diversity.sh` | rerank document diversity | isolated database, rerank fixture |
| `verify-quality-regression.sh` | quality regression thresholds | goldenset |
| `verify-recovery-readiness.sh` | backup/recovery readiness | backup artifacts |
| `verify-api-key-expiry-alerts.sh` | API key expiry alerting | alert delivery configuration |
| `verify-alert-notification-delivery.sh` | alert delivery chain | alert delivery configuration |
| `verify-flyway-schema-paths.sh` | Flyway schema paths are correct | isolated database |
| `verify-openapi-breaking-change.mjs` | OpenAPI baseline diff | service process |
| `verify-next-high-value-feature.sh` | next-feature checklist completeness | repository state |
| `verify-next-high-value-features.sh` | multi-feature checklist completeness | repository state |

### 6.1 Locally Reproducible Deep Acceptance (newly added recently)

| Script | Proposition | Measured | Dependencies |
|--------|-------------|----------|--------------|
| `verify-timezone-consistency-e2e.py` | storage/return/parsing/cron/TTL/logging agree across three timezones | 36.5s (22 checks) | isolated PostgreSQL, mock provider |
| `verify-collection-export-roundtrip-e2e.py` | export→rebuild keeps documents/chunks/vectors/retrieval equivalent | 12.5s (7 checks) | isolated PostgreSQL, mock provider |
| `verify-browser-security-headers-e2e.py` | security headers, CSP does not break features, injections are rejected | 19.5s | isolated PostgreSQL, Chromium, mock provider |
| `verify-webui-credential-security-e2e.py` | API key lives in memory only; refresh/logout/multi-tab/injected scripts cannot read it | 22.0s | isolated PostgreSQL, Chromium, mock provider |
| `verify-upload-boundaries-e2e.py` | 413/503, pre-read rejection, concurrency and interrupt cleanup | not measured | isolated PostgreSQL, mock provider |
| `verify-resource-saturation-e2e.py` | pool exhaustion/slow query/DB unreachable produce 503/504 and recover | not measured | isolated PostgreSQL, mock provider |
| `verify-concurrency-idempotency.py` | concurrent write idempotency and lease semantics | not measured | isolated PostgreSQL, mock provider |

## 7. WebUI Static Artifacts: Commit Decision And Reproducibility

### 7.1 Conclusion

Static artifacts **remain committed**, and a CI check now guarantees "rebuild result
equals committed result". The host jar must run directly, so users should not be
forced to install Node. With the dependency lockfile, artifacts are byte-reproducible,
so diff noise appears only when the build toolchain changes, not on every build.

### 7.2 Measured evidence (local)

- Same commit, same Node 24.21.0, two consecutive builds: the `sha256` manifest is
identical.
- Node 22.15.1 versus Node 24.21.0 builds: the manifest is identical.
- Different absolute paths (inside the repo versus `/tmp`): the manifest is identical.
- `TZ=Asia/Shanghai`, `LANG=zh_CN.UTF-8` builds: the manifest is identical.
- Only file mtimes change; Git compares content, so no diff is produced.
- No build timestamp, absolute path, parallel ordering, or locale leakage was found;
  Vite config has no time `define`, no sourcemaps, and chunk names are content hashes.

### 7.3 Scope and boundaries

- Reproducibility is promised only with versions pinned by `package-lock.json`.
  Upgrading Vite/Rolldown or a major Node version may change all artifacts; that is
  an expected change and must be regenerated and committed.
- Cross-operating-system identity is not promised (locally only macOS arm64 was
  verified). The CI check runs on Linux runners, so if a platform difference appears,
  the CI `diff -r` result is authoritative.
- `dist/` is not committed; only `spring-ai-rag-core/src/main/resources/static/webui/`
  is committed.

### 7.4 Local and CI check

```bash
cd spring-ai-rag-webui
npm ci
npm run build
diff -r dist ../spring-ai-rag-core/src/main/resources/static/webui
```

CI runs the same rebuild and directory comparison in the WebUI job step
`Committed static build matches a fresh build`; any uncommitted rebuild turns the PR red.

## 8. Where To Look First When It Turns Red

| Symptom | Look first |
|---------|------------|
| PR backend gate fails | the first `FAIL` in `.verification/ci/repo-test-gates.log`, then locate the script in section 4 |
| PR docs gate fails | the reported file and the specific diff from `verify-zh-translation`/`verify-docs-code-truth` |
| WebUI policy fails | the violation category and file/line from the matching `check-*.mjs` |
| Mock E2E fails | traces and screenshots under `playwright-report/`; first check whether it is flaky |
| nightly gated fails | migration version, transaction, or lease assertions in the gated IT log |
| security regression fails | the specific privilege or default-value assertion in `security-boundary`/`upgrade-path`/`posture` |
| static artifact gate fails | the files listed by `diff -r`; confirm whether a frontend change skipped the rebuild commit |
| real-model nightly fails | secrets, network, model quota, and goldenset diffs |
