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

### 2. pg_trgm — 全文检索（可选）

**用途：**
- `similarity(text, text)` 函数计算文本相似度
- 用于 `HybridRetrieverService.fullTextSearch()` 做模糊匹配
- 阈值 `> 0.1` 过滤低相关结果

**代码位置：**
- 迁移：`V1__init_rag_schema.sql` — `DO $$ EXCEPTION` 块（可选安装）
- 查询：`HybridRetrieverService.executeFulltextQuery()` — `similarity(chunk_text, ?)`
- 配置：`rag.retrieval.fulltext-enabled` 开关

**行为：**
```
启动时检测 → pg_trgm 可用？
  ├─ 是 → 混合检索（向量 + 全文融合）
  └─ 否 → 自动降级为纯向量检索 + warn 日志
```

**注意事项：**
- `pg_trgm` 对中文支持有限（按字符三元组切分，非语义分词）
- 当前实现只取查询的第一个空格分隔词做 `similarity()`，多词查询能力有限
- 纯向量检索对中文场景可能效果更好（语义匹配 vs 字符匹配）

### 3. pg_jieba — 预留（未使用）

**用途：**
- V1 迁移创建 `jiebacfg` 文本搜索配置（基于 jieba 分词器）
- **但 Java 代码中没有任何地方使用 `ts_vector`、`plainto_tsquery` 或 `jiebacfg`**

**代码位置：**
- 迁移：`V1__init_rag_schema.sql` — `DO $$ EXCEPTION` 块（可选安装）
- 配置创建：`CREATE TEXT SEARCH CONFIGURATION jiebacfg (parser = jieba)`（仅在 pg_jieba 可用时）

**结论：** 纯预留。当前全文检索用的是 `pg_trgm.similarity()`，不是 PostgreSQL 全文检索引擎。

## 架构图

```
客户端请求
    │
    ▼
HybridRetrieverService.search()
    │
    ├─ 检查：fulltextEnabled && pgTrgmAvailable？
    │
    ├─ 是（混合模式）              否（纯向量模式）
    │   │                           │
    │   ├─ CompletableFuture 1      └─ vectorSearch()
    │   │   └─ vectorSearch()           │
    │   ├─ CompletableFuture 2          └─ pgvector HNSW 索引
    │   │   └─ fullTextSearch()              ORDER BY embedding <=> ?::vector
    │   │       │
    │   │       └─ pg_trgm.similarity()
    │   │           WHERE similarity(chunk_text, ?) > 0.1
    │   │
    │   └─ fuseResults(vector, fulltext)
    │       └─ RRF 融合 + 去重
    │
    ▼
返回 List<RetrievalResult>
```

## 配置参考

```yaml
rag:
  retrieval:
    fulltext-enabled: true    # 是否启用全文检索（默认 true，不可用时自动降级）
    vector-weight: 0.5        # 向量检索权重
    fulltext-weight: 0.5      # 全文检索权重
    default-limit: 10         # 默认返回数量
    min-score: 0.3            # 最低相关分数
```

## 建议

### 短期（已完成）
- ✅ pg_trgm / pg_jieba 改为可选安装
- ✅ 自动检测 + 优雅降级 + 明确日志
- ✅ `fulltext-enabled` 配置开关
- ✅ 降级行为测试覆盖

### 中期（可考虑）
- [ ] **多词全文检索**：当前 `fullTextSearch()` 只用第一个词，可改用 `pg_trgm.word_similarity()` 或拼接多个 `similarity()` 条件
- [ ] **利用 pg_jieba**：如果安装了 pg_jieba，可使用 `to_tsvector('jiebacfg', chunk_text)` 做真正的中文分词全文检索，效果远优于 pg_trgm 的字符三元组
- [ ] **全文检索策略抽象**：定义 `FulltextSearchProvider` 接口，支持 pg_trgm / pg_jieba / Elasticsearch 等多种后端

### 长期
- [ ] **混合检索权重自适应**：根据查询语言（中文/英文）自动调整 vector/fulltext 权重
- [ ] **检索策略 A/B 测试**：利用已有的 AbTestService 对比不同检索策略效果

## 相关文件

| 文件 | 说明 |
|------|------|
| `db/migration/V1__init_rag_schema.sql` | 扩展安装 + 表结构 |
| `core/retrieval/HybridRetrieverService.java` | 混合检索核心实现 |
| `core/config/RagProperties.java` | `rag.retrieval.*` 配置绑定 |
| `core/retrieval/RetrievalUtils.java` | 分数融合算法 |
| `test/.../HybridRetrieverServiceTest.java` | 含 `PgTrgmFallbackTests` 降级测试 |
