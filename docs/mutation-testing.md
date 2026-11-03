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
