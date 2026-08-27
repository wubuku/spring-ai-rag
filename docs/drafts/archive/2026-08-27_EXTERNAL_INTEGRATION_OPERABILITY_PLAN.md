# 外部业务接入合同完备性与数据面可运维性实施规划

> **状态**：实施与最终验收完成；已归档
>
> **规划日期**：2026-08-27
>
> **实施事实基线**：`main` / `origin/main` @ `0993b702`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway 已发布到 `V53`
>
> **实施分支**：`feat/external-integration-operability-20260827`
>
> **实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-external-integration-operability`
>
> 模型调用用量账本已经完整交付并合入 `main`，占用 `V53`、`usage` 包和 WebUI Metrics
> 页面。本规划继续使用独立的 `V54`、独立 endpoint/package，且不把数据面可观测性塞进
> `/usage` 或 Metrics 页面。

本文是当前项目内部的单语、自包含过程文档。输入是典型客户需求，但本文只描述通用 RAG
服务应提供的能力，不依赖任何特定业务系统的名称、领域模型、资源拓扑、私有协议或代码。
实施完成后，稳定事实再提升到双语长青文档，本文与届时创建的进度账本归档。

## 1. 执行摘要

当前外部业务接入能力已经跨过“能否安全接入”的门槛：稳定 Collection key、受管 API
principal、Collection ACL、`RAG_READ` / `RAG_WRITE`、幂等 provisioning、JSON Record
revision CAS、tombstone/恢复、异步 embedding、权威 Sync Run 及持久 item receipt 均已落地。

因此，不能继续把已完成的 P0/P1 重新包装成下一轮，也不应为某个调用方增加领域专用 API。
当前真实剩余差距是生产扩量后的**合同自发现**和**故障定位**：

1. `/integration-capabilities` 已声明主要 feature 和身份长度，但没有发布运行时实际生效的
   JSON Record batch、总 payload、检索结果、payload filter、Sync Run batch 和分页上限；
2. JSON Record 限制实际上由 `RagStructuredRecordProperties` 强制，但 Client 只能从文档或
   部署约定得知，配置改变后机器合同与运行时可能漂移；
3. 现有 `@Timed`、SLO 和 `RagMetricsService` 主要是进程内、端点级或 Chat 级指标；
   `RateLimitObservability` 有安全的低基数标签，但只能区分 backend/result/principal type；
4. 当前没有一个跨重启、可按稳定 principal、授权 Collection、有限 operation 和 HTTP 结果
   查询的数据面操作事实；发生 `403`、`409`、`429` 或部分 Collection 异常时，operator
   只能拼接日志、数据库状态和 Client 侧 receipt；
5. 直接把 principal ID 或 Collection key 放入 Prometheus/Micrometer 标签会制造高基数和
   敏感性风险，不能用这种方式补缺口。

本轮选择一个边界完整的批次：

- **A. 机器可读运行时限制补全**：向现有 capability 响应添加结构化限制，直接读取当前
  配置和共享常量，不改变协议 `1.0` 的已有字段或语义；
- **B. 隐私安全的数据面操作 rollup**：异步、fail-open 地持久化 UTC 小时聚合，提供
  principal 自助和 root/ADMIN 管理查询；
- **C. 低基数运行指标与统一验收**：只用固定枚举作为 meter 标签，并扩展现有外部业务接入
  门禁验证合同、授权、并发聚合、丢失可见性和敏感信息边界。

本轮不新增普通 JSON batch receipt。普通增量 batch 已可通过相同 revision 精确重放和单条
lookup 对账；需要权威全量快照与持久 item receipt 的场景已有支持 `JSON_RECORD` 的 Sync Run。
再造平行 ledger 会增加身份、retention 和恢复语义，而不能解决当前最主要的运维缺口。

## 2. 需求评估与当前状态

### 2.1 评估口径

本次不把需求文档、历史规划或会话记忆当作实现事实。判断顺序固定为：

1. `main@0993b702` 的代码、Flyway、配置绑定和自动化测试；
2. `docs/rest-api*`、`docs/business-client-integration*`、`docs/project-context*` 等双语长青文档；
3. 已归档规划只用于解释设计来源，不用于宣称当前状态；
4. 并行 worktree 的未提交代码只用于避免冲突，不计入当前已交付能力。

### 2.2 分级需求逐项结论

| 需求组 | 当前实现证据 | 结论 | 本轮动作 |
|---|---|---|---|
| P0：JSON Record、ACL、CAS、tombstone、恢复、ASYNC | `RagJsonRecordController`、`JsonRecordService`、`DocumentMutationService`、PostgreSQL 合同矩阵 | 已交付 | 只做回归，不重做 |
| P0：当前 principal 自省 | `/auth/me` 返回 type、role、capabilities、access mode、完整 allow-list，解析不完整时 `503` | 已交付 | 作为查询授权输入 |
| P0：受管 credential 生命周期 | V48 stable principal/versioned credential、一次性 secret、轮换、吊销、到期、共享 quota | 已交付 | 复用 stable principal；不另建身份 |
| P0：可锁定版本和合同门禁 | binding preflight、business-client readiness、release manifest、Git SHA 和 migration 证据 | 已交付 | 扩展现有门禁，不另建平行体系 |
| P1：operation-scoped capability | V49 与中央 `ApiCapabilityFilter` 强制 `RAG_READ` / `RAG_WRITE` | 已交付 | 新查询端点要求 `RAG_READ` |
| P1：职责分离凭据 | provisioning 可创建精确 READ_ONLY / READ_WRITE principal | 已交付 | 查询必须按当前职责 fail closed |
| P1：principal/Collection 创建幂等 | V50、V52 独立 owner-scoped ledger | 已交付 | 只做迁移兼容回归 |
| P2：机器可读 feature/limit | `/integration-capabilities` 已有 feature 和五个长度/数量字段 | **部分交付** | 补全实际运行限制 |
| P2：大规模同步 receipt/status | V42 Sync Run + V51 keyset item receipt，支持 `TEXT` 与 `JSON_RECORD` | 已交付 | 不为普通 batch 再造 receipt |
| P2：按 operation/Collection/principal 的观测 | 端点 timer、全局 SLO、低基数 rate-limit meter、Collection readiness 分散存在 | **部分交付** | 新增持久安全 rollup 与查询 |

### 2.3 已确认的限制漂移

`IntegrationCapabilitiesResponse.Limits` 当前只包含：

- `maxCollectionKeysPerPrincipal=100`；
- `collectionKeyMaxLength=128`；
- `sourceNamespaceMaxLength=128`；
- `externalIdMaxLength=255`；
- `sourceRevisionMaxLength=255`。

但运行时还会强制：

| 限制 | 当前来源 | 默认值 |
|---|---|---:|
| 单条 JSONB payload bytes | `RagStructuredRecordProperties.maxJsonbPayloadBytes` | 1 MiB |
| `retrievalText` chars | `maxRetrievalTextChars` | 10,000 |
| JSON Record batch items | `maxBatchSize` | 20 |
| JSON Record batch payload bytes | `maxBatchPayloadBytes` | 10 MiB |
| JSON Record search results | `maxSearchResults` | 20 |
| `payloadContains` bytes | `maxPayloadFilterBytes` | 16 KiB |
| `payloadContains` depth | `maxPayloadFilterDepth` | 8 |
| Sync Run batch items | DTO validation | 100 |
| Sync Run item receipt page | controller/service validation | 200 |
| Sync Run list page | service validation | 100 |

这些限制有的可配置、有的散落在 annotation/service 常量中。Client 不能仅通过现有机器合同
决定 batch 切分和分页预算，preflight 也无法证明部署实例是否满足调用方所需下限。

### 2.4 已确认的可观测性边界

- `@Timed` 为端点提供 Micrometer timer，但没有统一 HTTP status、stable principal 或
  Collection 维度；
- `ApiSloTrackerService` 使用进程内窗口，重启后丢失，且主要按 endpoint；
- `RagMetricsService` 的 request/token 指标主要由 Chat 路径更新，不代表 JSON Record 数据面；
- `RateLimitObservability` 正确地只使用固定 backend/result/principal-type 标签，但不能回答
  某个 stable principal 或 Collection 的历史命中；
- `RagApiPrincipal.lastUsedAt`、到期和吊销状态已存在，能够回答 credential 生命周期，但不能
  解释具体 operation 的成功、冲突或限流；
- Collection embedding readiness 和 embedding job 列表已能按 Collection 查询派生状态，
  不需要复制到新的 operation ledger；
- `RagAuditLog` 面向管理实体变更，schema、授权和 fail-open 语义均不适合承担高频 API
  request 观测，不能复用。

## 3. 候选功能价值排序

| 候选 | 客户价值 | 当前紧迫度 | 成本/风险 | 决策 |
|---|---:|---:|---:|---|
| 补全 runtime limits capability | 高 | 高 | 低 | **本轮 P0 切片** |
| stable principal/Collection/operation rollup | 很高 | 高 | 中 | **本轮主功能** |
| 低基数 result/status/operation meter | 高 | 高 | 低 | **与主功能一起交付** |
| credential 到期提醒 UI/通知 | 中 | 中 | 中 | 延后；已有 expiresAt/lastUsedAt，可由 operator 先监控 |
| 普通 JSON batch 持久 receipt | 中 | 低 | 高 | 延后；精确重放、lookup 和 Sync Run 已覆盖恢复路径 |
| OAuth/OIDC 与独立 tenant federation | 中 | 低 | 很高 | 独立规划；当前后端 credential 模式足够 |
| token/cost hard limit | 高 | 非本需求 | 很高 | 等并行 invocation ledger 完成后单独规划 |
| per-Collection 强制召回覆盖 | 不确定 | 低 | 高 | 无质量证据，不进入本轮 |
| 领域专用查询、事件 inbox 或业务权限模型 | 负价值 | 无 | 很高 | 明确拒绝，保持领域解耦 |

选择结果不是“把所有 P2 都实现”，而是优先补齐生产扩量时最难由 Client 自行弥补的两项：
运行限制自发现和服务侧故障定位。其余事项已有可靠替代路径，或需要独立产品决策。

## 4. 目标、非目标与成功标准

### 4.1 目标

1. `/integration-capabilities` additive 返回当前实例实际生效的结构化记录、Sync Run 和分页限制。
2. 可配置限制直接来自当前 `RagProperties`；固定限制由验证与 capability 共用常量，禁止复制
   magic number。
3. 记录外部接入核心 HTTP operation 的 request count、status code、latency 和授权
   Collection contribution。
4. stable database principal 使用 `principalId` 归因；非数据库身份只按固定 principal type
   聚合，不把任何 credential 值当身份。
5. PostgreSQL 保存 UTC 小时 rollup，跨实例、跨重启可查询；它是 best-effort 运维事实，
   不是计费、配额或审计证据。
6. 普通 principal 只能查询自己；restricted 的 Collection filter 必须是当前 allow-list 子集，
   unrestricted 只能按自己当前有权访问的 active Collection 过滤；root/ADMIN 可做管理查询。
7. 401/403/404/409/429/5xx 有稳定分类；未知/越权 Collection 不写入 collection rollup，
   防止存在性泄漏。
8. Micrometer 只使用固定 operation、status class、principal type、drop reason 标签。
9. recorder DB/queue 故障 fail open，不改变业务响应，不触发 mutation/provider retry。
10. 所有 query、retention、flush、fan-out 和 shutdown drain 都有界。
11. 现有 capability、auth、JSON Record、Sync Run、Chat 和 WebUI 契约保持兼容。

### 4.2 非目标

- 不记录 prompt、query、retrieval text、JSON payload、metadata、external ID、source revision、
  document ID、API Key、Authorization、错误正文或 response body；
- 不提供逐请求审计、完整 trace 查询或供应商级 telemetry；
- 不把 rollup 当作精确 billing、hard quota、SLA 赔付或安全审计来源；
- 不改变当前 RPM quota 决策，不用 rollup 参与请求拒绝；
- 不复制 embedding readiness/job 状态，不从 operation success 推断向量 READY；
- 不新增普通 JSON batch receipt、异步 mutation queue、webhook 或 callback；
- 不扩大 Sync Run、Collection ACL、operation capability 或 principal 的语义；
- 不修改 `/api/v1/rag/usage`、usage 包、V53 或 WebUI Metrics；
- 首轮不新增 WebUI 页面。该能力首先服务自动化 Client、operator API 和监控系统；
- 不引入 Kafka、Redis、ClickHouse、OpenTelemetry Collector 或新的外部基础设施；
- 不新增任何领域专用资源、scope 或权限判断。

### 4.3 业务成功标准

交付后，一个标准业务 Client 可以在启动时：

1. 发现实际 batch、payload、filter、search 和 pagination 上限；
2. 在发送业务流量前拒绝低于自身最低要求的部署实例；
3. 使用自己的 credential 查询最近窗口内自身各 operation 的 2xx、403、409、429、5xx
   数量和延迟摘要；
4. 按当前授权 Collection 缩小查询，且无法观察其他 principal/Collection；
5. 在服务重启或多实例切换后继续查询保留窗口内的汇总；
6. 识别 recorder 是否暂停、当前实例是否丢弃观测事件以及数据的 best-effort 边界；
7. 继续使用既有 revision replay、lookup、Sync Run receipt 和 embedding readiness 完成恢复，
   不把 operation rollup 误当 mutation receipt。

## 5. 冻结决策

| 事项 | 冻结默认 | 理由与可逆边界 |
|---|---|---|
| 实施起点 | 已包含 V53 用量账本的 `origin/main@0993b702` | migration、Metrics 和共享文档冲突已经在基线处收敛 |
| migration | `V54__add_api_operation_rollups.sql` | V53 已发布；不得改写或复用已发布版本 |
| capability protocol | 保持 `spring-ai-rag-integration` `1.0` | 新字段 additive；现有 preflight 精确要求 `1.0`，不能无收益破坏 |
| limit contract | 在现有 `limits` 下增加 nested groups | 旧 Client 忽略未知字段；旧五字段保持原值和位置 |
| observation API | `GET /api/v1/rag/integration-observability` | 与模型 `/usage` 和旧 `/metrics` 解耦 |
| 时间粒度 | 持久化 UTC 小时；查询可按 HOUR/DAY 聚合 | 控制行数；不承诺分钟级 billing 精度 |
| 保留期 | 默认 90 天，允许 7–730 天 | 运维定位足够；可通过配置调整 |
| 查询窗口 | 默认 24 小时，最大 31 天；`from` inclusive、`to` exclusive | 保证响应和 SQL 有界 |
| recorder | 单个有界队列，异步 batch flush | 不给业务请求增加同步 DB round-trip |
| queue | 默认 10,000 个 request observation | 以请求为单位，不按 Collection fan-out 入队 |
| flush | 1 秒或 500 request，先到者触发 | 兼顾时效和写放大；配置范围受限 |
| shutdown drain | 最多 5 秒 | 尽力落账但不无限阻塞停机 |
| failure | fail open + fixed-reason dropped counter | 可观测性失败不能改变业务事实 |
| principal | DB principal 保存 stable `principalId`；其他类型保存固定 synthetic ref | 禁止使用 credential ID、raw key/hash 或 IP 代替 stable principal |
| Collection | 只记录已通过当前 ACL 并成功解析的内部 Collection ID | 未知/越权 key 不得落库或通过响应泄漏 |
| status | 保存最终 HTTP status code，并派生固定 status class | 能精确回答 401/403/404/409/429；标签仍低基数 |
| latency | wall response latency + 固定 histogram bucket | 支持 count/avg/max/近似分位，不保存单请求时序 |
| multi-Collection | request total 只计一次；另写每个授权 Collection contribution | 禁止把 contribution 相加冒充 request total |
| auth | NORMAL 仅 self；restricted 按 allow-list，unrestricted 按当前全局 Collection 权限；root/DB ADMIN 可指定 principal；auth-disabled 提供本地全局视图；legacy/static 拒绝 | 与现有授权兼容且不把 legacy credential 提升为管理身份 |
| WebUI | 本轮无 UI | 避免与并行 Metrics 改动撞车；API 先满足真实使用者 |

## 6. 机器可读合同设计

### 6.1 Additive 响应

现有 `limits` 五个字段保持不变，增加：

```json
{
  "features": {
    "optional": {
      "documentSyncRuns": true,
      "documentSyncRunItemReceipts": true,
      "integrationObservability": true
    }
  },
  "limits": {
    "maxCollectionKeysPerPrincipal": 100,
    "collectionKeyMaxLength": 128,
    "sourceNamespaceMaxLength": 128,
    "externalIdMaxLength": 255,
    "sourceRevisionMaxLength": 255,
    "structuredRecords": {
      "maxJsonbPayloadBytes": 1048576,
      "maxRetrievalTextChars": 10000,
      "maxBatchItems": 20,
      "maxBatchPayloadBytes": 10485760,
      "maxSearchResults": 20,
      "maxPayloadFilterBytes": 16384,
      "maxPayloadFilterDepth": 8
    },
    "syncRuns": {
      "maxBatchItems": 100,
      "maxItemReceiptPageItems": 200,
      "maxRunListPageItems": 100
    },
    "observability": {
      "retentionDays": 90,
      "maxQueryRangeDays": 31,
      "maxCollectionBreakdownItems": 100
    }
  }
}
```

约束：

- `structuredRecords` 始终存在并反映当前配置；
- `features.optional.integrationObservability` 反映记录与查询是否可用；
- Sync Run 关闭时仍发布固定 limits，便于 Client 预检配置，但 Client 仍必须检查
  `features.optional.documentSyncRuns` 和 `documentSyncRunItemReceipts`；
- observability 关闭时仍发布其配置上限，但 feature 为 `false`，历史保留且查询端点返回 `503`；
- 不返回数据库、provider、线程池、队列当前深度、principal ID 或 Collection key；
- `IntegrationCapabilitiesResponse.Limits` 和 `OptionalFeatures` 保留旧参数构造入口，避免 Java
  consumer 源码断裂；
- OpenAPI schema 和中英文 REST 示例必须同步。

### 6.2 单一事实来源

- JSON Record 动态限制直接来自 `RagStructuredRecordProperties`；
- Sync Run 固定上限提升为公共 compile-time constants，同时用于 Jakarta validation、service
  guard、OpenAPI annotation 和 capability projection；
- observability 限制来自新配置对象，经启动校验后投影；
- 测试必须改变非默认配置并证明 capability 响应与真正的拒绝边界一致，不能只断言默认 JSON。

### 6.3 Preflight 兼容

`business-client-binding-preflight.sh` 继续精确要求 protocol `1.0`，但增加可选最低要求：

- `RAG_BINDING_MIN_JSON_BATCH_ITEMS`；
- `RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES`；
- `RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY=true|false`。

默认不设置时保持当前行为。设置后若字段缺失、类型错误或低于最低值，preflight fail closed。
报告只保存数值和 feature 结果，不保存 URL、credential、Collection key 或业务数据。

## 7. 数据面操作模型

### 7.1 首版 operation 枚举

只覆盖外部业务接入的稳定面，不试图观测全部 controller：

| operation | 路由 |
|---|---|
| `INTEGRATION_CAPABILITIES` | `GET /integration-capabilities` |
| `CURRENT_PRINCIPAL` | `GET /auth/me` |
| `COLLECTION_LOOKUP` | `GET /collections/by-key` |
| `COLLECTION_READINESS` | `GET /collections/embedding-readiness` |
| `JSON_RECORD_UPSERT` | `POST /json-records/upsert` |
| `JSON_RECORD_BATCH_UPSERT` | `POST /json-records/batch-upsert` |
| `JSON_RECORD_SEARCH` | `POST /json-records/search` |
| `JSON_RECORD_LOOKUP` | `GET /json-records/by-external-id` |
| `JSON_RECORD_TOMBSTONE` | `DELETE /json-records/by-external-id` |
| `SYNC_RUN_BEGIN` | `POST /document-sync-runs` |
| `SYNC_RUN_BATCH_UPSERT` | `POST /document-sync-runs/{runId}/batch-upsert` |
| `SYNC_RUN_PREVIEW` | `POST /document-sync-runs/{runId}/preview-missing` |
| `SYNC_RUN_COMPLETE` | `POST /document-sync-runs/{runId}/complete` |
| `SYNC_RUN_ABORT` | `POST /document-sync-runs/{runId}/abort` |
| `SYNC_RUN_GET` | `GET /document-sync-runs/{runId}` |
| `SYNC_RUN_ITEMS` | `GET /document-sync-runs/{runId}/items` |
| `SYNC_RUN_LIST` | `GET /document-sync-runs` |

分类器使用 method + normalized route template，不保存原始 URI 或 query string。新增稳定路由时
必须显式扩枚举与测试；未知路由 no-op，不能退化为带 path 的动态标签。
动态 `{runId}` 使用 Spring `PathPatternParser` 与预编译固定 pattern 匹配，不用字符串切割、
正则提取业务 ID 或 controller method name 猜测 operation。

### 7.2 Request observation

每个匹配请求在内存中最多产生一个：

```text
ObservedRequest
  bucketStartUtc
  principalType
  principalRef
  operation
  httpStatus
  durationMs
  authorizedCollectionIds (0..100, unique, sorted)
```

不含 request/response body、headers、URI、trace ID、IP、session、external identity 或 error
message。

过滤器以 `FilterRegistrationBean` order `-20` 注册到 `/api/*`，在现有 order `-10` 认证之前
包裹调用链，以便记录 401/403/429；在 chain 返回后读取认证 filter 留下的不可变 principal
snapshot 和最终 HTTP status。若异常逃出内部 filter/MVC 且尚未形成响应，finally 路径按
`500` 记录后原样抛出，不能吞异常或自行改写错误信封。只有数据库 principal 使用 stable
`principalId`；其他身份映射到固定值：`ENVIRONMENT_ROOT`、`LEGACY_STATIC`、
`LOCAL_AUTH_DISABLED`、`ANONYMOUS`。任何不能证明是 stable principal 的 request attribute
都不持久化。

### 7.3 Collection scope 捕获

HTTP filter 不解析 JSON body。授权 Collection ID 由服务在完成“存在性解析 + 当前 ACL
校验”后，通过 request attribute accessor 追加：

- HTTP request 内 accessor 维护最多 100 个 unique `Long` ID；
- 非 HTTP 调用、后台任务和单元测试没有 request context 时安全 no-op；
- batch/multi-Collection 请求只在一个 request observation 中携带 ID 列表；
- 未知或 ACL 拒绝发生在确认前，不记录请求提供的 key；
- Collection 被软删除或 policy 移除后，查询授权按当前事实重新判断，不能借历史 rollup
  发现已不可见 Collection。

首版仅在上述 operation 的现有 Collection resolution 点接入。禁止把观测逻辑放入
repository 的通用 `findById`，避免内部调用和后台任务被误计为 HTTP traffic。

### 7.4 状态与延迟

数据库保存精确 `http_status`，查询时映射：

| class | 状态 |
|---|---|
| `SUCCESS` | 200–299 |
| `CLIENT_ERROR` | 400、404、405、408、413、415、422、425 |
| `UNAUTHENTICATED` | 401 |
| `FORBIDDEN` | 403 |
| `CONFLICT` | 409 |
| `RATE_LIMITED` | 429 |
| `SERVER_ERROR` | 500–599 |
| `OTHER` | 其余合法 HTTP 状态 |

延迟使用单调时钟计算并钳制为非负 `long`。固定 histogram bucket：

```text
<=25ms, <=50ms, <=100ms, <=250ms, <=500ms,
<=1000ms, <=2500ms, <=5000ms, >5000ms
```

rollup 保存 count、duration sum、duration max 和各 bucket count。查询返回 average、max 与
近似 p50/p95 bucket upper bound，明确 `estimated=true`，不伪装成逐请求精确 percentile。

## 8. V54 数据模型

### 8.1 `rag_api_operation_hourly`

请求级总量，每个请求只贡献一次：

| 列 | 类型/约束 |
|---|---|
| `bucket_start` | `TIMESTAMPTZ NOT NULL`，UTC 整点 |
| `principal_type` | `VARCHAR(32) NOT NULL`，固定枚举 |
| `principal_ref` | `VARCHAR(64) NOT NULL`，stable principal 或固定 synthetic 值 |
| `operation` | `VARCHAR(64) NOT NULL`，固定枚举 |
| `http_status` | `SMALLINT NOT NULL CHECK 100..599` |
| `request_count` | `BIGINT NOT NULL CHECK >=0` |
| `duration_sum_ms` | `NUMERIC(30,0) NOT NULL CHECK >=0` |
| `duration_max_ms` | `BIGINT NOT NULL CHECK >=0` |
| histogram columns | 九个 `BIGINT NOT NULL CHECK >=0` |
| `updated_at` | `TIMESTAMPTZ NOT NULL` |

主键：`(bucket_start, principal_type, principal_ref, operation, http_status)`。

### 8.2 `rag_api_collection_operation_hourly`

Collection contribution：

- 与请求表相同的 bucket/principal/operation/status/count/latency 字段；
- 增加 `collection_id BIGINT NOT NULL REFERENCES rag_collection(id)`；
- 主键增加 `collection_id`；
- 一次 multi-Collection 请求为每个已经授权的 Collection 各贡献一条增量；
- API 响应必须称其为 `collectionContributions`，不得把总和称为 requests。

不使用 nullable Collection 参与 unique key，避免 PostgreSQL NULL uniqueness 语义导致重复
rollup；也不使用 `0` sentinel 绕过外键。

### 8.3 写入与并发

flush 线程先按主键在内存 group delta，再使用固定 SQL：

```sql
INSERT ... VALUES (...)
ON CONFLICT (...) DO UPDATE SET
  request_count = target.request_count + EXCLUDED.request_count,
  duration_sum_ms = target.duration_sum_ms + EXCLUDED.duration_sum_ms,
  duration_max_ms = GREATEST(target.duration_max_ms, EXCLUDED.duration_max_ms),
  ...
```

要求：

- 不使用显式悲观锁、`SKIP LOCKED` 或 advisory lock；
- 一批写入一个短事务，失败整批回滚并计入 dropped；不无限重试；
- 两实例并发 upsert 同一 key 必须精确累加；
- recorder 不参加业务 transaction，不回滚主操作；
- 进程在 flush 前崩溃可能丢失内存事件，这是已声明的 best-effort 边界。

### 8.4 Retention

按 `bucket_start` 清理两表，单轮各最多配置的 batch size，使用 bounded delete + commit；
默认每小时执行。清理失败只记录固定 error counter，不影响 API。

不级联删除 principal。Collection 当前只软删除；如果未来引入物理 purge，必须先单独决定
历史 rollup 的保留/匿名化，不能由 V54 外键静默级联。

## 9. 查询 API 与授权

### 9.1 请求

```http
GET /api/v1/rag/integration-observability
  ?from=2026-08-26T00:00:00Z
  &to=2026-08-27T00:00:00Z
  &bucket=HOUR
  &operation=JSON_RECORD_SEARCH
  &collectionKey=customer-42:records:v1
  &principalId=rag_p_example
```

规则：

- `from`/`to` 可省略，默认最近 24 小时；必须是 `Instant`，`from < to`，最大 31 天；
- `bucket=HOUR|DAY`，默认 `HOUR`；DAY 由 SQL 对小时 rollup 再聚合；
- `operation` 可选，必须是公开固定枚举；
- NORMAL database principal：scope 永远是 self；缺省 `principalId` 自动使用 self，显式其他值
  返回通用 `403`；
- restricted NORMAL 的 `collectionKey` 必须当前 active 且属于 allow-list；未知或越权统一 `403`；
- unrestricted NORMAL 仍只能查询自己的 principal rows，但可按任意当前 active Collection
  过滤；无 filter 时返回自己在当前可见 Collection 上的 contribution；
- root/DB ADMIN 可省略 `principalId` 查看全局，或指定 stable DB principal；
- `LOCAL_AUTH_DISABLED` 仅在部署明确关闭认证时提供本地全局视图；响应 scope 标明该身份，
  不能伪装成 environment root 或 database ADMIN；
- legacy/static principal 返回 `403`，不能把兼容 credential 当管理身份；
- endpoint 需要 `RAG_READ`；观测 endpoint 自身不被 recorder 计入，防止自递归。

### 9.2 响应

```json
{
  "scope": {
    "from": "2026-08-26T00:00:00Z",
    "to": "2026-08-27T00:00:00Z",
    "bucket": "HOUR",
    "principalId": "rag_p_example",
    "collectionKey": null,
    "operation": null
  },
  "completeness": {
    "mode": "BEST_EFFORT",
    "recordingEnabled": true,
    "retentionDays": 90,
    "currentInstanceDropped": 0,
    "oldestIncludedBucket": "2026-08-26T00:00:00Z"
  },
  "totals": {
    "requestCount": 1200,
    "durationAverageMs": 42.5,
    "durationMaxMs": 810,
    "estimatedP50UpperBoundMs": 50,
    "estimatedP95UpperBoundMs": 250
  },
  "byStatus": [],
  "byOperation": [],
  "collectionContributions": [],
  "timeline": []
}
```

约束：

- 数量与 duration sum 在 Java/SQL 使用 `BigInteger`/`BigDecimal` 安全聚合，再检查 JSON
  可表示范围；不依赖 PostgreSQL `BIGINT SUM` 溢出行为；
- `byStatus` 按 status code 升序；`byOperation` 按 enum 顺序；timeline 按 bucket 升序；
- `collectionContributions` 最多 100 项，按 request contribution count DESC、collection key
  ASC 稳定排序；
- restricted NORMAL 只返回当前 allow-list 中仍可见 key 的映射，unrestricted NORMAL 只返回
  当前 active Collection 映射；无法完整安全映射时 `503`，不返回部分结果；
- 空窗口返回零 totals 和空数组，不返回 404；
- 响应 `Cache-Control: no-store`；
- 不返回 credential ID/version、raw key/hash、source/external ID、path、payload、query 或错误正文。

### 9.3 Completeness 语义

`currentInstanceDropped` 只代表当前 JVM 自启动以来已知丢失，不能证明历史窗口全局零丢失。
响应必须写 `mode=BEST_EFFORT`；不能提供误导性的 `complete=true`。

如果 recording 被关闭：

- capability 返回 `features.optional.integrationObservability=false`；
- 查询端点返回 `503 OBSERVABILITY_DISABLED`，而不是陈旧的假完整结果；
- 历史表不删除，重新开启后继续追加；
- root 可以直接通过只读数据库运维查询历史，但公开 API 不在关闭状态下发布半合同。

## 10. Micrometer 与日志

新增：

| meter | 类型 | 标签 |
|---|---|---|
| `rag.integration.requests` | Counter | `operation`, `status_class`, `principal_type` |
| `rag.integration.request.duration` | Timer | `operation`, `status_class`, `principal_type` |
| `rag.integration.observation.dropped` | Counter | `reason` |
| `rag.integration.observation.queue.depth` | Gauge | 无 |
| `rag.integration.observation.flush` | Counter | `result` |

所有标签值来自有限枚举。禁止 principal ID、credential ID、Collection key/ID、trace ID、URI、
HTTP header、external ID 或异常类/message 作为标签。

新增日志只允许：固定 operation、status class、principal type、计数、queue depth、batch size、
duration 和固定 failure category。正常请求不逐条 INFO 日志；flush/drop 使用采样或限频 WARN。

## 11. 配置

```yaml
rag:
  integration-observability:
    enabled: true
    retention: 90d
    max-query-range: 31d
    queue-capacity: 10000
    flush-batch-size: 500
    flush-interval: 1s
    shutdown-drain-timeout: 5s
    cleanup-batch-size: 5000
    cleanup-interval: 1h
```

启动校验：

- retention `7d..730d`；
- max query range `1d..90d` 且不得大于 retention；
- queue `100..100000`；
- flush batch `10..5000` 且不得大于 queue；
- flush interval `100ms..60s`；
- shutdown timeout `0..30s`；
- cleanup batch `100..50000`；
- cleanup interval `1m..24h`。

配置进入 `RagProperties`，同步 `application.yml`、`.env.example`、
`configuration-zh-CN.md` / `configuration.md`。不新增 secret。

## 12. 实施切片

### Slice 0：上一轮收口与实施基线

退出条件：

- V53 用量账本已合入并推送 `main@0993b702`；
- main 工作区干净，`main == origin/main`；
- 当前活动规划/进度已按生命周期归档，稳定事实已进入双语长青文档；
- 本规划仍与最新代码相符；V53 没有改变本规划冻结的 filter、principal、capability 或
  shared metrics 边界；
- 已从最新 `origin/main` 创建
  `feat/external-integration-operability-20260827` 分支和隔离 worktree。

### Slice A：限制合同

文件范围：

- `spring-ai-rag-api/.../IntegrationCapabilitiesResponse.java`；
- `spring-ai-rag-core/.../IntegrationCapabilityCatalog.java`；
- `RagStructuredRecordProperties`；
- Sync Run DTO/controller/service 的共享上限常量；
- capability controller/service/OpenAPI tests；
- binding preflight 和 business-client contract tests。

退出条件：自定义配置值在 capability JSON、service 拒绝和 OpenAPI/validation 中一致；旧字段
和 protocol 1.0 保持兼容。

### Slice B：V54、配置与 repository

新增：

- `RagIntegrationObservabilityProperties` 与 validator；
- operation/principal/status/latency 领域对象；
- V54 两张 hourly rollup 表和索引；
- repository batch upsert、bounded query、retention cleanup；
- PostgreSQL 集成测试，包含双实例并发精确累加。

退出条件：迁移从 V1–V54 成功；并发、范围、retention、溢出和索引路径可验证。

### Slice C：HTTP recorder 与 Collection context

新增/修改：

- 固定 route classifier；
- 位于 auth 之前的 wrapping filter；
- request-attribute Collection scope accessor；
- JSON Record、Sync Run、Collection lookup/readiness 的授权解析点；
- bounded queue、flush lifecycle、shutdown drain 和低基数 meters。

退出条件：200/401/403/404/409/429/5xx 均正确归类；未知/越权 key 不产生 collection row；
recorder 故障不改变业务 status/body 或调用次数。

### Slice D：查询 API 与授权

新增：

- `IntegrationObservabilityController`；
- query service、DTO、OpenAPI schema；
- NORMAL self、root/ADMIN global/selected principal、legacy deny；
- time/operation/Collection filters 和 no-store response。

退出条件：跨 principal、跨 Collection、soft-delete/policy change、disabled recorder 和空窗口合同
全部通过。

### Slice E：文档与统一门禁

- 更新 `rest-api*`、`business-client-integration*`、`architecture*`、`configuration*`、
  `project-context*`、`testing-guide*`、`developer-reference*` 和 release checklist；
- Flyway 版本更新到 V54；
- 扩展 `business-client-binding-preflight.sh` 与 `verify-business-client-readiness.sh`；
- 新增任务相关 PostgreSQL/WireMock/Mock HTTP 断言，但不复制已有启动脚本；
- 当前 plan/progress 在完成后归档。

本轮不修改 WebUI 源码。最终仍运行现有 TypeScript/build/core Mock Playwright，证明后端 additive
合同没有破坏前端。

## 13. 自动化验证计划

### 13.1 一次性测试设计原则

验收测试在进入实现审查前一次性按本节完成。禁止把 review 当正确性证明，也禁止在三轮审查
阶段发现一个问题就临时追加一个测试后全量重跑。只有影响本任务正确性、兼容性、隐私、成本或
数据一致性的缺陷才在审查阶段修改；任何实质修改重置审查计数。

### 13.2 单元与 Web 合同

必须覆盖：

1. 所有 operation route/method 正反分类和 trailing slash/query normalization；
2. unknown route no-op，动态 path/external ID 不进入标签；
3. DB principal、root、legacy、auth-disabled、anonymous 的 safe principal projection；
4. status class 与 histogram 边界；
5. duplicate Collection ID 去重、100 边界、非 HTTP no-op；
6. queue full、DB error、shutdown timeout 的 fail-open/drop meter；
7. capability 默认值和自定义配置值；
8. restricted/unrestricted NORMAL self、root/ADMIN、auth-disabled 和 legacy 查询授权；
9. 日期、窗口、enum、Collection 输入错误；
10. OpenAPI additive schema、旧五字段和 protocol `1.0` 兼容。

### 13.3 PostgreSQL 集成矩阵

至少覆盖：

1. V54 schema constraints、PK/FK、hour bucket；
2. 两 repository/两个线程模拟多实例并发 upsert 无丢计；
3. request total 一次、multi-Collection contribution N 次；
4. 精确 status、sum/max/histogram 聚合；
5. HOUR/DAY、from inclusive/to exclusive 和 31 天 guard；
6. restricted/unrestricted self scope 不读取其他 principal；root/ADMIN/auth-disabled 过滤正确；
7. restricted Collection subset、未知/越权 fail closed；
8. ACL 解析不完整返回 `503`，不发布部分 breakdown；
9. cleanup 有界且不删除窗口内数据；
10. recorder batch transaction 失败不产生部分增量；
11. V48–V53 既有 principal/quota/provisioning/usage schema 与 V54 可共同迁移；
12. `verify-no-pessimistic-locks.sh` 通过。

### 13.4 真实 HTTP 合同

在两个隔离实例和一个 disposable PostgreSQL 上：

1. root 幂等创建两个 Collection、READ_ONLY query principal 和两个 READ_WRITE dispatcher；
2. capability 返回非默认 structured-record limits；
3. 超出 batch item/payload 限制得到预期 400，边界值成功；
4. query principal search/lookup 成功，upsert/tombstone 403；
5. dispatcher 执行 create/replay/update/CAS 409/tombstone/restore；
6. 未认证 401、跨 ACL 403、lookup 404、共享 quota 429 + bounded `Retry-After`、模拟 5xx；
7. 等待 recorder flush 后，自助 API 的 request/status/operation/Collection 数量与实际请求一致；
8. 第二实例查询到第一实例写入的 rollup；
9. DB recorder 写故障时数据面响应保持原合同，drop counter 增加；
10. 证据 JSON 不含 credential、Collection key、external ID、payload、URL 或私有路径。

### 13.5 基本硬门槛

顺序固定：

1. 任务相关 focused tests；
2. PostgreSQL 集成矩阵；
3. `mvn clean compile test-compile`；
4. Maven 全量测试；
5. WebUI `tsc` / Vitest / production build / alignment；
6. 核心 Mock Playwright，仅用 DOM、accessibility、network/JSON 断言，不使用截图；
7. `verify-business-client-readiness.sh` 完整门禁；
8. 隔离端口真实全栈 Playwright 与 `dev.sh` 启动验证；
9. `verify-project-docs.sh`、`verify-no-pessimistic-locks.sh`、`git diff --check`、secret scan。

本功能不改变模型请求或响应。由于新 recorder filter 位于共享 HTTP filter chain，最终增加一次
有界真实 LLM Chat smoke 作为非功能回归：先通过全部 Mock/数据面门槛，再从 main `.env` 加载
credential，在隔离端口执行一次非流式和一次 SSE；观察日志并确认 provider 各调用一次、Chat
响应不变、且这两个非目标路由不进入 integration rollup。不得用真实 LLM 代替任何 Mock 或
PostgreSQL 测试。

### 13.6 证据

统一写入：

```text
.verification/integration-operability/<run-id>/
```

`summary.md` 和 machine-readable manifest 至少记录 Git SHA、tree state、Flyway 版本、实际运行
limits、通过步骤、HTTP check 数、PostgreSQL image、前后端版本和真实 LLM 调用计数。禁止记录
secret、URL、Collection key、principal ID、external ID、payload 或 response body。

## 14. 发布、回滚与兼容

### 14.1 发布顺序

1. 部署 V54 migration；
2. 部署默认启用 recorder 的新应用，先只观察 queue/drop/flush；
3. 用 root 和 restricted principal 验证 capability additive 字段；
4. 验证 self observability 与 Collection ACL；
5. 更新 Client preflight 最低 limit 要求；
6. 观察一个 retention/flush 周期后扩大流量。

### 14.2 回滚

- V54 只新增表/索引，不改业务表；应用回滚时保留 schema；
- `enabled=false` 可立即停止新记录，不删除历史；
- recorder/cleanup 失败不要求停数据面，但必须告警并标记 observation gap；
- capability 新字段 additive，旧 Client 继续忽略；
- 新 Client 依赖 observability 或新增 limits 前必须先跑 preflight，不能在旧实例上宽松启动；
- 不执行 destructive Flyway down migration。

### 14.3 数据量估算

小时 rollup 行数上界近似：

```text
principal 数 x operation 数 x status 数 x active hour
+ principal x Collection x operation x status x active hour
```

真实行数只为实际出现组合。默认 90 天、17 个 operation、通常 1–2 个 Collection/principal，
远小于逐请求 ledger。实现前 PostgreSQL 集成应生成至少 100 principal x 2 Collection x 24h
的 synthetic rollup，验证 query/cleanup 索引和响应有界；不把该数据量测试变成逐请求 HTTP
压测。

## 15. 风险与控制

| 风险 | 控制 |
|---|---|
| 异步 recorder 丢事件 | 明确 best-effort、drop/flush meter、bounded graceful drain；不用于账单 |
| principal/Collection 高基数污染 Prometheus | ID 只进 PostgreSQL，meter 只用固定枚举 |
| 未授权 key 泄漏 | 只在 ACL 成功后捕获内部 ID；查询重新按当前 policy 授权 |
| multi-Collection 重复计数 | request total 与 contribution 分表、字段命名和文档明确 |
| rollup 写放大 | request 单队列、flush 内 group、batch upsert、小时粒度 |
| recorder 影响业务 | 无同步 DB 写、fail open、不进入业务事务、不触发 retry |
| capability 与实际限制漂移 | 配置直读 + shared constants + 非默认配置合同测试 |
| protocol version 破坏旧 preflight | 保持 1.0，使用 additive fields/feature；未来 breaking change 再升 major/minor |
| 与 V53 usage/前端边界冲突 | 以已发布 V53 为基线；使用 V54、独立 endpoint/package；本轮无 WebUI |
| 审查发散 | 先过硬门槛，再做三轮互不重叠、限时、只读审查 |

## 16. 实现后三轮收敛审查

基本硬门槛全部通过后才开始：

1. **并发、事务与数据一致性**：V54 constraints、batch upsert、multi-instance、retention、
   shutdown 和 no-lock；
2. **API、授权与隐私**：self/root/ADMIN、ACL、未知 Collection、capability 兼容、敏感数据和
   高基数边界；
3. **可运维性与交付**：queue/drop/flush、指标、配置、证据、双语文档、脚本和 rollback。

每轮固定 45 分钟，只读且文件范围互不重叠。发现影响正确性、兼容性、隐私、成本或数据一致性
的实质问题时立即修复，重跑受影响硬门槛，计数归零；风格和可选优化记录到 backlog，不在该
循环中扩展范围。连续三轮无实质修改才允许 merge。

## 17. Git 与工作区交付

1. 本规划先在 main 提交、push；未获人工授权前停止；
2. 实施时从最新已 push 的 `origin/main` 创建专用分支；只有明确安排并行任务时才创建
   额外 worktree；
3. 实施期间关键进展先写独立 `EXTERNAL_INTEGRATION_OPERABILITY_PROGRESS.md`；
4. 大块完成后 merge 最新 `origin/main`，按合并后基线完整复验；
5. feature 提交/push 后合回 main，再执行 main 最终门禁；
6. 更新双语长青文档、归档 plan/progress、push main；
7. 确认 main 与 origin/main 相同、工作区干净、无测试服务；本轮已有 feature worktree
   在交付完成后安全移除；
8. 不删除远端 feature branch，不使用 stash，不丢弃其他工作区的任何修改。

## 18. 完成定义

只有全部满足才算实施完成：

1. runtime structured-record/sync-run/observability limits 可机器发现且与实际 guard 一致；
2. V54 两张 rollup 表可从 V1 全量迁移；
3. 目标 operation 的 2xx/401/403/404/409/429/5xx 和 latency 正确聚合；
4. request total 与 Collection contribution 语义无歧义；
5. NORMAL self、root/ADMIN 管理和 legacy deny 授权通过；
6. 未知/越权 Collection 不落 collection rollup、不泄漏；
7. queue/DB/shutdown 故障 fail open 且 drop 可见；
8. meter 无 principal/Collection/URI/external ID 高基数标签；
9. PostgreSQL、Maven、前端、Mock Playwright、真实全栈和有界真实 LLM 回归全部通过；
10. 任务证据不含 secret 或业务载荷；
11. 实现连续 `3/3` 无实质修改；
12. 跟进最新 main 后完整复验，feature/main 均 push，main 干净，worktree 已移除；
13. 稳定事实进入双语长青文档，规划和进度归档。

## 19. 规划审查记录

本节只记录发现问题并修复的轮次。连续无问题轮次只在最终提交说明和外部进度中记录，避免为了
记录“无修改”反而破坏三轮连续无修改条件。

| 时间 | 范围 | 发现问题 | 处理与计数 |
|---|---|---|---|
| 2026-08-27 | 初始探索 | 已有 P0/P1/P2 receipt 被旧评估低估；并行 V53 正在实施 | 以 main 代码重建矩阵；规划使用独立文件、V54、无 WebUI；计数 0 |
| 2026-08-27 | 第 1 轮：需求状态、运行限制、V54 schema | Collection 外键目标误写为不存在的复数表名 | 按 V1 迁移修正为 `rag_collection(id)`；计数重置为 0 |
| 2026-08-27 | 第 2 轮：API、授权与隐私 | 未冻结 unrestricted NORMAL 和 auth-disabled 查询语义 | 补齐 self/global 范围、Collection 过滤与 legacy deny；计数重置为 0 |
