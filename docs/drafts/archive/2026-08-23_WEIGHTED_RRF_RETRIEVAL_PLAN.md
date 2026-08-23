# 加权 RRF 检索融合实施规划

> **状态**：规划审查 3/3 通过；生产实现与基本集成门槛已完成，正在进行实现连续三轮收敛审查
>
> **规划日期**：2026-08-23
>
> **代码基线**：本地 `main` / `origin/main` @ `7dc7ab9d`
>
> **实施分支**：`codex/weighted-rrf-retrieval-20260823`
>
> **worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **近距离上下文**：[项目上下文](../../project-context-zh-CN.md)、
> [架构说明](../../architecture-zh-CN.md)、[REST API](../../rest-api-zh-CN.md)、
> [质量默认值](../../quality-defaults-zh-CN.md)、[测试指南](../../testing-guide-zh-CN.md)、
> [交付工作流](../../delivery-workflow-zh-CN.md)

本文件是本轮实施的恢复入口。目标只有一个：让当前已经对外描述为 RRF 的向量/全文混合
检索，真正按候选名次进行加权 RRF 融合，并保持结果分数、诊断字段和调用方契约可理解。
本轮不重新设计检索管线，不引入新的权限、用量、缓存、数据库或 WebUI 能力。

## 1. 执行摘要

当前主链路已经具备：

- `HybridRetrieverService` 并行执行向量检索与可用的中文/英文/pg_trgm 全文检索；
- `RetrievalUtils.fuseResults(...)` 合并同一 `documentId + chunkIndex` 的候选；
- 生产 profile 使用 `vectorWeight=0.55`、`fulltextWeight=0.45`，并在后续阶段执行启发式
  或 HTTP rerank；
- 质量回归脚本、goldenset 和直接检索 API，可在不调用 Chat LLM 的情况下观察排序变化。

但实现与稳定文档不一致：`fuseResults` 当前先按每个通道的原始分数除以该通道最大值，
再取加权最大值；它不是 RRF。不同全文提供方的分数（例如 `ts_rank`、trigram similarity）
没有可比的尺度，最大值归一化会使某个通道的局部分数形状影响跨通道排序。结果可能依赖
提供方，而不是依赖候选在各通道中的名次。

本轮将把融合改为**缩放后的加权 RRF**：

```text
rrf(d) = (K + 1) *
         (vectorWeight   / (K + vectorRank(d))
        + fulltextWeight / (K + fulltextRank(d)))
```

其中 `K=60`，名次从 1 开始；候选未出现在某通道时该通道不贡献分数。乘以 `K+1` 只
保持既有 `score` 的可读尺度：两个通道都排第 1 的候选得分等于两个权重之和，单通道
第 1 的候选约为该通道权重。当前 API 允许两个权重分别独立取 `0..1`，不要求总和为
`1`，因此该实现的正常上界是 `vectorWeight + fulltextWeight`（最多约为 `2`），而不是
强制归一化为 `1`。缩放不改变 RRF 的排序关系，也让后续 heuristic rerank 继续收到接近
原有范围的融合信号。

## 2. 本轮目标与非目标

### 2.1 目标

1. `RetrievalUtils.fuseResults` 使用固定 `K=60` 的加权 RRF，而不是原始分数归一化。
2. 同一候选在两个通道都出现时累加两个名次贡献；只命中一个通道时只保留该通道贡献。
3. 保留 `vectorScore` 与 `fulltextScore` 的提供方原始分数，用于诊断；`score` 明确作为
   当前查询内的排序信号。
4. 对相同融合分数使用稳定、可重复的 `documentId` 与 `chunkIndex` 次序，避免结果因
   集合/数据库返回顺序变化而产生无意义抖动。
5. 用单元测试证明分数尺度不再影响名次，用已有 PostgreSQL 检索回归证明 scope、过滤、
   命中和质量下限没有回退。
6. 将 REST API 与架构的分数语义补充为“RRF 排序信号”，并保持中英文文档同步。

### 2.2 非目标

- 不改变 HTTP 路径、请求字段、响应 JSON 字段名或 API 版本。
- 不新增 RRF 配置项；本轮固定 `K=60`，避免引入一次小修复所不需要的配置兼容面。
- 不改变向量、全文、query rewrite、rerank、Embedding Profile、Collection scope 或
  JSONB filter 的候选召回逻辑。
- 不调整 `vectorWeight`/`fulltextWeight` 的默认值和校验范围。
- 不实现 `EACH_COLLECTION`、跨 Collection fan-out、专业 rerank provider、权限体系、
  token 用量账本、成本估算或外部 Client 适配。
- 不修改数据库 schema、Flyway migration、缓存失效策略或 WebUI 代码。
- 不通过真实 Chat LLM 调用证明本轮功能；本轮修改发生在直接检索融合层，真实 embedding
  检索回归在环境具备时执行，Chat smoke 标记为不适用。

## 3. 已核对的当前事实

### 3.1 生产调用路径

- `HybridRetrieverService.searchInScopeDetailed(...)` 计算有效 limit 和两个通道权重，
  并行调用 `runVector(...)`、`runFulltext(...)`。
- 两个通道各自按数据库/provider 返回的相关性顺序返回候选；混合模式取
  `effectiveLimit * 2` 候选后调用 `RetrievalUtils.fuseResults(...)`。
- `RerankAdvisor`/生产 mode-aware Chat 继续消费融合后的 `score`，但 REST 契约已经要求
  调用方优先相信结果顺序，组件分数仅供诊断。
- `vectorScore` 和 `fulltextScore` 已分别保留原始提供方分数；本轮不改变这两个字段。

### 3.2 当前错误点

当前 `RetrievalUtils` 的 `buildMergedEntries(...)`：

1. 找到每个通道的最大原始分数；
2. 对每个结果做 `resultScore / channelMax * channelWeight`；
3. 对相同候选使用最大贡献，而不是两个通道贡献相加；
4. 仅按融合分数排序。

这会造成两个问题：第一，全文提供方分数尺度改变时排序可能改变；第二，同一候选同时
被向量和全文命中时没有按 RRF 语义累加两个证据。`docs/architecture*` 与
`docs/hybrid-search-enhancement-plan.md` 已将目标描述为 RRF，因此本轮是实现纠偏，不是
新增一个与文档相反的算法。

### 3.3 兼容边界

- `RetrievalResult.score` 没有被声明为概率或跨查询可比较的绝对分数；REST 文档已要求
  调用方优先使用排序顺序。本轮进一步说明它是缩放后的 RRF 排序信号。
- 配置参考当前把权重描述为“系统自动归一化”，但实际 `RetrievalConfig`、Controller 和
  `HybridRetrieverService` 都只校验/传递各自的 `0..1` 值，没有总和归一化；本轮会同步
  修正配置双语文档，明确“建议总和为 `1.0`，服务不会自动归一化”。权重还必须是有限
  数值；GET Controller 的边界校验会明确拒绝 `NaN`/无穷值，融合工具对直接调用也不把
  非有限权重传播到结果分数。
- 旧单元测试只依赖排序、非 NaN、原始组件分数和来源字段；没有稳定依赖旧归一化公式的
  对外契约测试。涉及全零输入的测试需要改为验证“输入分数不影响融合、输出为有限非负
  RRF 分数”，因为 RRF 依据名次而不是原始分数。
- RRF 分数的整体乘法不改变排序，并将 top-rank 信号维持在权重之和附近（当前合法范围
  为 `0..2`）；这保护启发式 rerank 对融合分数的既有相对量级。它不是向调用方承诺
  分数永远不超过 `1`。

## 4. 冻结算法契约

### 4.1 候选 identity 与保留字段

- 合并键仍为 `documentId + ":" + chunkIndex`，与当前行为保持一致。
- 第一次遇到候选的结果对象提供标题、正文、metadata 和 provenance；随后只更新融合
  分数及两个组件原始分数。
- 向量通道和全文通道按各自 provider 的原始 `score` 降序形成 rank 1、2、3……；provider
  已经承诺按相关性返回，因此这是对既有顺序的显式固化，而不是跨通道比较分数。通道内
  原始分数相同时，再按 `documentId` 的 null-safe 字典序和 `chunkIndex` 升序稳定化，
  消除数据库同分返回顺序抖动。若 provider 传入非有限原始分数，有限分数优先；非有限
  分数彼此按相同稳定 tie-break 排序，且仍可作为候选保留，但不参与原始分数之间的数值
  比较。
- 正常 provider 不会在同一通道返回重复 identity；若异常输入含重复 identity，只取该
  identity 在该通道的第一次出现，避免重复行凭空增加证据，后续重复行仍占用其原始位置。

### 4.2 公式与边界

- `RRF_K = 60`，`RRF_SCALE = RRF_K + 1`。
- 对第 `rank` 名候选：

  ```text
  contribution = RRF_SCALE * channelWeight / (RRF_K + rank)
  ```

- 同时出现在两个通道的候选，其 `score` 是两项 contribution 之和。
- 原始 score 只用于各自通道内确定 rank，不参与向量与全文之间的数值比较；因此
  `ts_rank`、trigram similarity 和 cosine similarity 的量纲差异不会直接改变跨通道贡献。
- 空通道不贡献；权重为 0 时该通道 contribution 为 0，但不改变候选是否存在。
- `limit <= 0` 返回空列表；null 列表按空通道处理。
- 两个权重都必须是有限值且位于 `0..1`；HTTP 入口拒绝非有限值，底层融合工具对绕过
  HTTP 的直接调用以 `IllegalArgumentException` 拒绝非法权重，不静默修正输入。
- 融合分数必须是有限的；RRF 贡献只由有限的权重、`K` 和名次计算，不从原始 score
  数值直接计算，因此不会把 NaN/Infinity 传播到融合分数。原始组件字段按现有映射保留；
  若未来 provider 传入非法原始值，本轮只保证候选仍可确定性排序且融合结果不产生 NaN。
- 融合完成后按 `score` 降序，再按 `documentId` 的 null-safe 字典序，最后按
  `chunkIndex` 升序；排序比较器必须保持确定性。

## 5. 文件范围与实施顺序

### 5.1 允许修改

1. `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalUtils.java`
   - 替换融合实现，加入 RRF 常量/辅助方法和稳定排序。
2. `spring-ai-rag-core/src/test/java/com/springairag/core/retrieval/RetrievalUtilsTest.java`
   - 增加 RRF 名次、分数尺度、双通道累加、重复 identity、稳定 tie-break、非法权重和
     边界测试；迁移不再适用的旧归一化断言。
3. `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java`
   - 让 GET 检索入口明确拒绝 `NaN`/无穷权重，保持 HTTP 契约与融合算法的有限值边界一致。
4. `spring-ai-rag-core/src/test/java/com/springairag/core/controller/RagSearchControllerTest.java`
   - 增加 GET 权重非有限值返回 `400` 的回归测试。
5. `spring-ai-rag-core/src/test/java/com/springairag/core/integration/HybridRetrieverRrfPostgresIntegrationTest.java`
   - 使用真实 PostgreSQL/pgvector 执行向量分支，使用可控的全文 provider 返回有序候选，
     通过真实 `HybridRetrieverService` 触达融合方法，证明跨通道命中会累加且分数尺度不影响
     名次；不依赖真实外部 embedding 或 Chat LLM。
6. `docs/rest-api.md`、`docs/rest-api-zh-CN.md`
   - 补充 `score` 的 RRF 语义。
7. `docs/architecture.md`、`docs/architecture-zh-CN.md`
   - 说明混合融合是 weighted/scaled RRF，组件原始分数不参与跨通道尺度比较。
8. `docs/configuration.md`、`docs/configuration-zh-CN.md`
   - 修正权重不自动归一化的配置语义，保留“建议总和为 `1.0`”的运维建议。
9. 如验证需要，允许在 `docs/quality-defaults*` 增加一行验收说明；不修改质量阈值或
  goldenset 数据。

### 5.2 实施顺序

1. 记录本计划审查完成后的 SHA-256，并先更新 progress 的恢复入口。
2. 先更新 `RetrievalUtilsTest` 的预期和新增回归用例，确认测试能捕获旧实现。
3. 修改 `RetrievalUtils`，保持方法签名和结果对象字段不变。
4. 更新双语 REST/架构/配置文档；执行 `git diff --check` 和项目文档门禁。
5. 运行本任务相关单元/集成测试，随后执行基本后端、前端和运行时门槛。
6. 记录验证基线，再按固定范围做三轮只读实现审查；任何实质修复重置计数。

## 6. 一次性验收矩阵

### 6.1 后端快速与集成验证

先一次性运行：

```bash
mvn -pl spring-ai-rag-core \
  -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=RetrievalUtilsTest,HybridRetrieverServiceTest \
  test
mvn -pl spring-ai-rag-core \
  -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhybrid-rrf.it.enabled=true \
  -Dtest=HybridRetrieverRrfPostgresIntegrationTest \
  test
mvn clean compile test-compile
```

PostgreSQL 集成测试在 Docker 可用时使用 Testcontainers；若使用外部一次性数据库，必须
显式设置 `HYBRID_RRF_IT_CLEAN_CONFIRM=YES`，不能指向日常开发库。该测试从空库运行当前
Flyway 迁移，插入一个活动 Embedding Profile 和有序向量候选，再通过受控全文 provider
构造跨通道结果，验证真实服务调用的融合排序。它不把 mock provider 冒充为真实全文质量
证据；真实 provider 的质量由后面的在线回归负责。

### 6.2 直接质量回归

服务使用 `postgresql` profile 启动于隔离端口后运行：

```bash
BASE_URL=http://127.0.0.1:${ISOLATED_PORT} \
  ./scripts/verify-quality-regression.sh
BASE_URL=http://127.0.0.1:${ISOLATED_PORT} \
  ./scripts/run-retrieval-goldenset.sh
```

真实 embedding key 可用时执行上述两项；脚本必须观察日志并保留 summary。质量回归的
绝对下限、允许回退和 scope/JSONB 隔离断言不改写。真实 Chat LLM smoke 为 `N/A`，因为
本轮不改 Chat 生成或 provider 适配。

### 6.3 通用项目门槛

即使没有 WebUI 文件修改，按项目交付门禁执行：

```bash
./scripts/verify-project-docs.sh
./scripts/verify-no-pessimistic-locks.sh
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
VITE_PORT=4198
npx vite preview --host 127.0.0.1 --port "${VITE_PORT}" --strictPort \
  >../target/weighted-rrf-vite-preview.log 2>&1 &
VITE_PID=$!
trap 'kill "${VITE_PID}" 2>/dev/null || true' EXIT
BASE_URL="http://127.0.0.1:${VITE_PORT}" \
  npx playwright test e2e/search.spec.ts e2e/navigation.spec.ts --project=chromium
```

Playwright 只用 DOM、可访问状态、网络请求/响应和自动化断言，不使用截图。若仓库脚本
已经提供更窄的 Mock Playwright 命令，以脚本实际命令为准并记录结果。执行前应等待
`http://127.0.0.1:${VITE_PORT}/webui/` 可访问，完成后清理 preview 进程；该 preview
只提供前端静态资源，测试中的 API 请求由各 spec 的 route mock 处理。

### 6.4 服务启动与真实联合验证

- 后端必须可使用 `postgresql` profile 启动；若启动脚本需要 `.env`，使用隔离端口和
  可处置数据库。
- 先用 `scripts/dev.sh` 在特性 worktree 启动隔离开发栈，避免占用日常端口；例如：

  ```bash
  BACKEND_PORT=18083 FRONTEND_PORT=15174 \
    SPRING_PROFILES_ACTIVE=postgresql RAG_DEV_OPEN_BROWSER=false \
    ./scripts/dev.sh
  ```

  等待 `http://127.0.0.1:18083/actuator/health` 和
  `http://127.0.0.1:15174/webui/` 可访问后，在 `spring-ai-rag-webui/` 执行：

  ```bash
  BASE_URL=http://127.0.0.1:15174 \
    npx playwright test e2e/search.spec.ts e2e/navigation.spec.ts --project=chromium
  ```

  只使用 DOM、可访问状态、网络请求/响应和自动化断言；完成后执行
  `BACKEND_PORT=18083 FRONTEND_PORT=15174 ./scripts/dev.sh --stop`，并确认隔离端口已释放。
  该真实全栈检查用于确认共享 bundle、代理、服务启动和既有搜索页面没有被本轮修改影响；
  它不是本轮算法正确性的唯一证据。
- 不调用真实 Chat LLM；真实 embedding 检索回归属于“必要时”的外部依赖验证，key 不可用
  时明确记录为环境限制，不把 Mock 结果冒充真实质量证据。

## 7. 规划审查与实现收敛

### 7.1 规划连续审查范围

规划 `0/3` 开始，三轮固定范围：

1. 目标闭环、自包含性、非目标和用户优先级；
2. 算法契约、调用链、兼容性、分数语义和测试可行性；
3. 文件范围、验收顺序、运行环境、回滚和 Git 交付。

只处理会影响正确性、质量、兼容性、验证可信度或可交付性的实质问题。无问题轮次不修改
本计划或 progress；达到 `3/3` 后一次性记录结果。

### 7.2 实现连续审查范围

基本门槛全通过后，计数器从 `0/3` 开始：

1. RRF 公式、重复 identity、非法数值、稳定排序和结果字段；
2. `HybridRetrieverService` 调用兼容性、rerank 量级、REST 语义和回归阈值；
3. 测试覆盖、文档同步、启动/发布/回滚与 Git 状态。

任何影响正确性、质量、兼容性或验证证据的实质修复都会重置为 `0/3`，并重跑受影响的
基本门槛；连续三轮无修改后才允许交付。

## 8. 回滚、风险与完成定义

- 回滚只需恢复 `RetrievalUtils` 的旧实现和对应测试/文档；不涉及迁移，数据库无需回滚。
- 风险是 RRF 改变候选名次或启发式 rerank 输入分布；通过缩放公式、单元测试和真实
  retrieval regression/goldenset 控制。若质量门禁回退，停止交付并回滚算法，不扩大范围
  调整其它管线。
- 完成定义：规划 `3/3`、相关测试通过、`mvn clean compile test-compile` 通过、项目门禁
  通过、适用的 PostgreSQL/质量回归证据具备、实现 `3/3`，双语文档同步，特性分支合入
  最新 `origin/main` 后重新验证，最终合并并推送 `main` 且工作区状态可核对。
