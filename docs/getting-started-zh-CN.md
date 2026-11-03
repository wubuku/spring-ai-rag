# 开发者上手指南

> 📖 English | 📖 中文

> 从零开始运行 spring-ai-rag，5 分钟跑通第一个 RAG 问答。

---

## 前置条件

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 21+（已在 21、25 上验证；编译目标 21） | 必选 |
| Maven | 3.9+ | 构建工具 |
| PostgreSQL | 15+ | 需安装 `vector` 和 `pg_trgm` 扩展 |
| API Key | LLM + Embedding | DeepSeek/OpenAI + SiliconFlow |

---

## 1. 克隆项目

```bash
git clone https://github.com/wubuku/spring-ai-rag.git
cd spring-ai-rag
```

## 2. 数据库准备

```bash
createdb spring_ai_rag_dev
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
# 可选：中文分词
# psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_jieba;"
```

Flyway 会在首次启动时自动创建表结构（V1 到最新版本）。

## 3. 配置环境变量

`.env` 已在 `.gitignore` 中，不会被提交。以 `.env.example` 为模板：

```bash
cp .env.example .env
```

至少替换下面几项（变量名必须与 `.env.example` 一致；旧的
`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL` 仍可作为回退）：

```bash
# 数据库（对应 createdb 的库名）
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DATABASE=spring_ai_rag_dev
POSTGRES_USER=postgres
POSTGRES_PASSWORD=yourpassword

# LLM（默认 DeepSeek，OpenAI 兼容；base-url 不要带 /v1）
SPRING_AI_OPENAI_API_KEY=sk-xxx
SPRING_AI_OPENAI_BASE_URL=https://api.deepseek.com
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=deepseek-chat

# 嵌入模型（示例：SiliconFlow BGE-M3）
RAG_EMBEDDING_API_KEY=sk-xxx
RAG_EMBEDDING_BASE_URL=https://api.siliconflow.cn
RAG_EMBEDDING_MODEL=BAAI/bge-m3
RAG_EMBEDDING_DIMENSIONS=1024
```

没有真实凭据时，可以用仓库自带的 mock provider 跑通链路（只验证流程，
不验证回答质量）：

```bash
./scripts/run-mock-llm.sh --port 8086 &
# 然后在 .env 中设置：
# SPRING_AI_OPENAI_API_KEY=mock-key
# SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:8086
# SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=mock-chat
# RAG_EMBEDDING_API_KEY=mock-key
# RAG_EMBEDDING_BASE_URL=http://127.0.0.1:8086
# RAG_EMBEDDING_MODEL=mock-embedding
# RAG_RETRIEVAL_MIN_SCORE=0
```

## 4. 构建 & 测试

```bash
# 加载 .env（值里含空格或引号时也比 export $(cat ...) 可靠）
set -a; source .env; set +a

# 先安装本仓库模块：demos 依赖 com.springairag:spring-ai-rag-starter:1.0.0，
# 该构件不在 Maven Central，必须先从源码 install 到本地仓库。
mvn -B -DskipTests install

# 运行全部后端测试（需要 .env 里的真实数据库）
mvn clean test
```

## 5. 启动 Demo

```bash
cd demos/demo-basic-rag
set -a; source ../../.env; set +a
mvn spring-boot:run
```

服务启动在 `http://localhost:8081`。先确认健康检查通过：

```bash
curl -sf http://localhost:8081/actuator/health
```

## 6. 第一次 RAG 问答

### 导入文档

```bash
curl -X POST http://localhost:8081/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Spring AI 介绍",
    "content": "Spring AI 是 Spring 生态的 AI 应用框架，提供 ChatClient、VectorStore、Advisor 等抽象。",
    "source": "manual"
  }'
```

响应里的 `id` 就是文档 ID（不要假设它一定是 1）：

```bash
DOC_ID=$(curl -s -X POST http://localhost:8081/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title":"Spring AI 介绍","content":"Spring AI 是 Spring 生态的 AI 应用框架，提供 ChatClient、VectorStore、Advisor 等抽象。","source":"manual"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
```

### 嵌入向量

```bash
curl -X POST "http://localhost:8081/api/v1/rag/documents/${DOC_ID}/embed"
```

### 检索（不经过 LLM）

```bash
curl -s "http://localhost:8081/api/v1/rag/search?query=Spring%20AI&limit=3"
```

### RAG 问答

```bash
curl -X POST http://localhost:8081/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 Spring AI？"}'
```

### 流式问答

```bash
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "解释 RAG 的工作原理"}'
```

> 上述完整流程已固化在 `scripts/verify-clean-clone.sh`：它在临时目录做干净 clone、
> 建临时数据库、用 mock provider 跑通 health → documents → embed → search → chat，
> 结束时清理。可随时执行：`./scripts/verify-clean-clone.sh`。

---

## 项目结构

```
spring-ai-rag/
├── spring-ai-rag-api/              # 接口定义、DTO
├── spring-ai-rag-core/             # 核心实现
│   ├── advisor/                    # RAG Pipeline Advisors
│   ├── controller/                 # REST 端点
│   ├── config/                     # 配置类
│   ├── retrieval/                  # 混合检索、查询改写
│   ├── service/                    # 业务服务
│   └── entity/                     # JPA 实体
├── spring-ai-rag-starter/          # Spring Boot 自动配置
├── spring-ai-rag-documents/        # 文档处理
│   └── chunk/                      # 分块策略
└── demos/
    ├── demo-basic-rag/             # 基础 RAG 示例
    ├── demo-multi-model/           # 多模型示例
    ├── demo-component-level/       # 组件级集成示例
    └── demo-domain-extension/      # 领域扩展示例
```

---

## 切换 LLM 提供者

### 使用 Anthropic

```yaml
app:
  llm:
    provider: anthropic

spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
```

### 使用智谱

```yaml
app:
  llm:
    provider: openai

spring:
  ai:
    openai:
      api-key: ${ZHIPU_API_KEY}
      base-url: https://open.bigmodel.cn/paas/v4
```

更多配置选项见 [配置参考](configuration.md)。

---

## 开发模式

### 热重载

```bash
# 使用 spring-boot:run 的 devtools 支持
cd demos/demo-basic-rag
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=true"
```

### 单元测试

```bash
# 只跑核心模块测试
mvn test -pl spring-ai-rag-core

# 跑特定测试类
mvn test -pl spring-ai-rag-core -Dtest=RagChatControllerTest
```

### JaCoCo 覆盖率

```bash
mvn test jacoco:report
# 报告在各模块的 target/site/jacoco/index.html
```

---

## 常见问题

**Q: Flyway 迁移失败？**

确保 PostgreSQL 已安装 `vector` 扩展。连接到数据库执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

**Q: 嵌入请求超时？**

SiliconFlow 免费 API 有限流。检查 `rag.embedding.batch-size`（默认 10）和 `rag.embedding.timeout-ms`。

**Q: 如何自定义分块策略？**

实现 `TextChunker` 接口或使用 `HierarchicalTextChunker`，配置 `rag.chunking.*` 参数。详见 [架构设计](architecture.md#文档处理)。

---

## 下一步

- [架构设计](architecture.md) — 理解 RAG Pipeline 和扩展机制
- [配置参考](configuration.md) — 全部配置项详解
- [领域扩展指南](extension-guide.md) — 接入你的业务领域
- [REST API 参考](rest-api.md) — 完整端点文档
