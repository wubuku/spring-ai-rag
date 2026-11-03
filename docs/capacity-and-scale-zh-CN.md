# 容量与规模退化说明

> 本轮验证「数据量放大后的行为」。目标不是绝对数字，而是**退化曲线**：数据量 ×2 时
> 延迟是否线性增长、是否存在 N+1 或全表扫描。所有结果由
> `scripts/run-scale-benchmark.py` 生成，可复现。

## 1. 验证口径

- 语料：`scale-bench-1000`（1,000 文档 / 10,000 chunk）与 `scale-bench-2000`
  （2,000 文档 / 20,000 chunk），加既有基础语料后共 **3,200 文档 / 31,000 向量**。
- 向量：确定性假 embedding（1024 维，32 个归一化向量循环），不调用真实模型。
- 环境：Apple M4 Pro / 64 GB，PostgreSQL 16.8 + pgvector 0.8.2。
- 检索请求走真实 HTTP 入口；每次请求都包含一次 mock embedding 调用（约 30–50 ms，
  与数据量无关），因此绝对值主要由该固定开销构成，比较时看**比值**。
- `requests=50`、`warmup=8`；p50 比 p95 更稳定，结论以 p50 为主、p95 作参考。

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
  --docs 1000 --scale-docs 2000 --chunks-per-doc 10 \
  --requests 50 --warmup 8 --output /tmp/scale.json

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

| 接口（p50 / p95，ms） | 1k 文档 | 2k 文档 | p50 比值 | 修复前 p50 |
|----------------------|---------|---------|----------|------------|
| 纯向量检索 | 52.5 / 118.9 | 59.2 / 90.1 | 1.13x | 255 → 451（1.77x） |
| 混合检索 | 57.0 / 99.4 | 82.8 / 109.4 | 1.45x | 251 → 439（1.75x） |
| 全文加权检索 | 58.7 / 90.0 | 80.0 / 109.1 | 1.36x | 244 → 446（1.83x） |
| 文档列表（首页/深页） | 23.2 / 24.9 | 29.4 / 31.4 | 1.27x | 近似持平 |
| Collection 列表 | 3.2 / 6.0 | 6.0 / 10.5 | 1.88x（绝对值小） | 近似持平 |
| 导出（单 Collection） | 10.4 | 11.4 | 1.10x | 近似持平 |
| 评估 | 10.3 | 5.2 | 0.50x | 近似持平 |

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
- 仍存在的有界查询：处理运行摘要（processing summary）仍按文档逐条查，但页面大小
固定（≤20），不会随语料增长；如需进一步压缩可后续批量。

### 3.5 TTL 清理

- 10,000 行过期会话（100 个 session）：候选 session 查询 4.2 ms，分批 DELETE
**287 ms**（约 29 µs/行）。
- 清理按 session 批次（50）和行批次（500）推进，没有全表一次性删除；未观察到
锁竞争或长事务。

## 4. 容量边界

| 维度 | 现状 | 边界与建议 |
|------|------|-----------|
| 已验证规模 | 3.2k 文档 / 31k 向量 | 脚本硬上限 5k 文档；更大规模未实测，结论不外推 |
| 写入吞吐 | 约 950 向量/秒（HNSW 在位） | 100k chunk ≈ 105 s 纯写入；批量导入需预留时间窗 |
| 索引构建 | 31k 向量 4.7 s | 100k 向量预计约 16 s；启动等待上限 60 s，接近时需调大或异步预热 |
| 检索 | p50 约 50–90 ms | 主要成本是 embedding 调用；SQL 部分已近常数。候选池上限 1000，过期向量过多时召回可能不足 |
| 列表 | 页面固定 ≤20 行 | 已消除随数据量增长的 N+1；处理摘要仍为有界逐条查询 |
| 导出 | `GET /rag/collections/by-key/export` 一次加载整个 Collection | **O(n) 内存/响应**，没有分页；大 Collection（数千文档或大正文）不适合在线导出 |
| 连接池 | 默认 max=10 | 高并发检索/导入会先耗尽连接；需按并发调大并压测 |
| TTL 清理 | 1 万行 287 ms，分批 | 行数线性增长但批次有界；百万级建议缩短调度间隔而非单次放大批次 |

## 5. 已知限制

1. 假 embedding 只验证「规模与退化」，不验证真实模型的时延、限流与维度差异。
2. 单机 PostgreSQL + HNSW 的结论；多副本、读写分离、外部向量库未覆盖。
3. p95 存在 GC/冷缓存噪声（文档首页 p95 曾出现 138 ms 单点），容量判断以 p50 与
   SQL 执行计划为准。
4. 向量检索的候选池是 10x overfetch（最小 100）；若某个作用域内过期向量比例很高，
   外层新鲜度过滤后可能少于请求条数，需要重新嵌入修复。
