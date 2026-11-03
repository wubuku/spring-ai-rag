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

## WebUI：Stryker 变异测试（默认关闭）

后端用 PIT 证明判定测试不是假绿；前端用 Stryker 10.1.1 + `@stryker-mutator/vitest-runner`
对四个最容易"分支删掉测试还绿"的模块做同样的事。

| 模块 | 变异体 | killed | survived | timeout | no coverage | 分数 |
|------|--------|--------|----------|---------|-------------|------|
| `src/utils/failureClass.ts` | 173 | 163 | 10 | 0 | 0 | **94.22%** |
| `src/hooks/useFileUpload.ts` | 103 | 98 | 5 | 0 | 0 | **95.15%** |
| `src/hooks/useSSE.ts` | 583 | 415 | 153 | 7 | 8 | **72.38%** |
| `src/pages/Chat.tsx:165-287` | 127 | 109 | 17 | 0 | 1 | **85.83%** |

配置在 [spring-ai-rag-webui/stryker.config.mjs](../spring-ai-rag-webui/stryker.config.mjs)，
`test:mutation` 不在 `lint` / `test:run` / CI 默认路径里；`thresholds.break: null`，
因为它只是诊断工具，不作为门禁。

```bash
cd spring-ai-rag-webui
npm run test:mutation
# 单模块：npx stryker run --mutate src/hooks/useSSE.ts
# 报告：spring-ai-rag-webui/coverage/mutation/index.html
```

### 成本取舍（如实记录）

- `useSSE` 与 `Chat` 使用 `--ignoreStatic`：模块级静态变异体每个都要重跑整份测试文件，
  信息量低、成本高；`failureClass` 的静态变异体约 15 个，成本可接受，因此保留。
- `useSSE` 的 7 个 timeout 是 jsdom + 流式 reader + 假定时器互相等待触发的 30s 上限，
  计为"已检出"，不是 killed。继续压这个分数需要重构 SSE 解析的可测边界，收益低于成本。
- `Chat.tsx` 只变异 165–287 行（SSE 回调与状态推进），整页布局/样式变异没有行为意义。

### 本轮补出的真实缺口

变异测试暴露了"删掉分支测试仍绿"的位置，已补有意义的断言：

- `failureClass`：状态码→分类、reason→文案 key、大小写/空白归一、`failureSignalOf`
  类型过滤、`parseRetryAfterMs` 的 `2.5`/`' 2 '`/`-1`/`Infinity` 边界、精确 `HTTP 503` 文案过滤。
- `useFileUpload`：`isUploading` 真实观测、多文件 multipart、`message`/`detail` 优先级、
  无 headers 的 502 回退、无 `randomUUID` 的幂等键回退、取消语义、无回调也能完成。
- `useSSE`：Retry-After 精确 2000ms 边界与 `Infinity`、无 body/无效 turn/重复 claim、
  `close()` 后的 generation 守卫、`done` 丢 turnId、错误响应解析优先级、`data: null` 安全忽略。
- `Chat.tsx`：非 streaming 回调忽略、sources 替换、同/不同 tool 的活动匹配、
  retry 清理 sources 与 tool 活动、retryable 保留 prompt、incomplete 提示、空 session 不导航。

### 存活变异体分类

1. **等价变异**：`failureClass` 的 10 个全部属于此类——`typeof status !== 'number'`、
   `default: return undefined`、`if (!cls) return true`、`!error` 后的 `typeof` 判断、
   `value.trim() === ''`、`seconds > 0 → >= 0`（`0` 与负值都返回 `0`）。
2. **依赖数组**：`useFileUpload` 3 个（47/157/161），`Chat` 多数 ref/依赖项。
   删掉依赖项在当前测试里不改变渲染结果，属于 React 生命周期而非业务分支。
3. **防御性 optional chaining**：`useSSE` 大量 `?.` 与字段兜底；真实输入不会为 `null`。
4. **前端不可观测**：`useFileUpload` 的单文件 `map` 三元（前端状态更新与回调等价）；
   `Chat` 的 `prev.length + 1`、`msg.id !== lastMsg.id`、retry 时清 prompt 在 UI 上不可见。
5. **已被前置分类遮蔽**：`useSSE` 的 `response.status === 409 || 429` 在带分类头时不可达；
   `attempt < MAX_ATTEMPTS` 的边界与重试次数等价。

**结论**：四个模块的判定分支都有可失败的测试；`useSSE` 的 72% 主要来自解析器的
防御性分支与静态变异体过滤，不是断言缺失。若重构 SSE 解析，应优先把 reader/事件解析
拆成纯函数，再对纯函数跑完整变异。
