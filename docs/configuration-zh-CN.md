# 配置参考

> 📖 English | 📖 中文

完整的 Spring AI RAG 配置项说明。所有业务配置通过 `rag.*` 前缀统一管理。

## 快速配置

### 最小启动配置

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag_dev
    username: postgres
    password: postgres
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        enabled: false

app:
  llm:
    provider: openai

rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
```

## LLM 配置

### 提供者切换

通过 `app.llm.provider` 切换 LLM 提供者：

| 提供者 | `app.llm.provider` | 配置前缀 | 说明 |
|--------|-------------------|---------|------|
| DeepSeek | `openai` | `spring.ai.openai.*` | OpenAI 兼容接口 |
| 智谱 GLM | `openai` | `spring.ai.openai.*` | OpenAI 兼容接口 |
| Anthropic | `anthropic` | `spring.ai.anthropic.*` | 独立 starter |

**重要**：`spring.ai.openai.chat.enabled` 和 `spring.ai.anthropic.chat.enabled` 必须设为 `false`，由 `SpringAiConfig` 手动创建 Bean。

### OpenAI / DeepSeek 配置

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.deepseek.com}
      chat:
        enabled: false
        options:
          model: ${OPENAI_MODEL:deepseek-chat}
          temperature: ${OPENAI_TEMPERATURE:0.7}
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `spring.ai.openai.api-key` | (必填) | API Key |
| `spring.ai.openai.base-url` | `https://api.deepseek.com` | API 端点；不要追加 `/v1` |
| `spring.ai.openai.chat.options.model` | `deepseek-chat` | 兼容路径的默认模型名 |
| `spring.ai.openai.chat.options.temperature` | `0.7` | 生成温度 |

### Anthropic 配置

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      base-url: ${ANTHROPIC_BASE_URL:https://api.anthropic.com}
      chat:
        enabled: false
        options:
          model: ${ANTHROPIC_MODEL:claude-3-5-sonnet-20241022}
          temperature: ${ANTHROPIC_TEMPERATURE:0.7}
          max-tokens: ${ANTHROPIC_MAX_TOKENS:4096}
```

### 运行时选模

`app.models.providers` 定义彼此独立的 ChatModel 实例。每个 provider 配置
API 类型、端点、密钥占位符和一个或多个模型 ID：

```yaml
app:
  models:
    config-file: ${MODELS_CONFIG_FILE:}
    providers:
      openrouter:
        displayName: OpenRouter
        baseUrl: https://openrouter.ai/api
        apiKey: ${OPENROUTER_API_KEY:}
        apiType: openai-completions
        enabled: true
        priority: 1
        models:
          - id: xiaomi/mimo-v2-pro
            name: MiMo V2 Pro
            type: chat
            contextWindow: 600000
            maxTokens: 32000
    chatModel:
      primary: openrouter/xiaomi/mimo-v2-pro
      fallbacks: []
```

调用 `GET /api/v1/rag/models` 获取 `providerId/modelId` 引用，并将其作为
两个 Chat 端点的 `model` 字段。显式指定未知或不可用模型会返回 HTTP
400；省略 `model` 时使用 primary/fallback 链。外部 JSON 会完整替换
YAML 模型注册表，见
[multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md)。

### OpenAI Chat Completions 服务端兼容

受控兼容入口默认关闭。启用后提供 `GET /v1/models`、`GET /v1/models/{id}` 和
`POST /v1/chat/completions`；公开的 model alias 表示 RAG pipeline 与后端候选链，
**不保存固定 Collection**。Collection 范围由每次请求的 `rag.scope` 或重复发送的
`X-RAG-Collection-Key` Header 提供，并继续受 API Key ACL 限制。

```yaml
rag:
  openai-compatibility:
    enabled: ${RAG_OPENAI_COMPATIBILITY_ENABLED:false}
    require-explicit-scope: ${RAG_OPENAI_REQUIRE_EXPLICIT_SCOPE:false}
    models:
      rag-default:
        candidates: []
        mode: KNOWLEDGE
        memory: STATELESS
        allow-request-mode-override: false
        allow-request-memory-override: false
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.openai-compatibility.enabled` | `false` | 注册 `/v1/models` 与 `/v1/chat/completions` |
| `rag.openai-compatibility.require-explicit-scope` | `false` | 要求每个请求显式提供 `rag.scope` 或 Collection Header |
| `rag.openai-compatibility.models.<alias>.candidates` | `[]` | 内部 `provider/modelId` 候选链；空列表使用全局 primary/fallback |
| `rag.openai-compatibility.models.<alias>.mode` | `KNOWLEDGE` | alias 的默认 Chat 模式 |
| `rag.openai-compatibility.models.<alias>.memory` | `STATELESS` | alias 的默认记忆模式 |
| `allow-request-*-override` | `false` | 是否允许请求覆盖 alias 的 mode/memory |

当前是 text-only、`n=1` 的受控预览；不支持的 OpenAI 参数会明确返回兼容错误信封，
不会静默忽略。协议与范围示例见 [REST API](rest-api-zh-CN.md)。

## 嵌入模型配置

嵌入模型配置独立于 Chat 提供者，始终生效。

```yaml
rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: ${SILICONFLOW_URL:https://api.siliconflow.cn}
    model: ${SILICONFLOW_MODEL:BAAI/bge-m3}
    dimensions: ${SILICONFLOW_DIMENSIONS:1024}
    retry-max-attempts: ${RAG_EMBEDDING_RETRY_MAX_ATTEMPTS:10}
    profile-key: ${RAG_EMBEDDING_PROFILE_KEY:siliconflow-bge-m3-1024-v1}
    provider: ${RAG_EMBEDDING_PROVIDER:siliconflow}
    model-revision: ${RAG_EMBEDDING_MODEL_REVISION:unspecified}
    distance-metric: COSINE
    normalization: PROVIDER_DEFAULT
    migration-mode: ${RAG_EMBEDDING_MIGRATION_MODE:none}
    migration-legacy-profile-key: ${RAG_EMBEDDING_MIGRATION_LEGACY_PROFILE_KEY:}
    migration-confirm: ${RAG_EMBEDDING_MIGRATION_CONFIRM:}
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.embedding.api-key` | `""` | SiliconFlow API Key |
| `rag.embedding.base-url` | `https://api.siliconflow.cn` | API 端点 |
| `rag.embedding.model` | `BAAI/bge-m3` | 嵌入模型名称 |
| `rag.embedding.dimensions` | `1024` | 向量维度（必须与模型输出一致） |
| `rag.embedding.retry-max-attempts` | `10` | 单次 provider 调用的最大尝试次数，范围 1–10；独立于持久化 job 的尝试预算 |
| `rag.embedding.profile-key` | `siliconflow-bge-m3-1024-v1` | 写入和检索使用的不可变模型空间身份 |
| `rag.embedding.provider` | `siliconflow` | Profile 中保存的提供商身份 |
| `rag.embedding.model-revision` | `unspecified` | 显式模型版本身份；模型语义变化必须创建新 Profile |
| `rag.embedding.distance-metric` | `COSINE` | 距离度量；本版本只支持 `COSINE` |
| `rag.embedding.normalization` | `PROVIDER_DEFAULT` | 保存于 Profile 的归一化语义 |
| `rag.embedding.migration-mode` | `none` | 显式 Legacy 认领的启动迁移模式 |
| `rag.embedding.migration-legacy-profile-key` | `""` | Legacy 认领使用的既有 Profile key |
| `rag.embedding.migration-confirm` | `""` | Legacy 认领操作要求的精确确认值 |

活动 Profile 注册在 `rag_embedding_profiles` 中，创建后身份不可变。当前支持的维度为
`1024`，存储在固定长度的 `rag_embeddings.embedding_1024 VECTOR(1024)` 列中。兼容窗口
内新向量还会双写旧 `embedding` 列。更换模型必须创建新 Profile 并完整重嵌入，不能只改
`dimensions`、把旧向量 cast 成新向量，或在一次检索中混用多个 Profile。Legacy 向量只能
通过显式认领并提供正确确认值接入。

`rag.embedding.retry-max-attempts` 控制一次 worker/provider 调用内部的 Spring Retry
预算；只重试 Spring AI transient 异常和网络访问异常，并保留默认指数退避。它与下方
`rag.embedding-jobs.default-max-attempts` /
`rag.embedding-jobs.max-attempts` 的持久化任务预算相互独立；生产环境应同时为两层设置
有限值，避免一个失败任务产生无界外部调用。

### 持久化 Embedding Jobs

job worker 用于持久化、可重试的 embedding/reindex。V41 起默认开启，因为文档正文
`SYNC` 与 `ASYNC` mutation 都先在同一事务中持久化任务；`SYNC` 只是对同一个任务做有界
等待。还可通过 `/api/v1/rag/embedding-jobs` 创建、查询、取消和重试运维任务。

```yaml
rag:
  embedding-jobs:
    enabled: ${RAG_EMBEDDING_JOBS_ENABLED:true}
    sync-wait-seconds: ${RAG_EMBEDDING_JOBS_SYNC_WAIT_SECONDS:30}
    poll-interval-ms: ${RAG_EMBEDDING_JOBS_POLL_INTERVAL_MS:1000}
    claim-batch-size: ${RAG_EMBEDDING_JOBS_CLAIM_BATCH_SIZE:4}
    lease-seconds: ${RAG_EMBEDDING_JOBS_LEASE_SECONDS:120}
    default-max-attempts: ${RAG_EMBEDDING_JOBS_DEFAULT_MAX_ATTEMPTS:3}
    max-attempts: ${RAG_EMBEDDING_JOBS_MAX_ATTEMPTS:5}
    max-documents-per-batch: 1000
    retry-backoff-seconds: ${RAG_EMBEDDING_JOBS_RETRY_BACKOFF_SECONDS:10}
    worker-concurrency: ${RAG_EMBEDDING_JOBS_WORKER_CONCURRENCY:4}
```

worker 使用 PostgreSQL lease 与带状态/过期条件的原子 `UPDATE ... RETURNING` 支持多
worker claim，并在提交向量前校验活动 Profile、任务 lease、取消标记、request generation、
document kind、chunker version 与 content hash。相同 generation 的活动任务会合并；
正文变更会分配更高 generation 并取消旧任务，旧 worker 不能提交到新正文。
仍有重试次数时，过期租约会被重新 claim；若最后一次允许的尝试也发生租约过期，任务会
原子转为 `FAILED` 并清空租约字段，不会永久停留在 `RUNNING`。

导入与显式 embed 入口接受可选 `embeddingPolicy`：`SYNC` / `ASYNC` / `SKIP`。
提供该字段时它覆盖旧的 `embed` 布尔值；省略时保持 `embed=true→SYNC`、
`embed=false→SKIP`。显式 embed/re-embed 拒绝 `SKIP`。`ASYNC` 要求
`rag.embedding-jobs.enabled=true`，否则返回 `503 EMBEDDING_JOBS_DISABLED`。

### 文档生命周期与外部来源同步

```yaml
rag:
  document-lifecycle:
    strict-external-cas: ${RAG_DOCUMENT_STRICT_EXTERNAL_CAS:true}
    allow-non-default-namespace: ${RAG_DOCUMENT_ALLOW_NON_DEFAULT_NAMESPACE:true}
    idempotency-ttl-hours: ${RAG_DOCUMENT_IDEMPOTENCY_TTL_HOURS:24}
    sync-runs-enabled: ${RAG_DOCUMENT_SYNC_RUNS_ENABLED:false}
    version-restore-enabled: ${RAG_DOCUMENT_VERSION_RESTORE_ENABLED:false}
    relocation-enabled: ${RAG_DOCUMENT_RELOCATION_ENABLED:false}
    derivation-repair-enabled: ${RAG_DOCUMENT_DERIVATION_REPAIR_ENABLED:false}
    sync-run-max-missing-absolute: ${RAG_DOCUMENT_SYNC_RUN_MAX_MISSING_ABSOLUTE:1000}
    sync-run-max-missing-percent: ${RAG_DOCUMENT_SYNC_RUN_MAX_MISSING_PERCENT:20}
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.document-lifecycle.strict-external-cas` | `true` | 已存在外部身份的新 revision 必须提供 `expectedSourceRevision`；精确重放不受影响 |
| `rag.document-lifecycle.allow-non-default-namespace` | `true` | 接受显式外部 `sourceNamespace`；关闭时只允许兼容值 `default` |
| `rag.document-lifecycle.idempotency-ttl-hours` | `24` | 本地 create/upload `Idempotency-Key` 记录的保留小时数，限制为 1–168 |
| `rag.document-lifecycle.sync-runs-enabled` | `false` | 开启权威外部快照 Sync Run API；默认关闭，开启前应先完成一次性 PostgreSQL/E2E 验收 |
| `rag.document-lifecycle.version-restore-enabled` | `false` | 开启本地文档 `FULL` 历史版本恢复 API；外部托管文档仍不可由该入口恢复 |
| `rag.document-lifecycle.relocation-enabled` | `false` | 开启外部文档跨 Collection 原子迁移；开启前运行 relocation 专项门禁 |
| `rag.document-lifecycle.derivation-repair-enabled` | `false` | 开启有副作用的派生修复 preview/apply；只读 derivation readiness 不受此开关影响 |
| `rag.document-lifecycle.sync-run-max-missing-absolute` | `1000` | `TOMBSTONE` 快照的绝对缺失保护阈值；超出且未显式确认时拒绝完成，限制为 1–100000 |
| `rag.document-lifecycle.sync-run-max-missing-percent` | `20` | `TOMBSTONE` 快照的相对缺失保护阈值（百分比）；限制为 1–100 |

本地文档使用公开 `documentRevision` 做 PATCH/disable/restore/permanent-delete CAS。
外部文档和 JSON record 使用
`collectionKey + sourceNamespace + externalId` 身份及 opaque `sourceRevision`。正文 mutation
与派生任务同事务提交；metadata、JSONB payload 或 Collection-only 变化不请求 embedding。

对启用文档，所有非 `SKIP` 的正文 mutation 都会准备本地关键词 chunk。
V43 将它们存入独立的 `rag_document_chunks`，并用独立的
`rag_document_local_index_state` 记录 freshness；这条路径不依赖 embedding provider 或
Profile。`SKIP` 会删除当前本地 chunk，并将本地状态标为 `NOT_REQUESTED`。因此远程
embedding 分支排队、执行中或失败时，全文检索仍可通过 `KEYWORD_ONLY` 工作。当前没有
额外的关键词索引开关配置。

普通外部文本文档同步要求 `collectionKey`，且它必须指向真实存在的活动 Collection。
JSON record upsert 仍兼容 deprecated 数字输入，但最终解析到同一个以 key 为准的规范地址。
`sourceNamespace` 可以省略或为空白，服务会将其规范化为 `default`；
`allow-non-default-namespace` 控制是否接受其他显式 namespace。当前标识长度上限为：
`collectionKey` 和 `sourceNamespace` 各 128 个字符，`externalId` 255 个字符。
这些上限属于外部 Client 契约，后续迁移不得缩短。`default` 是兼容 namespace，不是默认
Collection；`NULL` Collection 只表示本地/未归属状态。

## 检索配置

```yaml
rag:
  retrieval:
    vector-weight: 0.5
    fulltext-weight: 0.5
    default-limit: 10
    min-score: 0.3
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.retrieval.vector-weight` | `0.5` | 向量检索融合权重 |
| `rag.retrieval.fulltext-weight` | `0.5` | 全文检索融合权重 |
| `rag.retrieval.fulltext-enabled` | `true` | 启用全文检索（不可用时自动降级为纯向量检索） |
| `rag.retrieval.fulltext-strategy` | `auto` | 全文检索策略（见下表） |
| `rag.retrieval.default-limit` | `10` | 默认返回结果数 |
| `rag.retrieval.min-score` | `0.3` | 模糊全文提供者（如 `pg_trgm`）的最低相似度；`pg_jieba` / English FTS 以 `@@` 判定词法命中，再用 `ts_rank` 排序 |

> 💡 `vector-weight + fulltext-weight` 建议和为 `1.0`；服务会分别校验两个权重必须是
> 有限的 `0.0..1.0` 数值，但不会自动归一化权重之和。这样可以让缩放后的加权 RRF
> 分数保持易于理解的量级。

**全文检索策略（`fulltext-strategy`）：**

| 策略 | 说明 | 依赖 |
|------|------|------|
| `auto` | 自动检测：优先 pg_jieba → pg_trgm → 纯向量 | — |
| `pg_jieba` | PostgreSQL 中文分词（推荐中文场景） | `pg_jieba` 扩展 |
| `pg_trgm` | 三元组模糊匹配 | `pg_trgm` 扩展 |
| `none` | 禁用全文检索，纯向量检索 | — |

详见 [PostgreSQL 扩展文档](postgresql-extensions.md)。

### 检索诊断

```yaml
rag:
  retrieval-diagnostics:
    enabled: ${RAG_RETRIEVAL_DIAGNOSTICS_ENABLED:true}
    persist: ${RAG_RETRIEVAL_DIAGNOSTICS_PERSIST:true}
    retention-days: ${RAG_RETRIEVAL_DIAGNOSTICS_RETENTION_DAYS:7}
    store-query-text: ${RAG_RETRIEVAL_DIAGNOSTICS_STORE_QUERY_TEXT:false}
    max-detail-bytes: ${RAG_RETRIEVAL_DIAGNOSTICS_MAX_DETAIL_BYTES:32768}
```

默认记录 outcome / empty-reason / filter 摘要，不写 query 明文。写入失败 fail-open，
不影响 Search/Chat。

### 评估套件与 citation

```yaml
rag:
  evaluation:
    managed-suites-enabled: ${RAG_EVALUATION_MANAGED_SUITES_ENABLED:false}
    citation-validation-enabled: ${RAG_EVALUATION_CITATION_VALIDATION_ENABLED:true}
    max-concurrent-runs: ${RAG_EVALUATION_MAX_CONCURRENT_RUNS:1}
    run-concurrency: ${RAG_EVALUATION_RUN_CONCURRENCY:4}
    max-cases-per-version: ${RAG_EVALUATION_MAX_CASES_PER_VERSION:200}
    max-variants-per-run: ${RAG_EVALUATION_MAX_VARIANTS_PER_RUN:4}
    semantic-batch-limit: ${RAG_EVALUATION_SEMANTIC_BATCH_LIMIT:50}
```

citation 校验只解析约定的 `[S1]` token，不是覆盖率分数。受管 suite worker 默认关闭。
`max-concurrent-runs` 限制同时执行的 run；`run-concurrency` 限制单个 run 内并行执行的
检索 case 数，取值上限为 8。

## 查询改写配置

```yaml
rag:
  query-rewrite:
    enabled: true
    padding-count: 2
    synonym-dictionary:
      AI: [人工智能, Artificial Intelligence]
      机器学习: [ML, Machine Learning]
    domain-qualifiers: [皮肤科, 美容]
    llm-enabled: false
    llm-max-rewrites: 3
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.query-rewrite.enabled` | `true` | 启用查询聚焦与改写 |
| `rag.query-rewrite.padding-count` | `2` | 扩展查询数 |
| `rag.query-rewrite.synonym-dictionary` | `{}` | 同义词词典 |
| `rag.query-rewrite.domain-qualifiers` | `[]` | 领域限定词 |
| `rag.query-rewrite.llm-enabled` | `false` | 启用 LLM 辅助改写 |
| `rag.query-rewrite.llm-max-rewrites` | `3` | LLM 改写最大数 |

这些属性配置旧的/组件级 `QueryRewriteAdvisor`，不控制生产 mode-aware Chat。
生产 `KNOWLEDGE` 的查询转换由 `rag.chat.knowledge.query-transformer` 控制；
`AGENT` 由模型通过 Spring AI Tool Calling 形成工具查询。

## 重排序配置

```yaml
rag:
  rerank:
    enabled: false
    provider: heuristic
    diversity-weight: 0.2
    top-n: 5
    candidate-limit: ${RAG_RERANK_CANDIDATE_LIMIT:20}
    preferred-max-chunks-per-document: ${RAG_RERANK_PREFERRED_MAX_CHUNKS_PER_DOCUMENT:2}
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.rerank.enabled` | `false` | 启用重排序 |
| `rag.rerank.provider` | `heuristic` | 重排提供者；`none`、`noop`、`off` 表示关闭重排候选池扩展 |
| `rag.rerank.diversity-weight` | `0.2` | 结果多样性权重（避免相似结果堆叠） |
| `rag.rerank.top-n` | `5` | 调用方未提供正数 `maxResults` 时的最终结果数 fallback |
| `rag.rerank.candidate-limit` | `20` | 启用有效 rerank 时的内部候选池上限，绑定值限制为 `1..100` |
| `rag.rerank.preferred-max-chunks-per-document` | `2` | 第一遍按精确非空文档 ID 优先保留的 chunk 数，范围 `0..100`；`0` 关闭文档多样化 |

当请求的 `useRerank=true`、全局重排已启用且 provider 不是 no-op 时，检索服务使用
`max(requestedMaxResults, candidate-limit)` 作为 rerank 前的候选池；`maxResults` 仍是
Search、Chat、Agent 工具、JSON record 和 Evaluation 的最终对外数量。hybrid 检索会继续
按候选池的 `2x` 查询向量和全文通道，再以候选池上限执行加权 RRF。关闭重排、使用
no-op provider 或旧版 GET Search（该入口明确 `useRerank=false`）时，不会扩大查询池。

`candidate-limit` 只可通过服务端配置调整，推荐环境变量
`RAG_RERANK_CANDIDATE_LIMIT`。提高它可能改善 rerank 召回质量，但会增加数据库候选数、
HTTP rerank 请求体和响应延迟；先使用检索 goldenset 比较 MRR/nDCG 与延迟，再逐步调整。

候选池大于最终结果数时，有效 reranker 会先对有界候选池完整排序，再执行最终选择。
选择器第一遍按精确、非空 `documentId` 优先最多保留
`preferred-max-chunks-per-document` 项；如果不同文档不足以填满请求数量，再按 provider
原排名回填被跳过的 chunk。因此该值是软性的第一遍上限：它提高证据覆盖，但不会在
provider 已返回结果的基础上减少数量。null 或 blank 文档 ID 按独立结果处理。设置
`RAG_RERANK_PREFERRED_MAX_CHUNKS_PER_DOCUMENT=0` 可恢复原来的 provider top-N 选择。

## 文档分块配置

```yaml
rag:
  chunk:
    default-chunk-size: 1000
    default-chunk-overlap: 100
    min-chunk-size: 100
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.chunk.default-chunk-size` | `1000` | 默认分块大小（字符） |
| `rag.chunk.default-chunk-overlap` | `100` | 分块重叠大小（字符） |
| `rag.chunk.min-chunk-size` | `100` | 生成分块的尽力而为目标；非空文档不会被丢弃 |

## JSON 结构化记录配置

```yaml
rag:
  structured-records:
    max-jsonb-payload-bytes: 1048576
    max-retrieval-text-chars: 10000
    max-batch-size: 20
    max-batch-payload-bytes: 10485760
    max-search-results: 20
    max-payload-filter-bytes: 16384
    max-payload-filter-depth: 8
    agent-tool-enabled: ${RAG_JSON_AGENT_TOOL_ENABLED:false}
    agent-tool-max-results: 5
    agent-tool-max-payload-bytes: 32768
```

这些限制用于保护 JSONB 请求、批量请求和响应大小，不会改变 embedding 输入：
只有调用者提供的 `retrievalText` 会被分块和嵌入。`jsonbPayload` 以 JSONB 保存，不计算
hash；API 设计上也没有 payload hash 配置。`payloadContains` 使用 PostgreSQL
`jsonb @>` 做精确子树包含过滤，默认限制 16 KiB 和 8 层嵌套。可选的
`searchJsonRecords` Spring AI Tool 默认关闭；启用后仍由服务端注入 Collection/ACL
范围，模型只能提供查询、payload 子树和结果数。

## Chat 执行配置

```yaml
rag:
  chat:
    default-mode: KNOWLEDGE
    knowledge:
      query-transformer: none
      query-transform-timeout-seconds: 30
      query-expander-variants: 2
      query-expander-include-original: true
      max-retrieval-queries: 3
      allow-empty-context: false
    agent:
      enabled: true
      max-tool-rounds: 3
      max-retrieval-calls: 3
      max-results-per-call: 10
      max-unique-sources: 20
      max-tool-result-characters: 24000
    history:
      lease-ttl-seconds: 30
      lease-renew-interval-seconds: 10
    idempotency:
      enabled: true
      retention-hours: 24
      response-snapshot-max-bytes: 524288
      execution-snapshot-max-bytes: 65536
      max-attempts: 3
      lease-grace-ms: 10000
      cleanup-batch-size: 500
      cleanup-interval-ms: 600000
      cleanup-initial-delay-ms: 60000
    static-knowledge:
      enabled: false
      locations: []
      file-extensions: [md, markdown, txt]
      max-files-per-root: 200
      max-file-bytes: 262144
      max-total-bytes: 10485760
      chunk-max-characters: 4000
      chunk-overlap-characters: 200
      retrieval-max-results: 5
      retrieval-max-result-characters: 24000
      fail-fast: true
      visibility: GLOBAL
    skills:
      enabled: false
      locations: []
      max-skills: 50
      max-skill-body-bytes: 131072
      max-reference-bytes: 262144
      max-catalog-characters: 24000
      max-loads-per-request: 4
      max-reference-reads-per-request: 8
      fail-fast: true
    http-tools:
      enabled: false
      max-total-response-bytes: 262144
      endpoints: []
```

| 属性 | 默认值 | 说明 |
|---|---|---|
| `rag.chat.default-mode` | `KNOWLEDGE` | `ChatRequest.mode` 省略时使用的模式 |
| `rag.chat.knowledge.query-transformer` | `none` | `none` 或 `spring-ai`；`postgresql`、`local`、`prod` profile 使用 `spring-ai` |
| `rag.chat.knowledge.query-transform-timeout-seconds` | `30` | Spring AI 历史压缩调用超时；可用 `RAG_CHAT_QUERY_TRANSFORM_TIMEOUT_SECONDS` 覆盖 |
| `rag.chat.knowledge.query-expander-variants` | `2` | 启用 `spring-ai` 策略时由 LLM 生成的检索变体数量；可用 `RAG_CHAT_QUERY_EXPANDER_VARIANTS` 覆盖 |
| `rag.chat.knowledge.query-expander-include-original` | `true` | 是否保留原始请求作为额外检索查询；可用 `RAG_CHAT_QUERY_EXPANDER_INCLUDE_ORIGINAL` 覆盖 |
| `rag.chat.knowledge.max-retrieval-queries` | `3` | 每个 `KNOWLEDGE` attempt 最多计划的检索 query 数，范围 `1..5`；可用 `RAG_CHAT_KNOWLEDGE_MAX_RETRIEVAL_QUERIES` 覆盖 |
| `rag.chat.knowledge.allow-empty-context` | `false` | false 时，零召回会向模型注入明确的“无证据”指令 |
| `rag.chat.agent.enabled` | `true` | 是否启用 `AGENT` 模式 |
| `rag.chat.agent.max-tool-rounds` | `3` | 单次 attempt 的 Spring AI 工具调用轮数上限 |
| `rag.chat.agent.max-retrieval-calls` | `3` | 单次 attempt 未命中缓存的知识检索调用上限 |
| `rag.chat.agent.max-results-per-call` | `10` | 单次工具检索的服务端结果数上限 |
| `rag.chat.agent.max-unique-sources` | `20` | 多次工具调用累计保留的唯一 source chunk 上限 |
| `rag.chat.agent.max-tool-result-characters` | `24000` | 工具结果序列化字符上限；会以合法 JSON 安全减少结果/截断 snippet |
| `rag.chat.history.lease-ttl-seconds` | `30` | 单个 principal/session 请求的数据库 lease TTL |
| `rag.chat.history.lease-renew-interval-seconds` | `10` | lease 续租间隔 |
| `rag.chat.idempotency.enabled` | `true` | keyed Chat operation 与重放总开关；关闭时带 key 请求返回 `IDEMPOTENCY_DISABLED` |
| `rag.chat.idempotency.retention-hours` | `24` | 终态 operation 与 stale orphan 的保留时间；范围 1–168 |
| `rag.chat.idempotency.response-snapshot-max-bytes` | `524288` | UTF-8 响应快照上限；范围 65536–2097152 |
| `rag.chat.idempotency.execution-snapshot-max-bytes` | `65536` | UTF-8 不可变执行快照上限；范围 16384–262144 |
| `rag.chat.idempotency.max-attempts` | `3` | durable execution 总尝试上限，含 stale 接管；范围 1–8 |
| `rag.chat.idempotency.lease-grace-ms` | `10000` | 加在 Chat deadline 上的 operation lease 宽限；范围 1000–60000 |
| `rag.chat.idempotency.cleanup-batch-size` | `500` | 每次维护最多清理的终态/stale orphan 行数；范围 1–5000 |
| `rag.chat.idempotency.cleanup-interval-ms` | `600000` | cleanup 固定延迟间隔；范围 10000–86400000 |
| `rag.chat.idempotency.cleanup-initial-delay-ms` | `60000` | 服务启动后的首次 cleanup 延迟；范围 1–86400000 |
| `rag.chat.static-knowledge.enabled` | `false` | 启用启动期构建的非 embedding 静态知识快照 |
| `rag.chat.static-knowledge.locations` | `[]` | `classpath:`、`classpath*:`、`file:` 或受限 `jar:file:...!/prefix/` 资源目录 |
| `rag.chat.static-knowledge.file-extensions` | `[md, markdown, txt]` | 允许读取的 UTF-8 文件扩展名 |
| `rag.chat.static-knowledge.max-files-per-root` | `200` | 单个配置根的文件数上限 |
| `rag.chat.static-knowledge.max-file-bytes` | `262144` | 单文件字节上限 |
| `rag.chat.static-knowledge.max-total-bytes` | `10485760` | 所有静态知识根累计字节上限 |
| `rag.chat.static-knowledge.chunk-max-characters` | `4000` | 按 Markdown 标题和段落切分时的目标 chunk 字符上限 |
| `rag.chat.static-knowledge.chunk-overlap-characters` | `200` | 相邻 chunk 的字符重叠；必须小于 chunk 上限 |
| `rag.chat.static-knowledge.retrieval-max-results` | `5` | 单次静态词法检索结果上限 |
| `rag.chat.static-knowledge.retrieval-max-result-characters` | `24000` | 单次静态检索返回文本总字符上限 |
| `rag.chat.static-knowledge.fail-fast` | `true` | 加载失败时阻止启动；false 时整个静态快照降级且不发布部分结果 |
| `rag.chat.static-knowledge.visibility` | `GLOBAL` | 当前唯一支持的可见性；内容对所有已授权 Chat principal 相同 |
| `rag.chat.skills.enabled` | `false` | 启用 AGENT Runtime Skill catalog 和加载工具 |
| `rag.chat.skills.locations` | `[]` | 包含 `<skill-name>/SKILL.md` 的 classpath/filesystem/JAR 资源根 |
| `rag.chat.skills.max-skills` | `50` | Skill 数量上限 |
| `rag.chat.skills.max-skill-body-bytes` | `131072` | 单个 `SKILL.md` 正文字节上限 |
| `rag.chat.skills.max-reference-bytes` | `262144` | 单个 `references/` 文件及单次 reference 读取上限 |
| `rag.chat.skills.max-catalog-characters` | `24000` | Level 1 catalog 和单次 `loadSkill` 输出字符上限 |
| `rag.chat.skills.max-loads-per-request` | `4` | 单请求最多加载的 Skill 数 |
| `rag.chat.skills.max-reference-reads-per-request` | `8` | 单请求最多读取的 Skill reference 数 |
| `rag.chat.skills.fail-fast` | `true` | Skill 加载失败时阻止启动；false 时 catalog 降级且不注册 Skill 工具 |
| `rag.chat.http-tools.enabled` | `false` | 启用服务端配置、Skill gated 的只读 HTTPS 工具 |
| `rag.chat.http-tools.max-total-response-bytes` | `262144` | 单个逻辑请求累计 HTTP 响应字节预算 |
| `rag.chat.http-tools.endpoints` | `[]` | 固定 endpoint 列表；模型不能提供 URL、method、header 或 credential |

模式语义：

- `KNOWLEDGE` 始终通过项目混合检索器和可选 reranker 执行 Spring AI Modular RAG。
- 正常的 `postgresql`、`local`、`prod` profile 使用 Spring AI 内置的
  `CompressionQueryTransformer` 处理带历史的追问，并使用内置
  `MultiQueryExpander` 生成检索查询。默认保留原始请求，再生成两个额外变体；
  项目提供的 `BoundedMultiQueryExpander` 会在 advisor 执行前按
  `max-retrieval-queries` 收敛 fan-out，trim 空白项并按精确文本去重，同时保留
  Spring AI `Query` 的 history/context。项目提供的提示词要求保留产品名、引号短语、
  标识符和其他特殊词，并至少生成一个精确词检索变体。项目的
  `ProjectDocumentJoiner` 在 rerank 前按稳定 chunk identity 合并结果。这样即使语义改写产生了近义描述，
  也不会丢失 `破皮沙发` 这样的精确词；当配置的变体数超过预算时，也不会先为无效
  fan-out 生成多余的扩展请求。
- `query-expander-variants` 表示请求 LLM 生成的变体数，`max-retrieval-queries` 表示
  原始 query 与有效变体合计的上限。`include-original=true,max-retrieval-queries=1`
  时不调用多查询扩展模型，直接检索一次原始/转换后 query。KNOWLEDGE 使用该独立预算；
  `AGENT` 仍使用 `rag.chat.agent.max-retrieval-calls`。响应的
  `metadata.retrieval.queryExpansion` 与持久化 retrieval trace 的 attempt metadata
  只记录有界整数/布尔摘要，不记录扩展 query 文本。
- `AGENT` 使用 Spring AI Tool Calling；模型必须声明
  `capabilities.toolCalling=true`，Collection/document/credential 范围仍由服务端持有。
- `PLAIN` 不检索，并拒绝检索专用的请求覆盖项。
- 客户端取消的 partial turn 不会持久化。

### 非 embedding 静态知识

静态知识在启动时从配置根读取并构建不可变、有界词法快照；不调用 embedding，不写入
`rag_documents`/`rag_embeddings`。`KNOWLEDGE` 会把它和项目文档检索结果组合，
`AGENT` 会在快照健康时注册 `searchStaticKnowledge`，`PLAIN` 不读取。静态来源使用
`STATIC_KNOWLEDGE` 类型并跳过外部 reranker。资源变更需要重启服务才能生效。

```yaml
rag:
  chat:
    static-knowledge:
      enabled: true
      locations:
        - classpath*:knowledge/company-terms/
        - file:/opt/spring-ai-rag/knowledge/
```

`classpath:` 读取当前匹配根，`classpath*:` 搜索所有 classpath/JAR 匹配根；显式 JAR
目录可使用 `jar:file:/opt/lib/policies.jar!/knowledge/`。filesystem 路径必须写为
`file:` URI。当前 `GLOBAL` 可见性不适合保存 tenant/private 内容；这类内容仍应进入带
ACL 的项目文档库。

### Runtime Skill 与 allowlisted HTTP 工具

Runtime Skill 仅用于 `AGENT`。启动时只把名称、描述和 capability 的有界 catalog 加入
system prompt；模型必须先调用 `loadSkill` 才能取得 Skill 正文，之后才能调用
`readSkillReference` 或依赖该 Skill 的 HTTP 工具。加载状态和读取预算均为 request-local。

```text
skills/
  weather/
    SKILL.md
    references/
      response-schema.md
```

`SKILL.md` 使用 YAML frontmatter：

```markdown
---
name: weather
description: Query the configured weather service
version: "1.0"
capabilities:
  - weather.read
---

Load this Skill, then call the weather endpoint with a city name.
```

HTTP endpoint 由服务端固定配置，且 Skill 必须声明匹配 capability：

```yaml
rag:
  chat:
    skills:
      enabled: true
      locations:
        - classpath*:skills/
    http-tools:
      enabled: true
      max-total-response-bytes: 262144
      endpoints:
        - tool-name: getWeather
          skill-name: weather
          capability: weather.read
          base-url: https://weather.example.com
          path: /v1/current
          method: GET
          query-parameters:
            - name: city
              required: true
              max-length: 128
          response-content-types: [application/json]
          max-calls-per-request: 2
          timeout-ms: 5000
          max-response-bytes: 65536
          max-result-characters: 24000
          max-json-depth: 12
          max-json-nodes: 1000
          max-json-array-items: 100
          credential-env: WEATHER_API_TOKEN
          credential-header: Authorization
```

endpoint 仅允许 HTTPS `GET`/`HEAD`，禁止 redirect、私网/loopback/link-local/metadata
地址、任意 header 和模型指定 URL；固定 `path` 不允许百分号编码。DNS 解析得到的全部
地址先通过公网校验，再由 transport 钉扎到实际连接，TLS 仍按配置 hostname 校验。
凭据只从 `credential-env` 指定的进程环境变量读取。每个 endpoint 还受单请求调用数、
timeout、响应字节、JSON 深度/节点/数组项和工具结果字符上限约束；逻辑请求的累计响应
字节容量在下载前预留，避免并发或重复调用先读取、后超预算。

## 对话记忆配置

```yaml
spring:
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always
            platform: postgresql

rag:
  memory:
    max-messages: 20
    message-ttl-days: 30        # 0=不过期，非0=超过天数的历史记录被清理
    cleanup-cron: "0 0 3 * * *" # 每日凌晨3点执行清理（Asia/Shanghai时区）
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.memory.max-messages` | `20` | 单会话最大消息保留数 |
| `rag.memory.message-ttl-days` | `30` | 聊天历史保留天数（0=不过期） |
| `rag.memory.cleanup-cron` | `0 0 3 * * *` | 历史清理 cron 表达式（每日凌晨3点） |

系统维护双表：
- `spring_ai_chat_memory`：Spring AI JDBC 存储，项目只提交可恢复的 user/plain assistant
  消息，给 LLM 的近期上下文用
- `rag_chat_history`：按 principal 归属的业务历史，保存完整 `user_message`、
  `ai_response`、来源快照、mode/model 元数据和有界 `toolTranscript`，并按 TTL 清理

完成 turn 会在 `rag_chat_session_lease` 保护下原子更新两类存储。带 key 的 turn
还会在同一事务写入 V47 的 `rag_chat_turn_operations`，保存不可变的
transport-neutral 响应快照，并把业务 history 行绑定到 opaque `turn_id`。历史、导出、
清空与 Memory baseline 均按认证 principal 隔离。`GET /api/v1/rag/chat/turns/{turnId}`
提供状态查询，但不会暴露幂等 key 或其 hash。

## 异步线程池配置

```yaml
rag:
  async:
    core-pool-size: 4
    max-pool-size: 16
    queue-capacity: 100
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.async.core-pool-size` | `4` | 核心线程数 |
| `rag.async.max-pool-size` | `16` | 最大线程数 |
| `rag.async.queue-capacity` | `100` | 队列容量 |

## LLM API 超时配置

```yaml
rag:
  timeout:
    connect-timeout-ms: 10000   # 连接建立超时
    read-timeout-ms: 60000     # 读取超时（等待首字节时间）
    chat-ask-ms: 120000       # 非流式对话超时
    chat-stream-ms: 180000     # 流式对话超时（更长以支持 token 生成）
    search-ms: 30000          # 检索端点超时
    embed-ms: 60000           # 嵌入端点超时
    model-compare-ms: 90000    # 单模型对比超时
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.timeout.connect-timeout-ms` | `10000` | HTTP 连接建立超时（毫秒） |
| `rag.timeout.read-timeout-ms` | `60000` | LLM API 响应读取超时（毫秒） |
| `rag.timeout.chat-ask-ms` | `120000` | 非流式对话调用超时（毫秒） |
| `rag.timeout.chat-stream-ms` | `180000` | 流式对话调用超时（毫秒） |
| `rag.timeout.search-ms` | `30000` | 检索端点超时（毫秒） |
| `rag.timeout.embed-ms` | `60000` | 嵌入调用超时（毫秒） |
| `rag.timeout.model-compare-ms` | `90000` | 单模型对比超时（毫秒） |

超时作用于 `RestClient` 层级，对所有外部 LLM API 调用生效（OpenAI、Anthropic、MiniMax）。复杂查询或网络慢时建议增大配置值。

## 安全认证配置

```yaml
rag:
  security:
    root-api-key: ${RAG_ROOT_API_KEY:}
    api-key: ${RAG_API_KEY:}
    enabled: false
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.security.root-api-key` | `""` | 独立服务 root 凭据；对应环境变量 `RAG_ROOT_API_KEY` |
| `rag.security.enabled` | `false`（本地）/ `true`（`prod`） | 启用 API Key 认证 |
| `rag.security.api-key` | `""` | Legacy 静态全库 Key；root 模式下不参与认证 |

配置有效 `RAG_ROOT_API_KEY` 后进入独立服务 MVP 安全模式：

- 值必须至少 32 个不含空白的可打印 ASCII 字符；弱占位符会导致启动失败。
- 无论 `rag.security.enabled` 为何，所有 `/api/**` 都自动要求 environment root
  或有效的数据库业务 Key。
- 只接受 `Authorization: Bearer` 或 `X-API-Key` Header；query credential 被拒绝。
- 只有 environment root 能创建、列出、轮换和吊销业务 Key。
- root 不入库、不写日志；WebUI 只在当前页面内存中持有，刷新后需重新解锁。
- root 模式禁用空表 ADMIN 自动生成和 raw secret 日志分发。
- root 签发的业务 Key 可选择只读 `RAG_READ` 或完整
  `RAG_READ + RAG_WRITE` 数据面能力，不能管理 Key；省略能力字段时兼容为完整读写。
  expiry 必填、必须在未来且不设固定的最长有效期。

未配置 `RAG_ROOT_API_KEY` 时保持 legacy 行为：`rag.security.enabled` 控制认证开关，
`rag.security.api-key` 和数据库 ADMIN/NORMAL 语义继续生效，query credential 仍兼容。

数据库业务 Key 通过 `POST /api/v1/rag/api-keys` 的 `allowedCollectionKeys` 定义外部
范围；deprecated 的 `allowedCollectionIds` 继续兼容。V48 将 stable
`rag_api_principal` policy 与版本化 `rag_api_key` credential 分离，因此轮换会保留
`db:{principalId}` owner、role、Collection ACL、expiry、policy version 与 quota。
V49 在 principal policy 中增加规范化 `capabilities`：只允许 `RAG_READ` 或
`RAG_READ,RAG_WRITE`；创建省略时默认完整读写，策略更新省略时保留现值，轮换继承现值。
NORMAL principal 的读取和明确的只读 POST 需要 `RAG_READ`，其他写请求需要
`RAG_WRITE`；能力拒绝发生在共享 quota 计数之前。ADMIN、environment root、legacy
static 和关闭认证的兼容路径保持完整权限。
每次请求都联查权威 credential/principal；仅近似审计字段 `last_used_at` 的写入在五分钟内
抑制。legacy `api_key` 列为迁移兼容继续存在，但被约束为只能是 `NULL`。详见
[rest-api-zh-CN.md](rest-api-zh-CN.md)。

## API 限流配置

```yaml
rag:
  rate-limit:
    enabled: true
    backend: local
    requests-per-minute: 60
    strategy: ip
    key-limits:
      vip-key: 200
      basic-key: 60
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.rate-limit.enabled` | `false` | 启用 API 限流 |
| `rag.rate-limit.backend` | `local` | `local` 进程内计数或共享 `postgresql` UTC 固定分钟 bucket |
| `rag.rate-limit.requests-per-minute` | `60` | 默认每分钟最大请求数 |
| `rag.rate-limit.strategy` | `ip` | local 可选 `ip`、`api-key`、`user`；PostgreSQL 必须为 `principal` |
| `rag.rate-limit.key-limits` | `{}` | 仅 local 使用的分级限额；PostgreSQL 模式必须为空 |
| `rag.rate-limit.bucket-retention-minutes` | `1440` | PostgreSQL bucket 保留时间 |
| `rag.rate-limit.cleanup-interval-seconds` | `300` | best-effort 清理间隔 |
| `rag.rate-limit.cleanup-batch-size` | `10000` | 单轮清理最多删除的行数 |

**限流策略选择：**
- `ip`：按客户端 IP 独立计数，适合无认证场景
- `api-key`：按 API Key 限流（无 Key 回退 IP），适合多租户场景；`key-limits` 中未配置的 Key 使用默认 `requests-per-minute`

多实例受管调用方应使用：

```yaml
rag:
  rate-limit:
    enabled: true
    backend: postgresql
    strategy: principal
    requests-per-minute: 60
```

PostgreSQL backend 只使用认证后的 stable principal。principal policy 中可选的
`requestsPerMinute` 覆盖全局默认值；credential 轮换不会重置用量。store 故障时 fail
closed 返回 `503`，不会自动降级到 local。

超限返回 `429 Too Many Requests`，响应头包含 `Retry-After`、`X-RateLimit-Limit`、`X-RateLimit-Remaining`。

## CORS 跨域配置

```yaml
rag:
  cors:
    enabled: true
    allowed-origins:
      - "https://example.com"
      - "http://localhost:3000"
    allowed-methods: "GET,POST,PUT,DELETE,OPTIONS"
    allowed-headers: "*"
    max-age: 3600
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.cors.enabled` | `false` | 启用 CORS 配置 |
| `rag.cors.allowed-origins` | `["*"]` | 允许的源（生产环境应指定具体域名） |
| `rag.cors.allowed-methods` | `GET,POST,PUT,DELETE,OPTIONS` | 允许的 HTTP 方法 |
| `rag.cors.allowed-headers` | `*` | 允许的请求头 |
| `rag.cors.max-age` | `3600` | 预检请求缓存时间（秒） |

`./scripts/dev.sh` 会根据 `FRONTEND_PORT` 计算并启用精确的 Vite origin。生产部署仍应
显式配置 origin allow-list。

## 缓存配置

```yaml
rag:
  cache:
    maximum-size: 2000
    expire-after-write-minutes: 30
    embedding-maximum-size: 10000
    embedding-expire-after-write-hours: 2
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.cache.maximum-size` | `2000` | 检索结果 L1 缓存最大条目数 |
| `rag.cache.expire-after-write-minutes` | `30` | 检索结果缓存写入后过期时间（分钟） |
| `rag.cache.embedding-maximum-size` | `10000` | 嵌入缓存最大条目数 |
| `rag.cache.embedding-expire-after-write-hours` | `2` | 嵌入缓存写入后过期时间（小时） |

缓存使用 Caffeine 实现 L1 内存缓存，支持 LRU 驱逐。嵌入缓存基于内容哈希避免重复嵌入未变更文档。

## 分布式追踪配置

```yaml
rag:
  tracing:
    enabled: true
    sampling-rate: 1.0
    w3c-format: false
    span-id-enabled: false
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.tracing.enabled` | `true` | 启用请求追踪 |
| `rag.tracing.sampling-rate` | `1.0` | 采样率（0.0~1.0，1.0=全量追踪） |
| `rag.tracing.w3c-format` | `false` | 使用 W3C traceparent 格式输出（32 字符 traceId） |
| `rag.tracing.span-id-enabled` | `false` | 生成 spanId 支持嵌套追踪 |

追踪信息通过 `X-Trace-Id` 响应头传递，MDC 注入 traceId 写入日志。支持外部传入 `X-Trace-Id` 头实现跨服务链路追踪。

## API 版本管理

系统通过 `@ApiVersion("v1")` 注解支持 API 版本共存。当前所有端点标注为 `v1`，基础路径为 `/api/v1/rag`。

新增版本时，使用 `@ApiVersion("v2")` 标注新 Controller，即可实现 `/api/v1/rag` 和 `/api/v2/rag` 共存。

## 国际化

错误消息通过 Spring `MessageSource` 实现国际化，按 `Accept-Language` 请求头自动选择语言：

| 语言文件 | 语言 |
|----------|------|
| `messages.properties` | 默认（中文） |
| `messages_en.properties` | 英文 |
| `messages_zh_CN.properties` | 中文（简体） |

## 数据库配置

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DATABASE:spring_ai_rag_dev}
    driver-class-name: org.postgresql.Driver
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:20}
      minimum-idle: ${DB_POOL_MIN_IDLE:5}
      idle-timeout: 300000
      max-lifetime: 1800000
      connection-timeout: 10000
      leak-detection-threshold: 60000
      pool-name: rag-hikari
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

| HikariCP 属性 | 默认值 | 说明 |
|---------------|--------|------|
| `maximum-pool-size` | `20` | 最大连接数 |
| `minimum-idle` | `5` | 最小空闲连接 |
| `idle-timeout` | `300000` | 空闲连接回收时间（ms） |
| `max-lifetime` | `1800000` | 连接最大存活时间（ms） |
| `connection-timeout` | `10000` | 获取连接超时（ms） |
| `leak-detection-threshold` | `60000` | 连接泄漏检测时间（ms） |

## PostgreSQL 向量存储

应用使用自有的 `rag_embeddings` 表，而不是 Spring AI 独立的 vector-store 表。当前
1024 维活动 Profile 使用 `embedding_1024` 上的 Profile 专属 HNSW 索引；索引由
Embedding Profile 注册器管理。`postgresql` profile 提供 PostgreSQL 运行配置。Flyway
V25/V26 创建 Profile/状态结构，并在 `rag_vector_store` 不存在或为空时清理该无效表；
V27/V28 增加必填、全局唯一、不可变的 `rag_collection.collection_key`；V29 增加 JSONB
结构化记录列和 payload 感知的版本快照；V30 增加外部文档来源版本、tombstone、版本快照
以及 Collection 范围的外部身份唯一约束；V31 使用与 API 一致的 ASCII trim 语义规范化已存
external ID，并重建局部唯一索引；V32–V39 增加 Chat lease、持久化任务、过滤/诊断/质量
运营和无悲观锁协调；V40/V41 增加文档业务 revision、完整快照、source namespace、
generation fencing 与 lifecycle/idempotency contract；V42 增加权威外部快照对账 run
以及 SOURCE/RECONCILIATION 删除标记；V43 增加与 Profile 无关的本地关键词 chunk 和
独立的本地索引生命周期状态。

## Profile 一览

| Profile | 用途 |
|---------|------|
| `local` | 本地开发，从 `.env` 加载密钥 |
| `postgresql` | 启用 pgvector 自动配置 |

## 服务器配置

```yaml
server:
  port: 8081
```

## 监控配置

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  endpoint:
    health:
      show-details: always
```

Actuator 端点：
- `GET /actuator/health` — 健康检查（含数据库、向量存储、嵌入模型）
- `GET /actuator/metrics` — 指标（检索延迟、Token 用量等）
- `GET /actuator/info` — 应用信息

## 日志配置

```yaml
logging:
  level:
    com.springairag: INFO
    org.springframework.ai: INFO
```

建议生产环境将 `com.springairag` 设为 `INFO`，调试时改 `DEBUG` 可查看完整 RAG Pipeline 日志（查询改写 → 混合检索 → 重排 → Prompt 组装）。

## 配置继承

Starter 模块使用者只需配置：
1. 数据源（`spring.datasource.*`）
2. LLM 提供者（`spring.ai.openai.*` 或 `spring.ai.anthropic.*` + `app.llm.provider`）
3. 嵌入模型（`rag.embedding.*`）

其余配置项均有合理默认值，按需覆盖即可。
