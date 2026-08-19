# 下一批高价值功能实施进度

> 对应规划：[2026-08-18_NEXT_HIGH_VALUE_FEATURES_PLAN.md](2026-08-18_NEXT_HIGH_VALUE_FEATURES_PLAN.md)
> 状态：实现、硬门禁与连续三轮只读审查均已完成，审查 **3/3 CLEAN**
> 开始日期：2026-08-18
> 代码基线：`1a433e8`（规划提交）
> 当前 Flyway：V1–V39
> Spring AI：1.1.4

本文件只记录本轮实施状态、验证证据和已知剩余项，不替代规划文档或正式 API/架构文档。

> 2026-08-18 最终 review 续检：旧的 `3/3 CLEAN` 已作废。第一轮发现并修复
> embedding 向量提交门不在持久化事务内、Evaluation worker 复用进程级
> `lease_owner` 两个并发缺陷；当前正在重跑专项 PostgreSQL 与完整硬门禁。

> 2026-08-18 23:12：Embedding Operations 专项已重跑通过：
> focused tests **16/16**、真实 PostgreSQL integration **7/7**。期间发现
> `Propagation.MANDATORY` 漏 import 被增量编译缓存掩盖，已修复；这再次确认最终
> `mvn clean compile test-compile` 是不可省略的硬门禁。

> 2026-08-18 23:15：Managed Quality 专项已重跑通过：
> focused/contract/service tests **97/97**、真实 PostgreSQL suite integration
> **7/7**。专项验证阶段结束，开始执行 clean 编译、全量后端、前端与文档硬门禁。

> 2026-08-18 续检：Managed Quality 专项门禁再次通过：
> focused/contract/service tests **97/97**、真实 PostgreSQL suite integration
> **7/7**；隔离 PostgreSQL 从空库实际迁移 **V1–V39** 成功。该结果确认
> Evaluation suite 的契约、owner ACL、语义评估降级和并发版本分配仍满足当前实现。

> 2026-08-18 23:19：用户明确新增架构硬约束：数据访问层禁止显式悲观锁。
> 修改前基线 `mvn test` 已通过（Core **2813**，跳过 **7**；Starter
> **48/48**）。全库审计发现生产代码存在 `FOR UPDATE`、JPA
> `PESSIMISTIC_*` 和 PostgreSQL advisory lock；当前进入集中替换阶段，之前的
> 最终门禁结果不再作为交付证据。

> 2026-08-18：V39 与集中替换已完成。生产代码静态扫描不再包含显式
> `FOR UPDATE`、`SKIP LOCKED`、JPA `PESSIMISTIC_*` 或 PostgreSQL advisory lock。
> `scripts/verify-no-pessimistic-locks.sh` 已加入本批聚合门禁和 release 门禁。

> 2026-08-18：为绕过本机 Testcontainers Docker API 协商问题，
> `ChatSessionPostgresIntegrationTest` 增加一次性外部 JDBC 模式。Docker CLI 启动的
> 隔离 PostgreSQL 16 + pgvector 上，Chat / Embedding Profile / JSONB **19/19**，
> External Document **5/5** 通过；所有全量迁移均实际执行到 V39，0 失败、0 跳过。

> 2026-08-18 收敛审查第 1 轮发现并修复：External/JSON 写路径虽然已定义
> Collection 生命周期 CAS token，但未实际消费；同时唯一身份和 `@Version` 竞态缺少
> 进度矩阵所承诺的有界事务重试。现在两条路径均在同一事务末尾确认 Collection token，
> 对唯一键、乐观锁和瞬态并发失败最多重试 3 次，并清除回滚尝试产生的异步任务结果。
> 受影响单测（Collection resolver / External / JSON）已通过 **37/37**；审查计数重置为
> **0/3**，等待 clean 编译、聚合验证、真实 PostgreSQL 与完整硬门禁重新通过。

## 1. 实施矩阵

| 批次 | 目标 | 状态 | 关键产物 |
|---|---|---|---|
| Phase 0 | 基线、进度台账和测试矩阵 | 完成 | 进度文档、Spring AI 1.1.4 |
| Batch A | 检索诊断与“为什么没有结果” | 完成 | V35、RetrievalOutcome、trace API、`verify-retrieval-diagnostics.sh` |
| Batch B | 普通文档安全 metadata 过滤 | 完成 | V36、RetrievalFilters、SQL 下推、`verify-retrieval-filters.sh` |
| Batch C | 嵌入/重索引任务运营控制面 | 完成 | V37、SYNC/ASYNC/SKIP、PDF/upload/batch/re-embed、WebUI Embeddings、`verify-embedding-operations.sh` |
| Batch D | 受管质量套件与 citation 可信度 | 完成 | CitationValidator、V38 suite/run、semantic adapter、Evaluation tabs、`verify-managed-quality.sh` |
| 收敛 | 硬门槛后连续三轮实现检查 | **3/3 CLEAN** | 见第 5 节 |

## 1.1 无悲观锁集中替换矩阵

| 原用法 | 替代方案 | 不变量 |
|---|---|---|
| Embedding/Evaluation `FOR UPDATE SKIP LOCKED` claim | 带状态与 lease 条件的原子 `UPDATE ... RETURNING`，空结果时有界重试下一候选 | 同一执行令牌只能被一个 worker 获得 |
| Embedding document/job `FOR UPDATE` | 文档 `version/content_hash/enabled` 条件更新；job 在向量事务内执行 owner/lease/profile/document 快照 CAS | 失去 lease 或快照过期的 worker 不能提交向量 |
| Evaluation suite 行锁分配 version | suite `next_version` 原子递增并在同一事务插入 immutable version | 并发 version 单调且唯一 |
| Evaluation owner advisory lock + active count | run `concurrency_slot`；owner/slot 活动状态部分唯一索引，逐槽 `ON CONFLICT DO NOTHING` | 并发 run 数不超过配置上限 |
| Collection JPA `PESSIMISTIC_READ/WRITE` | Collection `version` token；文档写入结束与软删除使用 CAS | 删除与写入竞态最多一方提交 |
| External/JSON identity advisory lock | `(collection_id, external_id)` 唯一索引 + JPA `@Version` + 有界事务重试 | 同一外部身份唯一，失败重试不产生重复 |
| Chat session `FOR UPDATE` | 提交事务开始时按 owner/token/expiry 原子 `DELETE ... RETURNING` 消费 lease | 只有 lease owner 能提交，提交/回滚均保持原子 |
| HNSW startup advisory lock | `CREATE/DROP INDEX CONCURRENTLY IF [NOT] EXISTS` + SQLSTATE 有界重试与有效性复核 | 多实例启动最终得到一个有效索引 |
| Legacy embedding document `FOR UPDATE` | 文档 version CAS + legacy row 条件迁移 | 并发迁移至多一方采用旧向量 |

允许数据库为普通 `INSERT/UPDATE/DELETE` 和唯一约束维护其内部短事务锁；禁止的是应用显式
请求阻塞式行锁、JPA pessimistic lock 或 advisory lock。此边界将同步到中英文长青文档并
由一键静态检查脚本守护。

## 2. 验证门槛

### 已验证（当前工作区）

- `./scripts/verify-retrieval-diagnostics.sh`
- `./scripts/verify-retrieval-filters.sh`
- `./scripts/verify-embedding-operations.sh`
- `./scripts/verify-managed-quality.sh`
- `mvn clean compile test-compile`
- `./scripts/verify-project-docs.sh`（10 checks）
- `git diff --check`
- WebUI：`npx tsc -b`、`npm run build`、核心 Mock Playwright **60/60**
- Starter：`mvn -pl spring-ai-rag-starter test` **48** 通过（不要用 `-am` 把 core 全量测带进来）
- 切片/契约：`OpenApiContractTest`、`RagControllerIntegrationTest`（69）、`RagChatControllerTest`（30）、`SseStreamE2ETest`（12）、`RagSearchControllerTest`（25）
- 可启动性：`:18081` `/actuator/health` **UP**（冒烟后已停止该进程）
- 真实 LLM/embedding smoke：`real-llm-e2e-smoke.sh` → **PASS=10 FAIL=0**
  - SiliconFlow BGE-M3 embed、MiniMax-M3 chat
  - 隔离文档 embed → search 仅命中该文档
  - ask / stream 均含探测 token 与 `[S1]`

本轮续检新增的直接证据：

- `./scripts/verify-managed-quality.sh`：focused/contract/service **97/97**；
  真实 PostgreSQL `EvaluationSuitePostgresIntegrationTest` **7/7**；
  三个隔离数据库均完成 Flyway **V1–V39** 迁移，无失败、无跳过。
- 该专项脚本的 Maven reactor 构建成功，未依赖增量编译结果。

### 启动/smoke/切片期间修过的阻断

1. `ChatCommandMapper` 双构造 → 收成单构造。
2. `RetrievalFilterValidator` 加 `@Component`。
3. 诊断 bean 不再用不可靠的 `@ConditionalOnBean`；JPA 缺失时 repository **optional**，persist fail-open（修复 `OpenApiContractTest` 上下文）。
4. `RagDocumentController` embed 映射改到带 `@PathVariable` 的真实方法。
5. Search `Map.copyOf` 因 scope 摘要 null 值 NPE → 省略 null、`attachScope` 跳过 null，Search/Chat 诊断 fail-open。
6. `searchInScopeDetailed` 返回 null 时不再 NPE；切片测试改为 mock 三参数 `chat` / `chatEvents` 与 `searchInScopeDetailed`。

### 审查后修复的阻断（重置 3/3）

1. **SemanticEvaluationService / Spring AI 1.1.4**：`FactCheckingEvaluator` 改为 `builder(ChatClient.Builder).build()`；`EvaluationRequest` 的 context 包成 `Document`。补了不调用 LLM 的实例化测试。
2. **Suite worker ACL**：worker 按 owner `db:{keyId}` 加载当前数据库 Key 再授权；缺失/停用/ACL 收回则 `FAILED` / `AUTHORIZATION_CHANGED`。`EvaluationSuiteServiceOwnerAclTest` 纳入 `verify-managed-quality.sh`。
3. **embedding-readiness**：补 service / MockMvc / PostgreSQL 分类测试；`verify-embedding-operations.sh` 已覆盖这些测试类。
4. **GET suite-by-key**：补 `findSuite` owner 隔离（PG）、`getSuite` 按当前 principal 查找、`GET /evaluation/suites/{suiteKey}` MockMvc。`verify-managed-quality.sh` 已重跑通过。
5. **Embedding 提交 fencing**：provider 返回后的资格复核移入
   `EmbeddingPersistenceService.replace()` 事务；job owner/lease/Profile/document
   快照使用条件 `UPDATE ... RETURNING` 延长 lease，文档最终使用 version CAS，失去资格
   的 worker 无法提交向量。
6. **Evaluation per-run fencing**：worker 每次 claim 使用唯一 `lease_owner`，
   同一进程并发 run 或过期回收不再共享写入令牌；新增 worker 回归测试。
7. **Embedding fencing 验证夹具**：并发测试在 latch 未触发时会转抛后台
   `Future` 的真实异常，避免线程提前失败被伪装成超时；补齐
   `Propagation` import 后，CAS 提交门测试已在真实 PostgreSQL 通过。
8. **显式悲观协调清零**：Chat lease 改为事务内原子消费；suite version 改为原子计数器；
   managed run 使用 owner/slot 部分唯一索引；External/JSON identity 使用唯一约束与
   `@Version`；HNSW DDL 使用 concurrent `IF EXISTS` / `IF NOT EXISTS` 和有界重试。
9. **真实 PostgreSQL 回归**：Chat/Profile/JSONB 19/19，External Document 5/5；
   Chat 测试增加 `chat.it.jdbc-url` 与 `chat.it.clean-confirm=YES`，避免把
   Testcontainers API 协商失败误判成产品失败。
10. **Collection 生命周期与身份竞态收敛**：External/JSON 在文档写入与异步 job
    入队所在事务内消费 Collection version token；唯一约束、乐观锁和瞬态并发失败使用
    新事务有界重试，避免移除显式行锁后出现删除竞态或并发幂等退化。

### 约定提醒

- 隔离编译 `spring-ai-rag-core` 必须带 `-am`。
- 不重写 Flyway V1–V34。
- 密钥不入文档 / git。

## 3. 本轮落地摘要

### 默认值（与规划一致）

```yaml
rag.retrieval-diagnostics.enabled: true
rag.retrieval-diagnostics.persist: true
rag.retrieval-diagnostics.store-query-text: false
rag.embedding-jobs.enabled: false
rag.evaluation.managed-suites-enabled: false
rag.evaluation.citation-validation-enabled: true
```

### Batch A

- Search/Chat 返回 `X-RAG-Retrieval-Trace-Id`；诊断失败不得让检索/对话 500
- `GET /rag/retrieval-traces` 按 owner principal 过滤；跨 owner 读 detail 为 NOT_FOUND
- query 默认 redact；scores 只暴露 `rank_*`

### Batch B

- Search/Chat/JSON/OpenAI `filters`：只接受 JSON object，`@>` 参数绑定
- 深度/字节上限；OpenAI 未知 `rag.filters` 字段拒绝

### Batch C

- 导入/显式 embed 接受 `embeddingPolicy`；显式 embed 拒绝 `SKIP`
- job 列表 ACL 在 SQL `LIMIT` 之前下推
- WebUI `/embeddings`
- `GET /collections/embedding-readiness` 按活动 Profile 互斥分类，并有 focused + PostgreSQL 测试

### Batch D

- citation 只解析 `[S1]`
- suite 定义必须 `SELECTED_COLLECTIONS` + `collectionKey`/`externalId`
- compare 只接受同 suite version；环境漂移单独标记
- semantic evaluator 按 Spring AI 1.1.4 API 适配；类或 ChatClient 缺失返回 `DISABLED`
- suite worker 使用 owner 当前 API Key ACL，不再把无 HTTP 请求当成 unrestricted

## 4. 脚本

- `scripts/verify-retrieval-diagnostics.sh`
- `scripts/verify-retrieval-filters.sh`
- `scripts/verify-embedding-operations.sh`
- `scripts/verify-managed-quality.sh`
- `scripts/verify-no-pessimistic-locks.sh`
- `scripts/verify-next-high-value-features.sh`

## 5. 收敛审查

最终 review 发生代码修复后，已重新通过专项与完整硬门禁，并按三个互不重叠的固定范围
完成连续三轮只读审查，最终结果为 **3/3 CLEAN**。审查期间没有修改实现代码、测试、
配置或文档内容；仅在三轮完成后更新本进度记录。

### 固定范围与结果

1. **事务、并发、CAS/lease、数据一致性**：无问题。
2. **检索、API 契约、ACL、安全与错误语义**：无问题。
3. **WebUI、双语文档、验证脚本、发布门禁**：无问题。WebUI 路由与 API
   Controller 映射、Mock Playwright 断言、Flyway V1–V39、禁悲观锁静态门禁和
   release 聚合入口已交叉核对。

本轮收敛规则：只处理影响正确性、成本/安全、兼容性或数据一致性的缺陷；未发现此类
缺陷，因此没有回头修改验收测试或进行零碎式优化。

### 非阻断剩余项

- `docs/quality-defaults*` 仍只覆盖 prod 检索质量默认（rerank/rewrite），未展开本批 feature flag；正式配置文档已覆盖。
- Controller MockMvc 对 suite GET 只验证路由/404；owner 隔离由 service + PostgreSQL 测试承担。
- OpenAPI `REQUIRED_PATH_SUFFIXES` 尚未列入本批新路径；`POST /evaluation/semantic` 仅有 service 测试。

## 6. 当前停止点

实现、V39、静态禁锁门禁、专项 PostgreSQL 回归、WebUI 验证、文档门禁和连续三轮
固定范围只读审查均已完成，当前实现达到本任务的收敛交付条件。下一步是执行最终
工作区检查、合并 `origin/main`、提交全部工作区修改并推送；如合并远端后触及本任务
文件，将只对受影响的硬门禁进行必要重跑。
