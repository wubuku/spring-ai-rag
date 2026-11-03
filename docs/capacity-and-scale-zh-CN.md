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
- **导出**：WebUI 当前**没有导出入口**（只有 API client 方法）。用浏览器
  `fetch` 流式读取 5k Collection：2.73 MB、分块 186–714 个、首字节 12–13 ms、
  总耗时 75–78 ms。边界：若把 `collectionsApi.export`（axios，默认整体 JSON）
  直接接到按钮上，2.7 MB 会先完整缓冲；50k 文档约 27 MB，存在内存压力和
  “点了没反应”的风险。接入时必须用 `fetch` + `ReadableStream` 边读边落盘并显示
  进度，不能复用 axios 的整体响应方法。

前端性能预算与实测：

| 指标 | 预算 | 实测（2026-11） | 说明 |
|------|------|-----------------|------|
| 首屏初始资源（gzip） | ≤160 KB | 148.8 KB | index 114.5 + ui 23.9 + 其余 |
| 首屏初始资源（未压缩） | ≤500 KB | 471 KB | 最坏传输（代理未压缩） |
| 本地 FCP | ≤300 ms | 80 ms | — |
| Slow 4G FCP（1.6 Mbps / 150 ms） | ≤3.0 s | 2.72 s | 开 gzip 推算约 1.2–1.5 s |
| 文档列表就绪 | ≤1.5 s | 1.48 s（含解锁与 4 个 API） | 翻页 83 ms |
| 搜索端到端（10 条） | ≤1.0 s | 815 ms | 其中检索 374 ms |
| 200 条合成渲染 | ≤100 ms | 53–70 ms | 仅渲染路径，非 UI 可达上限 |
| 导出首字节 | ≤100 ms | 12–13 ms | 流式响应 |

未覆盖的边界：mock embedding/chat 不代表真实模型时延；反向代理未开 gzip；
只测了 Chromium。

## 4. 容量边界

| 维度 | 现状 | 边界与建议 |
|------|------|-----------|
| 已验证规模 | 单 Collection 5k 文档 / 50k chunk；库内约 11.2k 文档 / 112k 向量 | 脚本硬上限 5k 文档；更大规模未实测，结论不外推 |
| 写入吞吐 | 约 950 向量/秒（HNSW 在位） | 100k chunk ≈ 105 s 纯写入；批量导入需预留时间窗 |
| 索引构建 | 31k 向量 4.7 s | 100k 向量预计约 16 s；启动等待上限 60 s，接近时需调大或异步预热 |
| 检索 | p50 约 50–90 ms | 主要成本是 embedding 调用；SQL 部分已近常数。候选池上限 1000，过期向量过多时召回可能不足 |
| 列表 | 页面固定 ≤20 行 | 已消除随数据量增长的 N+1；处理摘要每页 4 次批量查询 |
| WebUI 首屏 | 初始资源 gzip 149 KB、未压缩 471 KB；Slow 4G FCP 2.72 s | 预算见 §3.8；代理开启 gzip 可显著缩短 |
| WebUI 列表 | 每页 20 行，DOM 510 节点、heap 9.5 MB | 文档/Collection/评估历史均已可分页；200 条合成渲染 53–70 ms |
| WebUI 导出 | 无 UI 入口；浏览器流式读取 2.73 MB，首字节 12–13 ms | 接入时必须用 fetch 流式读取，axios 会整体缓冲；50k 文档约 27 MB |
| 导出 | 流式，单批 200 行，响应头给出批次数 | 内存不随行数线性增长；受网络与客户端消费速度限制。超大 Collection 建议在低峰导出 |
| 文档列表关键词 | `LOWER(title) LIKE '%kw%'`；pg_trgm 可用时走 `idx_rag_documents_title_trgm` | 5k 文档：有索引 SQL 0.103 ms、HTTP p50 4.2 ms；无索引 SQL 2.134 ms、HTTP p50 8.4 ms。**关键词 ≥3 字符**才能被 trigram 提取；1–2 字符仍全表扫描。`pg_trgm` 缺失时迁移只记 NOTICE 并降级为顺序扫描，不阻塞启动 |
| 全文检索（检索链路） | pg_jieba / English FTS / pg_trgm 按能力自动选择 | 与上面的文档列表关键词是不同路径；5k 文档全文加权 p50 约 158 ms，主要成本是 embedding 调用 |
| 连接池 | 默认 max=10 | 高并发检索/导入会先耗尽连接；需按并发调大并压测 |
| TTL 清理 | 1 万行 287 ms，分批 | 行数线性增长但批次有界；百万级建议缩短调度间隔而非单次放大批次 |

## 5. 已知限制

1. 假 embedding 只验证「规模与退化」，不验证真实模型的时延、限流与维度差异。
2. 单机 PostgreSQL + HNSW 的结论；多副本、读写分离、外部向量库未覆盖。
3. p95 存在 GC/冷缓存噪声（文档首页 p95 曾出现 138 ms 单点），容量判断以 p50 与
   SQL 执行计划为准。
4. 向量检索的候选池是 10x overfetch（最小 100）；若某个作用域内过期向量比例很高，
   外层新鲜度过滤后可能少于请求条数，需要重新嵌入修复。
