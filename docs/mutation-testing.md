# Test Effectiveness: Mutation Testing Record (English/Chinese pair)

> Goal: answer "are our tests green but hollow?" Mutation testing samples only **critical decisions**,
> not chasing a score. Surviving mutants are treated as a refactoring risk list.
> Chinese: [mutation-testing-zh-CN.md](mutation-testing-zh-CN.md).

## Scope

| Area | Classes |
|------|---------|
| Retrieval scope and scoring | `RetrievalScope`, `RetrievalScopeSql`, `RetrievalScopeSummary`, `RerankResultSelector` |
| Quota / rate limiting | `PostgresRateLimitStore` |
| Session lease and budget | `ChatSessionCoordinator`, `ChatExecutionBudget` |
| Deletion consistency | `DocumentLifecycleService` |
| Failure classification | `UpstreamFailureClassifier`, `EmbeddingFailureClassifier`, `FailureClassHttpSupport` |

Only the unit tests for these classes run. The profile is off by default and does not slow down `mvn test`.

## Run

PIT 1.17.2 must run on **JDK 21** (the default JDK 25 class-file version is unsupported):

```bash
JAVA_HOME=<jdk21> mvn -pl spring-ai-rag-core -Pmutation \
  test-compile org.pitest:pitest-maven:1.17.2:mutationCoverage
# Report: spring-ai-rag-core/target/pit-reports/index.html
```

The `mutation` profile lives in [spring-ai-rag-core/pom.xml](../spring-ai-rag-core/pom.xml);
plain `mvn test` never activates it.

## Results (2026-11)

576 mutants: **512 killed (88.9%)**, 51 survived (8.9%), 13 no coverage (2.3%).
Test strength (killed / (killed + survived)) = **90.9%**.

| Class | killed | survived | no coverage | Note |
|-------|--------|----------|-------------|------|
| `RetrievalScope` / `RetrievalScopeSql` / `RetrievalScopeSummary` | 13 / 13 / 66 | 0 | 0 | boundaries, ID parsing, degraded flag asserted |
| `RerankResultSelector` | 32 | 7 | 0 | survivors are equivalent mutants |
| `PostgresRateLimitStore` | 7 | 0 | 0 | — |
| `ChatExecutionBudget` | 115 | 2 | 1 | budget boundaries, attribution, settlement asserted |
| `ChatSessionCoordinator` | 67 | 35 | 1 | survivors are internal lease-state calls |
| `DocumentLifecycleService` | 109 | 2 | 0 | derivation matrix and zero-chunk boundary asserted |
| `UpstreamFailureClassifier` | 65 | 2 | 0 | 500/429/timeout/dependency/redaction asserted |
| `EmbeddingFailureClassifier` | 20 | 3 | 11 | no-coverage mostly redundant branches shadowed by the shared classifier |
| `FailureClassHttpSupport` | 5 | 0 | 0 | 399/400 boundary and existing header asserted |

## Surviving mutants

### 1. Equivalent mutants — no assertions added

The 7 survivors in `RerankResultSelector` (`<= 0`, `>= finalLimit`, loop boundaries,
`selectedCount++ → --`) produce the same output because the result is always truncated to `target`.
Asserting them would bind implementation details.

### 2. Defensive / unreachable code — documented instead

- `ChatExecutionBudget`: `Integer.MAX_VALUE` overflow guard; `httpToolExecutionState` CAS-failure recursion
  (a single-threaded unit test cannot force CAS failure).
- `UpstreamFailureClassifier`: the 128-character `retryAfter` boundary is unobservable (longer numeric strings fail parsing anyway).
- `EmbeddingFailureClassifier` fallback loop: `UpstreamFailureClassifier` already returns at the entry,
  so the `RestClientResponseException` 4xx/5xx branches (including `408`/`429`/`>=500`) are unreachable in practice.
- `DocumentLifecycleService`: `profileKey` exception fallback and `processingSummary` empty return are pure defensive paths.

### 3. Internal state machine — deliberately not white-box tested

Most of the 35 `ChatSessionCoordinator` survivors are "removed call to `beginCommit` / `assertActive` /
`AtomicReference.set` / `stopRenewal` / `scheduleRenewal` / `Thread.setDaemon` / `memory.clear`".
They are the **concurrent internal state** of lease renewal and commit:
- visibility requires reflection or timing; a unit assertion would be a brittle white-box test;
- real risk is covered by the gated PostgreSQL integration suite and `deletion-consistency` scenarios.

**Known risk**: deleting a renewal call still leaves `mvn test` green; only integration/soak runs expose it.
When refactoring this state machine, add an integration assertion that "renewal stops during commit".

### 4. Deliberately not tested

- Log wording and metric-name spelling;
- Pure getters / `toString` / branchless DTO mapping;
- Null guards already guaranteed by Spring/JDBC.

## Conclusion

- Retrieval scope, rate limiting, budget boundaries, deletion derivation, and failure classification
  have effective assertions on their decision branches.
- The real gap is `ChatSessionCoordinator`'s internal state machine: the tests are not wrong, they simply **cannot see** it.
  Such changes must run the gated integration suite; `mvn test` alone is not evidence.

## WebUI: Stryker mutation testing (off by default)

The backend uses PIT to prove its decision tests are not hollow; the frontend uses
Stryker 10.1.1 with `@stryker-mutator/vitest-runner` on the four modules most likely
to stay green after a branch is deleted.

| Module | Mutants | killed | survived | timeout | no coverage | Score |
|--------|---------|--------|----------|---------|-------------|-------|
| `src/utils/failureClass.ts` | 173 | 163 | 10 | 0 | 0 | **94.22%** |
| `src/hooks/useFileUpload.ts` | 103 | 98 | 5 | 0 | 0 | **95.15%** |
| `src/hooks/useSSE.ts` | 583 | 415 | 153 | 7 | 8 | **72.38%** |
| `src/pages/Chat.tsx:165-287` | 127 | 109 | 17 | 0 | 1 | **85.83%** |

Config lives in [spring-ai-rag-webui/stryker.config.mjs](../spring-ai-rag-webui/stryker.config.mjs).
`test:mutation` is not part of `lint` / `test:run` / the default CI path; `thresholds.break: null`
because this is a diagnostic tool, not a gate.

```bash
cd spring-ai-rag-webui
npm run test:mutation
# single module: npx stryker run --mutate src/hooks/useSSE.ts
# report: spring-ai-rag-webui/coverage/mutation/index.html
```

### Cost trade-offs (recorded honestly)

- `useSSE` and `Chat` ran with `--ignoreStatic`: each module-scope static mutant reruns the whole
  test file, giving little information at high cost. `failureClass` keeps its ~15 static mutants
  (cheap enough).
- The 7 `useSSE` timeouts come from jsdom + streaming reader + fake timers waiting on the 30s cap;
  they count as detected, not killed. Raising that score would require refactoring the SSE parser's
  testable boundary, at a cost that outweighs the benefit.
- `Chat.tsx` mutates only lines 165–287 (SSE callbacks and state transitions); layout/style mutants
  carry no behavioural meaning.

### Real gaps closed in this round

Mutation testing exposed branches that stayed green when deleted. Assertions were added for:

- `failureClass`: status→class, reason→copy key, case/whitespace normalisation, `failureSignalOf`
  type filtering, `parseRetryAfterMs` boundaries (`2.5`, `' 2 '`, `-1`, `Infinity`), exact
  `HTTP 503` copy filtering.
- `useFileUpload`: real `isUploading` observation, multi-file multipart, `message`/`detail`
  precedence, header-less 502 fallback, idempotency-key fallback without `randomUUID`,
  cancellation semantics, completion without callbacks.
- `useSSE`: exact Retry-After 2000 ms boundary and `Infinity`, missing body/invalid turn/duplicate
  claim, generation guard after `close()`, `done` without turnId, error-response precedence,
  safe `data: null` handling.
- `Chat.tsx`: non-streaming callback suppression, source replacement, same/different tool activity
  matching, retry clearing sources and tool activity, retryable prompt retention, incomplete hint,
  empty session not navigating.

### Surviving mutants

1. **Equivalent**: all 10 `failureClass` survivors — `typeof status !== 'number'`,
   `default: return undefined`, `if (!cls) return true`, the `typeof` check after `!error`,
   `value.trim() === ''`, `seconds > 0 → >= 0` (zero and negatives both return `0`).
2. **Dependency arrays**: 3 in `useFileUpload` (47/157/161) and most refs/deps in `Chat`.
   Removing them does not change rendered output in current tests; that is React lifecycle,
   not a business branch.
3. **Defensive optional chaining**: many `?.` and field fallbacks in `useSSE`; real inputs are never `null`.
4. **Not observable in the frontend**: the single-file `map` ternary in `useFileUpload`
   (state update and callback are equivalent); `prev.length + 1`, `msg.id !== lastMsg.id`, and
   retry prompt clearing in `Chat` are invisible through the UI.
5. **Shadowed by earlier classification**: `response.status === 409 || 429` in `useSSE` is
   unreachable when the failure class header is present; the `attempt < MAX_ATTEMPTS` boundary
   is equivalent to the retry count.

**Conclusion**: all four modules have assertions that can fail on their decision branches.
`useSSE`'s 72% comes mostly from defensive parser branches and static-mutant filtering, not from
missing assertions. If the SSE parser is refactored, split reader/event parsing into pure
functions first, then run full mutation coverage on the pure functions.
