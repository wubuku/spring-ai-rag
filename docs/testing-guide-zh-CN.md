# 测试指南

新增[候选隔离、阶段时限、回答契约与字段级诊断验收](candidate-isolation-answer-contract-zh-CN.md#5-验收入口)。
受控 provider 验证应用处置，不冒充真实模型质量；仍需后端 API、PostgreSQL、SDK、真实模型与 dev.sh 门槛。

本批增量能力：[可信断言、主动诊断与离线质量发布准入](trustworthy-evaluation-admission-zh-CN.md)；既有 API 契约不变，普通 Chat 不增加评测费用。

## 多轮真实质量与双实例恢复闭环

先运行两个Python自测，再执行 `python3 scripts/verify-quality-production-e2e.py`，验证24例多轮
正反例、跨节点一致性/公平性/lease接管，以及旧备份后的删除/吊销/擦除闸门。
明确付费且安全注入模型环境后，运行 `python3 scripts/verify-quality-real-e2e.py --ack-paid-models`：
默认两provider各24例×3轮，外加真实Client JSON/SSE/SDK和HTTP rerank。普通Chat不会自动触发评测。
完整命令、结果归因、成本与信任边界见 [质量稳定性与生产恢复验收](quality-production-acceptance-zh-CN.md)。
旧三例smoke与单实例恢复入口保持不变；受控通过不得冒充真实模型质量。

不同provider默认配置的临时节点不要与正在评测的节点共用数据库：每个应用实例都可能claim
同库的待执行Evaluation任务，临时节点退出会中断其已claim的任务。追加诊断应等待评测完成，
或使用独立数据库；保留编排干扰导致的中断记录，不将它归因于模型质量或覆盖原结果。

## 依赖治理、混合故障与真实恢复

先执行 `python3 scripts/verify-upstream-recovery-e2e.py`，通过真实 HTTP/PG 验证上游错误的
旧信封兼容、增量分类、调用次数、失败 replay、Python/Node SDK 恢复决策和预算释放。
拒答必须正常交付；部分流不得冒充完整答案；受控故障不替代多轮真实 Embedding/LLM 质量矩阵。
专项覆盖及默认决策见[上游故障与 Client 恢复](upstream-client-recovery-zh-CN.md)。

```bash
mvn clean compile test-compile
python3 scripts/test-support/rag-backup-self-test.py
python3 scripts/verify-dependency-recovery-e2e.py
```

专项启动真实服务和自有 pgvector 容器，覆盖 embedding/rerank 慢响应、429、503、断连、
容量隔离、Search/Native/OpenAI JSON/SSE/导入/主动 QA 混载、强杀后 lease 接管、正常重启，
实际 dump/restore、逐表内容对账、原件/向量/权限/删除/API 复验及损坏/非空/同库负例。
受控 provider 不是实际模型质量证据；仍需执行下列真实 BGE-M3/LLM、SDK、API 契约门禁。
证据在 `.verification/dependency-recovery/`，包含测试业务数据，不能提交。

可选较长负载：`scripts/k6-load-test.js` 的 `K6_PROFILE=reliability` 要求 `K6_DOCUMENT_ID`
指向已嵌入的可处置样本，不创建/删除调用方数据。`API_KEY` 通过安全环境注入。
`RELIABILITY_P95_MS` 默认 30000，`RELIABILITY_P99_MS` 默认 60000，
`RELIABILITY_MIN_SUCCESS_RATE` 默认 0.5，`K6_OPENAI_MODEL` 默认 rag-default。
`RELIABILITY_VUS` 默认 8，`RELIABILITY_DURATION` 默认 30s。为上述隔离演练设置
`DEPENDENCY_RECOVERY_K6_IMAGE`（例如 `grafana/k6:0.57.0`）可额外执行 2 VU/12s 的 k6 硬门槛，
以 100% 成功率校验正常混载，导出 `k6-summary.json`；更大容量压力由受控并发阶段独立验证。
成功延迟、成功比例、显式 429、非预期错误分开计量；空 Search 不算成功，不给真实 LLM 套 500ms。
操作边界见[数据备份与隔离恢复](backup-restore-zh-CN.md)。

## 证据快照与容量隔离专项

- `./scripts/verify-gated-it.sh UnassignedDocumentChatFencePostgresIntegrationTest`：真实 V1–V65 迁移、
  revision/hash/禁用/删除/代次/chunker 竞争与提交栅栏、关键词 SQL 以及相同 anchor 文本跨 revision 拒绝 neighbor。
- `AdmissionServiceTest`、`AdmissionWebConfigTest`、`EvidenceSnapshotTest` 和
  `ChatExecutionServiceEvidenceSafetyIntegrationTest`：并发上限、后台隔离、幂等释放、预算前拒绝、
  JSON/stream 无成功提交和历史兼容；`PerformanceConfigTest` 覆盖 4 worker + 64 队列饱和拒绝。
- `python3 scripts/test-support/evidence-admission-provider-stub.py --port 18186` 启动受控 provider。
  仅在隔离后端将测试模型 `fixture/controlled` 路由到 `http://127.0.0.1:18186`，
  默认 Chat 指向该模型，OpenAI 配置 KNOWLEDGE alias `rag-default` 和 PLAIN alias `rag-plain`；
  设置 `rag.admission.max-model-calls=1`、`rag.admission.max-requests>=2`，关闭模型重试与查询改写，
  设置 `rag.chat.knowledge.max-retrieval-queries=1` 避免查询扩展先于检索进入 provider。
  配置格式见 [外部模型注册表](multi-model-external-config-zh-CN.md#完整示例)。
  对这个后端设置 `EVIDENCE_ADMISSION_BASE_URL`、`EVIDENCE_ADMISSION_STUB_URL`、
  `EVIDENCE_ADMISSION_API_KEY`，执行 `python3 scripts/verify-evidence-admission-e2e.py`。
  验收 Native/OpenAI 429 与 Retry-After、拒绝时无 provider 调用、健康/Search 不受模型容量影响、
  释放后恢复，以及生成期间更新导致的 JSON/SSE 冲突、无成功历史、测试文档清理。
- 请求容量单独验证：在同类隔离后端设置 `rag.admission.max-requests=1`、
  `rag.admission.max-model-calls>=2`，以 `EVIDENCE_ADMISSION_REQUEST_LIMIT_ONLY=1` 执行同一脚本。
  覆盖版本化 Native 路由、旧 Chat 别名、Search、OpenAI、健康/模型发现旁路与 JSON/SSE 释放恢复。
  真实 Evaluation 验收不要与使用受控 provider 的服务共用工作数据库，否则其他 worker 可能领取 run，
  使测试结果不再代表指定的真实模型服务。

受控 provider 只证明故障时序与协议，不证明模型质量。仍需单独执行真实 BGE-M3 与 LLM 的
`scripts/real-llm-e2e-smoke.sh`、受管 QA 与 SDK live 验收，并且全量 `mvn test` 后再执行报告型门禁。
只操作本批拥有的服务、端口与临时数据库；不要因端口占用停止其他项目。

> 📖 [English](testing-guide.md) · 📖 [中文](testing-guide-zh-CN.md)

Spring AI RAG 项目对测试的态度是"测试是生产代码"——写代码必须同步写测试，`mvn test` 不通过就不算完成。

> **永久规则**（来自项目要求，不可弱化）：  
> - 写生产代码必须同步写测试，两者同等重要  
> - `mvn test` 全部通过才算「完成」  
> - 有 REST 端点改动后运行 E2E（`scripts/e2e-test.sh`）  
> - 涉及 WebUI 修改时必须跑 Playwright（`scripts/webui-e2e-test.js` / `npm run test:e2e`）  
> - 重要改进后：重启服务 → 确认 `http://localhost:8081` 可用 → 再跑回归  

原生 Chat 引用/流式改动还必须覆盖请求级 `sourceHandle` 与兼容 `citationId` 别名、确定性的
SSE `sequence` 顺序、幂等重放去重和 OpenAI 兼容流隔离。重点证据包括
`CitationRegistryTest`、`RagChatControllerStreamEventTypesTest`、
`RagChatControllerNativeSnapshotTest` 和 `spring-ai-rag-webui/src/hooks/useSSE.test.ts`。

总文档导航：[index-zh-CN.md](index-zh-CN.md) · 命令速查：[developer-reference-zh-CN.md](developer-reference-zh-CN.md) · 规划到交付：[delivery-workflow-zh-CN.md](delivery-workflow-zh-CN.md)

## 测试金字塔

```
    ┌──────────┐
    │  E2E 测试  │  scripts/e2e-test.sh
    ├──────────┤
    │ 集成测试    │  @SpringBootTest
    ├──────────┤
    │ 单元测试    │  JUnit 5 + Mockito
    └──────────┘
```

## 快速开始

```bash
# 运行全部单元测试 + 集成测试
export $(cat .env | grep -v '^#' | xargs) && mvn test

# 只测试特定模块
mvn test -pl spring-ai-rag-core

# 只运行某个测试类
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest

# 跳过测试构建
mvn clean package -DskipTests
```

## Client API、证据安全与 Trace 验收

本批以外部 Client API 为主验收面，WebUI 测试不能替代这些检查。先运行静态契约 helper
和核心证据安全回归：

```bash
node scripts/lib/api-contract-check.mjs --self-test
mvn -pl spring-ai-rag-core -am \
  -Dtest=com.springairag.core.chat.ChatExecutionServiceEvidenceSafetyIntegrationTest,com.springairag.core.evaluation.EvidenceAssessmentEvaluatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

服务启动后执行版本化 OpenAPI/黑盒契约门禁；它检查端点/方法、请求/响应字段、错误 envelope、
`X-Trace-Id` 和 retrieval trace 查询参数。`API_CONTRACT_RUN_LIVE=1` 且提供 query/key 时，
再追加真实 Search/Chat smoke：

```bash
API_CONTRACT_BASE_URL=http://127.0.0.1:8081 \
  ./scripts/verify-api-compatibility.sh
API_CONTRACT_RUN_LIVE=1 \
API_CONTRACT_API_KEY="$RAG_ROOT_API_KEY" \
API_CONTRACT_QUERY='RAG' \
  ./scripts/verify-api-compatibility.sh
```

回归必须至少覆盖 Native Chat JSON/SSE、OpenAI-compatible JSON/SSE、Search、错误响应、ACL
隔离、幂等重放、`evidenceAssessment`、安全回答不泄漏原答案，以及 `X-Trace-Id` 与
`X-RAG-Retrieval-Trace-Id` 的不同语义。`scripts/dev.sh` 启动成功和外部 Client 黑盒通过，
比 WebUI 页面可用更能证明本批完成。

## 一键文档验证

`scripts/verify-project-docs.sh` 固化项目文档检查清单：OpenClaw 本地状态隔离、`.agents/skills/` 可跟踪性、相对链接、中英文标题结构、入口行数、项目固定约定、文档命令、Shell 语法、空白和新增行敏感信息扫描。

```bash
./scripts/verify-project-docs.sh
```

默认发布验证也会执行该门禁。

## 一键发布验证

`scripts/verify-release.sh` 固化 1.0 发布门禁，包括内嵌 WebUI 入口引用、资源存在性与 Git 可跟踪性检查，并将每一步的 stdout/stderr、状态表和 Markdown 汇总写入 `target/release-verification/<run-id>/`：

```bash
# 默认：静态检查、Maven、WebUI、Playwright、Helm、Docker
./scripts/verify-release.sh

# 已有 node_modules 时可省略 npm ci
./scripts/verify-release.sh --skip-npm-ci

# 对已启动的 PostgreSQL profile 服务追加 HTTP E2E 与 goldenset
BASE_URL=http://127.0.0.1:8081 \
  ./scripts/verify-release.sh --with-runtime-e2e --with-goldenset

# 对已启动服务追加版本化真实检索回归
BASE_URL=http://127.0.0.1:18081 \
  ./scripts/verify-release.sh --with-quality-regression

# 无需启动服务，检查回归 runner 对当前及兼容 embedding 成功态的判定
./scripts/run-retrieval-regression.sh --self-test

# 真实 LLM 服务通常由 scripts/start-real-e2e-server.sh 启动在 18081
./scripts/verify-release.sh --with-real-llm

# 完整本地门禁：自动启动 postgresql profile 服务，执行 HTTP E2E、
# goldenset、质量回归与真实 LLM smoke，归档日志后停止该服务
./scripts/verify-release.sh --with-local-runtime
```

`--with-local-runtime` 需要 PostgreSQL/pgvector 已运行，且 `.env` 中存在可用的数据库、Embedding 与 Chat LLM 配置；默认独占端口 `18081`，端口被占用时会失败，避免复用或误杀非本脚本启动的服务。可用 `RUNTIME_SERVER_PORT` 改端口。无论成功、失败或中断，脚本都会归档日志并清理自己启动的服务。

本批次服务端优先的聚焦门禁：

```bash
./scripts/verify-next-high-value-features.sh
```

它覆盖 `KNOWLEDGE_QA` 确定性 hard gate、旧 suite 定义兼容、owner ACL、PDF 资源预算、
文档处理相关编译、WebUI 类型/i18n/设计门禁、Vitest、生产构建、文档门禁和并发锁规则。
它不会自动调用真实模型；真实 `KNOWLEDGE` 评测只在显式创建 Evaluation Run 时执行，
需要 provider 的验收应使用 `scripts/start-real-e2e-server.sh` 后再运行对应 smoke。

发布脚本可追加同一门禁：

```bash
./scripts/verify-release.sh --with-next-high-value
```

Docker 默认先用中国境内镜像并自动回退官方源；详见 [中国境内开发网络避坑指南](china-network-guide-zh-CN.md)。外部服务失败必须保留为失败或明确跳过，不能伪造发布通过。

用户允许真实 LLM 验收时，先完成相关 Mock 单元/集成测试和 Mock Playwright，再运行真实
smoke；不要用高延迟真实调用代替基本执行路径覆盖。运行期间另开日志观察窗口或周期性读取服务
日志，尽早识别认证、模型名、限流、超时和响应协议错误。非 `main` worktree 使用与其他开发栈
隔离的 `BACKEND_PORT`、`FRONTEND_PORT` 和可处置测试数据库；联合启动优先使用会加载 `.env`
的 `scripts/dev.sh`。
真实 smoke 要求完整的 `RAG_EMBEDDING_*` 配置契约，并拒绝退役的
`RAG_EMBEDDING_URL`/`SILICONFLOW_*` 变量名。如果本地 `.env` 的
`MODELS_CONFIG_FILE` 指向过期注册表，要记住它会完整覆盖 YAML，并可能把 Chat 路由到
另一个 provider；应通过日志和 `/api/v1/rag/models` 核对生效模型，或为直接 provider
smoke 显式禁用该文件。

### WebUI `-real` 端到端用例运行手册

`spring-ai-rag-webui/e2e/*-real.spec.ts` 需要完整 dev 栈与真实凭据，普通
Mock Playwright（`npm run test:e2e` 不带 `-real` 过滤）不执行它们。已核实的
启动与需求：

```bash
# 1. 启动完整 dev 栈（加载 .env；后端 18082、前端 15173）
#    注意：后台 shell 需要 node 在 PATH 中（例如 nvm 用户先 source ~/.nvm/nvm.sh）
./scripts/dev.sh start

# 2. 携带真实凭据运行 -real 用例
set -a; source .env; set +a
cd spring-ai-rag-webui
BASE_URL=http://127.0.0.1:15173 \
RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" \
npx playwright test e2e/api-key-real.spec.ts
```

各用例的额外需求（2026-09-06 实测）：

| 用例 | 额外需求 | 实测状态 |
|------|----------|----------|
| `api-key-real` | `RAG_ROOT_API_KEY`（或 `REAL_E2E_API_KEY`） | 通过 |
| `alerts-real` | 另需 `ALERT_DELIVERY_EXPECTED_ALERT_ID` 与 `ALERT_DELIVERY_EXPECTED_DELIVERY_ID`（预置的真实告警/投递 id） | 缺预置 id 则失败 |
| `chat-real` | `/models` 须暴露至少一个**可用且支持 tool-calling** 的模型（实测通过：外部 models.json 配置 SiliconFlow Qwen3.5-27B + `toolCalling: true`） | 已跑通（~2 分钟，AGENT SSE + 历史恢复） |
| `files-real` | 真实 Embedding + Chat LLM；KNOWLEDGE 长上下文生成须在 `rag.timeout.chat-ask-ms`（默认 120s）内完成 | 已通过（本地慢 provider 时在 `.env` 设 `RAG_TIMEOUT_CHATASKMS=300000` 放宽） |
| `rerank-document-diversity-real` | 另需 `RERANK_DIVERSITY_FIXTURE_FILE` 指向 fixture 文件 | 缺 fixture 则失败 |

运行期间不要编辑被 dev server 服务的源文件：HMR/整页 reload 会让正在运行的
Mock Playwright 用例出现元素 detach。
推荐改用生产构建预览服务器跑 Mock 套件（无文件 watch，编辑零干扰，
且能暴露 dev 时序掩盖的生产缺陷）：

```bash
cd spring-ai-rag-webui
npm run test:e2e:mock   # build + vite preview（15174）+ 全部非 -real 用例
```
根因排查记录见
[加固循环账本](drafts/HARDENING_LOOP_PLAN.md) Batch 23/25。
为 `chat-real` 配置 tool-calling 模型时推荐使用外部 `models.json`
（`MODELS_CONFIG_FILE` 指向，gitignore 路径），示例骨架见
[多模型外部配置](multi-model-external-config-zh-CN.md)。三个已踩过的坑：

1. JSON 字段名是 **camelCase**（`chatModel` / `toolCalling`）；
   误写 `chat-model` 会静默不生效并回退 legacy 默认模型。
2. 修改后必须重启 dev 栈（`./scripts/dev.sh --stop && ./scripts/dev.sh start`），
   并用 `GET /api/v1/rag/models` 确认目标模型 `available: true` 且
   `capabilities.toolCalling: true`。
3. `LLM_PROVIDER` / `SPRING_AI_*_CHAT_OPTIONS_MODEL` 不等于最终路由模型；外部 registry
   完整覆盖 YAML，而禁用它又可能回到另一个默认模型。真实验收须同时核对 `/models` 与
   响应的 `resolvedModel`。`MODEL_CAPABILITY_UNSUPPORTED` 可能在 provider 调用前就拒绝
   AGENT；此时不能把能力预检拒绝当成 provider 故障，也不能记为真实 AGENT 验证成功。



### 模型调用级持久用量账本门禁

执行聚焦和完整的非 provider 验收门禁：

```bash
LLM_USAGE_LEDGER_VERIFY_RUN_ID=usage-ledger-gate \
./scripts/verify-llm-usage-ledger.sh
```

它要求归因/recorder/API 聚焦测试、一次性 PostgreSQL 从 V1 迁移到 V53、
`mvn clean compile test-compile`、Maven 全量测试、WebUI typecheck/Vitest/build/alignment、
持久用量 Mock Playwright、禁锁门禁、文档门禁和 `git diff --check`。浏览器阶段使用请求、
JSON 和可访问 DOM 断言 `/api/v1/rag/usage` 与 Metrics 展示；截图不作为正确性证据。

Mock 和一次性 PostgreSQL 阶段通过后，真实 provider 验收必须使用隔离服务和数据库。至少
覆盖一次 PLAIN、一次会触发 query transform/expand 与最终回答的 KNOWLEDGE、一次带有界
工具循环的 AGENT、fallback 或 provider 失败、summary compaction、带 key 的 replay
以及只读 usage 查询。每个阶段都观察服务日志。通过响应 JSON/SSE 和只读数据库聚合核对
provider 调用次数、purpose/mode 归因、终态结果、replay 不重复调用和 usage 聚合一致。
证据不得记录 prompt、answer、工具参数/结果、credential 或异常正文。

## 测试分类

### 分块正确性与只读预览门禁

```bash
./scripts/verify-chunking-quality.sh
```

升级恢复专项可用 `./scripts/verify-gated-it.sh ChunkingUpgradePostgresIntegrationTest`。
等效的直接命令显式启用门控开关，便于测试发现门禁和开发者核对运行入口：

```bash
TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16 \
mvn test -pl spring-ai-rag-core -am -Dchunking-upgrade.it.enabled=true \
  -Dtest=ChunkingUpgradePostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

覆盖内容守恒、尺寸上限、offset/Unicode、同级标题、无效表格、长表表头、代码/公式保护、
固定种子回归、Profile/version、JSON_RECORD兼容、preview分页/400/403/404、RAG_READ及
真实PostgreSQL的块落库/版本切换/显式重建。发布仍需全量 `mvn test` 与 `verify-gated-it.sh`。
新策略和自动升级默认启用；`verify-gated-it.sh ChunkingUpgradePostgresIntegrationTest`
验证旧库迁移、旧检索连续可用、失败保留、并发租约/CAS和自动回退。
V63专项进一步覆盖历史失败自动迁移、三次故障耗尽后的持久冷却、重启后同一正文/目标恢复、
拒绝/未知故障低频尝试、永久向量校验终止、跨实例小时配额、缺额延期与公平扫描。测试通过
推进隔离库的时间字段压缩等待，不改变产品默认冷却，也不把人工SQL当作正常恢复要求。
相关单测为 `EmbeddingFailureClassifierTest`、`RagChunkUpgradeRecoveryPropertiesTest`、
`ChunkingUpgradeMetricsTest`；真实依赖使用隔离库与端口，
核对preview和索引的chunk序列，再用真实Embedding/Chat测试检索与引用。不能只凭
模型答复主观判定内容完整；离线守恒断言与固定样本检索结果是独立门槛。

### 输入法交互回归

所有会触发动作的 WebUI 输入框，都必须把 IME 边界作为行为契约测试：

- 组合输入阶段按 Enter 不得提交、搜索、保存或导航；
- 输入框自己处理键盘事件时，应覆盖 `isComposing` 和兼容性的
  `keyCode === 229` 路径；
- 改写 URL 或启动 debounce 选项请求的输入框，不得处理组合期间的中间值；
- `compositionend` 只用最终值处理一次；
- 下一次普通 Enter 仍应执行正常动作。

使用 Testing Library 的 DOM 断言，以及 Playwright 的可访问 DOM、请求和响应断言。
不要使用截图证明该行为。共享实现位于 `src/utils/ime.ts` 和
`src/components/ImeSafeForm/`。

### 单元测试（JUnit 5 + Mockito）

**目标**：验证单个类/方法的逻辑，不依赖外部服务。

**命名规范**：`{ClassName}Test.java`

**位置**：各模块的 `src/test/java/`

**示例**：
```java
@SpringBootTest
@AutoConfigureMockMvc
class RagDocumentControllerTest {

    @MockBean
    private RagDocumentService ragDocumentService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnDocumentById() throws Exception {
        when(ragDocumentService.findById(1L)).thenReturn(Optional.of(doc));

        mockMvc.perform(get("/api/v1/rag/documents/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Test"));
    }
}
```

**Mock 要点**：
- 使用 `@MockBean` 替代 `@Mock`（Spring 上下文集成）
- Service 层可以 `@Mock` + `@ExtendWith(MockitoExtension.class)` 纯单元测试
- 涉及数据库的用 `@DataJpaTest` 切片测试

### API Route Policy 自检回归

本批 route policy 的 focused 回归由两层组成：

- `ApiRoutePolicyRegistryTest` 验证类级默认、方法级 `READ` 覆盖、`MANAGEMENT`、缺少声明
  和冲突 policy 的启动失败语义；
- `ApiCapabilityFilterRoutePolicyTest` 验证数据库 NORMAL Key 对未声明 route 返回
  `403 ROUTE_NOT_DECLARED`、OpenAI 兼容错误体、只读 POST 的 `RAG_READ`，以及管理 route
  继续绕过 capability gate。

运行命令：

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest=ApiRoutePolicyRegistryTest,ApiCapabilityFilterRoutePolicyTest,ApiCapabilityFilterTest,ApiCapabilityFilterCapabilityClassificationTest,ApiCapabilityFilterClassifierTailTest,RagWebSecurityConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Servlet 应用启动时会读取真实 `RequestMappingHandlerMapping`，所以完整启动也是自检的一部分；
缺少受保护 route 声明或发生 policy 冲突时，应用应在 readiness 前失败。`./scripts/dev.sh`
至少要验证 PostgreSQL profile 的 backend readiness、WebUI 启动输出和停止清理；本能力不新增
Flyway migration，也不以人工首次请求替代 focused 测试和启动证据。

### 消费者方向的路由契约

`OpenApiContractTest` 里除了一贯的**生产者方向**断言（schema 在不在、要求的路径
有没有被文档化），还有两个**消费者方向**的嵌套类：读消费方写下的每一个路由，
拿去和**运行时 spec** 对账。消费者有两个，规则方向相同，都是"存在性"：

- `WebUiRouteContract` —— WebUI 调的每一条路由，服务端都得真的挂着。
  Batch 932 建立（当时查出 11 条无路由），Batch 933 补上 `fetch` 写法
  （此前完全看不见的三处），当前覆盖 106 个调用点。
- `DocumentedRouteContract` —— `docs/rest-api{,-zh-CN}.md` 里**以标题形式写下的**
  每一条端点，服务端都得真的挂着；顺带要求两种语言写的是同一组端点。
  Batch 934 建立。

**为什么只查这一个方向。** 反方向（服务端有的端点是否都被文档写下来了）**不查**，
而且是有理由的不查：`rest-api.md` 从未承诺穷举，它开头就把完整清单指向
`/swagger-ui.html`，本身是一份**带取舍的参考**。实测后端约 177 条映射、文档标题
112 条，差 65 条——若为这 65 条做豁免表，**那份基线就不是检查**（Batch 924 之后
本仓库已经明确拒绝过这种做法）。而"文档写了却 404"是更严重的一侧：读过文档的人
会照着它接。

两道检查都带**地板断言**（WebUI 90 个调用点、文档 100 个端点），因为一个不再匹配
任何东西的扫描器，会对着空集报一个干净的结论。它们的**已知上限**都写在测试头部。

### 集成测试（@SpringBootTest）

**目标**：验证组件协作，用真实 Spring 上下文。

**命名规范**：`{ClassName}IntegrationTest.java`

**关键注解**：
```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers   // 如果需要 PostgreSQL
class RagChatControllerIntegrationTest {
    // 测试完整的 RAG Pipeline：查询 → 改写 → 检索 → 重排 → LLM
}
```

### E2E 测试（Shell + curl）

**目标**：验证 HTTP 端点完整链路（真实服务运行）。

**脚本**：`scripts/e2e-test.sh`

**用法**：
```bash
# 启动服务
export $(cat .env | grep -v '^#' | xargs) && bash scripts/start-server.sh

# 在另一个终端运行 E2E 测试
export $(cat .env | grep -v '^#' | xargs) && bash scripts/e2e-test.sh
```

E2E 测试覆盖的端点：
1. `GET /api/v1/rag/health` — 健康检查
2. `POST /api/v1/rag/collections` 及 by-key 获取/更新/列表/删除 — Collection Key 生命周期
3. `POST /api/v1/rag/documents` — 创建文档
4. `GET /api/v1/rag/documents/{id}` — 获取文档（含文档 metadata）
5. `GET /api/v1/rag/documents` — 文档列表（分页）
6. `POST /api/v1/rag/documents/{id}/embed` — 生成嵌入向量
7. `GET /api/v1/rag/search` — 直接检索
8. `POST /api/v1/rag/chat/ask` — RAG 问答
9. `POST /api/v1/rag/chat/stream` — 流式响应（SSE）
10. `GET /api/v1/rag/chat/history/{sessionId}` — 对话历史
11. `DELETE /api/v1/rag/documents/{id}` — 删除文档 + 验证 404

脚本每次运行生成唯一的可见 ASCII `collectionKey`，并在 Collection 路由、文档写入及检索
请求中优先使用 by-key 和 `collectionKey(s)`，避免重复执行与全局唯一约束冲突。

JSONB 结构化记录的真实 HTTP 验收使用：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/jsonb-records-e2e.sh
```

它覆盖 JSON record upsert、限定 Collection 的检索、详情、payload-only 更新、
`retrievalText` 更新、clone/export/import 和临时受限 API Key 的 allow/deny。
脚本需要真实 embedding provider；没有 root 凭据时只能显式使用 `--skip-acl`，
并在验证记录中注明。该脚本不调用 Chat LLM，也不会输出 API Key 或完整 payload。

### 外部文档同步验收门禁

普通外部文档路径具备 Service、MockMvc、OpenAPI、迁移和真实 HTTP 覆盖。对已经启动的
PostgreSQL profile 服务运行：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/external-documents-e2e.sh
```

它覆盖稳定身份、精确重放、来源版本 CAS、同 revision 冲突、批量隔离、按外部身份查询、
tombstone 删除与重放、恢复和 embedding freshness。默认运行需要可用的 embedding
provider 来验证内容变化更新；只有在明确记录 provider 故障时才使用
`EXTERNAL_DOCUMENT_E2E_EMBED=false`。该模式可以验证持久化与 no-embedding 状态，但不计入
完整 embedding 验收门禁。

迁移矩阵默认使用 Testcontainers：

```bash
mvn -pl spring-ai-rag-core \
  -Dtest=ExternalDocumentSyncPostgresIntegrationTest \
  -Dexternal-document.it.enabled=true \
  test
```

Docker 不可用时，可通过 `EXTERNAL_DOCUMENT_IT_JDBC_URL`、
`EXTERNAL_DOCUMENT_IT_USERNAME` 和 `EXTERNAL_DOCUMENT_IT_PASSWORD` 指向专用的一次性
PostgreSQL 数据库，并显式设置 `EXTERNAL_DOCUMENT_IT_CLEAN_CONFIRM=YES`。该测试会反复
调用 `Flyway.clean()`，绝不能指向开发库或生产库。

### 业务服务接入就绪验收门禁

```bash
./scripts/verify-business-client-readiness.sh
```

该门禁把业务 credential provisioning、当前 principal 自省和 JSON Record 数据面串成一条
真实合同，覆盖：

- root、restricted/unrestricted 数据库 principal，只读 query 与读写 dispatcher 画像，
  Header/Bearer 认证和有效 query credential 拒绝；
- `/auth/me` 的 role、精确 capability、access mode、完整 key allow-list、no-store 与
  无 secret；
- Collection key 1/128 成功、129/空白/控制字符/非 ASCII 拒绝，外部身份与 revision
  长度边界、双向跨 Collection ACL 和全数据面防枚举；
- JSON Record 精确重放、CAS、payload containment、tombstone/恢复和 payload-only
  不重嵌入；
- capability protocol `1.1`、非默认 JSON batch 上限、对应上限的真实 `400` 强制边界，
  以及可选 operation-observability feature；
- `ASYNC` 持久化 job 经真实 Spring AI embedding HTTP 路径收敛为 fresh；
- 确定性 provider `503` 使 job 收敛为 `FAILED`，但保留已提交的 Record identity、
  revision、payload、enabled 状态和 document revision；
- credential 一次性展示、轮换、旧 key 失效和吊销；
- 只读 query principal 可 lookup/search，但 upsert/delete 返回 `403`，且拒绝后 Record
  revision/state 不变；读写 dispatcher 和 rotation 保持完整能力；
- 代表性的租户/共享拓扑把一个只读 query principal 精确绑定到两个 Collection，租户与共享
  dispatcher 相互隔离，第三租户被拒绝；两种 payload scope 分路检索后确定性归并，投影剔除
  private transport/credential 材料并重建客户端安全 DTO，query credential 轮换后仍保留
  两个 binding；
- 通用 Client 记录 mutation envelope 会先编译为稳定哈希 external ID、opaque revision、
  检索文本和 allow-list JSON payload，再执行 `TENANT_PRIVATE` 的 CAS 更新、tombstone、恢复、
  轮换后再次 tombstone，以及 `SHARED_CATALOG` 的发布和撤销；原始 `privateAttachment`、
  URL、内部 record/event/fingerprint 字段不得进入 RAG 投影；
- 确定性回归测试和真实 envelope 快速更新/删除路径共同覆盖异步 embedding 完成与外部
  mutation 的乐观锁竞态，确认服务以全新事务有界重试，并且不把内部派生竞争误报成普通业务
  CAS 冲突；
- self operation/status/Collection observability、跨 principal/Collection 拒绝、有界延迟
  摘要与重启持久化；
- Flyway V55、明文 credential 为零、成功 embedding job 与 integration-operation
  rollup 的 PostgreSQL 只读事实；
- WebUI typecheck、Vitest、生产构建、核心 Mock Playwright 与真实 API Key Playwright。

真实 HTTP 阶段除业务客户端合同外，还会把已部署 binding preflight 作为黑盒 client
执行：`READ_ONLY` 与 `READ_WRITE` 画像成功、能力画像不匹配失败、allow-list 精确不匹配
失败、运行时最低限制/feature 要求、Bearer canary mutation 成功，以及 provider 失败后的
清理和最终 tombstone。测试会
校验 preflight 报告 schema，并确认报告不包含 credential、URL、Collection key、
external ID 或 payload。

最终候选 commit 应开启 clean-tree gate：

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

若客户端仓库已经能导出真实 mutation envelope，可通过
`BUSINESS_CLIENT_CLIENT_ENVELOPE_DIR` 指定目录。目录必须包含
`private-lifecycle-v1.json` 至 `private-lifecycle-v5.json`，以及
`shared-lifecycle-v1.json`、`shared-lifecycle-v2.json`。脚本只把编译后的安全投影发送给
RAG；不会把原始 envelope、私有 transport 引用或该目录路径写入 release manifest。未指定时
使用脚本内置的通用示例夹具，保证本仓门禁自包含；该示例协议不是 RAG 服务端 API 契约。

每次运行都会在证据目录生成 `release-manifest.json`，记录 PASS/FAIL、验证阶段、完整 Git
SHA、初始 tree state、项目/OpenAPI 版本、API base path、最新 Flyway migration、passed
steps、PostgreSQL image、HTTP 检查数、
`["READ_ONLY","READ_WRITE"]` 验证画像，以及实测 JSON batch item/payload 上限与
operation-observability 状态。未执行到的运行时事实为 JSON `null`；manifest 不保存
credential、URL、payload、external ID 或 private path。

聚焦复跑真实阶段：

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

脚本所有 PostgreSQL 数据库、端口和 credential 都是隔离且一次性的。private 文件权限为
`0600`，退出 trap 清理资源；证据不保存 raw secret 或完整业务 payload。前端断言只使用
DOM、网络请求/响应和 JSON，不以截图作为正确性证据。该脚本包含 `mvn clean`，必须与其他
使用同一 worktree `target/` 的 Maven 进程串行运行。

接入语义见[业务服务接入指南](business-client-integration-zh-CN.md)。

<a id="document-lifecycle-verification"></a>

### 文档 CRUD 与派生索引生命周期门禁

```bash
./scripts/verify-document-lifecycle.sh
```

该脚本串行执行：

1. 禁悲观锁静态门禁；
2. 本地 CRUD、外部 TEXT/JSON、Collection/PDF/batch 入口和 generation job 聚焦测试；
3. 一次性 PostgreSQL 上的 V39→V45、三元身份、freshness、本地/向量 generation fencing、
   事务回滚
   和硬删除级联验收，并解析 Surefire XML 强制 `skipped=0`；
4. reference client 的 HTTP 重试、CAS、checkpoint 恢复和密钥不落盘测试；
5. `mvn clean compile test-compile` 与全量后端测试；
6. WebUI Vitest、production build、alignment 和 Documents Mock Playwright；
7. 双语项目文档与 `git diff --check`。

也可以单独运行 V42/V51 Sync Run HTTP 合同验收：

```bash
./scripts/verify-document-sync-runs.sh
```

它会把一次性数据库从空库迁移到 V52，启用认证后创建受限读写/只读 principal，并验证
Sync Run begin、批量幂等、失败精确重放、preview/complete tombstone、namespace 与
Collection ACL、防枚举、持久化 item receipt、状态过滤、`limit=1` cursor 分页、
active/terminal 遍历语义、`Cache-Control: no-store`、敏感错误脱敏和禁悲观锁门禁。
证据写入 `.verification/document-sync-runs/<run-id>/summary.md`，不保存 credential、
cursor、external ID 或业务 payload。

专项 PostgreSQL service 测试也可使用一个明确可清空的隔离数据库运行：

```bash
DOCUMENT_SYNC_RUNS_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/<disposable-db> \
DOCUMENT_SYNC_RUNS_IT_USERNAME=postgres \
DOCUMENT_SYNC_RUNS_IT_PASSWORD= \
DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM=YES \
mvn -pl spring-ai-rag-core -am \
  -Ddocument-sync-runs.it.enabled=true \
  -Dtest=DocumentSyncRunsPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试会反复执行 `Flyway.clean()`，不得指向开发库或生产库。

### 本地关键词 / 向量解耦验收门禁

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-4 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4191 \
./scripts/verify-keyword-vector-decoupling.sh
```

该一键门禁会把隔离 PostgreSQL 从空库迁移到 V45，执行本地 chunk 生命周期以及
English/中文/pg_trgm 全文集成测试，并强制 `skipped=0`；随后执行
`mvn clean compile test-compile`，以及 WebUI TypeScript、Vitest、production build、
alignment 和无截图 Mock Playwright。它同时执行禁悲观锁静态门禁，证据写入
`.verification/keyword-vector-decoupling/<run-id>/`。

PostgreSQL 选择顺序为：显式 `DOCUMENT_LIFECYCLE_IT_JDBC_URL`、使用当前 shell/`.env`
的 `POSTGRES_*` 创建一次性数据库、最后使用 Docker CLI 启动 pgvector。调用方 JDBC URL
必须是可清空的一次性数据库。Mock Playwright 只使用 DOM、网络和断言，配置中关闭截图。
证据写入 `.verification/document-data-plane/<run-id>/summary.md`。

### 文档迁移与派生完整性专项门禁

```bash
./scripts/verify-document-relocation.sh
./scripts/verify-derivation-integrity.sh
```

迁移门禁覆盖 V44、双 Collection ACL、幂等精确重放、活动 Sync Run fencing、旧地址永久
阻断，以及 TEXT/JSON 共用的数据面语义。派生门禁覆盖 V45、严格物理 freshness、分页与
聚合诊断、preview token/fingerprint、短事务 repair item lease，以及只在确有需要时入队的
向量修复。两者都要求 PostgreSQL 测试 `skipped=0`，并执行后端编译、前端构建与基于
DOM/网络/接口断言的 Mock Playwright；截图不作为验收证据。

复用一次性数据库时使用 `NEXT_HIGH_VALUE_IT_JDBC_URL`、
`NEXT_HIGH_VALUE_IT_USERNAME`、`NEXT_HIGH_VALUE_IT_PASSWORD`，并设置
`NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES`。门禁会反复执行 `Flyway.clean()`，不得使用开发
库或生产库。

## 覆盖率

JaCoCo 已集成到所有模块：

```bash
# 生成覆盖率报告
mvn clean test jacoco:report

# 报告位置
# spring-ai-rag-core/target/site/jacoco/index.html
# spring-ai-rag-api/target/site/jacoco/index.html
# spring-ai-rag-documents/target/site/jacoco/index.html
# spring-ai-rag-starter/target/site/jacoco/index.html
```

**覆盖率目标**：
- 指令覆盖率 ≥ 90%
- 分支覆盖率 ≥ 75%

**查看覆盖率**：
```bash
# 快速查看（终端输出）
mvn jacoco:check

# 合并多模块报告
mvn jacoco:report-aggregate
```

## 测试数据库

单元测试使用 Mock 或 H2 兼容路径。Embedding Profile 迁移使用显式 PostgreSQL 集成测试，
因为它需要 pgvector，并验证 Flyway V1-V52、固定向量列、Profile 专属索引、原子替换、
Legacy 认领、检索新鲜度和 Spring Data Repository 查询。

启动 PostgreSQL 16 + pgvector 数据库后执行：

```bash
mvn -pl spring-ai-rag-core \
  -Dembedding-profile.it.enabled=true \
  -Dtest=EmbeddingProfilePostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drag.it.jdbc-url=jdbc:postgresql://127.0.0.1:35267/embedding_profile_test \
  -Drag.it.username=postgres \
  -Drag.it.password=postgres \
  test
```

未设置 `rag.it.jdbc-url` 时该测试会跳过，因此上述显式命令是本次迁移的必要验收门槛。

> **走外部库必须显式确认**。该套件会对 `-Drag.it.jdbc-url` 指向的库执行
> `flyway.clean()`——这会**删掉该 schema 下的所有对象**——所以必须同时设置
> `EMBEDDING_PROFILE_IT_CLEAN_CONFIRM=YES`：
>
> ```bash
> EMBEDDING_PROFILE_IT_CLEAN_CONFIRM=YES mvn -pl spring-ai-rag-core \
>   -Dembedding-profile.it.enabled=true \
>   -Dtest=EmbeddingProfilePostgresIntegrationTest \
>   -Dsurefire.failIfNoSpecifiedTests=false \
>   -Drag.it.jdbc-url=jdbc:postgresql://127.0.0.1:35267/embedding_profile_test \
>   -Drag.it.username=postgres -Drag.it.password=postgres \
>   test
> ```
>
> Batch 802 之前这个套件**既没有护栏也没有开关**：仓库里没有任何脚本设置
> `rag.it.jdbc-url`，所以它 7 个测试在任何自动化路径里都跑不到；而唯一能让它
> 跑起来的方式就是手动指定一个库，然后它会在无任何确认的情况下 `clean()` 掉
> 那个库。`scripts/verify-external-db-safety.mjs` 现在对**所有**接受外部库的
> 套件强制这条规则。

### Collection Key 验收门禁

Collection 身份具备 DTO、Resolver、ACL、Service、Controller、MockMvc、OpenAPI 和
PostgreSQL 专项覆盖。真实 PostgreSQL/Testcontainers 测试执行 V27/V28，并验证 legacy
回填候选冲突避让、1/128 字符边界、可见 ASCII 约束、大小写敏感、软删除保留 key、
SQL 层不可变和并发唯一性：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Dcollection-key.it.enabled=true \
  -Dtest=CollectionKeyPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

运行时验收前，串行执行编译和聚焦集成门禁：

```bash
mvn clean compile test-compile

mvn -pl spring-ai-rag-core -am \
  -Dtest='*Collection*,*ApiKey*,OpenApiContractTest,RagControllerIntegrationTest,RagSearchControllerTest,RagChatControllerTest,PdfImportControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

WebUI 验收要求 `npm run test:run`、`npm run build` 和 Mock API Playwright 全部通过。随后
运行时 smoke 使用唯一 key 覆盖创建、by-key 获取/更新、使用新目标 key 克隆、使用新 key
导出/导入、软删除、恢复、重复冲突，以及文档/Search/Chat 的 key 输入。

### Collection Provisioning 幂等验收门禁

```bash
./scripts/verify-collection-provisioning.sh
```

七阶段门禁先执行 owner、fingerprint、配置、service、controller、capability、OpenAPI 和
WebUI retry 聚焦测试；随后把一次性 PostgreSQL 迁移到 V52，并要求 9 个集成测试全部执行、
`skipped=0`。最终 HTTP 阶段让两个真实后端实例共用一个数据库，验证首次创建、跨实例和
重启后 replay、规范化请求等价、语义冲突、owner 隔离、restricted ACL 拒绝、软删除当前
状态 replay、恰好一次创建审计，以及账本不可用时 `503` fail closed。数据库断言确认
V52、预期 ledger/Collection 数量和明文 credential 为零。

只复跑一次性双实例 HTTP 阶段：

```bash
COLLECTION_PROVISIONING_VERIFY_PHASE=http \
./scripts/verify-collection-provisioning.sh
```

WebUI 通过请求拦截断言同一次用户提交在 Axios retry 中复用一个 UUID，后续新提交使用
另一个 UUID；验收证据不使用截图。

### Collection 受保护清理与退役验收门禁

```bash
./scripts/verify-collection-purge.sh
```

该 9 阶段门禁依次执行禁悲观锁检查、purge/Collection/feedback/audit/OpenAI scope
聚焦测试、真实 PostgreSQL V1–V61 清理矩阵、`mvn clean compile test-compile`、完整
WebUI typecheck/Vitest/lint/生产构建、Collection Mock Playwright、双语文档门禁、脚本
语法和空白检查。每次运行在
`.verification/collection-purge/<run-id>/summary.md` 记录逐步证据。

PostgreSQL 测试默认使用 Testcontainers，并要求 purge 矩阵 5 个场景与
integration-observability V56 兼容矩阵 6 个场景全部执行，且
`failures=errors=skipped=0`。也可显式指定一次性数据库：

```bash
COLLECTION_PURGE_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/purge_it \
COLLECTION_PURGE_IT_USERNAME=postgres \
COLLECTION_PURGE_IT_PASSWORD=postgres \
COLLECTION_PURGE_IT_CLEAN_CONFIRM=YES \
./scripts/verify-collection-purge.sh
```

矩阵覆盖空/非空 Collection、local/external 混合文档和全部派生数据、feedback/Chat
引用级联、无关数据与独立文件保留、active sync/repair/session lease 阻断、坏历史引用
fail closed、root/ADMIN/loopback 权限、preview 变化整体回滚、成功精确 replay、过期清理和
退役 tombstone。Mock Playwright 只使用 DOM、可访问状态、请求 JSON 和网络响应，验证
capability 门控、token 不渲染、精确 key 确认、成功后 active 卡片移除及 409 不自动重试；
不使用截图作为证据。

真实依赖阶段不包含在上述快速门禁中。Mock 全部通过后，使用隔离 PostgreSQL/端口启动
真实服务，然后执行：

```bash
BASE_URL=http://127.0.0.1:18081 \
REAL_LLM_ENV_FILE=.env \
REAL_COLLECTION_PURGE_LOG_DIR=.verification/collection-purge/real-provider \
POSTGRES_HOST=127.0.0.1 \
POSTGRES_PORT=5432 \
POSTGRES_DATABASE=spring_ai_rag_purge_acceptance \
POSTGRES_USER=postgres \
POSTGRES_PASSWORD=postgres \
./scripts/real-collection-purge-e2e-smoke.sh
```

该脚本覆盖真实 embedding 写入、Spring Event 在 Scheduled 恢复扫描前唤醒 worker、
readiness、纯向量自然语言检索、两轮原生 Chat、OpenAI-compatible Chat、preview/apply/
精确 replay、三条显式退役路径拒绝、默认范围排除、purge operation 的全局/Collection
rollup 和数据库 tombstone 事实。持久化证据只包含状态、模型、回答长度、布尔断言、计数
和来源 ID；不得包含 API key、请求正文、明文 confirmation token、文档正文或完整模型
回答。验收期间必须观察后端日志，任何 observation drop、provider 错误或数据库约束错误
都要解释并处理。四项全局/Collection preview/apply rollup 必须分别为正；脚本使用显式
条件失败，不能依赖不同 Bash 版本对 arithmetic command 与 `set -e` 的组合行为。

### 多 Collection 检索范围验收门禁

范围实现具备 DTO、Resolver、ACL、SQL fragment、Vector/Full-text provider、
Chat/Search/JSON、MockMvc、OpenAPI、WebUI 和 PostgreSQL 覆盖。真实
PostgreSQL/Testcontainers 测试会启动 `pgvector/pgvector:pg16`，从空 schema 执行
Flyway V1-V52，并用实际 PostgreSQL `bigint[]` 绑定执行 Vector 查询：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Dmulti.collection.it.enabled=true \
  -Dtestcontainers.pg.image=pgvector/pgvector:pg16 \
  -Dtest=MultiCollectionRetrievalPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

该矩阵验证：不受限 `CALLER_VISIBLE` 包含未归属文档；`ANY_COLLECTION` 排除未归属文档；
selected A+B 不会返回其他 Collection；空 selected Collection 保持空结果；selected
Collection 与显式 document ID 在 SQL 中取交集；JSON record 遵守 `document_type`；
disabled、stale 或错误 Profile 的文档仍被排除。English FTS、pg_jieba 与 pg_trgm
是否使用相同 `RetrievalScopeSql` predicate，由各 provider 的聚焦测试单独验证。

WebUI 验收覆盖三种模式、多选、服务端 Collection 搜索和分页、selected 空范围禁止提交，
以及 Chat SSE object request。执行 `npm run test:run`、`npm run typecheck`
（或 `npx tsc -b --pretty false`）、`npm run build` 和核心 Mock Playwright。

**不要在这里跑 `npx tsc --noEmit`。** WebUI 的 `tsconfig.json` 是 solution 文件：
只有 `references`，没有 `files` / `include`，所以裸的 `tsc --noEmit` **什么都不编译**
并且退出 0。2026-10-06 实测：往 `src/pages/Collections.tsx` 末尾追加
`const __probe: number = "definitely not a number";` 之后，它照样报成功。同一次运行
还暴露了它一直在掩盖的一个真错误——那个组件用到了从未解构出来的 `i18n`，另有六个
组件解构了 `i18n` 却没用。`npm run typecheck`（`tsc -b`）七个全报，`tsc --noEmit`
一个不报。`HEARTBEAT.md` 里 2026-04-25 那条 `tsc --noEmit ✅` 正是这个空绿——那份日志
是只追加的，所以它保留为"当时跑了什么"的记录，而不是"当时通过了什么"的记录。

`npm run typecheck` 现在是 `npm run lint` 的一环，而 `ci.yml` 的 webui job 会跑 lint，
所以即便 `npm run build` 本来也能抓到，类型错误也不可能无声地进到 `main`。

### JSONB 结构化记录验收门禁

JSONB 实现同时具备 Mock HTTP/Service 覆盖和真实 PostgreSQL/Testcontainers 测试。后者会
启动 `pgvector/pgvector:pg16`，从空库执行 Flyway V1-V52，并验证 JSONB round-trip、
嵌套 `payloadContains`、V34 GIN planner、仅更新 payload 的版本记录、相同描述下不同
记录共存以及级联清理：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Djsonb.it.enabled=true \
  -Dtest=JsonbStructuredRecordsPostgresIntegrationTest \
  test
```

聚焦的全层一键门禁：

```bash
./scripts/verify-jsonb-records.sh
```

该脚本还会运行 API DTO、分块器、JSON service/controller/OpenAPI、Maven 编译、WebUI 构建、
Mock Playwright、project-docs 和空白检查，并将结果记录到
`.verification/jsonb-verification/<run-id>/summary.md`。
浏览器 preview 对 `JSONB_PLAYWRIGHT_PORT`（默认 `4174`）使用严格端口绑定；若本地已有
服务占用该端口，请指定空闲端口。完整门禁必须串行运行，因为 Maven clean 会删除模块的
`target/` 输出。

### OpenAI 兼容预览验收门禁

```bash
./scripts/verify-openai-compatibility.sh
```

固定范围包括 model alias、body/Header Collection scope 合并、API Key ACL、完整
text-only messages、未知 alias 错误、非流式 JSON、SSE role/content/finish chunk 顺序和
最终 `[DONE]`。脚本还执行 focused tests、`test-compile`、Shell 语法和空白检查，证据写入
`.verification/openai-compatibility/<run-id>/`。

### 官方 SDK 外部 Client 黑盒验收

OpenAI 兼容层还必须经过真实 HTTP Client 验收，不能仅凭 MockMvc、Controller 测试或
WebUI 判断可用：

```bash
./scripts/verify-openai-sdk-e2e.sh
./scripts/verify-openai-sdk-e2e.sh --self-test
TESTCONTAINERS_RYUK_DISABLED=true mvn -pl spring-ai-rag-starter -am test \
  -Dtest=ExternalStarterConsumerContextTest -Dstarter-consumer.it.enabled=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

脚本默认启动隔离 PostgreSQL、Mock 上游、Core standalone 和不主动扫描
`com.springairag` 的 Starter Consumer，并用官方 Python SDK `2.14.0` 与 Node SDK
`7.34.0` 在两种拓扑下验证 models、JSON、SSE、幂等重放、错误信封和认证。它还确认
外部 Starter Consumer 执行 Flyway V1–V65 后可提供同一 `/v1` 客户端契约。结果位于
`.verification/openai-sdk-e2e/<run-id>/summary.md`，默认不产生真实模型费用。

只有显式提供真实地址和密钥才运行 real-LLM 模式；不能把 mock 通过当作真实模型质量
证据。该门禁与 `verify-api-compatibility.sh`、`verify-client-consumer-contract.sh`、
Maven 全量回归和 `scripts/dev.sh` 启停验收互补。`verify-gated-it.sh` 聚合 Core/Starter
数据库套件；显式启用 Starter IT 时 Docker 不可用会失败而非 skip。
SDK runner 的 20 项正/负变异自测覆盖意外成功、缺少 `[DONE]`、重复终态、顺序及身份错误；
隔离 HTTP 验收额外覆盖 ACL、PostgreSQL quota、上游 503、关闭 `/v1` 后 Native API
仍可用和幂等 provider 调用次数。发布时使用 `verify-release.sh --with-openai-sdk`。

### Client SDK 快速入门与恢复验收

外部 Client 的最小 Python/Node 示例和离线 secret-safety 门禁独立于 WebUI：

```bash
./scripts/verify-client-sdk-quickstart.sh --self-test
```

需要对已启动服务做真实 HTTP 检查时，必须显式提供服务根地址（不带 `/v1`）和 API Key：

```bash
CLIENT_SDK_LIVE=1 \
CLIENT_SDK_BASE_URL=http://127.0.0.1:18081 \
CLIENT_SDK_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/verify-client-sdk-quickstart.sh
```

恢复就绪门禁会启动一次性 PostgreSQL，执行仓库中的全部 Flyway 迁移，并用数据库实际的
`flyway_schema_history` 与源码迁移清单对账，再运行预算/治理 PostgreSQL 集成测试；它不是
备份、恢复点或云灾备系统：

```bash
./scripts/verify-recovery-readiness.sh
```

发布脚本默认不调用这两项额外门禁；需要时显式使用
`./scripts/verify-release.sh --with-client-sdk --with-recovery-readiness`。这些门禁也不把
Mock 或离线通过描述为真实 LLM 质量证据。

### 持久化 Embedding Jobs 验收门禁

```bash
./scripts/verify-embedding-jobs.sh
```

脚本串行执行 service/worker/Controller focused tests，自动启动隔离 PostgreSQL 并从空库
执行 V1–V52，验证 V33 active-job coalesce、force 原子升级和并发 worker 原子条件
claim，再执行 `test-compile`、Shell 语法和空白检查。已有隔离数据库时可用
`EMBEDDING_JOBS_IT_JDBC_URL` 覆盖。

### 文档处理 Run/Stage PostgreSQL 验收门禁

Processing Run 是现有持久化 Embedding Job 之上的阶段账本。该专项测试从空库执行 V1–V61，
验证五个固定 stage 初始化、worker claim 镜像、stage CAS/进度、Job 终态镜像和基础约束，
不替换旧 Job 的 lease、重试或 generation fencing：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Ddocument-processing.it.enabled=true \
  -Dtest=DocumentProcessingRunPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

套件受 `document-processing.it.enabled` 门控，已纳入 `scripts/verify-gated-it.sh`。只跑该
套件时执行 `./scripts/verify-gated-it.sh DocumentProcessingRunPostgresIntegrationTest`；
连接一次性 PostgreSQL 时可用对应的 `document-processing.it.*` 系统属性覆盖默认连接。

### PDF 导入 PostgreSQL 验收门禁

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dpdf-import.it.enabled=true \
  -Dtest=PdfImportPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

`PdfImportPostgresIntegrationTest` 由 `pdf-import.it.enabled` 门控，所以普通
`mvn test` 不需要 Docker 守护进程。套件启动 `pgvector/pgvector:pg16`
（可用 `TESTCONTAINERS_PG_IMAGE` 覆盖），从空 schema 跑 Flyway 并断言已安装的
最高版本号，然后用 PDFBox 生成的 PDF 通过 MockMvc 打真实的
`/api/v1/rag/files/pdf` 端点。它验证可读元数据在导入后保留、
`/api/v1/rag/files/tree` 能把存下来的 `pdf-import:{uuid}/default.md` 来源解析成
预览与原始文件路由、非 PDF 文件没有导入元数据，以及批次中途失败时会把
JPA flush 已经写下去的文件一并回滚。

在 Batch 790 之前，这个开关在任何脚本和文档里都没有出现过，只在一份已归档的
进度记录里，因此那两个测试方法没有任何有文档的运行方式。
`scripts/verify-integration-test-switches.mjs` 现在会在受门控的套件缺少运行路径时失败，
两个方向都查。

### 跑完整个门控清单

```bash
./scripts/verify-gated-it.sh                                     # 全部套件
./scripts/verify-gated-it.sh ChatSessionPostgresIntegrationTest  # 单个套件
```

当前门控清单由 `verify-integration-test-switches.mjs` 与
`verify-gated-it.sh` 交叉核对；本次审计基线为 28 个门控类、208 个测试方法，
每个都在自己的 `<x>.it.enabled` 开关后面，所以普通 `mvn test` 永远不需要
Docker 守护进程。`verify-gated-it.sh` 是唯一一条把它们全部打开的命令，本地约
9 分钟，`ci.yml` 的 "Gated PostgreSQL integration tests" 这一步跑的就是它。

关于这份清单，有两件事值得直说，因为它们在 Batch 912 之前**都是假的**：

- **Batch 912 起它才是完整的**。在此之前，脚本自己那句注释——"跑全部纯 DB 型套件"
  ——描述的是其中 3 个。另外 20 个只能由各自 feature 的脚本单独跑，没有别的命令
  能跑它们，而 `verify-integration-test-switches.mjs` 全程是绿的：
  "有运行路径点亮这个开关"和"有一条命令能跑完整个清单"是两个不同的问题。
  这道门禁现在也问第二个问题，它叫 `unaggregated-gated-suite`。
- **其中有 2 个测试方法当时是红的**。`PdfImportPostgresIntegrationTest` 的
  Spring 上下文加载失败：自 Batch 851 起控制器把 `CollectionIdentityResolver`
  作为必填构造依赖，而这套件手写的 `@Import` 清单一直没加上它。这个套件被开关
  挡着，而门禁只检查开关**能不能被打开**，所以从来没有人跑过它。
  把套件加进 `ALL_SUITES` 的时候，请预期你会找出点东西。

**要跑在 `verify-test-visibility` 之前，不是之后。** 两者都写
`spring-ai-rag-core/target/surefire-reports`，而这个脚本的产物是两者中较新的：
`verify-test-visibility.mjs` 拿这些报告和测试源码树做**双向**对账，
所以一个只装着部分门控类报告的目录，会让它报出几百个"既没跑也没跳过"的源。
`scripts/verify-*.sh` 里有 **12 个**验收脚本是弄丢这些报告的第三条路——
它们各自以 `mvn clean compile test-compile` 这样的编译门禁开头，
而 `clean` 会把整个 `target/` 目录一起带走。这 12 个里有 5 个会补一次不限范围的
`mvn test` 把报告放回来，另外 7 个在**自己的头注释里**写明了这一点，
`verify-gate-wiring.mjs` 以 `unannounced-report-destruction` 这条规则盯着：
再出现第十三条路，那道门禁会变红，而不是给你一个意外。可行的顺序是：

```bash
mvn test                                  # 全量
./scripts/verify-gate-entry-points.mjs    # 任何读 surefire 报告的门禁
./scripts/verify-gated-it.sh              # 门控清单——此后报告已过期
```

如果一个读报告的门禁报的是**根本没有 surefire 报告**，而不是计数对不上，
那说明你的 `mvn test` 之后有东西跑过 `clean`——这和报告过期是两种不同的失败，
自测现在会把这一点说出来。

### 未归属文档 Chat 提交栅栏验收

`UnassignedDocumentChatFencePostgresIntegrationTest` 验证 V65 迁移后，未绑定
Collection 的文档仍能在默认可见范围内完成 durable Chat 来源预留，同时受
revision/CAS 和集合清理约束。普通 `mvn test` 不启动 Docker；需要单独验收时运行：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16 \
mvn -pl spring-ai-rag-core -am \
  -Dunassigned-chat-fence.it.enabled=true \
  -Dtest=UnassignedDocumentChatFencePostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

也可以通过 `./scripts/verify-gated-it.sh UnassignedDocumentChatFencePostgresIntegrationTest`
加入完整门控清单运行；该测试只使用一次性 PostgreSQL，不调用真实模型 provider。

### 路径穿越探测（不设门控 — 随默认测试跑）

```bash
mvn -pl spring-ai-rag-core -Dtest=SecurityPathTraversalProbeTest test
```

`SecurityPathTraversalProbeTest` 回答的是 Batch 794 当时只能绕开的问题：
servlet 容器拿到 `/actuator/../api/v1/rag/...` 这样的请求行**到底会怎么做**。
套件在随机端口上起真实服务器，并把请求行用**裸 socket** 发出去——
因为 `HttpClient` 和所有浏览器都会在请求离开进程之前就规范化 URI，
那样测的是客户端的行为；MockMvc 同样不会走容器的 URI 映射。
只有线路上的字节才能决定容器把请求路由到哪。

实测结果由一个排在鉴权之前的过滤器记录下来：

```
requestURI  = /actuator/../api/v1/rag/probe-protected
servletPath = /api/v1/rag/probe-protected
```

过滤器按**规范化**路径被匹配，却拿到**原始**请求行——`SecurityPathExclusions`
要防的那个分歧是真的。请求随后**无论走不走排除判定都是 404**，
因为 Spring MVC 按未规范化的 URI 解析 handler。

**这个套件刻意不设门控。** 它不需要数据库、不需要模型 provider，
只需要一直都在的 servlet 容器；一条只在"有人记得加 flag"时才执行的安全不变量
是很弱的不变量。

**它证明不了什么**：把 `SecurityPathExclusions` 的 fail closed 分支删掉，
五个用例照样全绿，因为路由反正都会把穿越请求 404 掉。所以匿名用例的 404
**不能**当作排除规则生效的证据——真正钉住那条规则的是 `SecurityPathExclusionsTest`，
删掉分支它会红。探测负责容器层的前置事实，单元测试负责那条规则。

### 下一轮高价值能力验收门禁

```bash
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-no-pessimistic-locks.sh
# 或一次串行跑完 A–D：
./scripts/verify-next-high-value-features.sh
```

固定范围分别覆盖检索诊断（V35）、metadata/payload 过滤（V36）、embedding 运营
（V37，含 SYNC/ASYNC/SKIP、ACL 分页与就绪接口）、citation / 受管套件（V38），以及
V39 后禁止显式悲观锁的静态门禁。完整聚合脚本必须串行运行，因为 Maven clean 会删除
模块 `target/`。

### 真实检索质量回归门禁

```bash
BASE_URL=http://127.0.0.1:18081 \
  ./scripts/verify-quality-regression.sh
```

门禁先校验 `testdata/regression/retrieval-core-v1.json` 与提交的 baseline 契约，再调用
真实 embedding/search API。当前 fixture 使用稳定
`collectionKey + sourceNamespace(default) + externalId` 判断 relevant
identity，检查 Hit Rate、MRR、Recall@K、nDCG、每 case minimum、相对 baseline 回退、
Collection decoy 泄漏和 JSONB 明确空结果。外部 provider、数据库或 embedding 失败必须
返回非零，不能伪装为质量通过。

### `EACH_COLLECTION` 覆盖黑盒回归

```bash
./scripts/verify-collection-coverage.sh --self-test
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-collection-coverage.sh
```

第二条命令通过外部 HTTP API 幂等写入专用 `retrieval-collection-coverage-v1` 夹具，
分别重复执行 GLOBAL 与 EACH Search。POST Search 必须保持既有裸 `RetrievalResult` 数组响应契约；
它硬断言该数组形状、EACH 的每个预期
有命中 Collection、授权范围隔离、禁止来源不泄漏、覆盖摘要字段一致及 GLOBAL 不新增覆盖
header；另输出 Hit Rate、MRR、Recall@K、nDCG、结果数和 p95 延迟的对照报告。fixture 使用
专用 key/sourceNamespace，不删除现有数据；真实 embedding、PostgreSQL 和服务必须可用，
凭据从环境或 `.env` 读取。证据写入 `.verification/collection-coverage/`。发布脚本默认只跑
本地自测，实时 HTTP 验收使用 `--with-collection-coverage`。

### KNOWLEDGE 检索后证据组装聚焦回归

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest=RetrievalContextAssemblyPostProcessorTest,PromptBudgetDocumentPostProcessorTest,\
RetrievalDocumentMapperTest,ModeAwareChatClientFactoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./scripts/verify-gated-it.sh MultiCollectionRetrievalPostgresIntegrationTest
```

`testdata/regression/context-assembly-v1.json` 是版本化质量夹具，覆盖跨块定义、表头/单位、
多步骤流程、重叠去重、中文/emoji、合成标题和预算不足时恢复 anchor。单测必须检查最终
advisor 送入模型的文本，而不是只检查 post-processor 的返回值；来源仍只能使用原 anchor
的 citation handle，`contextAssembly` 只能作为加法 metadata。PostgreSQL 套件还验证
Collection/document/metadata scope、关键词-only、freshness、删除/禁用、hash/chunker/
generation/seed-text 失配和 JSON record 隔离。

真实 Chat 验收应使用隔离数据库和端口运行 `scripts/dev.sh`，用只命中中间 chunk 的自有
fixture 验证必要事实出现在最终模型输入和答案中、sources 仍保留 anchor、诊断存在且不含
正文/ID/hash 等高基数数据。真实调用只证明该 fixture 的契约，不替代确定性单测，也不能
只以答案非空或单一 LLM judge 分数判定通过。

### 受管 API Principal PostgreSQL 矩阵

在真实 PostgreSQL 上执行 V48→V55 迁移、credential lifecycle、operation capability、
幂等 provisioning/replay/conflict、owner 隔离、并发首次创建、即时与 staged rotation、
complete/cancel/expiry/family revoke、policy deadline clamp、rotation/revoke 后 replay
状态、policy concurrency、last-ADMIN、last-used、共享 quota 与有界清理矩阵：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dmanaged-api-principal.it.enabled=true \
  -Dtest=ManagedApiPrincipalPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

所有测试必须实际执行且 `skipped=0`。禁用 Ryuk 只是本地 Docker 兼容 workaround，测试
仍会停止自己创建的 PostgreSQL container。发布验收还需让两个隔离端口 backend 共用一个
数据库，证明吊销在下一次请求生效，并且请求 quota 全局只计一份。

完整发布门槛使用统一脚本：

```bash
./scripts/verify-managed-api-principals.sh

# Mock 门槛全部通过后，显式执行真实 provider 验收
MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=minimax \
./scripts/verify-managed-api-principals.sh --with-real-llm
```

脚本串行执行 PostgreSQL 迁移/并发矩阵、`mvn clean compile test-compile`、Maven 全量测试、
WebUI Vitest/TypeScript/生产构建/alignment、核心 Mock Playwright、禁锁与文档门禁；随后
启动两个共享一次性 PostgreSQL 的真实后端和一个 Vite 前端，验证只读 identity/GET 与
写入 `403`、轮换继承能力、非法能力不落库、认证 capability discovery、跨实例 keyed
provisioning 的 create/replay/conflict、staged prepare/replay/conflict/complete/cancel/
deadline/family revoke、overlap 双 credential 共享 quota、rotation/revoke 后 replay、
全局 quota、policy CAS、quota store 故障关闭和无截图真实 Playwright。真实 LLM 模式还
覆盖 native JSON/SSE 与 OpenAI-compatible JSON/SSE。真实 Chat principal 显式只有
`RAG_READ`；脚本先验证写请求 `403` 且 provider counter 不变，再证明幂等重放不产生重复
模型调用；随后覆盖 staged complete、cancel 后会话恢复和 pending family revoke，要求恰好
9 次成功真实 provider 调用，所有拒绝/replay 路径增量为 0。证据写入
`.verification/managed-api-principals/<run-id>/summary.md`，敏感响应只保存在 gitignored、
权限受限的 `private/` 子目录。

`MANAGED_API_REAL_LLM_PROVIDER` 支持 `openai`、`minimax` 和 `anthropic`。脚本只要求
`.env` 提供所选 provider 的 key、base URL 和 model，不会因未选择 provider 的变量缺失而
失败；摘要会记录实际选择，但不会记录 key。

这里的 9 次是该受管 principal staged 生命周期合同的确定性预期值，不是更广泛客户生命周期
验收的调用上限。完整接入验收应按实际场景继续覆盖创建、
更新、删除、恢复、凭据轮换和重启后的真实 Chat/Embedding 路径。

### 受管 API Principal 到期告警门禁

```bash
API_KEY_EXPIRY_ALERT_VERIFY_PHASE=focused \
./scripts/verify-api-key-expiry-alerts.sh

./scripts/verify-api-key-expiry-alerts.sh
```

focused 阶段一次性执行受影响后端测试、空库 V1-V61 PostgreSQL 生命周期矩阵、WebUI
typecheck/Vitest/alignment/production build 和 Alerts Mock Playwright。PostgreSQL 场景覆盖
8 路并发只产生一个 active condition/通知 claim、`WARNING → CRITICAL → EXPIRED` 原行
升级、同阶段刷新、延期/吊销解决、重新进入窗口的新历史、漏事件恢复、超过单批上限的公平
扫描和低敏持久化投影。Playwright 只使用网络、JSON、DOM 可见性与可访问断言，不使用截图。
`all` 阶段再执行 `mvn clean compile test-compile`、禁悲观锁、双语文档、Shell、diff 与
新增行密钥检查。真实 provider 生命周期验收在 Mock 门槛通过后单独执行，并持续观察服务日志。

### 告警通知 Durable Outbox 门禁

```bash
./scripts/verify-alert-notification-delivery.sh

MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=openai \
./scripts/verify-managed-api-principals.sh \
  --with-real-llm \
  --with-durable-notifications
```

专项门禁从空库迁移到 V59，使用隔离 PostgreSQL、真实本地 HTTP provider stub、两个后端
实例和真实 WebUI，验证 after-commit Event 早于一分钟 fallback、单个 ledger attempt
恰好调用 provider 一次、`503 -> RETRY_WAIT -> DELIVERED`、进程在阻塞调用中退出后的
过期 lease 回收、稳定 delivery UUID、低敏 receipt 与 DOM/network 前端合同。浏览器断言
不使用截图。

联合门禁只在 Mock、Maven、前端和文档门槛通过后装载 `.env`，并实际调用所选 Chat LLM
与 Embedding 服务。`--with-durable-notifications` 自动启动隔离通知 stub，把
WARNING/CRITICAL 生命周期接入 V58 receipt，并检查唯一性、最终送达、event-driven latency
和密钥不落盘。真实 provider 返回错误或账号池不可用时必须记录为失败并重试，不能用
本地 stub 结果冒充真实模型验收。

### Chat 对话能力重构验收门禁

Chat 实现被明确验证为三种模式：

- `KNOWLEDGE`：Spring AI Modular RAG，内部使用项目自己的混合检索和 rerank。
- `AGENT`：Spring AI Tool Calling，检索范围由服务端控制。
- `PLAIN`：普通 ChatClient 加 Memory，不执行知识检索。

运行可重复的本地门禁：

```bash
./scripts/verify-chat-capability.sh
```

该脚本会执行 Chat 执行链、Tool Calling、Memory/历史、结构化 SSE、
Controller/集成和导出测试；V32 会话 lease/原子性、V46 摘要 CAS、V47 durable
Chat turn 以及
`NextHighValueFeaturesPostgresIntegrationTest` PostgreSQL 矩阵；`mvn clean
compile test-compile`；完整 `mvn test`；安装当前 reactor 产物并测试独立的
`demos/demo-domain-extension` 与 `demos/demo-tool-calling-sql` 消费者；使用临时
PostgreSQL 和 dummy 模型端点执行
Spring Boot 启动/健康烟测；WebUI Vitest、TypeScript、生产构建；Chat 核心 Mock
Playwright；project-docs 和空白检查。日志及 Markdown 结果写入
`.verification/chat-capability/<run-id>/summary.md`。

独立 demo 不属于根 reactor。直接在 demo 目录运行 Maven 可能链接本地仓库里的旧
`spring-ai-rag-starter:1.0.0`；一键脚本会先安装当前工作区产物，再运行 demo 测试，
以验证新增 API 对外部消费者确实可编译。

浏览器门禁只使用 DOM 可见性、请求/响应断言、URL 断言和测试断言，不使用截图作为正确性
证据。浏览器套件覆盖模式/模型请求、AGENT 工具生命周期、来源、历史来源恢复、选定
Collection、移动端横向溢出、一次 retry 内复用 `Idempotency-Key`、response header/done
turn identity、部分 SSE replay 不重复 assistant bubble、409 输入保留以及 stop 不触发
retry。

本轮 `KNOWLEDGE` 查询扩展的门禁还必须覆盖 `BoundedMultiQueryExpander` 和真实
PostgreSQL 检索链路。focused 测试断言默认三路、`max-retrieval-queries=1` 不调用扩展
模型、超配置变体在执行前收敛、空白/精确重复去重、授权 context/history 保留、KNOWLEDGE
与 AGENT budget 隔离，以及响应 metadata 与持久化 attempt metadata 使用同一有界摘要。
PostgreSQL 矩阵中的 `HybridRetrieverRrfPostgresIntegrationTest` 会通过真实
`ProjectDocumentRetriever` 和 `HybridRetrieverService` 执行扩展后的 query，并验证重复
变体不会产生第二次 embedding/SQL 检索。运行：

同一 focused 门禁还包含 `ProjectDocumentJoinerTest`、
`ModeAwareChatClientFactoryTest`、`RetrievalTraceCollectorTest` 和
`ChatExecutionServiceTest`。这些测试共同验证不依赖 Map 遍历的规范排序、最高有限
score 保留、匿名和非有限 score 边界、rerank 前去重、四整数
`metadata.retrieval.documentJoin` 契约、持久化 attempt 一致性，以及 AGENT 不出现该
字段。诊断 payload 不使用 query 文本、Document ID、正文或 metadata 值。

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

该脚本的浏览器阶段仍只使用 DOM、可访问状态、网络请求/响应和 JSON 断言，不使用截图；
`metadata.retrieval.queryExpansion` 不应出现原始 query、模型输出或异常堆栈。若 PostgreSQL
或 Docker 不可用，必须保留 `SKIP` 证据，不能把 focused Mock 测试当作真实数据库通过。

PostgreSQL 门禁默认执行。如果 Docker 不可用，或 daemon 拒绝当前协商的 API 版本，脚本会
把 Docker 和 PostgreSQL 门禁都记录为 `SKIP`；`PASS_WITH_SKIPS` 不等于完整发布门禁。本项目
曾遇到 Testcontainers 协商 Docker API `1.32`，而本机 daemon 要求最低 `1.40`。可使用：

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

`ChatSessionPostgresIntegrationTest` 也支持一次性外部数据库，便于绕过 Testcontainers
Docker API 协商问题；该测试会执行 `Flyway.clean()`，不得指向开发库或生产库：

```bash
mvn -pl spring-ai-rag-core \
  -Dtest=ChatSessionPostgresIntegrationTest \
  -Dchat.it.enabled=true \
  -Dchat.it.jdbc-url=jdbc:postgresql://127.0.0.1:5432/disposable_chat_test \
  -Dchat.it.username=postgres \
  -Dchat.it.password=postgres \
  -Dchat.it.clean-confirm=YES \
  test
```

只有在环境确实不可用时才使用 `--skip-postgres`，并保留生成的 summary。境内 registry/证书、
Docker API 和 Ryuk 的排障经验见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)
和 [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md)。

后端启动烟测默认使用严格绑定的 `CHAT_STARTUP_PORT=4210`，可在端口冲突时覆盖。只有
明确不具备 Docker 环境时才使用 `--skip-startup`，并保留该 `SKIP` 记录。

真实 Provider 验证必须显式开启：

```bash
./scripts/verify-chat-capability.sh --with-real-llm
```

本轮 Chat turn 幂等性还提供一个更窄、可重复的真实 provider smoke。它固定使用
`PLAIN`，因此在只验证 Chat 幂等链路时不需要 Embedding key：

```bash
BASE_URL=http://127.0.0.1:18081 \
REAL_LLM_ENV_FILE=.env \
./scripts/real-llm-chat-idempotency-smoke.sh
```

该 smoke 必须连接已启动的隔离 PostgreSQL profile 服务，并验证 native JSON/SSE 首次
请求、相同 key 的完整响应重放、key conflict、`GET` turn status，以及 provider counter
在 replay 前后只增加一次。它不能替代需要真实 Embedding 的检索 smoke，也不能用 Mock
Playwright 或代码 review 代替真实 provider 证据。

脚本会从 `.env` 或调用环境读取真实模型配置，并要求真实 WebUI 路径提供
`RAG_ROOT_API_KEY`。它会创建一次性 PostgreSQL 数据库，在隔离端口启动
`scripts/dev.sh`（默认后端 `18083`、WebUI `15175`），执行真实 WebUI
`chat-real.spec.ts` 和 provider smoke，最后清理服务、临时环境文件和数据库。
需要时可用 `CHAT_REAL_BACKEND_PORT`、`CHAT_REAL_FRONTEND_PORT` 覆盖端口。未传该选项时，
真实 LLM 门禁会明确记录为 `SKIP`；本地测试和 Mock Playwright 不代表真实模型或真实
Tool Calling endpoint 已验证。

rerank 文档级证据多样化使用独立门禁：

```bash
./scripts/verify-rerank-document-diversity.sh
```

该门禁先通过单元测试和真实 PostgreSQL/pgvector 夹具证明两阶段选择器，再用 POST Search
JSON 验证多样化契约，用实际 GET Search 页面验证 DOM、认证和代理兼容。前端证据只使用
可见 DOM、请求/响应、JSON 和数据库支持的行为断言，不使用截图。真实 LLM baseline
通过后，它还会在同一可处置数据库与固定夹具上比较 cap=`0` 和 cap=`2`：每个变体默认先
预热，再采集 20 个 Search、5 个 Chat 请求，通过 trace ID 只读关联
`rag_retrieval_logs`。`runtime-comparison.json` / `runtime-comparison.md` 记录
Search/Chat retrieval p95、rerank stage p95、HTTP latency/payload 和最终 unique document
count；延迟与 payload 不设通过阈值，确定性正确性继续由 PostgreSQL 集成矩阵承担。

只读关联得到的数据库 `result_count` 表示 latest retrieval outcome 数量。Search 必须与
最终 HTTP result count 相等；KNOWLEDGE Chat 的最终 HTTP sources 来自 advisor
后处理后的 document context，query join、rerank 或 prompt budget 都可能使它与 latest
retrieval outcome 不同。运行时产物会记录两者是否一致，但不会把两个不同流水线阶段误当成
同一个契约。

运行时 Chat 采样对明确的瞬时 HTTP `429/502/503/504` 默认最多尝试两次，并输出每次重试
日志；Search、不可重试 HTTP、无效 payload 和尝试耗尽仍使门禁失败。只有需要调整这个正整数
上限时才使用 `RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS`。

同一门禁的 `HeuristicRerankProviderTest`、`ReRankingServiceTest` 和
`HttpRerankProviderTest` 还覆盖无空格 CJK 局部匹配、混合语言、英文兼容、blank/长输入、
完全重复 chunk、默认权重排序、title-only 英文/CJK/混合 ID、blank title 精确兼容、
title/diversity 隔离、字段复制，以及 HTTP 成功请求不含 title、fallback 使用标题感知
heuristic；它们还覆盖拒绝更长标识符内嵌的 Latin/数字 term、外层标点、CJK/非 CJK
边界 transition、带符号技术词，以及先出现非法内嵌 occurrence、后出现合法 occurrence。
`HybridRetrieverRrfPostgresIntegrationTest` 使用真实 PostgreSQL/pgvector 先生成“原始
向量略高但正文词法无关”的第一候选，再分别证明 CJK chunk relevance 和来自
`rag_documents.title` 的权威标题可以通过真实 factory 与 `ReRankingService` 纠正排序；
它还证明 `storage` / `OpenAI` / `19042` 等 title 内嵌 substring 不会压过完整的
`RAG` / `AI` / `9042` title term。该整类测试必须 `failures=0`、`errors=0`、
`skipped=0`。

## Client 契约治理验收

外部 Client API 是主要验收边界；WebUI 可用不能替代服务端 API 的验收。每次发布验证都运行
静态治理门禁：

```bash
./scripts/verify-client-contract-governance.sh
node scripts/lib/api-contract-check.mjs --self-test
```

针对正在运行的 PostgreSQL profile 服务，还要执行 HTTP 兼容性门禁：

```bash
API_CONTRACT_BASE_URL=http://127.0.0.1:18081 \
API_CONTRACT_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/verify-api-compatibility.sh
```

黑盒门禁检查原生 Chat/Search 参数校验、Retrieval Trace、治理 policy/audit 路由、
`X-Trace-Id`、`X-RAG-Failure-Class`、增量错误字段，以及成功审计响应不包含已持久化的
`details`。治理路由会接受符合部署认证模式的 `200/401/403`，但不会接受服务端 `5xx`。
设置 `API_CONTRACT_RUN_LIVE=1` 后，还会执行真实 Search 和 OpenAI-compatible Chat。
如果部署的默认 RAG 模式无法安全完成该实时请求、但仍需单独验证 OpenAI-compatible 传输，
可设置 `API_CONTRACT_RAG_MODE=PLAIN`。门禁会为请求增量加入可选的
`rag.mode=PLAIN` 和 `rag.memory=STATELESS` 字段；默认行为不变，且该选项不能宣称已经
完成 KNOWLEDGE 证据安全验收。

## Client Consumer Contract Kit

这是面向外部 Client 的独立消费方验收包，WebUI 通过不能替代它。离线检查默认启用：

```bash
node scripts/lib/client-consumer-contract.mjs --self-test
./scripts/verify-client-consumer-contract.sh
```

它固定验证旧请求仍可接受、Native JSON/SSE 的增量字段与终态、OpenAI JSON/SSE 的标准字段
隔离、`FailureClass`/`EvidenceAssessment` 映射、trace/retry header 和 deprecated registry。
显式设置 `CLIENT_CONTRACT_LIVE=1` 后才会调用运行中的服务；缺少 live 环境变量时记录
conditional skip，不伪造通过。完整示例见 [Client Consumer Contract Kit](client-contract-kit-zh-CN.md)。

## LLM 预算与数据治理验收

本批必须按服务契约验收，不能只看 WebUI。服务端最低矩阵包括：

- 预算超限 `429`、预算存储不可用 `503`、`Retry-After`，以及 provider 未被调用的证明；
- 同步、流式取消、query transform/expand、summary、fallback 和 AGENT 模型路径；
- durable reservation 幂等、settle/release/expiry 恢复、并发 CAS，以及空库和已有 V63 数据库
  上的 V64/V65 Flyway 迁移；
- 擦除 preview/status/apply 的权限、确认过期/不匹配、路径/正文 operation id 不匹配、终态
  幂等重放、部分失败恢复、有界删除和审计日志保护；
- 默认 central retention 只运行一次，旧清理入口仍可手工调用且不会重复调度。

确定性的 PostgreSQL/Flyway 验收套件使用门控，因此普通 `mvn test` 不需要 Docker。调试套件时
可直接执行，或通过聚合运行器执行（同时确保它仍在仓库的 gated inventory 中）：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16 \
mvn -pl spring-ai-rag-core -am \
  -Dllm-budget-governance.it.enabled=true \
  -Dtest=LlmBudgetAndGovernancePostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16 \
./scripts/verify-gated-it.sh LlmBudgetAndGovernancePostgresIntegrationTest
```

完成上述测试后仍必须通过外部 Client 门禁：`mvn test`、
`scripts/verify-client-consumer-contract.sh`、OpenAPI additive-only 检查、Native/OpenAI
JSON/SSE 探针，以及 `scripts/dev.sh` 启动/状态/停止。真实 LLM smoke 取决于凭据是否可用，
但 mock provider 结果不能替代“超限时没有调用 provider”的证据。

## 编写新测试的规则

1. **先写测试再写实现**（TDD 友好）
2. **每个 public 方法至少一个正向测试 + 一个边界测试**
3. **Controller 测试**用 `MockMvc`，不启动真实 HTTP
4. **Service 测试**用 `@Mock` 纯单元测试或 `@SpringBootTest` 集成测试
5. **不要用 `@Ignore` 跳过测试**——修好它或删掉它
6. **测试名称用 `should_描述预期行为` 格式**
7. **每个测试独立**——不依赖执行顺序
8. **不要为了让测试够得着而加 package-private 的生产重载。**一个把参数丢掉的
   便捷重载就是一条旁路，而调用它的测试会把产品永远不可能表现出的行为钉住。
   Batch 815 从 `RagChatController` 删掉了四个，每一个都有通过的测试，
   **而正是那些测试让这条旁路看起来合情合理**。

   识别特征是某个请求派生的参数凭空消失。`ChatPrincipal` 是从
   `HttpServletRequest` 派生的，而 `ChatPrincipal.from(null)` **不抛异常**——
   它返回 `local()`。所以一个传 `null` 的"仅供测试"重载不会炸，
   它会静默返回**无作用域**的数据，而这正是危险的方向。
   如果测试需要不同的参数个数，就传一个带好 principal 属性的
   `MockHttpServletRequest`，走生产真正使用的那个签名。

   让这次普查比"删掉、让编译器帮忙"更难的，是下面两件事：

   - **那个 `null` 常常藏在变量后面。**Batch 819 找到过一个重载：它自己去读
     `RequestContextHolder` 并把读到的东西转发出去——
     `HttpServletRequest currentRequest = … instanceof … ? … : null;`——
     字面量在调用点下方三行，任何按字面量匹配的判据都看不见它。
     **普查要从"没有映射注解的 public 方法"入手，而不是从 `null` 字面量入手。**
   - **删掉重载是简单的一半，迁移调用点才是会静默引入 bug 的那一半。**
     新参数要插到它的**语义位置**上；**追加**只在"新参数是最后一个"时才正确。
     `listDocuments` 把 `collectionKey` 加在了**第 8 位**、两个日期参数**之前**，
     于是脚本追加的那个 `null` 把两个日期参数整体挤左了一格——
     而**编译器抓不到**：`String` 同时满足 `String collectionKey` 和
     `String createdAfter`。**只有测试抓到了。**
     每处迁移都要和本批改动前的版本对拍，不要相信"编译绿了"。
   - **其中一些重载永远只会抛异常。**Batch 819 删掉的 11 个里有 2 个存在的唯一目的
     就是抛 `IllegalArgumentException`，而它们的测试正是在断言这个异常。
     迁移这种测试等于把一个已经没有方法可栖身的行为保留下来——
     **删掉它并写进账本，而不是另造一条"测别的东西"的替代测试。**

9. **变异实验必须一次只跑一个，且绝不在它运行时读文件。**Batch 815 第一轮三个
   变异**全是空变异**——模式没匹配上，文件根本没改，测试自然全绿。
   而在那个窗口里读源码，看到的正是变异**临时写上去**的那一行，
   它看上去和一个真实的越权漏洞一模一样。
   后台跑的变异会改工作区；这期间任何 `grep` 读到的都是实验状态。
   两条推论：**变异必须串行**；遇到可疑发现，必须拿**实验开始前的副本**复核。

## 性能基准测试

`RetrievalBenchmarkTest` 验证核心操作在合理时间内完成：

| 操作 | 目标 | 实测 |
|------|------|------|
| 向量检索 | < 500ms | ~1.9ms |
| 融合检索 | < 500ms | ~6ms |
| Cosine 计算 (10万次) | < 200ms | ~75ms |

## 常见问题

### `mvn test` 报错 "Connection refused"

PostgreSQL 未启动或 `.env` 未加载：

```bash
# 确认 PostgreSQL 运行
pg_isready -h localhost -p 5432

# 加载环境变量
export $(cat .env | grep -v '^#' | xargs)
```

### 嵌入模型测试太慢

嵌入模型调用（SiliconFlow API）需要网络，在 CI 中可以 Mock：

```java
@MockBean
private EmbeddingModel embeddingModel;

@BeforeEach
void setup() {
    when(embeddingModel.embed(any(String.class)))
        .thenReturn(new float[1024]); // 返回固定向量
}
```

### JaCoCo 覆盖率不准确

```bash
# 清理后重新测试
mvn clean test jacoco:report
```
