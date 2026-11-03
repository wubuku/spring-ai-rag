# 测试有效性：变异测试记录（中英成对）

> 目的：回答“测试是不是假绿”。变异测试只对**关键判定**做抽样，不追求分数，
> 重点是把**存活变异体**当作未来重构的风险清单。English: [mutation-testing.md](mutation-testing.md)。

## 范围

| 领域 | 类 |
|------|----|
| 检索范围与打分 | `RetrievalScope`、`RetrievalScopeSql`、`RetrievalScopeSummary`、`RerankResultSelector` |
| 配额 / 限流 | `PostgresRateLimitStore` |
| 会话租约与预算 | `ChatSessionCoordinator`、`ChatExecutionBudget` |
| 删除一致性 | `DocumentLifecycleService` |
| 失败分类映射 | `UpstreamFailureClassifier`、`EmbeddingFailureClassifier`、`FailureClassHttpSupport` |

只跑这些类对应的单测，profile 默认关闭，不拖慢日常 `mvn test`。

## 怎么跑

PIT 1.17.2 需要 **JDK 21** 运行（本机默认 JDK 25 的 class 版本超出支持范围）：

```bash
JAVA_HOME=<jdk21> mvn -pl spring-ai-rag-core -Pmutation \
  test-compile org.pitest:pitest-maven:1.17.2:mutationCoverage
# 报告：spring-ai-rag-core/target/pit-reports/index.html
```

配置在 [spring-ai-rag-core/pom.xml](../spring-ai-rag-core/pom.xml) 的 `mutation` profile；
`mvn test` 不会激活它。

## 结果（2026-11）

576 个变异体：**512 killed（88.9%）**，51 survived（8.9%），13 no coverage（2.3%）。
Test strength（killed / (killed + survived)）= **90.9%**。

| 类 | killed | survived | no coverage | 备注 |
|----|--------|----------|-------------|------|
| `RetrievalScope` / `RetrievalScopeSql` / `RetrievalScopeSummary` | 13 / 13 / 66 | 0 | 0 | 边界、ID 解析、degraded 标记已断言 |
| `RerankResultSelector` | 32 | 7 | 0 | 存活均为等价变异（见下） |
| `PostgresRateLimitStore` | 7 | 0 | 0 | — |
| `ChatExecutionBudget` | 115 | 2 | 1 | 预算边界、归属校验、结算释放已断言 |
| `ChatSessionCoordinator` | 67 | 35 | 1 | 存活集中在内部租约状态机（见下） |
| `DocumentLifecycleService` | 109 | 2 | 0 | 推导矩阵与 zero-chunk 边界已断言 |
| `UpstreamFailureClassifier` | 65 | 2 | 0 | 500/429/超时/依赖失败/脱敏已断言 |
| `EmbeddingFailureClassifier` | 20 | 3 | 11 | no coverage 多为被共享分类器遮蔽的冗余分支 |
| `FailureClassHttpSupport` | 5 | 0 | 0 | 399/400 边界与已有 header 已断言 |

## 存活变异体分类

### 1. 等价变异：不补断言

`RerankResultSelector` 的 7 个存活体（`<= 0` / `>= finalLimit` / 循环边界 / `selectedCount++ → --`）：
无论走“按文档上限去重”还是“按原顺序补齐”，最终输出都被 `target` 截断，结果集合相同。
为它们写断言只会绑定实现细节。

### 2. 防御性 / 不可达代码：记录理由

- `ChatExecutionBudget`：`Integer.MAX_VALUE` 溢出保护、`httpToolExecutionState` 的 CAS 失败递归
  （单线程测试无法构造 CAS 失败）。
- `UpstreamFailureClassifier`：`retryAfter` 长度 128 的边界不可观测（更长的数字串必然解析失败）。
- `EmbeddingFailureClassifier` 的 5xx/4xx 兜底循环：`UpstreamFailureClassifier` 已在入口返回，
  这些分支对 `RestClientResponseException` 实际不可达；`status == 408/429/>=500` 同理。
- `DocumentLifecycleService`：`profileKey` 的异常回退、`processingSummary` 的空返回，属于纯防御路径。

### 3. 内部状态机：刻意不做白盒断言

`ChatSessionCoordinator` 的 35 个存活体几乎都是 `beginCommit` / `assertActive` /
`AtomicReference.set` / `stopRenewal` / `scheduleRenewal` / `Thread.setDaemon` /
`memory.clear` 的“调用被删除”。它们是租约续期与提交的**并发内部状态**：
- 直接可见性依赖反射或时序，单测断言会变成脆弱白盒测试；
- 真实风险由 gated PostgreSQL 集成测试与 `deletion-consistency` 场景覆盖。

**已知风险**：续期被删除时单测仍会绿，只有集成/长时间运行场景能暴露。
若未来重构该状态机，应优先补一条“提交期间不再续期”的集成断言。

### 4. 刻意不强测

- 日志文案、指标名拼写；
- 纯 getter / `toString` / 无分支 DTO 映射；
- 与框架重复的空值防御（Spring/JDBC 已保证非空）。

## 结论

- 检索范围、限流、预算边界、删除推导、失败分类的**判分支路**已有有效断言。
- 最需要警惕的是 `ChatSessionCoordinator` 的内部状态机：不是测试写错，而是测试**看不见**。
  这类代码改动必须跑 gated 集成，不能只看 `mvn test`。
