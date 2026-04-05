# 故障排查指南

> 📖 English | 📖 中文

> 常见问题和解决方案，按症状分类。

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
curl http://localhost:8080/api/v1/rag/documents/stats

# 2. 直接调用检索接口（跳过 LLM）
curl "http://localhost:8080/api/v1/rag/search?query=测试&limit=5"

# 3. 降低相似度阈值
curl -X POST http://localhost:8080/api/v1/rag/search \
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
curl "http://localhost:8080/api/v1/rag/search?query=xxx&vectorWeight=0.7&fulltextWeight=0.3"
```

3. **启用重排序**：
```yaml
rag:
  rerank:
    enabled: true
    top-k: 20  # 重排前保留的结果数
```

---

## LLM 问题

### LLM 调用 401/403

**症状**：`Unauthorized` 或 `Forbidden`

**排查**：

```bash
# 检查 API Key 配置
echo $OPENAI_API_KEY | head -c 10

# 检查 base-url 是否正确
grep "base-url" application.yml
```

**解决**：确认 API Key 有效且未过期，base-url 与 provider 匹配。

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
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
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
curl -H "X-API-Key: your-key" http://localhost:8080/api/v1/rag/chat/ask ...
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
curl http://localhost:8080/api/v1/rag/documents?page=0&size=10
curl http://localhost:8080/api/v1/rag/collections?page=0&size=10
```

---

## 监控问题

### 告警误报

**症状**：频繁收到 SLO 告警

**排查**：

```bash
# 查看活跃告警
curl http://localhost:8080/api/v1/rag/alerts/active

# 查看 SLO 配置
curl http://localhost:8080/api/v1/rag/alerts/slos
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
curl http://localhost:8080/api/v1/rag/health | jq .

# 导出文档统计
curl http://localhost:8080/api/v1/rag/documents/stats | jq .

# 导出告警统计
curl http://localhost:8080/api/v1/rag/alerts/stats | jq .
```

---

## 获取帮助

- [架构设计](architecture.md) — 理解系统设计
- [配置参考](configuration.md) — 所有配置项
- [测试指南](testing-guide.md) — 如何编写测试
- GitHub Issues — 提交 Bug 报告
