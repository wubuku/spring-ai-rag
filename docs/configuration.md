# 配置参考

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
      base-url: https://api.deepseek.com/v1
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
  openai:
    api-key: ${OPENAI_API_KEY}
    base-url: ${OPENAI_BASE_URL:https://api.deepseek.com/v1}
    chat:
      enabled: false
      options:
        model: ${OPENAI_MODEL:deepseek-chat}
        temperature: ${OPENAI_TEMPERATURE:0.7}
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `spring.openai.api-key` | (必填) | API Key |
| `spring.openai.base-url` | `https://api.deepseek.com/v1` | API 端点 |
| `spring.openai.chat.options.model` | `deepseek-chat` | 模型名称 |
| `spring.openai.chat.options.temperature` | `0.7` | 生成温度 |

### Anthropic 配置

```yaml
spring:
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

## 嵌入模型配置

嵌入模型配置独立于 Chat 提供者，始终生效。

```yaml
rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: ${SILICONFLOW_URL:https://api.siliconflow.cn/v1}
    model: ${SILICONFLOW_MODEL:BAAI/bge-m3}
    dimensions: ${SILICONFLOW_DIMENSIONS:1024}
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.embedding.api-key` | `""` | SiliconFlow API Key |
| `rag.embedding.base-url` | `https://api.siliconflow.cn/v1` | API 端点 |
| `rag.embedding.model` | `BAAI/bge-m3` | 嵌入模型名称 |
| `rag.embedding.dimensions` | `1024` | 向量维度（必须与模型输出一致） |

> ⚠️ 更换嵌入模型时，`dimensions` 必须同步修改，且需重建 pgvector 表索引。

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
| `rag.chunk.min-chunk-size` | `100` | 最小分块大小（字符） |

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
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.memory.max-messages` | `20` | 单会话最大消息保留数 |

系统维护双表：
- `spring_ai_chat_memory`：Spring AI 自动管理，给 LLM 上下文用
- `rag_chat_history`：业务审计表，保留完整 `user_message` + `ai_response`

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

## 安全认证配置

```yaml
rag:
  security:
    api-key: ${RAG_API_KEY:}
    enabled: false
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `rag.security.enabled` | `false` | 启用 API Key 认证 |
| `rag.security.api-key` | `""` | API Key 值 |

启用后，所有 `/api/v1/**` 请求需携带 `X-API-Key` 头。

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

## pgvector 向量存储配置

```yaml
# 通过 postgresql profile 激活
spring:
  ai:
    vectorstore:
      pgvector:
        enabled: true
        vector-table-name: rag_vector_store
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        dimensions: 1024
```

激活方式：`--spring.profiles.active=postgresql`

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `vector-table-name` | `rag_vector_store` | 向量表名 |
| `distance-type` | `COSINE_DISTANCE` | 距离算法（COSINE_DISTANCE / EUCLIDEAN_DISTANCE / NEGATIVE_INNER_PRODUCT） |
| `index-type` | `HNSW` | 索引类型（HNSW / IVFFlat） |
| `dimensions` | `1024` | 向量维度 |

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
