# 容量与规模退化说明

> 本轮验证「数据量放大后的行为」。目标不是绝对数字，而是**退化曲线**：数据量 ×2 时
> 延迟是否线性增长、是否存在 N+1 或全表扫描。所有结果由
> `scripts/run-scale-benchmark.py` 生成，可复现。

## 1. 验证口径

- 语料：`scale-bench-1000` / `scale-bench-2000` / `scale-bench-3000` /
  `scale-bench-5000`，单 Collection 最大 **5,000 文档 / 50,000 chunk**；
  连同既有基础语料，本轮库内共约 **11.2k 文档 / 112k 向量**。
- 向量：确定性假 embedding（1024 维，32 个归一化向量循环），不调用真实模型。
- 环境：Apple M4 Pro / 64 GB，PostgreSQL 16.8 + pgvector 0.8.2。
- 检索请求走真实 HTTP 入口；每次请求都包含一次 mock embedding 调用（约 30–50 ms，
  与数据量无关），因此绝对值主要由该固定开销构成，比较时看**比值**。
- 放大验证用 `requests=10`、`warmup=2`（p95 只有 10 个样本，仅作参考）；上一轮
  1k→2k 使用 `requests=50`、`warmup=8`。结论以 p50 与 SQL 执行计划为主。

## 2. 复现

```bash
# 1) 启动 mock embedding + 应用（示例，密钥用占位符）
node scripts/mock-llm-server.js --port 18096
SPRING_PROFILES_ACTIVE=postgresql \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/spring_ai_rag_scale \
RAG_EMBEDDING_BASE_URL=http://127.0.0.1:18096 RAG_EMBEDDING_API_KEY=dummy \
RAG_ROOT_API_KEY='32-ascii-chars-placeholder-key-000' \
java -jar demos/demo-basic-rag/target/demo-basic-rag-1.0.0.jar --server.port=18095

# 2) 生成语料并压测（有界：文档数硬上限 5000）
python3 scripts/run-scale-benchmark.py --ack-write \
  --dsn 'postgresql://postgres:postgres@localhost:5432/spring_ai_rag_scale' \
  --base-url http://127.0.0.1:18095 \
  --api-key '32-ascii-chars-placeholder-key-000' \
  --docs 3000 --scale-docs 5000 --chunks-per-doc 10 \
  --requests 10 --warmup 2 --output /tmp/scale.json

# 3) 只测导入吞吐（不测 HTTP）
# 加 --skip-http；只测 TTL 可先停应用，再用 --seed-ttl-rows 10000

# 4) 清理 fixture
# 追加 --cleanup
```

脚本只写 `scale-bench-*` 集合；`--ack-write` 是破坏性写入护栏。

## 3. 实测结果

### 3.1 批量导入与向量写入

| 阶段 | 1k 文档 / 1 万 chunk | 2k 文档 / 2 万 chunk | ×2 比值 |
|------|---------------------|---------------------|---------|
| 文档写入 | 74.9 ms（13.3k/s） | 64.8 ms（30.9k/s） | 0.87x |
| chunk 写入 | 2.31 s（4.3k/s） | 3.75 s（5.3k/s） | 1.63x |
| 向量写入（含 HNSW 维护） | 10.7 s（937/s） | 21.0 s（952/s） | 1.97x |
| `ANALYZE` | 5.45 s | 6.52 s | 1.20x |
| 总耗时 | 18.5 s | 31.4 s | 1.70x |

结论：文档/chunk 批量写入**亚线性**（批大小摊薄了固定开销）；向量写入近似线性，
吞吐稳定在 **约 950 向量/秒**（HNSW 索引在位时），插入是主要成本。

### 3.2 HNSW 索引构建

- 31k 向量：`CREATE INDEX CONCURRENTLY ... USING hnsw` 用时 **4.7 s**（约 157 µs/向量），
  `DROP INDEX CONCURRENTLY` 26 ms。
- 应用启动时 `EmbeddingProfileIndexManager` 会等待索引可用（上限 60 s）；索引缺失或
  无效会阻塞启动，这是多副本/大数据量下最容易被感知的容量边界。

### 3.3 检索延迟与退化曲线

| 接口（p50 / p95，ms） | 3k 文档（3 万 chunk） | 5k 文档（5 万 chunk） | p50 比值 |
|----------------------|----------------------|----------------------|----------|
| 纯向量检索 | 105.2 / 163.2 | 101.7 / 455.4 | 0.97x |
| 混合检索 | 150.8 / 206.7 | 203.1 / 277.5 | 1.35x |
| 全文加权检索 | 143.5 / 164.0 | 211.0 / 252.2 | 1.47x |
| 文档列表（首页/深页） | 36.4 / 54.6 · 19.2 / 36.7 | 17.5 / 26.7 · 23.8 / 36.1 | 0.48x / 1.24x |
| Collection 列表 | 7.1 / 11.4 | 5.5 / 14.8 | 0.78x（绝对值小） |
| 文档列表关键词检索（trigram 索引） | 未测 | 4.2 / 6.0 | — |
| 文档列表关键词检索（无索引） | 未测 | 8.4 / 21.1 | — |
| 流式导出 | 82.3 / 92.4 | 58.6 / 59.1 | 0.71x |
| 评估 | 7.9 / 11.4 | 5.5 / 6.4 | 0.70x |

1k→2k 的旧结果保留在 git 历史与本轮 3k→5k 的对照中：修复 HNSW 候选 CTE 后，
p50 从 255→451 ms 的线性退化已消失。本轮 3k→5k（文档 1.67x）的 p50 全部
低于 1.47x，导出与列表甚至随缓存/批量优化下降；纯向量 p95 的 455 ms 是
10 个样本中的单点冷查询，p50 持平。

文档列表关键词（`title` 参数）单独验证：`V73` 在 `pg_trgm` 可用时创建
`idx_rag_documents_title_trgm`（`GIN (lower(title) gin_trgm_ops)`）。5k 文档下
同一查询的执行计划从 `Seq Scan`（2.134 ms）变为 `Bitmap Index Scan`
（0.103 ms）；HTTP p50 从 8.4 ms 降到 4.2 ms。批量导入后必须
`VACUUM ANALYZE rag_documents`，否则新写入的 trigram 条目还在 GIN pending
list 里，查询仍可能走顺序扫描——压测脚本已把这一步纳入 seed 流程。

**关键发现（已修复）**：原向量检索 SQL 把 `rag_embeddings`、新鲜度状态表和
`rag_documents` 放在同一个 `ORDER BY <=> LIMIT` 查询里，规划器无法使用部分 HNSW
索引，退化为「扫描作用域内全部向量再排序」，所以 1k→2k 时 p50 从 255 ms 涨到
451 ms（≈线性）。修复后改为 **MATERIALIZED 候选 CTE**：先在只含 `rag_embeddings`
的 CTE 内用 HNSW 取候选（10x overfetch，至少 100、上限 1000），再在外层做新鲜度
JOIN 和最终排序。同一数据量下 p50 稳定在 50–60 ms，×2 比值约 1.1x。

同时 `resolveEmbeddingProfiles` 在「已授权到具体 Collection」时不再扫描
`rag_documents`，直接读 `rag_collection.active_embedding_profile_id`。

### 3.4 列表接口的 N+1（已修复）

- Collection 列表：原实现每个 Collection 调一次 `countByCollectionId`（整页 20 次）；
改为一次 `GROUP BY collection_id` 查询。
- 文档列表：原实现每篇文档各查一次 chunk 数、一次完整性快照；改为一次分组 count
+ 按 Profile 分组的一次 `inspectIds` 批量查询。
- 处理运行摘要：`summaryForDocument` 原按文档逐条查询（generation、当前 Run、
  最新 Run、活动 job）。本轮新增 `summariesForDocuments`，按 Embedding Profile
  分组后每页只做 **4 次批量查询**（`findCurrentGenerations` /
  `findExactBatch` / `findLatestPerDocument` / `findCurrentActiveBatch`），
  语义与单条读取一致。仍保留的逐条写入只有「无 Run 但有活动 job」时的
  `ensureBridgeForJob`：数量受活动 job 限制，正常稳态为 0。

### 3.5 流式导出（已改造）

- 旧实现 `findAllByCollectionId` 把整个 Collection 读进内存再序列化，内存随
  文档数线性增长，且响应开始前无法释放。
- 新实现 `CollectionExportService` 用 **主键游标** `id > afterId ORDER BY id
  LIMIT 200` 分批读取、边读边用 Jackson 写 JSON；输出结构与
  `CollectionExportResponse` 完全一致，现有客户端无需改动。
- 内存上界 = 单批 200 行 + 当前 JsonGenerator 缓冲，**与 Collection 总行数无关**。
  `documentCount` 在流开始前由 `countByCollectionId` 取得，属于开始时刻的快照：
  并发删除时实际写出可能略少，不会因此报错或无限循环。
- 新增 Flyway `V72__document_export_keyset_index.sql`：
  `(collection_id, id)` 复合索引。没有它时每批会沿主键扫描并丢弃不属于该
  Collection 的行（实测首批 10 ms、丢弃 6,200 行）；有索引后后续批次
  0.37 ms、只读 20 个 buffer。
- 响应头（便于压测与运维断言）：
  `X-Rag-Export-Batch-Size: 200`、`X-Rag-Export-Documents`、
  `X-Rag-Export-Planned-Batches = ceil(documentCount / 200)`。
- 压测结果：3k 文档 1.63 MB / 15 批，p50 82.3 ms；5k 文档 2.73 MB / 25 批，
  p50 58.6 ms。`scripts/run-scale-benchmark.py` 的 `measure_export` 会把响应
  **流式落盘**并断言分块数与 JSON 文档数；断言失败时脚本非零退出。

### 3.6 TTL 清理

- 10,000 行过期会话（100 个 session）：候选 session 查询 4.2 ms，分批 DELETE
**287 ms**（约 29 µs/行）。
- 清理按 session 批次（50）和行批次（500）推进，没有全表一次性删除；未观察到
锁竞争或长事务。

### 3.7 跨浏览器验证

- `playwright.config.ts` 新增 `firefox` / `webkit` project，只跑安全相关套件
  `accessibility.spec.ts`、`keyboard.spec.ts`、`responsive.spec.ts`；其余业务
  spec 仍只跑 Chromium（依赖 Mock 时序，跨引擎收益低）。这是显式 `testMatch`，
  不是静默跳过。
- 三个引擎各 61 条，共 182 条通过。WebKit 唯一差异是「鼠标点击按钮不会让按钮
  获得焦点」的平台默认行为；键盘用例改为先 `focus()` 再按 Enter，测的是真实
  键盘路径，产品焦点策略未变。
- Mock 页面导航从 `networkidle` 改为 `domcontentloaded`：后台轮询时
  `networkidle` 可能永远不到达，三引擎并行会偶发超时；页面就绪由后续 URL /
  元素断言保证。
- WebKit 在单个进程内跑完 61 条安全套件时，约第 50 个 context 之后偶发整页导航
  卡死（`goto` 超时，`browserContext.close` 也超时）；Chromium / Firefox 从未出现，
  同一用例单独跑或 responsive 单独跑均通过。`openProtectedPage` 对 WebKit 增加
  「换一个新 Page 重试一次」，能恢复部分场景；若整个 context 已卡死，则保留原始
  错误交给 Playwright 测试级重试（CI `retries=2`）恢复，不掩盖首因。WebKit
  project 的单次导航预算为 20s、用例超时 90s。
- 结论：这是 Playwright + WebKit 的浏览器进程/导航稳定性边界，不是页面实现缺陷；
  没有登记静默 skip，用例仍然执行，失败会以 flaky 形式显式出现在报告里。
  若某环境无法启动 WebKit，应写显式 `test.skip` 与原因，并保留其他引擎的结果。

### 3.8 WebUI 大数据量表现（本轮实测）

环境：生产构建（`npm run build`）经普通反向代理（**未开 gzip**，按最坏传输计），
Chromium 1440×900；数据为 8000 文档 / 80k 向量（3k 与 5k 两个 Collection）加
2000 条评估历史；搜索与聊天使用 mock embedding / mock chat。

- **文档列表（发现并修复分页失效）**：WebUI 原来发送 `page`/`size`，后端只认
  `offset`/`limit`，于是第 1 页之后永远返回最新的 20 条。修复前 `page=0` 与
  `page=399` 的首行 id 都是 8000；修复后 `page=399` 首行是最早的 167/202。
  每页仍只渲染 20 行（DOM 510 节点、JS heap 9.5 MB），翻页就绪约 83 ms。
- **Collection 列表**：原页面把页码固定为 0，超过 20 个 Collection 后无法访问。
  新增共享 `Pager` 与 `?page=`，请求仍为每页 20 条。
- **评估历史**：2000 行数据只渲染 20 行；原同样固定第 0 页，已补分页（反馈历史一并补齐）。
- **搜索**：UI 不传 `limit`，默认 10 条；5k 文档混合检索 API 374 ms、端到端 815 ms。
  以真实渲染路径注入 200 条结果的压测耗时 53–70 ms、1188 节点、heap 不变，
  说明 UI 有界的 10 条无需虚拟列表。
- **聊天**：一次真实检索返回 4 个引用 chip；端到端 1.5 s 主要来自检索与模型，
  渲染可忽略。
- **导出（已接入 UI，流式落盘）**：Collection 卡片新增“导出”。实现用
  `fetch` + `ReadableStream` 边收边写：支持 File System Access API 的浏览器
  （Chrome / Edge）逐块写入用户选定的文件，内存占用与 Collection 大小无关；
  不支持写盘 API 的浏览器（Firefox / Safari）只能退回 Blob，这是平台边界而
  不是实现选择。进度按**已写出字节**上报——服务端是 chunked 响应、没有
  `Content-Length` 时只显示已下载字节，有总量时才显示比例；取消用
  `AbortController` 中断读取并丢弃已收分块，不产出半截文件。5k Collection：
  2.73 MB、首字节 12–13 ms、总耗时 75–78 ms。Mock E2E 用 8 MB 分块流验证
  进度在流结束前递增，并验证取消后没有下载落盘。

前端性能预算与实测（2026-11 由门禁脚本重新测量）：

| 指标 | 预算 | 实测（2026-11） | 说明 |
|------|------|-----------------|------|
| 首屏初始资源（gzip） | ≤160 KB | 130.4 KB | index 115.6 + jsx 5.1 + Query 4.6 + CSS 4.1 + 其余；余量 **29.6 KB（18.5%）** |
| 首屏初始资源（未压缩） | ≤500 KB | 403.8 KB | 最坏传输（代理未压缩）；余量 96.2 KB |
| 首屏关键资源数 | ≤8 个 | 6 个 | script + 3 modulepreload + 1 stylesheet + runtime |
| 本地 FCP | ≤300 ms | 40 ms | 门禁每次重测 |
| Slow 4G FCP（1.6 Mbps / 150 ms） | ≤3.0 s | 2.44 s | 未开 gzip；余量 **560 ms（18.7%）** |
| 文档列表就绪 | ≤1.5 s | 1.48 s（含解锁与 4 个 API） | 翻页 83 ms |
| 搜索端到端（10 条） | ≤1.0 s | 815 ms | 其中检索 374 ms |
| 200 条合成渲染 | ≤100 ms | 53–70 ms | 仅渲染路径，非 UI 可达上限 |
| 导出首字节 | ≤100 ms | 12–13 ms | 流式响应 |

**首屏资源构成**（由门禁脚本从 `dist/index.html` 读取，2026-11）：

| 资源 | 未压缩 | gzip | 主要内容 |
|------|--------|------|----------|
| `index-*.js` | 358.7 KB | 115.6 KB | React DOM 约 174 KB、i18next 41 KB、react-router 38 KB、中英文语言包 JSON 约 66 KB、react-query core 约 15 KB、Layout/App/auth/设计系统约 25 KB |
| `jsx-runtime-*.js` | 12.8 KB | 5.1 KB | React JSX runtime |
| `QueryClientProvider-*.js` | 13.5 KB | 4.6 KB | TanStack Query 的 Provider 与共享基元 |
| `createLucideIcon-*.js` | 0.9 KB | 0.6 KB | Lucide 图标工厂（首屏只加载用到的图标） |
| `index-*.css` | 17.3 KB | 4.1 KB | 设计 token、全局样式、首屏组件样式 |
| `rolldown-runtime-*.js` | 0.7 KB | 0.4 KB | chunk 加载运行时 |
| **合计** | **403.8 KB** | **130.4 KB** | |

不在首屏的资源（因此新增依赖前应先看它们是否已被延迟）：

- `client-*.js` 64.6 KB（axios + retry + 认证请求栈）：解锁动作或首个页面请求时才加载；
  首屏只保留 `src/api/baseUrl.ts` 的常量。这个拆分本轮把 gzip 从 156.7 KB 降到 130.4 KB。
- `useChartTheme-*.js` 350.5 KB（recharts）：只有指标 / ABTest 页面请求时才加载。
- 其余页面与 `Unlock` 都是路由级 `lazy` chunk；`Unlock` 本轮从首屏静态导入改为懒加载。

这些指标不再是人工记录：`npm run check:performance-budget` 会先跑
[`performance-budget-self-test.mjs`](../spring-ai-rag-webui/scripts/test-support/performance-budget-self-test.mjs)，
再由 [`check-performance-budget.mjs`](../spring-ai-rag-webui/scripts/check-performance-budget.mjs)
重新构建、读取 `dist/index.html` 的首屏资源并计算 gzip/未压缩大小与资源数，然后用
Playwright Chromium 在本地静态服务器上测本地与 Slow 4G FCP（3 次取中位数）。CI 的
无后端 job 直接执行该命令，任一指标超限即失败；预算值维护在
[`performance-budget.json`](../spring-ai-rag-webui/scripts/testdata/performance-budget.json)。
预算未放宽；本轮通过拆分首屏依赖获得余量。新增首屏依赖时应先对照上表判断是否
必须同步进入，否则优先懒加载或拆到独立 chunk。

未覆盖的边界：mock embedding/chat 不代表真实模型时延；反向代理未开 gzip；
只测了 Chromium。

### 3.9 SSE 并发连接资源边界（2026-11 实测）

复现：`python3 scripts/verify-sse-concurrency.py --burst 20`（真实 PostgreSQL + 真实
Spring Boot 进程 + mock provider，默认端口 18088/18089，有界并发，脚本拒绝 `--burst > 64`）。

**上限来源（配置项，不是硬编码）**

| 项 | 默认值 | 作用 |
|----|--------|------|
| `rag.admission.max-requests` | 64 | 单实例同时接纳的请求总数（SSE 连接在整轮生成期间占用一个名额） |
| `rag.admission.max-requests-per-principal` | 16 | 单个 API Key / principal 的同时请求数 |
| `rag.sse.heartbeat-interval-seconds` | 30 | SSE comment 心跳间隔 |
| `rag.timeout.chat-stream-ms` | 180000 | 流式执行预算；本轮同时作为 `SseEmitter` 异步超时上限 |
| `rag.timeout.read-timeout-ms` | 60000 | 非流式 RestClient 读超时；流式走 WebClient，不由它兜底 |

心跳调度器是**共享的 2 个守护线程**（`RagChatController.SSE_HEARTBEAT_SCHEDULER`），
不再每个连接创建一个调度线程。Tomcat 默认 200 线程不是本轮先触达的上限。

**实测结果（单个 API Key，默认每 principal 16）**

- 20 条并发 SSE：**16 条 HTTP 200 建立并持续输出**，**4 条 HTTP 429**，响应
  `code=TOO_MANY_REQUESTS` 且带 `Retry-After: 1`；**不排队**，第 17 条起直接拒绝。
  全局 64 的上限要换多个 API Key 才能触达，本轮只验证了单 key 的 16 条。
- 并发峰值：`rag.admission.active{kind=request}` = **16**；16 条流全部完成/断开后
  回落到 **0**。
- 线程：基线 79 → 峰值 91（+12）；其中心跳线程始终 **2** 条（旧实现为 16 条，
  每个连接一个单线程 `ScheduledExecutorService`）。其余增量来自 Tomcat NIO 线程池
  扩展，结束后保留在池中，属于线程池语义而不是每连接泄漏。
- 内存：进程 RSS 基线 820.6 MB → 峰值 823.6 MB（**+3.0 MB**，16 条连接合计，
  约 <200 KB/连接，含 socket 与 async 请求状态；RSS 含 JVM 堆波动，只作量级参考）。
  结束后回落到 779.0 MB。
- 客户端断开（收到首个 `content` 后立即关闭 socket）：约 1.8 s 内
  `active_request` 回到 0；心跳任务随 `onCompletion` 取消，不留残余任务。
- 上游空闲：mock 发完 SSE 头后 9 s 不吐 token；把 `chat-stream-ms` 设为 6 s 后，
  连接在 **6.02 s** 以 `error` 事件结束，lease 回到 0。默认 180 s 生效。
  心跳只保证客户端连接不被代理提前关闭，不延长上游生命周期。

**本轮修复的两个泄漏点**

1. 每个 SSE 连接新建单线程调度器 → 改为共享 2 线程池：16 条并发从 16 个心跳线程
   降为 2 个。
2. `SseEmitter.onCompletion` 只取消模型订阅、不取消心跳任务 → 补上 `heartbeat.stop()`；
   同时把 emitter 超时从 `0`（永不超时）改为 `rag.timeout.chat-stream-ms`，避免上游
   静默时连接、上游 socket 与 admission 名额被无限占用。

### 3.10 资源饱和与降级语义（2026-11 实测）

复现：`python3 scripts/verify-resource-saturation-e2e.py`（一次性 pgvector 容器 + 真实
Spring Boot 进程 + mock provider；`DB_POOL_SIZE=2`、`DB_CONNECTION_TIMEOUT_MS=1500`、
`DB_STATEMENT_TIMEOUT_MS=3000` 把等待窗口压缩到可测尺度）。

| 场景 | 实测语义 |
|------|----------|
| 连接池耗尽（两个请求占满池） | 第 3 个请求在 **1.51 s** 后返回 **503 `DATABASE_UNAVAILABLE`**，`X-RAG-Failure-Class: DEPENDENCY_UNAVAILABLE`，`Retry-After: 5`；**有界排队，不是 500、不是挂死** |
| 慢查询/锁等待 | 单个请求在 **3.02 s** 被 `statement_timeout` 截断，返回 **504 `DATABASE_TIMEOUT`**，`X-RAG-Failure-Class: TIMEOUT`，`Retry-After: 1` |
| 池释放后恢复 | 无需重启；请求回到 200，健康检查 UP，`hikaricp.connections.active=0`（无泄漏） |
| 数据库临时不可达 | **1.51 s** 返回 503 `DATABASE_UNAVAILABLE`；`/actuator/health` 为 DOWN |
| 数据库恢复 | 无需重启自动恢复：请求 200、健康检查 UP；日志无 `Apparent connection leak detected` |

**健康检查分工**：Helm 的 `liveness` 组只含 `livenessState`，数据库饱和/不可达不会触发
重启；`readiness` 组包含 `db`，池耗尽或数据库不可达时会 DOWN 并从 Service 摘流量。
readiness 探针 `timeoutSeconds` 必须大于 `DB_CONNECTION_TIMEOUT_MS`（默认 10 s），
否则被提前掐断的探针会在 Hikari 里留下等待者，反而加重饱和。

**持续并发 + 长事务 + 客户端中断（2026-11 复测）**

复现同上脚本的场景 3：6 线程连续 8 s 打 `documents`，同时用 `psql` 反复
`BEGIN; LOCK TABLE rag_documents ACCESS EXCLUSIVE; pg_sleep(1.5); ROLLBACK;`，
并做 39 次“发出请求后立刻关闭 socket”的客户端中断。

- 1933 次请求、39 次中断：状态只出现 200 / 429 / 503 / 504，**0 个 500**，无超过 20 s 的挂死；
- 长事务结束后请求恢复 200，`hikaricp.connections.active ≤ 2`（池容量 2），
  `pg_stat_activity` 无 `idle in transaction`；
- 日志无 `Apparent connection leak detected`。

**关键修复**（本轮）：

1. 池耗尽经 `CannotCreateTransactionException` 抛出，原先绕过 `DataAccessException`
   处理器、落到通用 500（`Could not open JPA EntityManager for transaction`）；
   现在与数据库不可达共用同一分类器，统一 503 + `Retry-After`。
2. 增加 `connection-init-sql: SET statement_timeout`，锁等待/慢查询不再永久占用连接。
3. multipart 超限/临时目录不可写不再落 500：见 §3.11 与
   [rest-api-zh-CN.md](rest-api-zh-CN.md#错误响应rfc-7807-problem-detail)。

### 3.11 上传与存储边界（2026-11 实测）

复现：`python3 scripts/verify-upload-boundaries-e2e.py`（真实进程，`max-file-size=2MB`、
`max-request-size=2.5MB`、multipart location 指向可控临时目录）。

| 场景 | 实测语义 |
|------|----------|
| 单文件超过 `max-file-size` | 容器在解析阶段拒绝，**0.01 s** 返回 **413 `PAYLOAD_TOO_LARGE`**，`X-RAG-Failure-Class: CLIENT_REQUEST`；控制器未收到文件 |
| 单请求总量超过 `max-request-size` | **0.00 s** 返回 413 `PAYLOAD_TOO_LARGE` |
| 5 路并发上传 | 全部 200，multipart 临时目录无残留 |
| 客户端中断上传 | 2 s 内无残留临时文件，服务健康保持 UP |
| multipart 临时目录不可写 | **503 `UPLOAD_STORAGE_UNAVAILABLE`**，`RETRY_AFTER: 5`，错误体不泄漏路径/内部类 |
| PDF 超过 `rag.pdf.max-upload-bytes` | 在拷贝/转换前返回 400；4 路并发失败后 `pdf-import-*` 临时目录全部清理 |

`server.tomcat.max-swallow-size` 默认抬到 100MB（与 `max-request-size` 一致），让常见
超限请求仍能收到结构化 413，而不是被直接断连；吞掉的是丢弃字节，不占堆内存，但会
消耗带宽。文本上传的拒绝路径只依赖文件元数据，不再用 `getBytes()` 先读完整文件。

### 3.12 并发基准与退化门禁（2026-11 实测）

复现：

```bash
python3 scripts/verify-performance-baseline.py --run --profile quick \
  --baseline scripts/testdata/performance-baseline.json
```

脚本自建一次性数据库与 mock provider，固定并发 **1 / 8 / 32**，每档每类负载按
`requests-per-worker=2` 采样；基线落在
[performance-baseline.json](../scripts/testdata/performance-baseline.json)。
环境：14 核 macOS、JDK 25、PostgreSQL 16.8 + pgvector、连接池 20、fixture 200 文档 /
800 chunk。fixture 刻意保持小规模以控制 CI 时长，索引退化另由 §3.13 的 9600 向量场景覆盖。

| 并发 | 负载 | 吞吐 (rps) | p50 (ms) | p95 (ms) | p99 (ms) | 错误率 |
|-----|------|-----------|----------|----------|----------|--------|
| 1 | `search_vector` | 12.0 | 73 | 125 | 125 | 0% |
| 1 | `search_hybrid` | 10.4 | 96 | 129 | 129 | 0% |
| 1 | `export_collection` | 100.7 | 10 | 10 | 10 | 0% |
| 1 | `document_create` | 30.0 | 25 | 85 | 85 | 0% |
| 1 | `upload_parse` | 4.4 | 72 | 579 | 579 | 0% |
| 1 | `embedding_pipeline` | 20.2 | 47 | 80 | 80 | 0% |
| 8 | `search_vector` | 58.1 | 89 | 185 | 185 | 0% |
| 8 | `search_hybrid` | 65.0 | 106 | 159 | 159 | 0% |
| 8 | `export_collection` | 296.0 | 20 | 24 | 24 | 0% |
| 8 | `document_create` | 93.5 | 62 | 134 | 134 | 0% |
| 8 | `upload_parse` | 3.2 | 1788 | 4307 | 4307 | 0% |
| 8 | `embedding_pipeline` | 83.3 | 74 | 132 | 132 | 0% |
| 32 | `search_vector` | 51.5 | 458 | 736 | 813 | 0% |
| 32 | `search_hybrid` | 33.9 | 875 | 973 | 994 | 0% |
| 32 | `export_collection` | 239.7 | 76 | 108 | 116 | 0% |
| 32 | `document_create` | 47.2 | 557 | 917 | 970 | 0% |
| 32 | `upload_parse` | 8.1 | 3515 | 7004 | 7637 | 0% |
| 32 | `embedding_pipeline` | 140.9 | 158 | 309 | 429 | 0% |

**门禁**（`verify-performance-baseline.py`，任一不满足即红）：

- 相对基线：p95 ≤ 基线 × **2.5**，p99 ≤ 基线 × **3.0**，吞吐 ≥ 基线 × **0.3**；
- 宽松绝对上限：`search_vector` 3 s、`search_hybrid` 4 s、`export` 3 s、`document_create` 4 s、
  `upload_parse` 20 s、`embedding_pipeline` 6 s；
- 错误率 ≤ **5%**。

**余量**：相对阈值是主要约束；c=32 最紧的是 `upload_parse`（p95 7.0 s / 绝对上限 20 s，
2.9×）和 `search_hybrid`（p95 0.97 s / 绝对上限 4 s，4.1×）。吞吐下限 0.3× 允许共享 CI
机器明显降速；绝对上限是兜底，不用于卡正常抖动。

**本轮修复的两个“只有持续压才暴露”的问题**：

1. **OSIV 让每个请求全程占用数据库连接**。demo 的 `application.yml` 遮蔽了 core
   jar 内的 `spring.jpa.open-in-view: false`，OSIV 实际默认开启。c=32 时
   Hikari `total=20, active=20, idle=0, waiting=32`，p50 13–20 s、大量 503，并出现
   177 条 `Apparent connection leak`（栈停在 `CollectionIdentityResolver.resolveActiveKeyIds`
   与 `DocumentQueryService.findById`）。四个 demo 均已显式关闭 OSIV；修复后 c=32
   0 错误、0 等待。
2. **验收栈未启用 `postgresql` profile，HNSW 索引根本不存在**。`EmbeddingProfileBootstrap`
   带 `@Profile("postgresql")`，而共享 `LocalStack` 与 demo 默认启动都没设置 profile，
   导致引导未执行、三个后台 worker 未注册。9600 向量上 ANN 退化为 Seq Scan（24 ms），
   修复后 HNSW 1.9 ms。共享 `scripts/lib/rag_local_stack.py` 与四个 demo
   `application.yml` 现已默认 `postgresql`。

### 3.13 慢 SQL 与执行计划（2026-11 实测）

复现：

```bash
python3 scripts/verify-query-plans.py --docker --documents 1200 --chunks-per-doc 8
```

脚本自建预加载 `pg_stat_statements` 的临时 pgvector 容器（宿主机日常库通常未预加载），
写入 9600 条向量后清空统计、跑 40 轮向量/混合检索与 10 次导出，再取 top SQL 并 EXPLAIN。

| top SQL | calls | total (ms) | mean (ms) |
|---------|-------|-----------|-----------|
| 向量候选 CTE（`WITH candidates AS MATERIALIZED`） | 80 | 476.4 | 5.96 |
| 证据回填（`local_chunk_id`/`embedding_id` 关联） | 40 | 1740.8 | 43.52 |
| 新鲜度/状态统计（`COUNT(*) FILTER`） | 40 | 169.5 | 4.24 |
| 文档列表批量查询 | 60 | 22.9 | 0.38 |
| retrieval_logs insert/update、collection/profile 查询 | 80–90 | < 7 | < 0.1 |

关键计划（`EXPLAIN (ANALYZE, BUFFERS)`）：

| 路径 | 计划 | 耗时 |
|------|------|------|
| 向量 ANN（含 collection scope） | `Nested Loop` + `Index Scan(idx_rag_emb_p_1_1024_hnsw)` + `Memoize` + `Index Scan(rag_documents_pkey)`，**无 Seq Scan** | **1.94 ms** |
| 向量 ANN（无 scope，诊断） | `Index Scan(idx_rag_emb_p_1_1024_hnsw)` | 0.72 ms |
| 导出 keyset | `Index Scan(rag_documents_pkey)` | 0.14 ms |
| 标题关键词 | `Bitmap Index Scan(idx_rag_documents_title_trgm)` | 0.07 ms |

**结论**：关键检索路径仍走 HNSW，没有退化为 Seq Scan；导出与关键词路径都命中索引。
验证脚本会断言 `vector_ann.hnsw_used=true` 且 `rag_embeddings` 上无 Seq Scan。

**注意（索引可用性约束）**：HNSW 是 **部分索引**，谓词是
`embedding_profile_id = <id> AND embedding_<dim> IS NOT NULL`。SQL 里的 profile id
必须是**内联字面量**（生产代码即如此）；如果改成绑定参数，规划器无法证明
`embedding_profile_id = $1` 蕴含部分索引谓词，会静默退回 Seq Scan。改动 ANN SQL
时必须保持字面量，并跑本脚本复验。

### 3.14 长时间 soak：资源趋势与 GC（12 分钟，2026-11 实测）

复现：

```bash
python3 scripts/run-bounded-soak.py --minutes 12 --concurrency 4 \
  --pid <app-pid> --dsn <postgres-dsn> --sample-seconds 15
```

`--pid` / `--dsn` 可选；缺失时只跑负载、跳过资源断言。nightly 用 `--minutes 10
--sample-seconds 15` 覆盖短版。负载是 3 路连续混合检索 + 1 路 chat（每轮间隔 2 s），
mock provider，真实 PostgreSQL，共 48 个采样点。

判定规则：取**首尾各 1/4 样本的中位数**比较，`后 ≤ 前 × 相对上限 + 绝对上限`。
允许 GC 造成的锯齿，不允许单调爬升；任一指标越界即 `degraded=true`、退出码 1。

| 指标 | 前 1/4 中位数 | 后 1/4 中位数 | 比值 | 上限 | 结论 |
|------|--------------|--------------|------|------|------|
| Java 线程数 | 84 | 84 | 1.00 | 1.5× + 8 | 通过 |
| 堆使用 | 255 MB | 335 MB | 1.31 | 1.5× + 64 MB | 通过（锯齿，0 次 Full GC） |
| 进程 RSS | 803 MB | 671 MB | 0.84 | 2.0× + 128 MB | 通过 |
| FD 数 | 55 | 56 | 1.02 | 1.5× + 20 | 通过 |
| DB 连接数 | 7 | 8 | 1.14 | 1.5× + 4 | 通过（池上限 20） |

负载面：5858 次检索（0 错误，p50 350 ms、p95 523 ms、p99 645 ms，后半 p95 漂移 1.04）
与 213 次 chat（0 错误，p50 1367 ms、p95 1598 ms，漂移 0.88）。GC：young 194 次、
old 0 次，总停顿 2.24 s（墙钟的 0.31%）。`idle in transaction` 仅 1 个采样点出现 2 条，
其余 47 个点均为 0，属于请求进行中的瞬时状态而非泄漏。

**soak 抓到的真实泄漏（已修）**：`ai-advisor-*` 线程随请求数线性增长，12 分钟从 153
涨到 725（4.3×）。根因是 Spring AI 1.1.8 的 `RetrievalAugmentationAdvisor` 在未注入
`TaskExecutor` 时，会为每个 advisor 实例新建 core=4 / max=16 的
`ThreadPoolTaskExecutor` 且从不 shutdown；本仓每个 chat attempt 都会重建 advisor，
于是每请求泄漏 3–4 条线程。修复：`PerformanceConfig` 新增共享有界
`ragAdvisorExecutor`（core 8 / max 16 / 队列 256 / `CallerRunsPolicy`，
并传递 MDC 与 request context），`ModeAwareChatClientFactory` 通过
`setAdvisorTaskExecutor` 注入并传给 advisor builder。修复后同样负载下线程数稳定在
84–86、`ai-advisor-*` 为 0；`ModeAwareChatClientFactoryTest` 增加回归用例：
20 次 attempt 前后 `ai-advisor-*` 线程数不变，且检索任务确实落在注入的执行器上。

## 4. 容量边界

| 维度 | 现状 | 边界与建议 |
|------|------|-----------|
| 已验证规模 | 单 Collection 5k 文档 / 50k chunk；库内约 11.2k 文档 / 112k 向量 | 脚本硬上限 5k 文档；更大规模未实测，结论不外推 |
| 写入吞吐 | 约 950 向量/秒（HNSW 在位） | 100k chunk ≈ 105 s 纯写入；批量导入需预留时间窗 |
| 索引构建 | 31k 向量 4.7 s | 100k 向量预计约 16 s；启动等待上限 60 s，接近时需调大或异步预热 |
| 检索 | p50 约 50–90 ms | 主要成本是 embedding 调用；SQL 部分已近常数。候选池上限 1000，过期向量过多时召回可能不足 |
| 列表 | 页面固定 ≤20 行 | 已消除随数据量增长的 N+1；处理摘要每页 4 次批量查询 |
| WebUI 首屏 | 初始资源 gzip 130.4 KB、未压缩 403.8 KB；Slow 4G FCP 2.44 s | 预算与门禁见 §3.8；gzip 余量 29.6 KB、Slow 4G 余量 18.7%；axios 与 recharts 已移出首屏 |
| SSE 并发 | 单 key 16 条、全局 64 条；16 条峰值线程 +45（Tomcat/boundedElastic 扩展），两轮后 `SseEmitter` 回落到 0、心跳线程固定 2 条 | 上限见 §3.9；第 17 条（单 key）起 HTTP 429 `TOO_MANY_REQUESTS`，不排队；emitter 超时 = `chat-stream-ms`（默认 180 s） |
| 并发延迟基线 | c=32：检索 p95 0.74–0.97 s、导出 108 ms、文档创建 0.92 s、上传解析 7.0 s（200 文档 fixture） | 门禁 p95 ≤ 基线 2.5× 且不超过绝对上限；上传解析是当前最紧的边界，见 §3.12 |
| 检索执行计划 | 9.6k 向量 ANN 走 HNSW 1.9 ms，无 Seq Scan；导出 keyset 0.14 ms | 改 ANN SQL 时 profile id 必须保持字面量，否则部分索引谓词不匹配会静默退化，见 §3.13 |
| Soak | 12 分钟混合负载：线程 84→84、堆 255→335 MB（锯齿）、FD 55→56、DB 连接 7→8；GC 194 次 young / 0 次 old，停顿占 0.31% | 门禁按首尾 1/4 中位数比较（1.5× + 绝对上限），单调爬升即失败，见 §3.14 |
| Advisor 线程池 | 共享 `ragAdvisorExecutor`：core 8 / max 16 / 队列 256，`CallerRunsPolicy` | 不要移除 advisor builder 的 `taskExecutor(...)`，否则每次 chat attempt 会重建 4/16 线程池，见 §3.14 |
| WebUI 列表 | 每页 20 行，DOM 510 节点、heap 9.5 MB | 文档/Collection/评估历史均已可分页；200 条合成渲染 53–70 ms |
| WebUI 导出 | Collection 卡片内置；fetch + ReadableStream 边收边写，支持进度与取消；5k 文档 2.73 MB、首字节 12–13 ms | 无写盘 API 的浏览器退回 Blob（平台边界）；50k 文档约 27 MB |
| 导出 | 流式，单批 200 行，响应头给出批次数 | 内存不随行数线性增长；受网络与客户端消费速度限制。超大 Collection 建议在低峰导出 |
| 文档列表关键词 | `LOWER(title) LIKE '%kw%'`；pg_trgm 可用时走 `idx_rag_documents_title_trgm` | 5k 文档：有索引 SQL 0.103 ms、HTTP p50 4.2 ms；无索引 SQL 2.134 ms、HTTP p50 8.4 ms。**关键词 ≥3 字符**才能被 trigram 提取；1–2 字符仍全表扫描。`pg_trgm` 缺失时迁移只记 NOTICE 并降级为顺序扫描，不阻塞启动 |
| 全文检索（检索链路） | pg_jieba / English FTS / pg_trgm 按能力自动选择 | 与上面的文档列表关键词是不同路径；5k 文档全文加权 p50 约 158 ms，主要成本是 embedding 调用 |
| 连接池 | core 默认 max=20 / min=5；四个 demo 默认 max=10 | 池耗尽时请求最多排队 `DB_CONNECTION_TIMEOUT_MS`（默认 10 s），随后 503 `DATABASE_UNAVAILABLE` + `Retry-After`；慢查询被 `DB_STATEMENT_TIMEOUT_MS`（默认 30 s）截断为 504。详见 §3.10 |
| TTL 清理 | 1 万行 287 ms，分批 | 行数线性增长但批次有界；百万级建议缩短调度间隔而非单次放大批次 |

## 5. 已知限制

1. 假 embedding 只验证「规模与退化」，不验证真实模型的时延、限流与维度差异。
2. 单机 PostgreSQL + HNSW 的结论；多副本、读写分离、外部向量库未覆盖。
3. p95 存在 GC/冷缓存噪声（文档首页 p95 曾出现 138 ms 单点），容量判断以 p50 与
   SQL 执行计划为准。
4. 向量检索的候选池是 10x overfetch（最小 100）；若某个作用域内过期向量比例很高，
   外层新鲜度过滤后可能少于请求条数，需要重新嵌入修复。
