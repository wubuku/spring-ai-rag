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
| 服务 / 后端单独启动默认端口 | `8081` |
| `dev.sh` 后端端口 | `18082` |
| 本地 profile | `postgresql` |
| 真实 LLM E2E 端口 | `18081` |
| Embedding | SiliconFlow `BAAI/bge-m3` |
| 向量维度 | `1024` |
| Flyway | V1–V59 |

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

执行文档 CRUD、外部全量同步、版本恢复、一次性 PostgreSQL、reference client 和 WebUI
验收：

```bash
./scripts/verify-document-lifecycle.sh
```

针对 V42/V51 Sync Run、一次性 PostgreSQL、认证权限与持久化 item receipt 完整 HTTP
合同的专项验收：

```bash
./scripts/verify-document-sync-runs.sh
```

该门禁会创建临时受限读写/只读 principal，并验证 ACL、游标分页、终态复扫语义、
失败回执恢复、`no-store` 与敏感信息保护；证据不保存 credential、cursor、external ID
或业务 payload。

针对按调用方隔离、跨 PostgreSQL/双后端实例/进程重启恢复的 Collection 创建持久化幂等：

```bash
./scripts/verify-collection-provisioning.sh
```

该门禁覆盖 V52 迁移与约束、精确 replay、key 语义复用冲突、owner 隔离、restricted
ACL、并发首次创建、软删除后的当前状态、恰好一次创建审计、账本故障关闭和不含 secret
的数据库事实。只复跑一次性双实例 HTTP 阶段可设置
`COLLECTION_PROVISIONING_VERIFY_PHASE=http`。

针对 V56 Collection 内容清理、永久 key tombstone、引用级联和 WebUI preview/apply：

```bash
./scripts/verify-collection-purge.sh
```

默认使用 Testcontainers 运行 5 个真实 PostgreSQL 场景；也可用
`COLLECTION_PURGE_IT_JDBC_URL`、`COLLECTION_PURGE_IT_USERNAME`、
`COLLECTION_PURGE_IT_PASSWORD` 和
`COLLECTION_PURGE_IT_CLEAN_CONFIRM=YES` 指向调用方提供的一次性数据库。脚本同时执行
聚焦后端、Maven clean 编译门槛、完整 WebUI、无截图 Collection Mock Playwright、
禁锁、文档、脚本语法和空白检查，证据写入
`.verification/collection-purge/<run-id>/`。

针对模型调用级持久用量账本及按 principal 隔离的聚合 API：

```bash
LLM_USAGE_LEDGER_VERIFY_RUN_ID=usage-ledger-gate \
./scripts/verify-llm-usage-ledger.sh
```

该门禁执行归因/recorder/API 聚焦测试，把一次性 PostgreSQL 从空库迁移到 V53，执行
完整 Maven 与 WebUI 门槛、禁悲观锁和项目文档规则，并执行不使用截图的 Metrics Mock
Playwright。证据写入 `.verification/llm-usage-ledger/<run-id>/`，不保存密钥或业务
内容。该脚本不调用真实 provider；专项门禁通过后，使用[测试指南](testing-guide-zh-CN.md)
中的真实 LLM 生命周期流程，在隔离服务和一次性数据库上验收。

针对 V43 本地关键词/向量派生解耦边界：

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-4 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4191 \
./scripts/verify-keyword-vector-decoupling.sh
```

该门禁要求真实 PostgreSQL 生命周期/全文集成测试、
`mvn clean compile test-compile`，以及 WebUI TypeScript、Vitest、production build、
alignment 和无截图 Mock Playwright 检查。

## 3. 启动与健康检查

前后端一键开发入口：

```bash
./scripts/dev.sh
```

该脚本完整导出仓库根目录 `.env` 给 Maven / Spring Boot，并为后端放行本次精确的
Vite origin；只有 root 管理 POST 探针通过后才报告 ready。默认启动：

```text
Backend: http://127.0.0.1:18082
WebUI:   http://127.0.0.1:15173/webui/unlock
```

如果 `.env` 或调用环境未设置 `RAG_ROOT_API_KEY`，脚本会为当前后端进程生成临时 root
credential；macOS 默认复制到剪贴板，不写入文件或日志。状态、停止和端口覆盖：

```bash
./scripts/dev.sh --status
./scripts/dev.sh --stop
./scripts/dev.sh --force-kill
BACKEND_PORT=19082 FRONTEND_PORT=15174 ./scripts/dev.sh
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
```

默认启动遇到非本启动器管理的端口监听时会保守失败，不会误杀进程。仅在明确确认目标
`BACKEND_PORT` / `FRONTEND_PORT` 上的旧进程可以终止时使用 `--force-kill`；该参数只终止
这两个端口的监听进程及其子进程，先发送 `TERM`，超时后才发送 `KILL`，随后继续正常启动。

启动器绝不自动执行 Flyway repair。若启动时检测到迁移 checksum 不一致，它会直接输出
相关根因；正确处理方式是恢复已经执行过的迁移，并把后续变化放入新的迁移，而不是改写
schema 历史。

只启动后端：

```bash
bash scripts/start-server.sh
```

手动启动：

```bash
set -a
source .env
set +a
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

后端单独运行 `8081` 时的端口清理和健康检查：

```bash
lsof -ti :8081 | xargs kill -9 2>/dev/null
curl -fsS http://127.0.0.1:8081/actuator/health
```

Swagger：`http://127.0.0.1:8081/swagger-ui.html`

使用前后端一键启动器时，改用 `18082`：

```bash
curl -fsS http://127.0.0.1:18082/actuator/health
```

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

直接运行时默认监听 `http://127.0.0.1:15173/webui/`，并把 `/api` 代理到
`http://127.0.0.1:8081`。日常前后端联调优先从仓库根目录运行 `./scripts/dev.sh`，
由启动器统一端口和代理目标。

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
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/real-llm-e2e-smoke.sh
```

该流程会执行 provider preflight、创建唯一文档、embedding、search、ask 和 stream。
如果配置了 `RAG_ROOT_API_KEY`，必须通过 `RAG_API_KEY` 或等价的 `X-API-Key` 传给
数据面请求；脚本也会自动从 `.env` 读取 root key。Mock Playwright 不能替代真实
LLM 验证。

Collection 受保护清理的真实 provider 生命周期使用
`scripts/real-collection-purge-e2e-smoke.sh`。它要求运行中的隔离服务和一次性数据库，
并验证事件优先嵌入、真实检索/Chat、purge/replay、退役拒绝与 tombstone；完整命令和
证据安全边界见 [测试指南](testing-guide-zh-CN.md#collection-受保护清理与退役验收门禁)。

本轮 Chat turn 幂等性验收使用独立的 PLAIN smoke，不要求 Embedding provider：

```bash
BASE_URL=http://127.0.0.1:18081 \
./scripts/real-llm-chat-idempotency-smoke.sh
```

该脚本强制使用 OpenAI-compatible Chat provider，验证原生 JSON/SSE 首次请求、相同
key 重放、key 冲突、turn 状态查询，并通过
`/actuator/metrics/rag.chat.provider.calls` 的前后计数证明重放没有再次调用 provider。
服务启动时仍应使用隔离 PostgreSQL 和独占端口；不要把 API key 写入命令行历史或文档。

### Chat 对话能力一键验证

该门禁包含 Maven clean 输出，必须串行执行：

```bash
./scripts/verify-chat-capability.sh
```

脚本会验证 `KNOWLEDGE`、`AGENT`、`PLAIN` 三种模式，Spring AI Tool Calling 边界，
principal 隔离的 Memory/历史，V32 会话 lease、V46 持久化摘要 CAS、V47 Chat turn 幂等重放、V48 stable managed principal 与共享 quota、有界执行 metadata、
结构化 SSE，WebUI 模式/能力/来源展示，以及 Chat 导出来源快照；同时执行
`NextHighValueFeaturesPostgresIntegrationTest` 矩阵和独立的领域扩展、只读 SQL
工具 demo 测试。每一步都会记录到
`.verification/chat-capability/<run-id>/summary.md`。

PostgreSQL/Testcontainers 默认配置：

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

Docker 不可用时，脚本会把 PostgreSQL 门禁记录为 `SKIP`，不会伪称通过。显式使用
`--skip-postgres` 也必须在 summary 中保留。Docker API `1.32` 与 daemon 最低 `1.40`
不匹配的已知问题见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)。

Chat Mock Playwright 使用严格绑定且可覆盖的 Vite preview 端口：

```bash
CHAT_PLAYWRIGHT_PORT=4199 ./scripts/verify-chat-capability.sh
```

浏览器门禁只使用 DOM、网络、URL 和测试断言；截图不作为正确性证据。真实 Provider 调用
必须显式开启：

```bash
./scripts/verify-chat-capability.sh --with-real-llm
```

启用后，脚本会创建一次性 PostgreSQL 数据库，在隔离端口启动
`scripts/dev.sh`（默认后端 `18083`、WebUI `15175`），执行真实 WebUI
`chat-real.spec.ts` 和 provider smoke，最后清理服务、临时环境文件和数据库。
`.env` 或调用环境必须提供 `RAG_ROOT_API_KEY`；需要时可用
`CHAT_REAL_BACKEND_PORT`、`CHAT_REAL_FRONTEND_PORT` 覆盖端口。未开启时，
真实 LLM 步骤会明确记录为 `SKIP`。

### OpenAI 兼容一键验证

```bash
./scripts/verify-openai-compatibility.sh
```

该脚本验证 model alias、请求级 Collection scope/ACL、完整 text-only messages、
非流式 OpenAI JSON、兼容错误信封、SSE chunk 顺序和 `[DONE]`，并执行相关
`test-compile`、Shell 语法和空白门禁。日志写入
`.verification/openai-compatibility/<run-id>/`。兼容 Controller 默认关闭；运行服务时
需要显式设置 `RAG_OPENAI_COMPATIBILITY_ENABLED=true`。

### 持久化 Embedding Jobs 一键验证

```bash
./scripts/verify-embedding-jobs.sh
```

脚本覆盖 service、worker、HTTP API、V33 migration、活动任务 coalesce 与双 worker
原子条件 claim。默认自动启动隔离的 `pgvector/pgvector:pg16` 容器；已有数据库时可用
`EMBEDDING_JOBS_IT_JDBC_URL`、`EMBEDDING_JOBS_IT_USERNAME` 和
`EMBEDDING_JOBS_IT_PASSWORD` 覆盖。验证日志位于
`.verification/embedding-jobs/<run-id>/`。

<a id="document-lifecycle-verification"></a>

### 文档生命周期一键验证

```bash
./scripts/verify-document-lifecycle.sh
```

该命令验证本地文档 create/PATCH/disable/restore/permanent-delete、外部 TEXT/JSON
`collectionKey + sourceNamespace + externalId`、revision CAS、完整快照、正文变化后的
generation-aware 重嵌入、非文本更新不重嵌入、WebUI CRUD 和 reference client。

脚本优先从当前 shell 或 `.env` 的 `POSTGRES_*` 创建一次性数据库，避免 Testcontainers
与新 Docker daemon 的协议协商问题；也可显式提供
`DOCUMENT_LIFECYCLE_IT_JDBC_URL`、`DOCUMENT_LIFECYCLE_IT_USERNAME` 和
`DOCUMENT_LIFECYCLE_IT_PASSWORD`。不得指向开发库或生产库。证据保存在
`.verification/document-data-plane/<run-id>/`。

### 文档迁移与派生完整性一键验证

```bash
./scripts/verify-document-relocation.sh
./scripts/verify-derivation-integrity.sh
```

两个专项门禁都执行禁悲观锁检查、聚焦 HTTP 测试、一次性 PostgreSQL 集成测试、
`mvn clean compile test-compile`、WebUI typecheck/Vitest/生产构建/alignment、无截图 Mock
Playwright、双语文档门禁和空白检查。证据分别写入
`.verification/relocation/<run-id>/` 与
`.verification/derivation-integrity/<run-id>/`。

默认使用 Testcontainers；也可通过 `NEXT_HIGH_VALUE_IT_JDBC_URL`、
`NEXT_HIGH_VALUE_IT_USERNAME`、`NEXT_HIGH_VALUE_IT_PASSWORD` 指向调用方创建的专用
一次性数据库，并必须显式设置 `NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES`。该测试会清空
数据库，绝不能指向开发库或生产库。可用 `NEXT_HIGH_VALUE_PLAYWRIGHT_PORT` 指定 Mock
Playwright 的 Vite preview 起始端口。

### 受管 API Principal 一键验证

```bash
MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=minimax \
./scripts/verify-managed-api-principals.sh --with-real-llm
```

该门槛在 Mock 与构建检查通过后，使用两个后端（默认 `18181`、`18182`）、一个 Vite
前端（默认 `15181`）和一次性 PostgreSQL 执行真实全栈及有界真实 LLM 验收。V55 矩阵
验证跨实例 provisioning 幂等、运行时能力发现、NORMAL principal 的只读/读写能力、
策略 CAS、staged prepare/replay/complete/cancel/deadline/family revoke、overlap 共享
quota，以及写请求在限流前返回 `403`。真实 LLM 模式要求即时数据访问和 staged
complete/cancel/revoke 生命周期共 9 次成功 provider 调用，同时 replay 和拒绝请求不能增加
provider counter。端口冲突时可
分别覆盖 `MANAGED_API_BACKEND_A_PORT`、`MANAGED_API_BACKEND_B_PORT` 和
`MANAGED_API_FRONTEND_PORT`。`MANAGED_API_REAL_LLM_PROVIDER` 支持 `openai`、
`minimax` 和 `anthropic`，脚本只校验并装载所选 provider 的配置；证据位于
`.verification/managed-api-principals/<run-id>/`。

### 受管 API Principal 到期告警一键验证

```bash
# 聚焦后端、V1-V59 PostgreSQL 与前端 Mock 门槛
API_KEY_EXPIRY_ALERT_VERIFY_PHASE=focused \
./scripts/verify-api-key-expiry-alerts.sh

# 加上 Maven clean、禁锁、文档、shell、diff 与新增行密钥扫描
./scripts/verify-api-key-expiry-alerts.sh
```

脚本覆盖到期配置校验、事务 after-commit Spring Event、异步代理合同、create/update/revoke
生命周期、operator-only Alerts API、通知渠道、V57 多实例 dedupe/CAS、阶段升级、自动解决、
公平 fallback scan、WebUI `firedAt` 与无截图 Alerts Mock Playwright。默认使用
`pgvector/pgvector:pg16` Testcontainers；也可通过
`API_PRINCIPAL_EXPIRY_ALERT_IT_JDBC_URL` 等变量指向明确允许清空的一次性数据库。证据写入
`.verification/api-key-expiry-alerts/<run-id>/`。

### 告警通知 Durable Outbox 一键验证

```bash
./scripts/verify-alert-notification-delivery.sh

MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=openai \
./scripts/verify-managed-api-principals.sh \
  --with-real-llm \
  --with-durable-notifications
```

第一条命令使用隔离 PostgreSQL、真实本地 HTTP provider、双后端实例和真实 WebUI，覆盖
Event 首投、transient retry、单 attempt 单 HTTP 调用、进程退出/过期 lease 恢复、低敏
receipt 和无截图 DOM/network Playwright。第二条在全部本地门槛通过后再实际调用 `.env`
中的 Chat/Embedding 服务，并让受管 principal 的 WARNING/CRITICAL 告警经过 V58 durable
delivery。证据分别位于 `.verification/alert-notification-delivery/<run-id>/` 和
`.verification/managed-api-principals/<run-id>/`。

<a id="业务服务接入就绪一键验证"></a>

### 业务服务接入就绪一键验证

```bash
./scripts/verify-business-client-readiness.sh
```

该门槛从 focused API/core 测试开始，串行创建一次性 PostgreSQL 集成测试数据库，执行
`mvn clean compile test-compile`、WebUI typecheck/Vitest/生产构建、核心 Mock
Playwright、文档/禁锁/密钥/diff 检查，最后启动一次性 PostgreSQL、确定性 embedding
stub、真实 Spring Boot 和真实 Vite 前端，运行通用业务 credential HTTP 合同与真实 API
Key Playwright。

只复跑真实服务阶段：

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

最终候选 commit 要求 Git tree 干净：

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

默认端口为后端 `18084`、embedding stub `18085`、Mock 前端 `15184`、真实前端 `15185`；
可分别用 `BUSINESS_CLIENT_BACKEND_PORT`、`BUSINESS_CLIENT_EMBEDDING_PORT`、
`BUSINESS_CLIENT_MOCK_FRONTEND_PORT`、`BUSINESS_CLIENT_REAL_FRONTEND_PORT` 覆盖。
PostgreSQL 镜像可用 `BUSINESS_CLIENT_POSTGRES_IMAGE` 覆盖。证据写入
`.verification/business-client-readiness/<run-id>/`，private credential 文件、容器、
端口和进程由退出 trap 清理。真实 HTTP 合同包含只读/canary binding preflight、运行时
限制强制、按 principal/Collection 隔离的 operation observability、重启持久化，以及
provider `503` 后的 Record 保留语义。`release-manifest.json` 锁定完整 Git SHA、初始
tree state、项目/OpenAPI 版本、API base path、最新 Flyway migration、passed steps、
PostgreSQL image、HTTP 检查数、已验证 credential 画像，以及实测 JSON batch
item/payload 上限与 operation-observability 状态；未到达的运行时事实为 JSON `null`，
不记录 credential、URL、payload、external ID 或 private path。

也可以对已经运行的实例单独执行已部署 binding runner：

```bash
./scripts/business-client-binding-preflight.sh
```

它默认只读。`RAG_BINDING_*` 输入见[业务服务接入指南](business-client-integration-zh-CN.md)；
`RAG_BINDING_MIN_JSON_BATCH_ITEMS`、
`RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES` 与
`RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY` 可以增加 fail-closed 的运行时要求。
Mutation 模式必须使用专用 canary Collection，并且机器报告不包含 credential、URL、
Collection key、external ID 或 payload。

本门禁验证真实 Spring AI embedding HTTP 路径，但本能力不改变 Chat，因此不调用 Chat
LLM。接入契约和部署 binding 见
[业务服务接入指南](business-client-integration-zh-CN.md)。

### 检索诊断 / metadata 过滤 / 嵌入运营 / 受管质量

```bash
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-no-pessimistic-locks.sh
# 或一次跑完 A–D：
./scripts/verify-next-high-value-features.sh
```

这些脚本分别覆盖 V35 诊断、V36 metadata `@>` 下推、V37 embedding 运营分页/readiness，
V38 受管 suite 与 citation 校验，以及 V39 后的数据访问并发规则。禁锁脚本静态拒绝
`FOR UPDATE`、`SKIP LOCKED`、JPA `PESSIMISTIC_*` 和 PostgreSQL advisory lock；
其余脚本默认启动隔离 PostgreSQL，可用对应 `*_IT_JDBC_URL` 覆盖。

### JSONB 结构化记录一键验证

运行 JSONB 实现及其 API、数据库、WebUI、文档和空白检查的可重复门禁：

```bash
./scripts/verify-jsonb-records.sh
```

只有在浏览器依赖不可用时才使用 `--skip-playwright`，并在验证记录中明确记载跳过。
脚本默认自动启动隔离 PostgreSQL，绕开 Testcontainers 1.20.4 与新 Docker daemon API
协商不兼容；也可通过 `JSONB_IT_JDBC_URL`、`JSONB_IT_USERNAME`、
`JSONB_IT_PASSWORD` 复用调用者提供的隔离数据库。镜像可用
`TESTCONTAINERS_PG_IMAGE` 覆盖。日志和 Markdown 汇总写入
`.verification/jsonb-verification/<run-id>/`。
Mock Playwright preview 使用 `JSONB_PLAYWRIGHT_PORT`（默认 `4174`），并启用严格端口绑定，
不会复用无关进程。如果端口已被占用，请指定空闲端口，例如：

```bash
JSONB_PLAYWRIGHT_PORT=4199 ./scripts/verify-jsonb-records.sh
```

该门禁必须串行执行：其中的 `mvn clean` 不能与使用相同模块 `target/` 目录的其他 Maven
测试进程并发运行。

### JSONB 真实 HTTP E2E

在已经启动的 PostgreSQL profile 服务上执行 JSON structured-record 的真实 HTTP 链路：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/jsonb-records-e2e.sh
```

脚本会验证 JSON record upsert、collection-scoped search、detail、payload-only 更新、
`retrievalText` 更新、clone/export/import，以及使用 root 创建临时受限 API Key 后的
允许/拒绝范围。`embed=true` 会调用真实 embedding provider；不会调用 Chat LLM。
需要跳过 ACL 时必须显式使用 `--skip-acl`，并把该事实记录在验证结果中。脚本不会打印
API Key 或完整 payload，临时响应写入被忽略的 `.verification/jsonb-e2e/` 后清理。

<a id="external-document-synchronization-http-e2e"></a>

### 外部文档同步真实 HTTP E2E

在已经启动的 PostgreSQL profile 服务上运行普通外部文档同步流程：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/external-documents-e2e.sh
```

脚本会验证 `embed=false` 创建、精确重放、带
`expectedSourceRevision` 的更新、CAS 冲突、同 revision 冲突、批量 upsert、按外部身份
查询、tombstone 删除与重放，以及使用不同后续 `sourceRevision` 恢复。默认还会验证内容变化后的成功重新
embedding。只有 embedding provider 确实不可用时才设置
`EXTERNAL_DOCUMENT_E2E_EMBED=false`；此时脚本会明确记录 embedding 检查被跳过，不会
伪报 embedding 完成。日志写入被忽略的 `.verification/external-documents-e2e/`，脚本不会
输出 API Key 或完整文档内容。

## 8. Goldenset 与发布门禁

检索 goldenset：

```bash
BASE_URL=http://127.0.0.1:8081 ./scripts/run-retrieval-goldenset.sh
```

版本化真实检索回归：

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-quality-regression.sh
```

rerank 文档多样化专项验收会聚合后端测试、PostgreSQL/pgvector、WebUI 门禁、隔离
`dev.sh`、真实 Search/Playwright、goldenset、版本化回归和真实 LLM：

```bash
./scripts/verify-rerank-document-diversity.sh
```

runner 会拒绝覆盖已有 `.dev` 栈，默认使用隔离端口 `18083`/`15175`，创建一次性
PostgreSQL 数据库（优先本机，失败时回退 Docker），生成的 root key 只保存在 shell，
证据写入 `.verification/rerank-document-diversity/`。真实 provider baseline 通过后，
runner 会在同一测试库和夹具上依次用 cap=`0`、cap=`2` 重启服务，默认各采集 20 个 Search
和 5 个 Chat 固定样本。它通过 trace ID 只读关联 `rag_retrieval_logs`，把 retrieval/rerank
p95、HTTP 响应 payload 和最终文档覆盖写入 `runtime-comparison.json` /
`runtime-comparison.md`；这些墙钟与 payload 数据是观测证据，不是易波动的阈值门禁。

关联到的数据库结果数表示 latest retrieval outcome 数量。Search 要求它与最终 HTTP
结果数相等；KNOWLEDGE Chat 的 HTTP sources 经过 advisor 的 query join、rerank 和
prompt budget 后处理，因此运行时产物单独记录两者关系，不强制把不同阶段的数量视为相同。

Chat 样本只对明确的瞬时 HTTP `429/502/503/504` 做有界重试，正整数上限由
`RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS` 控制（默认 `2`）。每次重试都会输出日志；Search
和不可重试失败仍立即失败。

数据集和提交的 baseline 位于 `testdata/regression/`。runner 使用稳定
`collectionKey + sourceNamespace(default) + externalId` 身份创建 fixture，检查 Hit Rate、MRR、Recall@K、nDCG、
minimum、相对 baseline 回退、Collection decoy 泄漏和 JSONB 明确空结果，并把 JSON
artifact 与 Markdown 汇总写入 `.verification/quality-regression/<run-id>/`。未显式设置
`RAG_API_KEY` 时会安全读取 `.env` 的 `RAG_API_KEY` / `RAG_ROOT_API_KEY`，不会输出密钥。
可用 `./scripts/run-retrieval-regression.sh --self-test` 在不启动服务时检查 runner 对当前
`READY` 及兼容 `COMPLETED/CACHED` embedding 成功态的判定。

发布级一键验证：

```bash
./scripts/verify-release.sh
./scripts/verify-release.sh --with-quality-regression
./scripts/verify-release.sh --with-local-runtime
```

`--with-quality-regression` 对已经启动的 `BASE_URL` 追加版本化回归；
`--with-local-runtime` 默认包含 HTTP E2E、goldenset、质量回归和真实 LLM smoke。

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
