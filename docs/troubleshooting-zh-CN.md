# 故障排查指南

> 📖 [English](troubleshooting.md) · 📖 [中文](troubleshooting-zh-CN.md)

> 常见问题和解决方案，按症状分类。
>
> 文档导航：[index-zh-CN.md](index-zh-CN.md)

---

## 启动问题

### Flyway 迁移失败

**症状**：启动时报 `FlywayException` 或 `Migration failed`

**原因**：PostgreSQL 未安装必需扩展

**解决**：

```sql
-- 连接到目标数据库执行
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

验证扩展安装：

```sql
SELECT extname FROM pg_extension WHERE extname IN ('vector', 'pg_trgm');
```

---

### 数据库连接失败

**症状**：`Connection refused` 或 `FATAL: database does not exist`

**排查步骤**：

```bash
# 1. 检查 PostgreSQL 是否运行
pg_isready -h localhost -p 5432

# 2. 检查数据库是否存在
psql -h localhost -U postgres -l | grep spring_ai_rag

# 3. 检查配置
grep -A5 "spring.datasource" application.yml
```

**常见原因**：
- PostgreSQL 未启动
- 端口不是 5432
- 数据库名不匹配
- 用户名/密码错误

---

### Bean 创建失败

**症状**：`NoSuchBeanDefinitionException` 或 `UnsatisfiedDependencyException`

**排查步骤**：

```bash
# 检查 starter 是否引入
mvn dependency:tree | grep spring-ai-rag-starter

# 检查组件扫描
grep -rn "@ComponentScan" src/
```

**解决**：确保 demo 的主类在 `com.springairag` 包下，或添加：

```java
@SpringBootApplication(scanBasePackages = "com.springairag")
```

---

## 嵌入问题

### 嵌入请求超时

**症状**：嵌入文档时长时间无响应，最终超时

**原因**：SiliconFlow API 限流或网络问题

**解决**：

```yaml
rag:
  embedding:
    timeout-ms: 30000      # 增加超时时间
    batch-size: 5           # 减小批次大小
    retry-count: 3          # 增加重试次数
```

---

### 向量维度不匹配

**症状**：`Vector dimension mismatch` 或 `expected 1024, got xxx`

**原因**：嵌入模型与数据库向量维度不一致

**排查**：

```sql
-- 检查 pgvector 列维度
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'rag_embeddings' AND column_name = 'embedding';
```

**解决**：确保 `rag.embedding.dimensions` 与 pgvector 列定义一致。BGE-M3 是 1024 维。

---

## 检索问题

### 检索结果为空

**症状**：查询返回空列表，但文档已导入

**排查步骤**：

```bash
# 1. 检查文档是否已嵌入
curl http://localhost:8081/api/v1/rag/documents/stats

# 2. 直接调用检索接口（跳过 LLM）
curl "http://localhost:8081/api/v1/rag/search?query=测试&limit=5"

# 3. 降低相似度阈值
curl -X POST http://localhost:8081/api/v1/rag/search \
  -H "Content-Type: application/json" \
  -d '{"query": "测试", "config": {"minScore": 0.1, "maxResults": 10}}'
```

**常见原因**：
- 文档未执行 `embed`（只创建了文档，未生成向量）
- `minScore` 阈值过高
- 查询语言与文档语言不匹配

---

### 检索结果质量差

**症状**：返回的结果与问题不相关

**优化策略**：

1. **启用查询改写**：
```yaml
rag:
  query-rewrite:
    enabled: true
    llm-enabled: true  # LLM 辅助改写
```

2. **调整混合检索权重**：
```bash
curl "http://localhost:8081/api/v1/rag/search?query=xxx&vectorWeight=0.7&fulltextWeight=0.3"
```

3. **启用重排序**：
```yaml
rag:
  rerank:
    enabled: true
    top-n: 5  # 调用方未提供 maxResults 时的最终 fallback 数量
    candidate-limit: 20  # rerank 前候选池上限，范围 1..100
```

`candidate-limit` 只扩大 rerank 前的内部候选池，不扩大最终响应。启用有效 rerank
时，候选池为 `max(request.maxResults, candidate-limit)`；Search、Chat、Agent 工具和
Evaluation 最终仍不超过请求的 `maxResults`。如质量没有改善或延迟升高，先将
`RAG_RERANK_CANDIDATE_LIMIT` 调为 `1`，再运行 goldenset 比较 MRR/nDCG 和 p95 延迟。

---

### 搜索页能看到结果，但对话回答“没有找到”

**症状**：搜索页输入短关键词能看到语义结果；对话中使用“找到 X 相关的内容”等完整命令时，
模型回答参考资料中没有相关内容。

**原因与判断**：

1. 搜索页和对话页使用同一检索服务，但查询文本不同。命令词可能改变查询向量，使较小的
   对话 Top-K 被无关内容占满。
2. `fulltextScore=0` 表示结果仅由向量语义通道召回，不代表文档包含该关键词。
3. PDF 提取文本可能含肉眼近似、码位不同的 CJK Radical/Kangxi Radical 字符。例如
   `风格基调` 与 `⻛格基调` 看起来接近，但全文检索不会视为同一词。
4. 正常的 `postgresql` profile 已启用 Spring AI 查询转换；如果服务是在该配置加入前启动的，
   必须重启服务。否则 `metadata.retrieval.effectiveQuery` 仍可能显示完整命令句。
5. Spring AI 多查询扩展会额外调用一次 Chat 模型。默认
   `query-expander-include-original=true`，会把原始请求保留在检索集合中，同时用生成的
   变体提高语义召回。`30s` 超时用于历史压缩；如果模型或网络更慢，可谨慎设置
   `RAG_CHAT_QUERY_TRANSFORM_TIMEOUT_SECONDS` 提高上限，并检查后端日志中的 transformer
   回退警告。

**排查**：

```bash
# 分别比较短主题和完整命令
curl "http://localhost:8081/api/v1/rag/search?query=风格基调&limit=5"
curl --get "http://localhost:8081/api/v1/rag/search" \
  --data-urlencode 'query=找到 “风格基调” 相关的内容' \
  --data 'limit=5'

# 查看 Chat 实际使用的检索词（需要配置 API Key）
curl -sS -H "X-API-Key: $RAG_ROOT_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"message":"找到和 “风格基调” 有关的内容","mode":"KNOWLEDGE"}' \
  http://localhost:8081/api/v1/rag/chat/ask \
  | jq '.metadata.retrieval'
```

启用 Spring AI 多查询策略后，`metadata.retrieval.effectiveQuery` 可能对应某一个扩展查询。
应同时检查响应 sources 和后端日志，确认原始精确词与语义变体都参与了检索。打开 Pipeline
INFO 日志后，应看到原始问题与扩展检索词：

```text
original query: "找到 “风格基调” 相关的内容" → retrieval queries:
  "找到 “风格基调” 相关的内容", "风格基调", "风格基调搜索"
```

若怀疑 PDF 字符映射问题，可检查包含相邻词的实际存储文本；修复抽取规则后需重新导入并嵌入
受影响文档。

---

## LLM 问题

### LLM 调用 401/403

**症状**：`Unauthorized` 或 `Forbidden`

**排查**：

```bash
# 检查 API Key 配置
echo $OPENAI_API_KEY | head -c 10
echo $SPRING_AI_OPENAI_API_KEY | head -c 10

# 检查 base-url 是否正确
grep -n "base-url\|baseUrl" spring-ai-rag-core/src/main/resources/application*.yml
```

**解决**：

1. 确认 API Key 有效且未过期，base-url 与 provider 匹配。  
2. **`base-url` 不要包含 `/v1` 后缀**。Spring AI 的 OpenAI 兼容客户端会自动追加 `/v1/chat/completions` 或 `/v1/embeddings`；若已带 `/v1`，最终变成 `/v1/v1/...` → 401/404。  
   - 正确示例：`https://api.deepseek.com`、`https://api.siliconflow.cn`  
   - 错误示例：`https://api.deepseek.com/v1`  
   - 参考：[Spring AI #710](https://github.com/spring-projects/spring-ai/issues/710)

### 启动后配置像没生效 / 连不上模型或数据库

**症状**：`.env` 已填写，但应用仍用默认值或报鉴权/连接失败。

**原因**：仅 `source .env` 后执行 `mvn spring-boot:run` 时，fork 出的 JVM 不一定继承全部变量。

**解决**：

```bash
# 推荐：项目脚本显式传参
bash scripts/start-server.sh

# 或 export 后启动，并确认 profile
export $(cat .env | grep -v '^#' | xargs)
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

本地默认端口为 **8081**（不是 8080）。

---

### LLM 响应慢

**症状**：单次问答耗时 >10 秒

**排查**：

```bash
# 查看各环节耗时（检查日志）
grep -i "retrieval\|llm\|total" logs/application.log | tail -10
```

**优化**：
- 减少 `maxResults`（检索结果数）
- 缩短系统提示词
- 使用更快的模型
- 启用流式响应（`/stream`）提升感知速度

---

### 流式响应中断

**症状**：SSE 流中途断开

**排查**：

```bash
# 检查 SSE 连接
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "test", "sessionId": "s1"}' 2>&1
```

**常见原因**：
- 反向代理（Nginx）超时：增加 `proxy_read_timeout`
- LLM 响应超时：增加 `rag.llm.timeout-ms`
- 客户端断开：检查网络稳定性

---

## API 问题

### 400 Bad Request

**响应**：

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "消息内容不能为空"
}
```

**排查**：检查请求体是否满足字段校验要求：

| 字段 | 约束 |
|------|------|
| `message` | 非空，≤10000 字符 |
| `sessionId` | 非空 |
| `title` | 非空 |
| `content` | 非空 |
| `name` | 非空，≤255 字符 |

---

### 401 Unauthorized

**原因**：启用了 API Key 认证但未提供

**解决**：

```bash
curl -H "X-API-Key: your-key" http://localhost:8081/api/v1/rag/chat/ask ...
```

或临时禁用认证：

```yaml
rag:
  security:
    api-keys: ""  # 清空则不启用认证
```

---

### 404 Not Found

**症状**：文档/集合 ID 不存在

**解决**：先查询确认 ID 存在：

```bash
curl http://localhost:8081/api/v1/rag/documents?page=0&size=10
curl http://localhost:8081/api/v1/rag/collections?page=0&size=10
```

---

## 监控问题

### 告警误报

**症状**：频繁收到 SLO 告警

**排查**：

```bash
# 查看活跃告警
curl http://localhost:8081/api/v1/rag/alerts/active

# 查看 SLO 配置
curl http://localhost:8081/api/v1/rag/alerts/slos
```

**调整**：

```yaml
rag:
  slo:
    search-latency-ms: 1000  # 调整搜索延迟阈值
    chat-latency-ms: 10000   # 调整问答延迟阈值
    error-rate-percent: 5    # 调整错误率阈值
```

---

## 性能问题

### 检索延迟高 (>500ms)

**排查**：

```sql
-- 检查索引是否存在
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'rag_embeddings';

-- 检查向量索引类型
SELECT * FROM pg_indexes WHERE indexdef LIKE '%hnsw%' OR indexdef LIKE '%ivfflat%';
```

**优化**：

如果启用了 rerank，先检查候选池是否明显大于请求数量：

```bash
rg -n "candidate-limit|RAG_RERANK_CANDIDATE_LIMIT" \
  spring-ai-rag-core/src/main/resources/application*.yml
```

`candidate-limit` 越大，向量/全文 SQL 的候选数和 HTTP rerank 请求体越大。先恢复为
`1` 作为回退，再用 goldenset 验证是否值得提高；不要通过放大请求 `maxResults` 来替代
服务端候选池配置。

```sql
-- 如果没有向量索引，创建 HNSW 索引
CREATE INDEX ON rag_embeddings
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 200);
```

```yaml
rag:
  search:
    cache-enabled: true      # 启用检索缓存
    cache-ttl-seconds: 300   # 缓存 5 分钟
```

---

### 内存占用高

**排查**：

```bash
# 查看 JVM 内存使用
jmap -heap $(pgrep -f spring-ai-rag)
```

**优化**：

```bash
# 限制 JVM 内存
java -Xms512m -Xmx1024m -jar spring-ai-rag.jar
```

---

## 日志与调试

### 开启详细日志

```yaml
logging:
  level:
    com.springairag: DEBUG
    org.springframework.ai: DEBUG
```

### 关键日志标识

| 日志关键字 | 说明 |
|-----------|------|
| `HybridRetrieverService` | 检索执行 |
| `QueryRewritingService` | 查询改写 |
| `ReRankingService` | 重排序 |
| `RagChatService` | 问答流程 |
| `DomainExtensionRegistry` | 领域扩展注册 |
| `ApiKeyAuthFilter` | API 认证 |

### 导出诊断信息

```bash
# 导出健康检查
curl http://localhost:8081/api/v1/rag/health | jq .

# 导出文档统计
curl http://localhost:8081/api/v1/rag/documents/stats | jq .

# 导出告警统计
curl http://localhost:8081/api/v1/rag/alerts/stats | jq .
```

---

## 中国境内网络问题

### Docker 在拉取基础镜像时连续超时

**症状**：构建停在 `FROM` / `load metadata`，访问 `gcr.io` 或 Docker Hub 连续超时。

**处理**：

```bash
# 默认从 docker.m.daocloud.io 拉取，失败后回退官方源
./scripts/docker-build-local.sh

# 使用团队镜像仓库
MIRROR_BASE_URL=your.registry.example ./scripts/docker-build-local.sh

# 当前网络直连 Docker Hub 更稳定时
./scripts/docker-build-local.sh --official
```

发布 Dockerfile 已移除 `gcr.io` 依赖，并允许通过 `MAVEN_IMAGE` / `RUNTIME_IMAGE` build arg 覆盖。架构、Maven/npm/Playwright 下载和代理注意事项见 [中国境内开发网络避坑指南](china-network-guide-zh-CN.md)。

### JSONB PostgreSQL 测试无法启动容器

**症状**：

- Testcontainers 报告 Docker API `1.32` 低于 daemon 要求的最低版本 `1.40`。
- Ryuk 辅助镜像因代理替换证书或 registry 超时而拉取失败。

**解决**：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-jsonb-records.sh --skip-playwright
```

验证脚本默认传递 `-Dapi.version=1.40`，并使用 `pgvector/pgvector:pg16`。本机环境不同
时可覆盖 `TESTCONTAINERS_API_VERSION`、`TESTCONTAINERS_RYUK_DISABLED` 或
`TESTCONTAINERS_PG_IMAGE`。这些是测试环境覆盖，不应写入 application YAML 或 Dockerfile。
CI / 共享环境的 registry 和证书链可信时，应重新启用 Ryuk。

### JSONB 验证器 preview 端口被占用

验证器的 Mock Playwright 阶段会使用严格端口绑定启动自己的 Vite preview。如果默认
`4174` 已被其他开发服务占用，应指定空闲端口，不要复用已有进程：

```bash
JSONB_PLAYWRIGHT_PORT=4199 ./scripts/verify-jsonb-records.sh
```

验证器会检查自身 preview 进程，并对 readiness 请求设置超时。端口占用应视为验证失败，
不能因此终止或复用其他项目的服务。

---

## 获取帮助

- [架构设计](architecture.md) — 理解系统设计
- [配置参考](configuration.md) — 所有配置项
- [测试指南](testing-guide.md) — 如何编写测试
- [中国境内网络避坑](china-network-guide-zh-CN.md) — Docker / Maven / npm / Playwright
- GitHub Issues — 提交 Bug 报告
