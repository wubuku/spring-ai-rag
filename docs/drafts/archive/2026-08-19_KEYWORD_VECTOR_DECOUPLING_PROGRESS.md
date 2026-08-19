# 下一批高价值功能实施进度

> 对应规划：[2026-08-19_KEYWORD_VECTOR_DECOUPLING_PLAN.md](2026-08-19_KEYWORD_VECTOR_DECOUPLING_PLAN.md)
>
> 本账本记录本批从规划检查到实施、验证、归档的关键状态。发生中断时，先读本文件，
> 再按规划文档和代码/测试真相继续。实现、硬门槛、三轮限定审查和长青文档同步均已完成，
> 本文随规划归档。

## 1. 当前目标

本批聚焦“本地关键词索引与远程向量派生解耦”：

- 新正文提交后，旧 local/vector 派生立即退出；
- 新 `rag_document_chunks` 可先 READY；
- provider 失败时公开 `KEYWORD_ONLY`；
- retry/provider 成功后升级 `READY`；
- CRUD、外部文档、JSON record、PDF-to-RAG 共用同一联动路径；
- 不使用任何显式悲观锁。

## 2. 已完成的规划调研

- [x] 核对工作区：`main` 与 `origin/main` 一致、初始工作区干净。
- [x] 阅读 `.agents/skills/project-docs/SKILL.md`。
- [x] 核对当前 V1–V43 migration、V40/V41 lifecycle、V42 sync run。
- [x] 阅读 `DocumentMutationService`、`ExternalDocumentService`、`JsonRecordService`、
  `DocumentEmbedService`、`EmbeddingDispatchService`、`EmbeddingPersistenceService`、
  `EmbeddingJobRepository`、`DocumentLifecycleService`。
- [x] 阅读 `HybridRetrieverService`、三个全文 provider、
  `EmbeddingProfileSqlScope`、`RetrievalScopeSql` 和 `RetrievalEmptyReasonProbe`。
- [x] 阅读现有 PostgreSQL lifecycle/job/fulltext 测试、验证脚本和 Documents WebUI
  Mock Playwright。
- [x] 明确本批不包含 API Key 配额、XML/Office、Collection 原子迁移、EACH_COLLECTION
  和 OpenAI 兼容协议扩展。
- [x] 将已完成的上一批草稿移入
  `docs/drafts/archive/2026-08-19_NEXT_HIGH_VALUE_FEATURES_{PLAN,PROGRESS}.md`。
- [x] 新建当前 active plan 与本进度账本。
- [x] 修正规划中的 Profile/local state 混用：local chunk/state 独立于 embedding
  Profile，全文 freshness 只依赖独立 local state。
- [x] 明确 `excludeIds` 保持 `rag_embeddings.id` 契约，通过同 document/chunk 的活动
  Profile 向量行可选映射，禁止与 local chunk ID 混用。
- [x] 修正中文索引规划：pg_jieba 使用与查询一致的 `jiebacfg` 表达式索引，不复制
  V15 `simple` generated column 的历史错配。
- [x] 确认现有 chunker 已将 `minChunkSize` 作为质量目标、保留非空短文本；规划要求
  local/vector 共享该现有分块结果，不新增第二套 fallback。

## 3. 规划检查计数器

计数器：`3 / 3`，规划检查已通过，允许实施。

规则：规划文档发现影响正确性、兼容性、数据一致性、安全/成本或实施可行性的问题时，
立即修复并归零；连续三轮未修改才进入实施。无问题轮次只在本次会话汇报，不追加到
本文，避免破坏“连续三轮无修改”条件。

| 轮次 | 时间 | 范围 | 结果 | 是否修改规划 |
|---|---|---|---|---|
| 1 | 2026-08-19 | 代码事实、V43 数据模型、迁移回填、全文/vector SQL、锁边界 | 通过；未发现问题 | 否 |
| 2 | 2026-08-19 | CRUD/JSON/external/PDF、SYNC/ASYNC/SKIP、并发 generation、lifecycle/client 语义 | 通过；未发现问题 | 否 |
| 3 | 2026-08-19 | 验收矩阵、脚本/命令、文档路径、WebUI 无截图门禁、实施范围和恢复性 | 通过；未发现问题 | 否 |

## 4. 实施阶段记录

| 阶段 | 状态 | 证据 |
|---|---|---|
| 规划连续三轮检查 | 已完成 | 本文 §3，计数 3/3 |
| V43 schema 与本地 chunk persistence | 已完成 | V43 migration、local chunks/state、回填与 generation/CAS 已交付 |
| CRUD/JSON/external/PDF 联动 | 已完成 | 普通 CRUD、外部文本、JSON retrievalText、PDF-to-RAG 共用联动路径 |
| 全文 provider 与 lifecycle | 已完成 | 三个全文 provider 读取 local chunks；公开 `KEYWORD_ONLY` |
| 后端测试和一键验收 | 已完成 | 专项脚本 8/8，PostgreSQL 相关测试 45/45，编译通过 |
| WebUI 测试/构建/Playwright | 已完成 | Vitest 214/214，Mock Playwright 12/12，tsc/build/alignment 通过 |
| 实现代码固定范围三轮检查 | 已完成 | 数据/SQL、生命周期/并发、API/WebUI/文档三轮连续通过且未修改业务代码 |
| 长青文档同步与归档 | 已完成 | V43、`KEYWORD_ONLY`、client 指引和验证命令已同步到双语长青文档 |
| commit/merge/push/status | 待执行 | 本账本归档后执行一次 Git 收口 |

### 4.1 当前验证发现与修复

- PostgreSQL 真实集成测试首次收敛时发现：
  `PgTrgmFulltextProvider` 使用一次 JDBC 连接设置
  `pg_trgm.similarity_threshold`，再用另一条连接执行查询；非池化
  `PGSimpleDataSource` 下该设置不会传递到查询连接，短关键词可能被 PostgreSQL
  默认阈值过滤。
- 已修复生产实现：将 `SIMILARITY_THRESHOLD` 直接写入
  `similarity(e.chunk_text, ?) >= ?` 查询谓词，不依赖连接会话状态，也不修改测试来
  掩盖问题。
- `RetrievalEmptyReasonProbe` 已补充真实 local chunk 行数校验，避免仅凭
  `local_index_state.chunk_count` 把损坏状态计为 fresh。
- PostgreSQL 验收已重新通过：完整类 11/11；探针专测验证“READY 状态但无 chunk =
  0，补齐真实 chunk 后 = 1”。
- `DocumentLifecycleService` 已补充当前 local generation 的实际 chunk 行数校验；
  仅有 `local_index_state.chunk_count` 而缺少对应 `rag_document_chunks` 时，不再公开
  `localIndexStatus=READY` 或 `searchability=KEYWORD_ONLY`。新增集成断言覆盖该损坏状态。
- `KeywordIndexPersistenceService.isCurrent()` 已补充 chunk 行的
  `content_hash/chunker_version` 校验；行数未变但派生元数据损坏时会重建 local
  generation。集成测试已覆盖该边界，修复后的硬门槛正在重新执行。
- 修复后的专项硬门槛 `full-gate-6` 已通过 8/8；Flyway V1–V43、lifecycle 12、
  pg_trgm 13、pg_jieba 9、English FTS 11（共 45/45）、Maven clean
  compile/test-compile、WebUI 214/214、Mock Playwright 12/12、对齐、文档、
  无截图、无悲观锁和 whitespace 门禁均通过。
- 硬门槛后完成三轮限定范围只读审查：数据模型/SQL、本地索引/生命周期与并发、
  API/WebUI/测试/文档依次通过，连续三轮未修改业务代码。
- WebUI `KEYWORD_ONLY` 重试已修正为按 `lifecycle.retryable` 发送
  `force=true`；Mock Playwright 已断言
  `POST /api/v1/rag/documents/4/embed?force=true`。
- 专项脚本已收敛为可重复的一键门禁：preview 启动失败 fail-fast、支持
  `--webui-only`、优先复用本地一次性 PostgreSQL、Docker fallback 优先尝试境内镜像，
  Maven 使用 `-am` 并禁止“无测试也算通过”。脚本只保留 summary 和日志到
  `.verification/keyword-vector-decoupling/<run-id>/`，运行产物不提交。

## 7. 最终硬门槛证据

命令：

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-6 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4193 \
./scripts/verify-keyword-vector-decoupling.sh
```

结果：**8/8 通过**。

- Flyway V1–V43 在一次性 PostgreSQL 上成功迁移；
- 本批 PostgreSQL 相关测试 45/45 通过：lifecycle 12、pg_trgm 13、
  pg_jieba 9、English FTS 11；
- `mvn clean compile test-compile` 通过；
- WebUI Vitest 214/214、Mock Playwright 12/12、TypeScript、production build、
  alignment 和无截图策略检查通过；
- `verify-no-pessimistic-locks.sh` 与 `git diff --check` 通过。

直接使用 Testcontainers 的一次运行因当前 Docker API 协商问题只执行了 0 个测试，
不作为通过证据；最终 45/45 证据来自脚本连接的外部一次性 PostgreSQL 数据库。

## 8. 关键不变决策

- 外部身份继续是 `collectionKey + sourceNamespace + externalId`；
- `sourceNamespace` 不是检索范围；
- `SKIP` 表示不准备当前本地和向量派生，不保留旧正文索引；
- provider 故障不允许继续返回旧正文向量；
- local generation 与 embedding request generation 分开；
- worker 继续使用 lease/CAS/条件 DML，不使用悲观锁；
- 前端验证不使用截图；
- OpenClaw `TOOLS.md`、`MEMORY.md`、`memory/` 和其他本地状态不进入项目文档或 Git。

## 9. 验证证据目录约定

本批一键脚本和测试输出写入：

```text
.verification/keyword-vector-decoupling/<run-id>/
```

运行产物不提交。最终只在本账本记录命令、结果、跳过原因和关键 JSON/SQL 断言摘要。
