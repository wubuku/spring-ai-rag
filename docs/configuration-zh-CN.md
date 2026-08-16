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

## 嵌入模型配置

嵌入模型配置独立于 Chat 提供者，始终生效。

```yaml
rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: ${SILICONFLOW_URL:https://api.siliconflow.cn}
    model: ${SILICONFLOW_MODEL:BAAI/bge-m3}
    dimensions: ${SILICONFLOW_DIMENSIONS:1024}
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
| `rag.retrieval.min-score` | `0.3` | 最低相似度阈值（低于此分数的结果被过滤） |

> 💡 `vector-weight + fulltext-weight` 建议和为 `1.0`，系统会自动归一化。

**全文检索策略（`fulltext-strategy`）：**

| 策略 | 说明 | 依赖 |
|------|------|------|
| `auto` | 自动检测：优先 pg_jieba → pg_trgm → 纯向量 | — |
| `pg_jieba` | PostgreSQL 中文分词（推荐中文场景） | `pg_jieba` 扩展 |
| `pg_trgm` | 三元组模糊匹配 | `pg_trgm` 扩展 |
| `none` | 禁用全文检索，纯向量检索 | — |

详见 [PostgreSQL 扩展文档](postgresql-extensions.md)。

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
| `rag.query-rewrite.enabled` | `true` | 启用查询改写 |
| `rag.query-rewrite.padding-count` | `2` | 扩展查询数 |
| `rag.query-rewrite.synonym-dictionary` | `{}` | 同义词词典 |
| `rag.query-rewrite.domain-qualifiers` | `[]` | 领域限定词 |
| `rag.query-rewrite.llm-enabled` | `false` | 启用 LLM 辅助改写 |
| `rag.query-rewrite.llm-max-rewrites` | `3` | LLM 改写最大数 |

## 重排序配置

```yaml
rag:
  rerank:
    enabled: false
    diversity-weight: 0.2
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.rerank.enabled` | `false` | 启用重排序 |
| `rag.rerank.diversity-weight` | `0.2` | 结果多样性权重（避免相似结果堆叠） |

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
```

这些限制用于保护 JSONB 请求、批量请求和响应大小，不会改变 embedding 输入：
只有调用者提供的 `retrievalText` 会被分块和嵌入。`jsonbPayload` 以 JSONB 保存，不计算
hash；API 设计上也没有 payload hash 配置。

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
- `spring_ai_chat_memory`：Spring AI 自动管理，给 LLM 上下文用
- `rag_chat_history`：业务审计表，保留完整 `user_message` + `ai_response`，按 TTL 自动清理

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
- root 签发的业务 Key固定为 `FULL_RAG` 数据面能力，不能管理 Key；expiry 必填、
  必须在未来且不设固定的最长有效期。

未配置 `RAG_ROOT_API_KEY` 时保持 legacy 行为：`rag.security.enabled` 控制认证开关，
`rag.security.api-key` 和数据库 ADMIN/NORMAL 语义继续生效，query credential 仍兼容。

数据库业务 Key 通过 `POST /api/v1/rag/api-keys` 的 `allowedCollectionKeys` 定义外部
范围；deprecated 的 `allowedCollectionIds` 继续兼容。Controller 会把 key 解析为内部
ID，Flyway V24 的存储仍为 `rag_api_key.allowed_collection_ids`；空值表示全库权限。
显式空 key 列表会被拒绝，不会静默变成全库权限。详见
[rest-api-zh-CN.md](rest-api-zh-CN.md)。

## API 限流配置

```yaml
rag:
  rate-limit:
    enabled: true
    requests-per-minute: 60
    strategy: ip
    key-limits:
      vip-key: 200
      basic-key: 60
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.rate-limit.enabled` | `true` | 启用 API 限流 |
| `rag.rate-limit.requests-per-minute` | `60` | 默认每分钟最大请求数 |
| `rag.rate-limit.strategy` | `ip` | 限流策略：`ip`（按 IP）/ `api-key`（按 API Key，无 Key 回退 IP） |
| `rag.rate-limit.key-limits` | `{}` | 按 API Key 分级限额（key → requests-per-minute） |

**限流策略选择：**
- `ip`：按客户端 IP 独立计数，适合无认证场景
- `api-key`：按 API Key 限流（无 Key 回退 IP），适合多租户场景；`key-limits` 中未配置的 Key 使用默认 `requests-per-minute`

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
以及 Collection 范围的外部身份唯一约束。

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
