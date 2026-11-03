# 配置错误快速失败矩阵

本页记录“配置写错时会发生什么”：失败发生在启动期还是首次调用，错误消息是否指出配置项，以及哪些情况曾经静默降级、现在已改为显式失败。相关背景见[配置参考](configuration-zh-CN.md)、[多模型外部配置](multi-model-external-config-zh-CN.md)、[测试指南](testing-guide-zh-CN.md)。

## 1. 总览

原则：**能在启动期判断的配置错误，一律在启动期失败**；确实要等到请求才知道的错误（上游 401、collection 不存在等），必须在首次调用时给出可操作的错误码与消息。

| 配置错误 | 失败时机 | 结果 |
|----------|----------|------|
| 上游 `base-url` 带 `/v1` | 启动期 | 归一化并 WARN，不再产生 `/v1/v1/...` |
| 上游 `base-url` 含未解析占位符 | 启动期 | `IllegalStateException`，消息指出配置项与占位符 |
| 远程主机使用明文 `http` | 启动期 | 拒绝启动，并给出 `rag.security.allow-insecure-upstream` 逃生阀 |
| 空 / 无效 embedding key | 启动期 | 创建 `embeddingModel` 时失败 |
| 空 chat provider key | 启动期（标记）/ 首次调用（报错） | 模型标记 unavailable；无 fallback 时首次调用失败 |
| chat / embedding 路由引用不存在的模型 | 启动期 | 拒绝启动，消息指出 `app.models.*.primary/fallbacks` |
| `rag.embedding` 维度与 Profile 不一致 | 启动期 | 拒绝启动（需要数据库） |
| Profile key 与已播种 Profile 不一致 | 启动期 | 拒绝启动（需要数据库） |
| 数据源不可达 / 账号错误 | 启动期 | Flyway / 连接池初始化失败 |
| Hikari 池参数非法（负数、`min>max`） | 启动期 | 拒绝启动，消息指出 `spring.datasource.hikari.*` |
| `rag.timeout.*` 超出允许范围 | 启动期 | 拒绝启动，消息指出具体超时项 |
| `rag.async.*` 线程池参数非法 | 启动期 | 拒绝启动，消息指出具体线程池项 |
| `security.enabled=true` 但无任何静态 key | 启动期（WARN） | 启动成功，所有请求 fail closed（401） |
| `prod` + `security.enabled=false` | 启动期 | 拒绝启动 |
| 显式 `app.models.config-file` 不存在 / 不可读 / 非法 JSON | 启动期 | 拒绝启动，消息指出 `app.models.config-file` |
| `models.json` 使用 kebab-case 键 | 启动期 | 拒绝启动（这些键会静默失效） |
| `models.json` 含未知键 | 启动期（WARN） | 忽略并列出完整路径 |
| `collectionKey` 引用不存在 | 首次调用 | HTTP 404 + `COLLECTION_NOT_FOUND` |
| 数据库迁移版本低于应用要求 | 启动期 | Flyway 自动迁移 |
| 数据库迁移版本高于应用 | 启动期 | Flyway validate 失败，拒绝启动 |

## 2. 上游地址与凭据

### 2.1 base-url 归一化

所有模型上游地址统一经过 `UpstreamEndpointPolicy`：

- 末尾 `/` 被去掉；
- 末尾 `/v1` 被去掉并输出 WARN，因为 Spring AI 会自行追加 `/v1/...`；
- 未解析的 `${...}` 占位符视为配置缺失，直接失败；
- 非 `http(s)`、无 host、带 userinfo、带 query 或 fragment 的 URL 直接失败。

覆盖的配置项：

| 路径 | 配置项 |
|------|--------|
| 内置 ChatModel | `spring.ai.openai.base-url`、`spring.ai.anthropic.base-url`、`spring.ai.minimax.base-url` |
| 外部模型 | `app.models.providers.<id>.base-url` |
| Embedding | `rag.embedding.base-url`、`rag.embedding.profiles.*.base-url` |

### 2.2 明文 http 的边界

默认只允许回环地址（`localhost`、`127.x`、`::1`）使用明文 `http`。远程主机必须使用 `https`；可信内网可显式设置：

```yaml
rag:
  security:
    allow-insecure-upstream: ${RAG_ALLOW_INSECURE_UPSTREAM:false}
```

该开关只影响模型上游地址，不放宽 `rag.security.enabled` 的鉴权语义。

### 2.3 密钥缺失

- **Embedding**：Bean 创建阶段（启动期）失败，消息为 `Embedding API key is not configured for the selected Profile`。这是正确的，因为检索链路无法在无 key 的情况下工作。
- **Chat**：`ConfiguredChatModelFactory` 把空 key、未解析占位符或非法配置的模型标记为 unavailable；主模型不可用且没有可用 fallback 时，首次调用抛出可操作的模型解析错误。配置了 fallback 时会切换，这是有意设计（见第 8 节）。

## 3. 模型与路由

### 3.1 模型引用必须存在

`app.models.chat-model.primary`、`app.models.chat-model.fallbacks`、`app.models.embedding-model.primary`、`app.models.embedding-model.fallbacks` 引用的 `providerId/modelId` 必须在 `app.models.providers` 中真实存在，且 provider 已启用、模型类型匹配。否则启动失败，错误消息包含具体配置项与非法引用。

### 3.2 Embedding Profile 与向量维度

启动时 `EmbeddingProfileRegistry` 会：

- 校验 Profile key、provider、model、revision、distance、normalization 非空；
- 只接受 `COSINE`；
- 通过 `EmbeddingVectorColumns.columnFor` 校验维度必须是 384 / 768 / 1024 / 1536；
- 将配置与数据库中已播种的 Profile 逐字段比较，维度、模型、revision 等任一不一致都会拒绝启动。

因此“只改 `dimensions` 不建新 Profile”不会静默污染数据，而是直接启动失败。换模型必须新建 Profile 并重嵌入，见[项目上下文](project-context-zh-CN.md)。

## 4. 数据源与连接池

- 数据源不可达、账号错误：由 Flyway 或连接池初始化在启动期抛出，属于快速失败。
- `spring.datasource.hikari.maximum-pool-size < 1`、`minimum-idle < 0`、`minimum-idle > maximum-pool-size`、`connection-timeout < 250`：由 `RagDataSourceStartupValidator` 拒绝启动。以前 `min>max` 只会被 Hikari 打 WARN 并静默压到 `maximum-pool-size`，现在改为显式失败。
- 未配置 `spring.datasource.url` 时跳过校验，便于不连数据库的切片测试。

## 5. 安全开关

- `prod` / `production` profile 且 `rag.security.enabled=false`：拒绝启动，消息要求设置 `RAG_SECURITY_ENABLED=true` 与 `RAG_ROOT_API_KEY`。
- `rag.security.enabled=true` 但 `api-key` 与 `root-api-key` 都为空：启动成功并输出 WARN。这是有意的 fail closed：`/api/**` 全部返回 401，数据库中已登记的 managed API key 仍然可用。健康检查 `ragSecurityPosture` 继续报告 UP，避免编排系统重启健康实例。

## 6. 运行时错误与 404

`collectionKey` 不存在不属于启动期错误，因为集合是运行期数据。`CollectionIdentityResolver` 抛出 `RagException`，错误码为 `COLLECTION_NOT_FOUND`，HTTP 映射为 404，消息包含非法 `collectionKey`。

## 7. 迁移版本

- 数据库低于应用：Flyway 在启动期自动迁移到最新版本，不需要人工开关。
- 数据库高于应用（例如回滚到旧镜像）：Flyway validate 失败，启动被拒绝，避免旧代码读写新 schema。
- 迁移文件位于 `spring-ai-rag-core/src/main/resources/db/migration/`，修改 schema 必须新增迁移。

## 8. 静默降级清单

以下行为仍然保留“降级而不是失败”，因为它们属于运行期弹性，而不是配置错误：

| 行为 | 原因 | 是否应改为报错 |
|------|------|----------------|
| chat 主模型不可用时切换 fallback | 多模型路由的设计目标 | 否；但 `/models` 会显示 unavailable 原因 |
| `models.json` 未知键被忽略 | 前向兼容 | 否；已在启动日志列出完整路径 |
| 安全开启但无静态 key 只 WARN | managed key 仍可用 | 否；已有明确 WARN 与健康检查 |
| 向量维度不匹配 | —— | 已改为启动失败，不再降级 |
| Hikari `min>max` 被 clamp | —— | 已改为启动失败，不再降级 |
| `app.models.config-file` 缺失回退 YAML | —— | 已改为启动失败，不再降级 |

## 9. 测试与门禁

- 启动期校验的单测：`UpstreamEndpointPolicyTest`、`RagAsyncPropertiesTest`、`RagTimeoutPropertiesTest`、`RagDataSourceStartupValidatorTest`、`MultiModelConfigLoaderTest`、`ConfiguredChatModelFactoryStartupValidationTest`、`SecurityPostureConfigurationTest`。
- 需要数据库的 Profile 维度 / key 一致性断言放在 gated 套件中，按 `scripts/verify-gated-it.sh` 运行。
- 提交前除 `mvn test` 外，还需通过 `scripts/verify-project-docs.sh`、`scripts/verify-integration-test-switches.mjs`、`scripts/verify-no-pessimistic-locks.sh`。

## 10. 排查

1. 先看启动失败消息里的配置项名称，直接修正对应属性或环境变量。
2. `base-url` 报 `/v1/v1` 相关 404：确认配置没有以 `/v1` 结尾。
3. 远程 `http` 被拒绝：改用 `https`；仅在可信内网才打开 `allow-insecure-upstream`。
4. Embedding 启动失败：检查 `rag.embedding.*` 与活动 Profile 的 key、维度、模型、revision 是否一致。
5. `min>max` 或超时范围错误：按消息给出的上下界调整，不要依赖框架兜底。
