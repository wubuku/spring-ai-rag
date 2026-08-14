# 开发者参考

> [English](developer-reference.md) | [中文](developer-reference-zh-CN.md)

> **用途**：提供可复制的构建、启动、数据库、模型、WebUI、E2E 和发布验证命令。
> **维护原则**：命令必须与仓库脚本保持一致；本地 Agent 状态文件可以链接本文，但本文不依赖本地状态。

文档总入口：[index-zh-CN.md](index-zh-CN.md)。稳定项目认知：[project-context-zh-CN.md](project-context-zh-CN.md)。

## 1. 固定约定

| 项目 | 值 |
|------|----|
| Java | 21+ |
| Maven | 3.9+ |
| 默认端口 | `8081` |
| 本地 profile | `postgresql` |
| 真实 LLM E2E 端口 | `18081` |
| Embedding | SiliconFlow `BAAI/bge-m3` |
| 向量维度 | `1024` |
| Flyway | V1–V24 |

OpenAI / Embedding 的 `base-url` **不要带 `/v1`**。Spring AI 会自行追加 `/v1/chat/completions` 或 `/v1/embeddings`。

## 2. 构建与测试

```bash
mvn clean compile
mvn test
mvn clean package -DskipTests
```

单模块和单测试：

```bash
mvn test -pl spring-ai-rag-core
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest
```

覆盖率：

```bash
mvn clean test jacoco:report
open spring-ai-rag-core/target/site/jacoco/index.html
```

测试策略和更多命令见 [testing-guide-zh-CN.md](testing-guide-zh-CN.md)。

### 文档体系

一键检查项目文档边界、链接、双语结构、固定约定、命令、空白和敏感信息：

```bash
./scripts/verify-project-docs.sh
```

## 3. 启动与健康检查

推荐入口：

```bash
bash scripts/start-server.sh
```

手动启动：

```bash
export $(grep -v '^#' .env | grep -v '^$' | xargs)
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

端口占用和健康检查：

```bash
lsof -ti :8081 | xargs kill -9 2>/dev/null
curl -fsS http://127.0.0.1:8081/actuator/health
```

Swagger：`http://127.0.0.1:8081/swagger-ui.html`

## 4. 数据库

- PostgreSQL 默认连接信息以 `.env` 为准。
- 必需扩展：`vector`。
- 推荐扩展：`pg_trgm`；`pg_jieba` 可选。
- 迁移目录：`spring-ai-rag-core/src/main/resources/db/migration/`。

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

优先使用已安装扩展的 Docker PostgreSQL。扩展说明见 [postgresql-extensions.md](postgresql-extensions.md)。

## 5. 模型配置

### Embedding

```text
Provider: SiliconFlow
Model: BAAI/bge-m3
Dimensions: 1024
Base URL: https://api.siliconflow.cn
```

### Chat Provider

| Provider | 配置入口 |
|----------|----------|
| OpenAI-compatible | `spring.ai.openai.*` |
| Anthropic | `spring.ai.anthropic.*` |
| MiniMax | `spring.ai.minimax.*` |

Provider 默认通过 `LLM_PROVIDER` / `app.llm.provider` 选择。多模型实例和外部配置见 [multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md)。

真实密钥只放 `.env`，不要写入命令历史、Markdown 或 Git。

## 6. WebUI

```bash
cd spring-ai-rag-webui
npm ci
npm run lint
npm run test:run
npm run build
```

开发模式：

```bash
npm run dev
```

生产 bundle 由发布流程复制到：

```text
spring-ai-rag-core/src/main/resources/static/webui/
```

## 7. E2E

### 常规 HTTP E2E

```bash
bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 bash scripts/e2e-test.sh
```

### WebUI Playwright

```bash
cd spring-ai-rag-webui
npm run build
npx vite preview --host 127.0.0.1 --port 4173
BASE_URL=http://127.0.0.1:4173 npx playwright test
```

### 真实 LLM

```bash
./scripts/start-real-e2e-server.sh
BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh
```

该流程会执行 provider preflight、创建唯一文档、embedding、search、ask 和 stream。Mock Playwright 不能替代真实 LLM 验证。

## 8. Goldenset 与发布门禁

检索 goldenset：

```bash
BASE_URL=http://127.0.0.1:8081 ./scripts/run-retrieval-goldenset.sh
```

发布级一键验证：

```bash
./scripts/verify-release.sh
./scripts/verify-release.sh --with-local-runtime
```

日志和汇总写入 `target/release-verification/<run-id>/`。门禁详情见 [release-checklist-zh-CN.md](release-checklist-zh-CN.md)。

## 9. Docker 与境内网络

境内优先：

```bash
./scripts/docker-build-local.sh
```

Dockerfile 基础镜像保持可覆盖，不硬编码区域源。DaoCloud、阿里云 Maven、npm、Playwright 和 Git 代理经验见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)。

## 10. 关键路径

| 路径 | 用途 |
|------|------|
| `spring-ai-rag-api/` | DTO、SPI |
| `spring-ai-rag-core/` | 核心实现和可运行应用 |
| `spring-ai-rag-starter/` | 自动配置 |
| `spring-ai-rag-documents/` | 文档处理 |
| `spring-ai-rag-webui/` | React 管理台 |
| `scripts/` | 启动、E2E、goldenset、文档与发布验证 |
| `docker/` | Dockerfile 和 Compose |
| `k8s/` | Helm Chart |

## 11. 排障入口

- 通用排障：[troubleshooting-zh-CN.md](troubleshooting-zh-CN.md)
- 配置参考：[configuration-zh-CN.md](configuration-zh-CN.md)
- 境内网络：[china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)
- Claude Code + grok：[claude-grok-proxy-zh-CN.md](claude-grok-proxy-zh-CN.md)
