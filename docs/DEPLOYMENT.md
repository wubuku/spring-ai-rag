# DEPLOYMENT.md — Spring AI RAG 部署指南

## 1. 环境要求

| 组件 | 最低版本 | 说明 |
|------|---------|------|
| Java | 21+ (LTS, 虚拟线程) | 必选 |
| PostgreSQL | 15+ | 需要 `vector` 和 `pg_trgm` 扩展 |
| Maven | 3.9+ | 构建工具 |
| LLM API | — | OpenAI / DeepSeek / Anthropic 等兼容 API |
| Embedding API | — | SiliconFlow（BGE-M3）或其他 OpenAI 兼容 API |

## 2. 数据库准备

### 2.1 安装扩展

```sql
CREATE EXTENSION IF NOT EXISTS vector;    -- pgvector 向量存储
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- 全文检索三元组匹配
```

### 2.2 创建数据库

```sql
CREATE DATABASE spring_ai_rag
    WITH ENCODING 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8';
```

### 2.3 迁移

项目使用 Flyway 自动迁移。首次启动时按顺序执行
`db/migration/V1__*.sql` 至 `V24__*.sql`。

如需手动迁移：

```bash
mvn flyway:migrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/spring_ai_rag \
  -Dflyway.user=postgres \
  -Dflyway.password=your_password
```

## 3. 配置

### 3.1 核心配置（application.yml）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag
    username: postgres
    password: your_password
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
  ai:
    openai:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        enabled: false
        options:
          model: deepseek-chat
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        enabled: false
        options:
          model: claude-sonnet-4-20250514

# LLM 配置（选择一个 provider）
app:
  llm:
    provider: openai    # openai | anthropic

# 嵌入模型（SiliconFlow BGE-M3）
rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: https://api.siliconflow.cn
    model: BAAI/bge-m3
    dimensions: 1024
  memory:
    max-messages: 20

# Actuator 监控
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  endpoint:
    health:
      show-details: always
```

### 3.2 环境变量

```bash
cp .env.example .env
# 仅在本机 .env 中填写 LLM、Embedding 与数据库凭据
```

## 4. 构建与运行

### 4.1 构建

```bash
cd spring-ai-rag
mvn clean install
mvn -f demos/demo-basic-rag/pom.xml clean package
```

### 4.2 运行

```bash
set -a
source .env
set +a
java -jar demos/demo-basic-rag/target/demo-basic-rag-1.0.0.jar \
  --spring.profiles.active=postgresql
```

### 4.3 Docker 部署

```bash
docker build -f docker/Dockerfile -t ghcr.io/wubuku/spring-ai-rag:1.0.0 .
docker run -d \
  --name spring-ai-rag \
  -p 8081:8081 \
  --env-file .env \
  -e POSTGRES_HOST=host.docker.internal \
  -e POSTGRES_DATABASE=spring_ai_rag \
  -e SPRING_PROFILES_ACTIVE=postgresql,prod \
  ghcr.io/wubuku/spring-ai-rag:1.0.0
```

生产环境同时发布不可变 tag `1.0.0`；不要只依赖 `latest`。

### 4.4 中国境内 Docker 构建

直接访问境外 registry 不稳定时，使用仓库脚本。它会按当前机器架构拉取境内镜像，失败后自动回退官方源，并把最终镜像写为 `spring-ai-rag:1.0.0`：

```bash
./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0
```

自建 Harbor 或其他团队镜像仓库可通过 `MIRROR_BASE_URL` 覆盖。不要修改 Dockerfile 去绑定单一镜像站：

```bash
MIRROR_BASE_URL=your.registry.example \
  ./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0
```

更多网络、架构与回退说明见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)。

## 5. 监控

### 5.1 健康检查

```bash
curl http://localhost:8081/api/v1/rag/health
# 自定义端点
curl http://localhost:8081/actuator/health
```

### 5.2 监控指标

```bash
# 所有指标
curl http://localhost:8081/actuator/metrics

# RAG 特定指标
curl http://localhost:8081/actuator/metrics/rag.requests.total
curl http://localhost:8081/actuator/metrics/rag.response.time
curl http://localhost:8081/actuator/metrics/rag.requests.success
```

### 5.3 关键指标说明

| 指标 | 类型 | 说明 |
|------|------|------|
| `rag.requests.total` | Counter | RAG 请求总数 |
| `rag.requests.success` | Counter | 成功请求数 |
| `rag.requests.failed` | Counter | 失败请求数 |
| `rag.response.time` | Timer | 响应时间分布（P50/P95/P99） |
| `rag.retrieval.results.total` | Gauge | 累计检索结果数 |
| `rag.llm.tokens.total` | Gauge | 累计 LLM token 消耗 |

## 6. 集成使用

### 6.1 作为依赖引入

```xml
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 6.2 自定义领域扩展

```java
@Component
public class MyDomainExtension implements DomainRagExtension {
    @Override
    public String getDomainId() { return "my-domain"; }

    @Override
    public String getDomainName() { return "我的领域"; }

    @Override
    public String getSystemPromptTemplate() {
        return "你是一个{领域}专家。基于以下资料回答：\n{context}";
    }
}
```

## 7. 故障排查

| 症状 | 检查项 |
|------|--------|
| 启动报 `vector` 扩展不存在 | `CREATE EXTENSION IF NOT EXISTS vector;` |
| 嵌入模型调用失败 | 检查 `rag.embedding.api-key` 和网络连通性 |
| LLM 返回 401 | 检查 API Key 是否正确 |
| 检索无结果 | 检查 `rag_embeddings` 表是否有数据 |
| 响应慢 | 查看 `/actuator/metrics/rag.response.time` 定位瓶颈 |
