# 开发者上手指南

> 从零开始运行 spring-ai-rag，5 分钟跑通第一个 RAG 问答。

---

## 前置条件

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 推荐 21 |
| Maven | 3.9+ | 构建工具 |
| PostgreSQL | 15+ | 需安装 `vector` 和 `pg_trgm` 扩展 |
| API Key | LLM + Embedding | DeepSeek/OpenAI + SiliconFlow |

---

## 1. 克隆项目

```bash
git clone https://github.com/your-org/spring-ai-rag.git
cd spring-ai-rag
```

## 2. 数据库准备

```sql
CREATE DATABASE spring_ai_rag;

-- 连接到新数据库后执行
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Flyway 会自动创建表结构（首次启动时）。

## 3. 配置环境变量

在项目根目录创建 `.env`：

```bash
# LLM（默认 DeepSeek，OpenAI 兼容）
OPENAI_API_KEY=sk-xxx
OPENAI_BASE_URL=https://api.deepseek.com/v1

# 嵌入模型（SiliconFlow BGE-M3）
SILICONFLOW_API_KEY=sk-xxx

# 数据库
DB_URL=jdbc:postgresql://localhost:5432/spring_ai_rag
DB_USERNAME=postgres
DB_PASSWORD=yourpassword
```

## 4. 编译 & 测试

```bash
# 加载环境变量并编译
export $(cat .env | grep -v '^#' | xargs)
mvn clean compile

# 运行全部测试（700+ 个）
mvn test
```

## 5. 启动 Demo

```bash
cd demos/demo-basic-rag
export $(cat ../../.env | grep -v '^#' | xargs)
mvn spring-boot:run
```

服务启动在 `http://localhost:8080`。

## 6. 第一次 RAG 问答

### 导入文档

```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Spring AI 介绍",
    "content": "Spring AI 是 Spring 生态的 AI 应用框架，提供 ChatClient、VectorStore、Advisor 等抽象。",
    "source": "manual"
  }'
```

### 嵌入向量

```bash
# 假设文档 ID 是 1
curl -X POST http://localhost:8080/api/v1/rag/documents/1/embed
```

### RAG 问答

```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 Spring AI？"}'
```

### 流式问答

```bash
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "解释 RAG 的工作原理"}'
```

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
    └── demo-basic-rag/             # 示例项目
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
