# Token 用量账本与成本估算实施规划

> **状态**：规划冻结候选；规划连续审查结果记录在
> [实施进度](2026-08-23_TOKEN_USAGE_LEDGER_PROGRESS.md)
>
> **规划日期**：2026-08-23
>
> **代码基线**：最新本地 `main` / `origin/main` @ `7dc7ab9d`，Spring Boot `3.5.16`，
> Spring AI `1.1.8`，Java `21`，Flyway V1–V48
>
> **实施分支**：`codex/token-usage-ledger-20260823`
>
> **长青上下文**：[项目上下文](../../project-context-zh-CN.md)、
> [REST API](../../rest-api-zh-CN.md)、[多模型外部配置](../../multi-model-external-config-zh-CN.md)、
> [规划、实施与验收工作流](../../delivery-workflow-zh-CN.md)

本文件是本轮实施的单一恢复入口。它冻结问题、范围、默认决策、数据和 HTTP 契约、事务
边界、实施顺序、一次性验收矩阵、发布和回滚边界。实施中不应再次临场决定“哪些请求记账”
或“重放是否重复收费”。

## 1. 执行摘要

当前项目已经具备：

- Chat 响应和 `rag_chat_history.metadata` 中的 provider usage；
- `ChatExecutionResult` 中的 prompt/completion/total token 字段；
- `MultiModelProperties.ModelCost` 中按百万 token 配置的 input/output/cache 价格；
- V48 提供的稳定受管 API Principal、幂等 Chat turn 和 PostgreSQL 主数据；
- 内存型 Micrometer 总量指标和已有 WebUI Metrics 页面。

但这些能力仍然缺少一个可审计的持久边界：历史只保存一份 JSON metadata，不能稳定按
Principal、模型、日期聚合；重启后内存指标丢失；幂等 replay 没有独立的“已计费”事实；
当前共享 quota 只限制请求数，不能为容量、成本或外部调用方提供用量证据。

本轮新增 **完成 turn 用量账本**：

```text
successful coordinated Chat commit
  -> rag_chat_history
  -> rag_chat_usage_event       (同一事务、history_id 唯一)
  -> principal/model/day aggregates
  -> read-only usage API
  -> WebUI Metrics 用量区
```

账本只记录已完成且已提交的 Chat turn。失败、取消、超时、被拒绝和未完成的 stream 不产生
用量事件；同一个幂等 turn 的 replay 只返回已保存响应，不再次写账。估算成本严格来自
模型配置和 provider 返回的 token usage，不把缺失 usage 猜成真实 token，也不把估算值描述
成供应商最终账单。

## 2. 候选比较与本轮选择

| 候选 | 当前价值 | 实施风险 | 结论 |
|---|---|---|---|
| Token 用量账本与成本估算 | 补齐 V48 后的成本可观测性、审计、容量规划和 replay 计费边界 | 需要贯穿 Chat 事务、迁移、查询 API 和 WebUI，但已有 usage/principal/metrics 基础 | **本轮实施** |
| OAuth/OIDC 与租户层级 | 适合公网身份 federation | 需要 issuer/JWKS、tenant ACL、token 生命周期和部署安全契约，不能在本轮低风险完成 | 独立规划 |
| `EACH_COLLECTION` 召回覆盖 | 对明确的多知识库覆盖需求有价值 | 需要 bounded fan-out、fusion/rerank、质量 goldenset；当前 TODO 已定义为非紧急 backlog | 延后 |
| 多 embedding profile 路由 | 支持不同知识库使用不同向量空间 | 触及写入、检索、job、readiness 和 repair 全链路 | 独立规划 |

本轮不写入任何外部 Client、行业领域或特定业务含义。稳定 Principal 是当前项目已有的
通用身份模型；账本以它作为唯一授权和聚合边界。

## 3. 已核对的当前事实

### 3.1 Chat 提交边界

- 生产 Chat 经 `ChatExecutionService` 执行，由 `ChatSessionCoordinator.commit(...)` 提交
  `rag_chat_history`、Spring AI JDBC Memory 和 session lease。
- 带 `Idempotency-Key` 的请求经 `ChatTurnOperationService`，最终由
  `ChatSessionCoordinator.commitOperation(...)` 以 operation CAS、history 和 Memory 同事务提交。
- `RagChatHistoryRepository.saveDurable(...)` 会返回已持久化的 history entity，可作为账本的
  稳定幂等键；不需要把原始消息或凭据复制到账本。
- 生产 Chat 的 `ChatExecutionResult` 已有 `resolvedModel`、`mode` 和 `usage`；旧兼容
  `RagChatService.save(...)` 不属于本轮记账路径，避免给 legacy/null owner 伪造 Principal。

### 3.2 Identity、认证和 replay

- `ChatPrincipal.id()` 使用 `root:environment-root`、`db:{stablePrincipalId}` 或兼容
  `legacy:static` 命名；数据库 API credential 轮换不改变稳定 Principal。
- `ChatTurnOperation` 的成功 replay 从持久化 response snapshot 返回，不再次执行模型，不再
  进入 `commitOperation`。
- 账本查询必须沿用当前请求认证 Principal；只有 environment root 或 ADMIN Principal
  可以通过显式 `principalId` 查询其他 Principal，普通调用方永远只能读取自己。

### 3.3 Usage 与 pricing

- Chat usage map 可能包含 `promptTokens`、`completionTokens`、`totalTokens`，也可能因
  provider 没有返回 usage 而为空。
- `ModelCost` 的数值语义是配置文件声明的“每 1,000,000 token 成本单位”；当前配置没有
 统一货币字段，因此本轮 API 将其明确命名为 `configuredCost`，并返回
  `costUnit=CONFIGURED_MODEL_COST`，不擅自声称 USD 或任何供应商结算货币。
- 现有 `cacheRead/cacheWrite` 配置先不进入估算公式，因为当前 Chat usage 契约没有稳定的
  cache token 字段；账本必须把该缺口表示为普通 input/output 估算，而不是猜测。

### 3.4 数据与并发

- 当前迁移为 V48；并发规则禁止 `FOR UPDATE`、`SKIP LOCKED`、JPA 悲观锁和 advisory lock。
- V49 可以新增表和索引，不改写已执行迁移；账本写入使用同一事务和唯一约束，读取使用
  SQL 聚合与有界日期范围。
- 账本不包含用户消息、答案、来源正文、query、API key、hash 或 access token；查询响应只
  返回聚合数字和有限的 model/day 维度。

## 4. 目标与非目标

### 4.1 目标

1. 新增 V49 `rag_chat_usage_event`，以一个完成的 business history 为一条不可变 usage event；
   `history_id` 是唯一的幂等引用，不设置外键，避免既有历史 TTL/会话清理被账本阻断。
2. 在 native Chat 与 OpenAI-compatible Chat 的协调提交中同事务写入账本。
3. 记录稳定 Principal、history/turn identity、session、mode、resolved model、token usage、
   usage 可用性、配置成本估算及其可用性。
4. 提供按日期、Principal、模型和 UTC 日聚合的只读 HTTP API。
5. 让 WebUI Metrics 页面展示认证主体的 30 日用量；普通 Principal 看到自身数据，
   root/ADMIN 默认看到全局数据。root/ADMIN 可以在 API 级别切换 Principal，不在本轮
   做复杂的管理筛选控件。
6. 通过 PostgreSQL 集成测试证明 replay 不重复、失败事务不留下孤立账本、ACL 不越权。
7. 通过真实 provider smoke 证明实际 Chat 完成后能看到持久账本；若 provider 不返回 usage，
   仍证明事件存在且 `usageAvailable=false`，不把缺失信息当成功能失败。

### 4.2 非目标

- 不执行 token/cost hard limit，不改变 V48 requests-per-minute quota。
- 不生成发票、余额、充值、供应商最终账单或财务结算。
- 不引入 OAuth/OIDC、租户层级、Redis、消息队列或异步最终一致性账本。
- 不把失败、取消、超时、重试中间 attempt、工具单独结果或 embedding 调用写入本轮 Chat
  usage event；这些属于后续独立用量模型。
- 不猜测 provider 缺失的 token，不从字符串长度反推真实账单 token。
- 不加入原始消息、答案、来源文本、query 或凭据字段。
- 不允许普通数据库 API key 查询其他 Principal，也不在 WebUI URL/localStorage 中保存
  Principal ID 或用量数据。

## 5. 冻结的数据模型与契约

### 5.1 V49 表

新增 `rag_chat_usage_event`：

| 字段 | 类型/约束 | 语义 |
|---|---|---|
| `id` | `BIGSERIAL PK` | 账本事件 ID |
| `owner_principal_id` | `VARCHAR(128) NOT NULL` | 稳定 Principal |
| `history_id` | `BIGINT NOT NULL UNIQUE` | 对完成 history 的唯一幂等引用；不设 FK，允许历史清理后账本继续保留 |
| `turn_id` | `UUID NULL` | 幂等 Chat turn；旧/非 keyed turn 可为空 |
| `session_id` | `VARCHAR(255) NOT NULL` | 聚合和审计维度，不是授权依据 |
| `model_ref` | `VARCHAR(255) NOT NULL` | resolved model，缺失时固定 `UNKNOWN` |
| `mode` | `VARCHAR(32) NOT NULL` | `PLAIN`、`KNOWLEDGE` 或 `AGENT` |
| `prompt_tokens` | `BIGINT NOT NULL CHECK >= 0` | provider 返回值，缺失为 0 |
| `completion_tokens` | `BIGINT NOT NULL CHECK >= 0` | provider 返回值，缺失为 0 |
| `total_tokens` | `BIGINT NOT NULL CHECK >= 0` | provider total；缺失时为 prompt+completion |
| `usage_available` | `BOOLEAN NOT NULL` | 至少存在一个 provider usage 字段 |
| `estimated_cost` | `NUMERIC(20,8) NOT NULL CHECK >= 0` | 配置成本估算，无 pricing 时为 0 |
| `cost_available` | `BOOLEAN NOT NULL` | resolved model 有有效 `ModelCost` |
| `created_at` | `TIMESTAMPTZ NOT NULL` | 事件提交时间，数据库生成 |

索引为 `(owner_principal_id, created_at)`、`(owner_principal_id, model_ref, created_at)`；
不为 session 或 message 建立无界索引。V49 必须保留 V48 的明文 secret 禁写和现有检查。
新增 `fn_rag_chat_usage_event_immutable()` 与
`trg_rag_chat_usage_event_immutable`，拒绝对账本事件执行 `UPDATE` 或 `DELETE`，使用稳定
约束错误（SQLSTATE `23514`）；账本只允许通过成功 Chat commit 插入。

`rag_chat_usage_event` 是用量事实表，不承载聊天正文，也不拥有历史表的生命周期。现有
`rag_chat_history` 会被 TTL、按会话删除和旧数据维护路径硬删除；V49 不得通过 FK 阻止这些
操作。历史删除后，`history_id` 仅作为已脱敏的审计关联标识，事件仍参与用量聚合。账本本身
本轮不提供删除接口；若未来需要独立的用量保留策略，应新增明确的账本 retention policy，
不能借用聊天历史的清理副作用。

### 5.2 Usage normalization 与 cost 公式

- 从 usage map 读取 Number；只有有限、非负、可精确归一为整数且不超过
  `Long.MAX_VALUE` 的值才算合法 token。非 Number、负数、小数、NaN、Infinity 和溢出值
  视为缺失，不能让非法 provider metadata 破坏提交。
- `usage_available=true` 只要 prompt/completion/total 中至少一个是合法 token。
- 缺失 prompt 或 completion 单项写 0；缺失 total 写 prompt+completion；provider 给出的
  total 即使包含 cache/round 额外 token 也原样保留。
- 有 `ModelCost` 时：

  ```text
  configuredCost =
      promptTokens     * inputCost  / 1,000,000
    + completionTokens * outputCost / 1,000,000
  ```

- `cacheRead/cacheWrite` 不参与本轮公式；cost 字段为非负有限值时
  `cost_available=true`，四项为 0 仍表示“已配置的免费/未知价模型”，而不是缺失。
- 没有匹配模型或成本字段不可用时，`estimated_cost=0`、`cost_available=false`。当前
  `ModelCost` 配置对象会把负数归一化为 0，因此实现按“配置对象中的有效成本”计算，不
  试图从已归一化对象反推原始非法输入；非有限值仍必须视为不可用。
- 成本使用 `BigDecimal` 计算并以 `HALF_UP` 归一到 8 位小数；若成本计算结果不能表示在
  `NUMERIC(20,8)` 的非负范围内，则只标记 `cost_available=false` 并写入 0，不让异常
  provider usage 阻断 history 提交。
- 所有金额使用 `BigDecimal`，数据库写入 scale 8，API 不转成浮点数；响应字段名使用
  `configuredCost`，避免把估算值误解成结算金额。

### 5.3 Usage API

`GET /api/v1/rag/usage`

查询参数：

| 参数 | 默认 | 约束 |
|---|---|---|
| `from` | UTC 今天往前 29 天 | `YYYY-MM-DD` |
| `to` | UTC 今天 | 必须不早于 `from`，含首尾日期 |
| `principalId` | 普通 Principal 为当前认证 Principal；root/ADMIN 省略时为全局聚合 | 仅 root/ADMIN 可显式指定某个 Principal |

日期范围最多 366 个 UTC 日；超出返回 400。响应固定为：

`principalId` 使用账本 owner identity，即 `ChatPrincipal.id()` 的值（数据库受管调用方形如
`db:<stablePrincipalId>`，environment root 为 `root:environment-root`）；它不是 raw API key、
credential ID 或 `/auth/me` 返回的未加前缀数据库 principal ID。

```json
{
  "principalId": "db:principal-1",
  "from": "2026-08-01",
  "to": "2026-08-23",
  "requestCount": 12,
  "promptTokens": 1200,
  "completionTokens": 800,
  "totalTokens": 2000,
  "usageEventsWithUsage": 11,
  "usageEventsWithoutUsage": 1,
  "configuredCost": 0.03450000,
  "costEventsWithPricing": 10,
  "costEventsWithoutPricing": 2,
  "costUnit": "CONFIGURED_MODEL_COST",
  "byModel": [
    {
      "modelRef": "openrouter/example",
      "requestCount": 12,
      "totalTokens": 2000,
      "configuredCost": 0.03450000,
      "usageEventsWithoutUsage": 1,
      "costEventsWithoutPricing": 2
    }
  ],
  "byDay": [
    {
      "day": "2026-08-23",
      "requestCount": 3,
      "totalTokens": 600,
      "configuredCost": 0.01000000
    }
  ]
}
```

root/ADMIN 省略 `principalId` 时返回全局聚合，响应中的 `principalId` 为 `null`；显式传入
`principalId` 时查询指定主体。普通 Principal 省略参数时只能查询自己，显式传入其他主体
返回 403。全局响应仍只按模型和日期聚合，不返回每个 Principal 的明细，避免 API 变成
身份枚举接口。默认 API 只读，不支持删除或修改账本。

## 6. 事务、错误和兼容性

### 6.1 成功提交

`ChatSessionCoordinator.commit` 和 `commitOperation` 都执行：

```text
consume session lease / operation CAS
  -> saveDurable history
  -> insert usage event(history_id unique)
  -> update shared Memory
  -> commit
```

任何账本写入错误都回滚 history、Memory、operation CAS 和 lease；不能返回“回答成功但账本
缺失”的半成功结果。账本服务缺失属于生产装配错误，必须通过启动/Bean registration test
暴露，不在正式生产路径静默退化。

### 6.2 Replay、失败与并发

- 成功 replay 不执行 `ChatExecutionService`，因此不产生新的 history 或 usage event。
- 同一个 history_id 的重复写入由唯一约束拒绝；服务在同一事务中只调用一次。
- 两个相同 Idempotency-Key 的竞争请求最终只能完成一个 operation/history/event。
- 失败、取消、超时、预算拒绝和未开始模型调用不写 event。
- 账本查询读不到其他 Principal；principal rotation 继续沿用稳定 owner。

### 6.3 兼容性与回滚

- `ChatResponse.usage`、OpenAI `usage` 和 SSE `done` 契约不改变，只增加独立 GET API。
- `rag_chat_history.metadata` 继续保留，账本是规范化查询投影，不删除旧 JSON。
- V49 只新增表；旧版本应用可以忽略该表，但旧应用不会产生账本事件。发布时先迁移 schema
  再启用新应用；回滚应用不删除 V49。
- 成本估算可由模型配置调整，但历史事件保存写入时的估算值，不随配置变更回算。

## 7. 实施切片

### Phase 0：规划、归档与 characterization

1. 将已交付 V48 plan/progress 移入 `docs/drafts/archive/2026-08-23_*`；
2. 新建本文件和进度账本；
3. 固定当前 `main@7dc7ab9d`、V48、Chat coordinator、usage map、ModelCost 和 WebUI
   Metrics 的事实；
4. 先补 usage normalization/cost calculator 的纯测试，不触碰提交事务。

### Phase 1：V49 与账本服务

新增：

- `V49__add_chat_usage_ledger.sql`
- `ChatUsageLedgerRepository`
- `ChatUsageLedgerService`
- `ChatUsageCostCalculator`
- API DTO：`ChatUsageResponse`、`ChatUsageByModel`、`ChatUsageByDay`

Repository 使用 JdbcTemplate 聚合；写入只接受已保存的 `RagChatHistory` 和
`ChatExecutionResult`，不暴露 raw message。

### Phase 2：Chat commit 集成

修改 `ChatSessionCoordinator`，让普通 coordinated commit 和 idempotent commitOperation
共用 ledger service。增加组件注册测试和 coordinator unit test，确认测试替身缺失时不会
掩盖生产 bean。

### Phase 3：HTTP/OpenAPI

新增 `ChatUsageController`，复用 `ChatPrincipal.from(request)` 和当前 ADMIN/root 语义；
更新双语 `rest-api`、`project-context`、`testing-guide`、OpenAPI contract fixture 和
错误/日期参数说明。不要把 usage API 放入管理 API Key 路径，也不开放写操作。

### Phase 4：WebUI

扩展 `metricsApi` 和 `Metrics.tsx`：

- 普通 Principal 默认查询自身最近 30 个 UTC 日，root/ADMIN 默认查询全局最近 30 个 UTC 日；
- 展示请求数、prompt/completion/total token、configured cost、缺失 usage/pricing 计数；
- 展示按模型的紧凑表格；
- API 失败和空数据有可访问的 `role=alert` / empty state；
- 不把 principalId、原始事件、凭据或日期筛选草稿写入 URL/localStorage；
- 不增加截图验收，沿用 Metrics 页面 Mock 配置和 DOM 断言。

### Phase 5：脚本、长青文档和门禁

- 在 `scripts/verify-chat-capability.sh` 或独立专项脚本中加入 V49 PostgreSQL 矩阵；
- 增加真实 provider usage ledger smoke，使用隔离端口和可处置数据库；
- 把已实施事实同步到双语 `project-context`、`rest-api`、`multi-model-external-config`
  或新的 usage 专题；
- 完成文档、无悲观锁和 whitespace 门禁。

## 8. 一次性验收矩阵

### 8.1 快速后端测试

- `ChatUsageCostCalculatorTest`：正常、缺失字段、负数/小数/NaN/Infinity/溢出字段、总量
  回退、BigDecimal scale/舍入、免费模型和缺失 pricing；
- `ChatUsageLedgerServiceTest`：稳定 model fallback、usage normalization、principal/history
  identity、无 raw payload；
- `ChatUsageLedgerRepositoryTest`：mapper 和聚合空结果；
- `ChatSessionCoordinatorTest`：普通 commit 和 commitOperation 都调用一次 ledger；
  ledger 异常向外传播；
- `ChatUsageControllerTest`：默认日期、366 日边界、非法日期、当前 Principal、root/ADMIN
  默认全局聚合、root/ADMIN 查询其他 Principal、普通 key 403；
- `OpenAiCompatibilityControllerWebTest`：OpenAI response usage 保持不变。

### 8.2 PostgreSQL 集成

新增固定范围 `ChatUsageLedgerPostgresIntegrationTest`，从空库执行 V1–V49，至少覆盖：

1. V49 迁移、索引和非负约束；
2. complete history 写入一条 event，history_id 唯一重放写失败；
3. UPDATE/DELETE 账本事件被 append-only trigger 拒绝；
4. principal/session/model/date 聚合和 UTC 日边界；
5. 缺失 usage 与缺失 pricing 的事件仍可查询，计数准确；
6. owner ACL：Principal A 不能读取 B，root/ADMIN 可读取指定 B；
7. coordinator commit 失败时 history、event、Memory/operation 均不留下半写；
8. 同一 Idempotency-Key 的首请求和 replay 只有一条 history/event；
9. rotation 前后按稳定 principal 连续聚合；
10. TTL/会话删除 history 后，usage event 不被 FK 阻断且仍可聚合；
11. 账本查询最大 366 日，超范围由 HTTP 层拒绝；
12. V48 明文 credential 禁写规则继续通过。

### 8.3 后端与服务门槛

```bash
mvn clean compile test-compile
mvn test
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

必须使用 `postgresql` profile 启动隔离服务并验证 readiness；账本 API 的 HTTP 集成必须
覆盖 JSON、认证和错误响应。

### 8.4 前端

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npm run check:alignment
```

新增/扩展 Mock Playwright：

- `/metrics` 与 `/usage` 请求均按预期发生；
- 请求返回的 summary、model breakdown 在 DOM 可见；
- API error 以可访问 alert 显示；
- 不生成截图，不依赖 canvas pixel 或视觉判断。

### 8.5 真实 provider 与全栈

Mock 和 PostgreSQL 先通过后，使用 `.env` 中真实配置、隔离端口和一次性数据库：

1. `scripts/dev.sh` 启动后端/前端；
2. 执行一个真实 native Chat 和一个真实 OpenAI-compatible Chat，优先使用现有 provider
   smoke 的同一批次，持续观察日志；
3. 使用 response usage 和 `GET /api/v1/rag/usage` 对比 request/event count；若 provider
   不返回 usage，断言 event 存在且 `usageEventsWithoutUsage` 增加，不能猜 token；
4. 重放同一个 Idempotency-Key，确认 event/requestCount 不增加；
5. 轮换 credential 后继续查询稳定 Principal 的累计账本；
6. 真实 WebUI Metrics 页面只用 DOM、网络响应、API JSON 和数据库只读查询确认用量可见；
7. `finally` 只清理允许删除的 probe history/collection，账本 event 不做原地删除；停止
   dev.sh、释放端口并销毁一次性 PostgreSQL 数据库，使 probe 账本随测试数据库整体消失。

### 8.6 交付前固定顺序

实现完成并跟进最新 `origin/main` 后：

```text
记录 merge 后基线
  -> V1–V49 PostgreSQL ledger matrix
  -> mvn clean compile test-compile + full Maven
  -> WebUI tsc/test/build/alignment + Mock Playwright
  -> 隔离 dev.sh + real frontend/backend Playwright + ledger JSON
  -> 真实 LLM usage/replay/rotation smoke
  -> docs/no-locks/diff checks
  -> 连续三轮限定范围实现审查
  -> feature merge main
  -> push main + status clean
```

任何实质修复都重置实现审查计数，并重跑受影响门槛；不能沿用修复前证据。

## 9. 规划审查与实现审查范围

规划审查固定为：

1. 需求闭环、自包含性、候选选择、目标/非目标和默认值；
2. V49 schema、事务、ACL、replay、usage/cost normalization 和兼容可行性；
3. API、WebUI、真实 provider、测试矩阵、回滚和 Git 交付顺序。

基本门槛通过后，实现审查固定为：

1. 数据一致性、唯一键、事务 rollback、Principal ACL 和 replay；
2. token/cost 语义、HTTP/JSON/WebUI 契约、兼容性与无敏感数据；
3. 集成证据、真实启动、文档、脚本、发布/回滚和工作区交付。

只修复影响正确性、数据一致性、成本安全、兼容性或验收证据的实质问题；风格和可选增强
不触发循环。

## 10. 完成定义

只有以下条件全部满足才算完成：

1. V49 从空库可迁移，账本事件和聚合由 PostgreSQL 集成测试覆盖；
2. native/OpenAI Chat 的 coordinated commit 都会写账本，失败和 replay 不重复；
3. 稳定 Principal ACL、rotation continuity、root/ADMIN 查询边界有自动化证据；
4. usage 缺失、pricing 缺失和 provider total 不完整时行为符合冻结规则；
5. `GET /api/v1/rag/usage` 契约、日期边界、错误和 OpenAPI 文档完成；
6. WebUI Metrics 展示 summary/model breakdown，tsc/test/build/Mock Playwright 全通过；
7. `mvn clean compile test-compile`、全量 Maven、服务启动、无悲观锁和文档门禁通过；
8. 真实 provider smoke 完成，或明确记录 provider usage 缺口及已验证的降级语义；
9. 双语长青文档同步，旧 V48 规划/进度已归档；
10. 实现连续三轮无实质修改；
11. 跟进 `origin/main` 后完整复验，特性分支合入并推送 `main`，最终工作区状态已核对。
