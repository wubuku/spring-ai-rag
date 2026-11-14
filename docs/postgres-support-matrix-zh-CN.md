# PostgreSQL / pgvector 支持矩阵

本文回答三个问题：**哪些版本受支持、每个版本实际测过什么、pgvector 最低要什么版本**。
矩阵结论来自真实 `pgvector/pgvector` 官方镜像容器，不使用 mock 数据库。

## 结论

| PostgreSQL | 官方镜像 | pgvector | 全量迁移 V1..最新版本 | 核心 gated 套件 | 状态 |
|---|---|---|---|---|---|
| 15 | `pgvector/pgvector:pg15`（15.19） | 0.8.7 | 通过 | 62 tests 通过 | 支持 |
| 16 | `pgvector/pgvector:pg16`（16.15） | 0.8.7 | 通过 | 完整 gated 套件 nightly + 62 tests 通过 | 支持（默认） |
| 17 | `pgvector/pgvector:pg17`（17.11） | 0.8.7 | 通过 | 62 tests 通过 | 支持 |
| 18 | `pgvector/pgvector:pg18`（18.6） | 0.8.7 | 通过 | 62 tests 通过 | 支持 |

- **默认与 CI 基线**是 PostgreSQL 16；镜像固定为 `pgvector/pgvector:pg16`。
- 15/17/18 作为兼容性矩阵在 nightly 中运行（见下文 CI 成本）。
- PostgreSQL 14 及更早未验证，不在支持范围内；pgvector 0.5.0 以下不支持。

### pgvector 最低版本

| 能力 | 最低 pgvector | 说明 |
|---|---|---|
| `vector(1024)` 存储、余弦距离 `<=>` | 0.5.0 | 项目固定 1024 维，列类型 `VECTOR(1024)` |
| `USING hnsw (embedding vector_cosine_ops)` | **0.5.0** | HNSW 自 0.5.0 起提供；项目当前所有 HNSW 索引都依赖它 |
| 本项目实测版本 | **0.8.7** | 四个 PostgreSQL 官方镜像当前携带的版本 |

生产建议直接使用 0.8.x（官方镜像默认携带 0.8.7）。0.5.0–0.7.x 理论上可用，但本项目
未在矩阵中逐版本验证，不应作为承诺；0.8.7 以下的 0.x 版本如要使用，请先跑一次
`scripts/verify-postgres-version-matrix.sh`。

## 每个版本测了什么

每个版本都会执行：

1. **全量迁移**：按 Flyway 顺序执行 `spring-ai-rag-core/src/main/resources/db/migration/`
   下 V1..最新版本 全部脚本，确认没有版本相关语法/行为差异；
2. **扩展版本**：`SELECT extversion FROM pg_extension WHERE extname='vector'`，并断言
   ≥ 0.5.0；
3. **HNSW**：创建 `USING hnsw (embedding vector_cosine_ops)` 索引，校验索引定义；
4. **检索正确性**：1024 维余弦最近邻查询返回同向向量而不是反向向量；
5. **`CREATE INDEX CONCURRENTLY`**：验证 `EmbeddingProfileIndexManager` 使用的并发建
   索引路径可执行且索引可见；
6. **`SET NOT NULL`**：确认 V28/V41/V48 收紧的关键列
   （`rag_collection.collection_key`、`rag_embedding_jobs.document_kind` /
   `chunker_version`、`rag_api_key.principal_id` / `credential_version`）
   在迁移后 `attnotnull = true`；
7. **RLS 事实**：当前 schema **没有启用 RLS**，也没有 policy；租户隔离在应用层完成。
   矩阵只确认这一点没有随 PostgreSQL 版本漂移，而不是把 RLS 当作已支持能力。

在此基础上，**核心 gated 套件**覆盖检索与数据生命周期的主要路径：

- `RetrievalCorrectnessPostgresIntegrationTest` — 检索正确性（向量/关键词/混合）
- `HybridRetrieverRrfPostgresIntegrationTest` — RRF 融合
- `MetadataFiltersPostgresIntegrationTest` — 元数据过滤
- `MultiCollectionRetrievalPostgresIntegrationTest` — 多 Collection 检索
- `RetrievalDiagnosticsPostgresIntegrationTest` — 检索诊断
- `DocumentLifecyclePostgresIntegrationTest` — 文档生命周期
- `CollectionPurgePostgresIntegrationTest` — 永久清理/退役
- `EmbeddingProfileMigrationLockPostgresIntegrationTest` — 嵌入 Profile 与并发建索引
- `JsonbStructuredRecordsPostgresIntegrationTest` — JSONB 结构化记录

pg16 另有 nightly 完整 gated 套件（全部 33 个类），矩阵任务只跑上述核心集，避免
重复成本。

## 版本差异与边界

- **没有发现版本相关缺陷**：15/16/17/18 上述路径行为一致，`vector` 扩展均为 0.8.7。
- `SET NOT NULL` 在四个版本都会做一次全表校验并在持有 `ACCESS EXCLUSIVE` 锁期间完成，
  语义一致；大表升级窗口仍需按迁移文档评估。
- `CREATE INDEX CONCURRENTLY` 在四个版本都不能放在事务块内，`EmbeddingProfileIndexManager`
  已按非事务执行；`IF NOT EXISTS` 行为一致。
- `hnsw.ef_search` 等 GUC 由扩展注册；应用未在运行期修改这些参数，所以不构成版本差异。
- PostgreSQL 18 的镜像与核心路径已验证，但尚未纳入“完整 gated 套件 nightly”；若要
  把 18 升为默认，需要先补跑完整套件。
- RLS：仓库当前没有 RLS/policy。若未来引入 RLS，本矩阵的“RLS 未被隐式启用”检查需要
  改成逐表策略断言。

## 如何复跑

```bash
# 真实容器探针：全量迁移 + HNSW / CONCURRENTLY / SET NOT NULL / RLS 事实
./scripts/verify-postgres-version-matrix.sh --versions 15,16,17,18

# 核心 gated 套件（任选版本）
TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg15 \
  ./scripts/verify-gated-it.sh \
  RetrievalCorrectnessPostgresIntegrationTest,HybridRetrieverRrfPostgresIntegrationTest,\
MetadataFiltersPostgresIntegrationTest,MultiCollectionRetrievalPostgresIntegrationTest,\
RetrievalDiagnosticsPostgresIntegrationTest,DocumentLifecyclePostgresIntegrationTest,\
CollectionPurgePostgresIntegrationTest,EmbeddingProfileMigrationLockPostgresIntegrationTest,\
JsonbStructuredRecordsPostgresIntegrationTest
```

境内网络拉取失败时，先通过镜像站拉取并 `docker tag` 为
`pgvector/pgvector:pg<N>`；脚本与 CI 都不硬编码镜像站地址，见
[境内网络指南](china-network-guide-zh-CN.md)。

## CI 成本与 nightly 决策

- **pg16**：nightly 的 `gated-postgres` 跑完整套件，作为发布基线。
- **15/17/18**：新增 `postgres-version-matrix` 矩阵任务，每个版本跑全量迁移探针 +
  9 个核心 gated 类（本地实测约 8–10 分钟/版本）。三个 runner 并行，总墙钟时间与单个
  版本接近，成本可接受，因此放进 nightly，而不是每次 PR 都跑。
- 若矩阵任务持续不稳定，降级顺序是：先保留探针（迁移/HNSW/DDL），再把核心套件缩到
  检索正确性 + 生命周期两类；不要直接删除矩阵，否则版本回归会重新变成盲区。
