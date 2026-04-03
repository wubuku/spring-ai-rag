# PostgreSQL 扩展依赖分析

> 最后更新：2026-04-03 | 基于代码分析 v1.0.0-SNAPSHOT (commit 6ead2e5)

## 概述

spring-ai-rag 依赖 PostgreSQL 扩展实现检索能力。本文档分析各扩展的**实际使用情况**、**是否必需**、以及**不可用时的行为**。

## 扩展依赖矩阵

| 扩展 | 是否必需 | 实际用途 | 不可用时行为 |
|------|---------|---------|-------------|
| **vector** (pgvector) | ✅ 必需 | `VECTOR(1024)` 列 + HNSW 索引 + `<=>` 距离算子 | ❌ 迁移失败，应用无法启动 |
| **pg_trgm** | ⚡ 可选 | `similarity()` 函数，全文模糊匹配 | ✅ 自动降级为纯向量检索 |
| **pg_jieba** | ⚡ 可选 | 未在查询中使用（预留） | ✅ 跳过 jiebacfg 配置创建 |

## 详细分析

### 1. vector (pgvector) — 核心依赖

**用途：**
- `rag_embeddings.embedding` 列定义为 `VECTOR(1024)`
- HNSW 索引 (`vector_cosine_ops`) 加速向量检索
- `<=>` 算子计算余弦距离

**代码位置：**
- 迁移：`V1__init_rag_schema.sql` — `CREATE EXTENSION IF NOT EXISTS vector`
- 查询：`HybridRetrieverService.vectorSearch()` — `ORDER BY embedding <=> ?::vector`
- 实体：`RagEmbedding.embedding` 字段

**结论：** 不可省略。向量检索是 RAG 的核心能力。

### 2. pg_trgm — 全文检索（可选，降级方案）

**用途：**
- `similarity(text, text)` 函数计算文本相似度
- 用于 `PgTrgmFulltextProvider` 做多词模糊匹配（每个关键词取最高相似度）
- 阈值 `> 0.1` 过滤低相关结果

**原理：** 把文本切成连续 3 个字符的片段（三元组），通过重叠度计算相似度。
- "搜索文档" → 字符级三元组切分
- "苹果手机" 和 "苹果电脑" 的三元组重叠度很高（都含"苹""果""手""机/电""脑"）

**适用场景：** 英文模糊纠错、拼写近似匹配

**中文局限：** 字符级匹配，无法理解词边界，精度远低于分词方案。

**代码位置：**
- Provider：`core/retrieval/fulltext/PgTrgmFulltextProvider.java`
- 配置：`rag.retrieval.fulltext-strategy=pg_trgm` 或 `auto`（降级方案）

### 3. pg_jieba — 全文检索（可选，中文首选）

**用途：**
- `to_tsvector('jiebacfg', text)` + `plainto_tsquery('jiebacfg', query)` 实现中文分词全文检索
- `ts_rank()` 计算相关度排序

**原理：** 用 jieba 分词器把文本切成词语，基于词语匹配而非字符匹配。
- "搜索文档" → `搜索` `文档`（正确识别词边界）
- "苹果手机" → `苹果` `手机`（不会误匹配"苹果电脑"）

**适用场景：** 中文全文检索，精度显著高于 pg_trgm

**安装要求：** 需要额外编译安装（C 扩展），不是所有 PostgreSQL 环境都支持（如云 RDS）。

**代码位置：**
- Provider：`core/retrieval/fulltext/PgJiebaFulltextProvider.java`
- 配置：`rag.retrieval.fulltext-strategy=pg_jieba` 或 `auto`（首选方案）

### pg_trgm vs pg_jieba 对比

| 维度 | pg_trgm（三元组） | pg_jieba（分词） |
|------|------------------|-----------------|
| 中文精度 | 差——字符级匹配，无法区分词边界 | 好——词语级匹配，正确识别中文分词 |
| 英文精度 | 中——适合拼写纠错 | 好——同样适用 |
| 安装难度 | 低——PostgreSQL 自带扩展 | 高——需编译 C 扩展 |
| 查询性能 | 快（简单字符串运算） | 稍慢（分词 + 倒排索引） |
| 云数据库 | 通常可用 | 受限（AWS RDS、阿里云 RDS 多数不支持） |
| 推荐场景 | 英文为主 / 降级兜底 | 中文为主（首选） |

**结论：对中文场景，pg_jieba 是正确选择，pg_trgm 是降级兜底。**

两者共存的原因：不是所有环境都能安装 pg_jieba（如云数据库），pg_trgm 保证最低限度的全文检索能力。当前 `auto` 策略优先选 pg_jieba，不可用时降级 pg_trgm。

## 架构图

```
客户端请求
    │
    ▼
HybridRetrieverService.search()
    │
    ├─ 检查：fulltextEnabled && fulltextProvider.isAvailable()？
    │
    ├─ 是（混合模式）                     否（纯向量模式）
    │   │                                  │
    │   ├─ CompletableFuture 1             └─ vectorSearch()
    │   │   └─ vectorSearch()                  │
    │   ├─ CompletableFuture 2                 └─ pgvector HNSW 索引
    │   │   └─ fulltextProvider.search()           ORDER BY embedding <=> ?::vector
    │   │       │
    │   │       ├─ PgJiebaFulltextProvider (首选)
    │   │       │   └─ to_tsvector('jiebacfg') @@ plainto_tsquery('jiebacfg', ?)
    │   │       │
    │   │       ├─ PgTrgmFulltextProvider (降级)
    │   │       │   └─ similarity(chunk_text, ?) > 0.1（多词取最高分）
    │   │       │
    │   │       └─ NoOpFulltextSearchProvider (禁用)
    │   │           └─ 返回空列表
    │   │
    │   └─ fuseResults(vector, fulltext)
    │       └─ RRF 融合 + 去重
    │
    ▼
返回 List<RetrievalResult>
```

### 策略选择流程

```
rag.retrieval.fulltext-strategy = ?
    │
    ├─ "none"  ──→ NoOpFulltextSearchProvider（纯向量）
    │
    ├─ "pg_jieba" ──→ pg_jieba 扩展可用？
    │                    ├─ 是 → PgJiebaFulltextProvider
    │                    └─ 否 → IllegalStateException（启动失败）
    │
    ├─ "pg_trgm" ──→ pg_trgm 扩展可用？
    │                   ├─ 是 → PgTrgmFulltextProvider
    │                   └─ 否 → IllegalStateException（启动失败）
    │
    └─ "auto"（默认）──→ pg_jieba 可用？
                           ├─ 是 → PgJiebaFulltextProvider
                           └─ 否 → pg_trgm 可用？
                                      ├─ 是 → PgTrgmFulltextProvider
                                      └─ 否 → NoOp（纯向量，warn 日志）
```

## 配置参考

```yaml
rag:
  retrieval:
    fulltext-enabled: true          # 总开关（默认 true）
    fulltext-strategy: auto         # 策略：auto / pg_jieba / pg_trgm / none
    vector-weight: 0.5              # 向量检索权重
    fulltext-weight: 0.5            # 全文检索权重
    default-limit: 10               # 默认返回数量
    min-score: 0.3                  # 最低相关分数
```

| 策略值 | 行为 |
|--------|------|
| `auto`（默认） | 自动检测：pg_jieba → pg_trgm → 纯向量（降级时 warn 日志） |
| `pg_jieba` | 强制使用 jieba 分词，扩展不可用时**启动失败** |
| `pg_trgm` | 强制使用三元组匹配，扩展不可用时**启动失败** |
| `none` | 纯向量检索，不检测任何扩展 |

## 建议

### 已完成
- ✅ pg_trgm / pg_jieba 改为可选安装
- ✅ 自动检测 + 优雅降级 + 明确日志
- ✅ `fulltext-enabled` 配置总开关
- ✅ `fulltext-strategy` 策略配置（auto / pg_jieba / pg_trgm / none）
- ✅ FulltextSearchProvider 策略接口抽象
- ✅ pg_jieba 中文分词全文检索实现
- ✅ pg_trgm 多词检索支持（每个关键词取最高相似度）
- ✅ 降级行为测试覆盖
- ✅ 策略配置测试覆盖

### 可选优化
- [ ] **全文检索 GIN 索引**：对 rag_embeddings.chunk_text 预建 `to_tsvector('jiebacfg')` 索引列，避免运行时计算（大数据集必做）
- [ ] **检索策略 A/B 测试**：利用已有的 AbTestService 对比 pg_jieba vs pg_trgm vs 纯向量效果
- [ ] **混合检索权重自适应**：根据查询语言（中文/英文）自动调整 vector/fulltext 权重

## 相关文件

| 文件 | 说明 |
|------|------|
| `db/migration/V1__init_rag_schema.sql` | 扩展安装 + 表结构 |
| `core/retrieval/HybridRetrieverService.java` | 混合检索核心实现 |
| `core/config/RagProperties.java` | `rag.retrieval.*` 配置绑定 |
| `core/retrieval/RetrievalUtils.java` | 分数融合算法 |
| `test/.../HybridRetrieverServiceTest.java` | 含 `PgTrgmFallbackTests` 降级测试 |
