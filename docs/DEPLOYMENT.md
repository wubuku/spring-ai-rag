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
`db/migration/V1__*.sql` 至当前最新迁移（目前为
`V55__bounded_api_credential_rotation.sql`）。

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
    retry-max-attempts: ${RAG_EMBEDDING_RETRY_MAX_ATTEMPTS:10}
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

### 3.3 独立 RAG 服务与 Web 管理台

独立服务 MVP 使用环境变量 `RAG_ROOT_API_KEY` 解锁 Web 管理台并签发业务 Key。
root 至少为 32 个不含空白的可打印 ASCII 字符，不能使用示例占位符。可在部署环境中
生成随机值：

```bash
export RAG_ROOT_API_KEY="$(openssl rand -base64 48 | tr -d '\n')"
```

启动后访问 `http://localhost:8081/webui/unlock`，输入 root 解锁控制台。root 只保存在
当前页面内存；刷新、关闭页面或退出后需要重新输入。控制台可创建、列出、分阶段轮换、
即时轮换和吊销 `FULL_RAG` 业务 Key，业务 Key 用于外部系统调用 RAG 数据面，不需要
访问 WebUI。

```bash
curl http://localhost:8081/api/v1/rag/search?query=example \
  -H "Authorization: Bearer ${RAG_BUSINESS_API_KEY}"
```

部署边界：

- `postgresql` profile 下，受管 principal、Collection ACL、共享 quota、即时吊销和
  V55 分阶段轮换以 PostgreSQL 为多实例共同真相源；本地 quota backend 仍只适合显式的
  单实例兼容环境。
- 数据面必须使用 TLS；WebUI 只部署在本地或受控管理网络。
- root 通过 Secret 管理系统或环境变量注入，不写入镜像、YAML、日志或 Git。
- 修改 root 后重启实例生效；没有 WebUI 修改 root 的接口。
- root 模式拒绝 `?apiKey=`，只接受 Bearer 或 `X-API-Key` Header。

#### 分阶段 Credential 轮换

滚动更新多个调用实例时，优先使用 staged rotation：

1. 先从能力发现响应确认 `features.credentialRotation.staged=true`。
2. 使用稳定的 `Idempotency-Key` prepare，并把首次响应中的一次性 replacement secret
   和 `rotationId` 原子写入受控 Secret 存储。
3. 在有界 overlap 内滚动更新调用实例，并分别验证 `/auth/me`、Collection binding 和
   代表性读写请求。新旧 credential 共享同一个 stable principal、ACL 和 quota。
4. 全部实例切换成功后 complete；需要放弃部署时必须在 deadline 前 cancel。deadline
   到达后 retiring credential 会在认证查询中立即失效，不依赖 cleanup 是否及时执行。

即时 `/api/v1/rag/api-keys/{keyId}/rotate` 仍用于明确要求原子切换的兼容场景；它会在
事务提交后立即禁用旧 credential。完整 HTTP 契约见
[rest-api-zh-CN.md](rest-api-zh-CN.md#api-key-管理)，调用方操作手册见
[business-client-integration-zh-CN.md](business-client-integration-zh-CN.md#6-重试与-credential-生命周期)。

#### V54/V55 滚动升级与回滚

- Flyway V55 是向前兼容的增量迁移，不执行破坏性 schema 回退。
- V54/V55 混合实例期间必须冻结 API Key 管理写，尤其禁止 prepare staged rotation；
  V54 binary 不理解同一 principal 同时存在 current 与 retiring 两个 enabled row。
- 只有所有服务实例都运行 V55 后，才允许调用方使用 staged rotation。
- 应用回滚到 V54 前，必须确认不存在 enabled retiring credential，也不存在
  `PENDING` rotation operation。保留 V55 schema，并暂停依赖 staged rotation 的调用方。
- 合并或升级基线后必须重新执行 PostgreSQL、Maven、WebUI、Mock/真实 HTTP 与真实模型
  验收，不能沿用升级前结果。命令见
  [testing-guide-zh-CN.md](testing-guide-zh-CN.md)。

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
# Kubernetes/编排器探针
curl http://localhost:8081/actuator/health/liveness
curl http://localhost:8081/actuator/health/readiness
```

Actuator readiness 表示进程、Spring readiness 与数据库可用，不承诺外部 embedding
provider 可用或任一 Collection 已完成派生索引。Collection 级状态应查询
`/api/v1/rag/collections/embedding-readiness` 或 derivation readiness API。

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
