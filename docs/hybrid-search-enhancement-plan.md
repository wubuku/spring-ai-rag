# 混合检索增强规划文档

> 基于 "PostgreSQL Hybrid RAG" 架构方案，改进 spring-ai-rag 的全文检索能力
>
> 编写日期：2026-04-07
>
> **决策状态**：✅ 已批准（GENERATED 列 + GIN 索引 + 重建脚本方案）

---

## 0. 关键决策记录

| 日期 | 决策 | 背景 |
|------|------|------|
| 2026-04-07 | 采用 GENERATED 列 + GIN 索引 + 一次性重建脚本 | 实时计算效率太低，不可接受 |

---

## 1. 现状分析

---

## 1. 现状分析

### 1.1 当前实现

```
HybridRetrieverService
├── vectorSearch()     ✅ 语义检索（pgvector，1024维 BGE-M3）
├── fulltextSearch()   ✅ pg_jieba 中文分词全文检索
└── fuseResults()      ✅ RRF 融合
```

**问题清单**：

| # | 问题 | 严重程度 | 备注 |
|---|------|---------|------|
| P1 | pg_jieba 对 2 字中文词（如"痘痘"）返回 0 分 | 🔴 高 | jieba 分词粒度问题，词库覆盖不足 |
| P2 | 没有英文/通用 FTS | 🟡 中 | 纯中文场景够用，但英文场景无文本增强 |
| P3 | 没有 pg_trgm 降级策略 | 🟡 中 | 短词/分词不佳时无兜底 |
| P4 | `plainto_tsquery` 对短词支持差 | 🟡 中 | `websearch_to_tsquery` 更适合用户输入 |
| P5 | 没有语言检测机制 | 🟢 低 | 无法根据中英文切换不同 FTS 配置 |
| P6 | 能力探测粒度粗 | 🟢 低 | 只检测 pg_jieba 可用性，不检测索引是否存在 |

### 1.2 当前 pg_jieba FTS 实测结果

| 查询 | 向量分数 | FTS 分数 | 结果 |
|------|---------|---------|------|
| "Spring AI" | 0.54 | 0.88 | ✅ 两个通道都有效 |
| "痘痘" (2字) | 0.50 | **0.00** | ❌ FTS 失效，向量有效 |
| "痘痘肌肤" (4字) | 0.43 | 0.43 | ✅ FTS 有效 |

结论：**向量搜索是鲁棒的**，FTS 只是增强信号。短中文词的 FTS 失效不影响最终结果，但降级链缺失会浪费 FTS 能力。

---

## 2. 改进目标

### 2.1 核心原则（来自文章）

1. **向量搜索是必选项、兜底能力** — 不依赖任何可选扩展
2. **文本检索完全基于"能力探测 + 索引存在"原则** — 避免无索引的 LIKE/ILIKE 全表扫描
3. **自动降级** — 任何能力不足时，不会退化到低效查询
4. **RRF rank-level 融合** — 对排序名次而非原始分数敏感

### 2.2 目标架构

```
查询输入
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  语言检测（轻量启发式：CJK Unicode 区块判断）                 │
│  detectLang(query) → ZH | EN                                │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  向量检索（必走，pgvector HNSW，CAST(? AS vector) 绑定）     │
│  → RetrievalResult{ vectorScore }                           │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  文本检索（能力驱动，自动降级）                               │
│                                                             │
│  ZH 语言：                                                  │
│    1. pg_jieba FTS（有 jiebacfg 配置 + GIN 索引）          │
│    2. pg_trgm 模糊搜索（备选降级）                          │
│    3. 跳过（避免无索引 LIKE）                               │
│                                                             │
│  EN/Other 语言：                                            │
│    1. 内置 FTS with 'english' config（有 search_vector）    │
│    2. pg_trgm 模糊搜索（备选降级）                          │
│    3. 跳过                                                  │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  RRF 融合（k=60，平滑常数）                                  │
│  score(d) = Σ 1/(k + rank_s(d))                            │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
检索结果（topK，混合分数）
```

---

## 3. 详细设计

### 3.0 索引策略决策

**关键问题**：jieba 实时计算 vs GENERATED 列？

| 方案 | 查询效率 | 重建成本 | 结论 |
|------|---------|---------|------|
| 实时计算 | ❌ 每行实时计算 tsvector，无索引，大数据极慢 | 无 | ❌ 不可接受 |
| GENERATED + GIN | ✅ 索引加速 | 一次性重建脚本 | ✅ 推荐 |

**决策**：采用 **GENERATED 列 + GIN 索引 + 一次性重建脚本** 方案。

### 3.0.1 GENERATED 列特性说明

`GENERATED ALWAYS AS ... STORED` 列的行为：
- **INSERT/UPDATE 时自动计算**：新数据自动生效
- **已有数据不会自动更新**：需要手动重建
- **扩展安装前后**：如果 `to_tsvector('jiebacfg', ...)` 失败，该列为 NULL

**重建触发条件**：
1. 安装 pg_jieba 扩展后，需要重建 `search_vector_zh` 列
2. 安装 pg_trgm 扩展后，需要重建 trigram 索引（无需重建列）
3. 数据库迁移到新环境时，需要检查并重建

### 3.0.2 重建脚本设计

```sql
-- rebuild_search_vectors.sql
-- 用于在安装扩展后重建 tsvector GENERATED 列

-- 1. 检查当前扩展状态
SELECT extname FROM pg_extension WHERE extname IN ('pg_jieba', 'pg_trgm');

-- 2. 如果安装了 pg_jieba，重建中文搜索向量
UPDATE rag_embeddings 
SET search_vector_zh = to_tsvector('jiebacfg', chunk_text)
WHERE search_vector_zh IS NULL;

-- 3. 如果安装了 pg_trgm，重建 trigram 索引（自动完成，无需手动）

-- 4. 验证重建结果
SELECT COUNT(*) as total, 
       COUNT(search_vector_zh) as with_zh_vector,
       COUNT(search_vector_en) as with_en_vector
FROM rag_embeddings;
```

**Flyway 迁移中的自动重建**：在 V15/V16 迁移后，通过 `AFTER EACH STATEMENT` 触发器或单独 SQL 步骤自动重建。

### 3.1 能力探测框架

新增 `SearchCapabilities` 类，统一管理扩展和索引探测：

```java
public class SearchCapabilities {
    
    // 扩展检测
    private boolean hasPgVector;      // 必然为 true（向量搜索前提）
    private boolean hasPgTrgm;       // pg_trgm 扩展
    private boolean hasJieba;        // pg_jieba 扩展
    private boolean hasZhparser;      // zhparser 扩展
    
    // 索引检测
    private boolean hasJiebaIndex;    // jiebacfg GIN 索引
    private boolean hasEnglishFtsIndex; // english tsvector GIN 索引
    private boolean hasTrgmIndex;    // gin_trgm_ops 索引
    
    // 能力方法
    public boolean enableChineseFts() { return hasJieba && hasJiebaIndex; }
    public boolean enableEnglishFts() { return hasEnglishFtsIndex; }
    public boolean enableTrgm() { return hasPgTrgm && hasTrgmIndex; }
}
```

**数据库探测 SQL**：

```sql
-- 扩展检测
SELECT extname FROM pg_extension 
WHERE extname IN ('vector', 'pg_trgm', 'pg_jieba', 'zhparser');

-- 索引检测
SELECT indexname, indexdef FROM pg_indexes 
WHERE tablename = 'rag_embeddings' AND schemaname = 'public';
```

### 3.2 语言检测

```java
public enum QueryLang { ZH, EN_OR_OTHER }

public QueryLang detectLang(String text) {
    return text.codePoints().anyMatch(cp -> {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B;
    }) ? QueryLang.ZH : QueryLang.EN_OR_OTHER;
}
```

### 3.3 文本检索策略接口

```java
public interface FulltextSearchProvider {
    String getName();
    boolean isAvailable();  // 能力探测
    List<RetrievalResult> search(String query, List<Long> documentIds, 
                                List<Long> excludeIds, int limit, double minScore);
}
```

### 3.4 策略优先级（降级链）

#### 3.4.1 中文（ZH）降级链

| 优先级 | 策略 | 条件 |
|--------|------|------|
| 1 | pg_jieba FTS | `hasJieba && hasJiebaIndex` |
| 2 | pg_trgm 模糊搜索 | `hasPgTrgm && hasTrgmIndex` |
| 3 | 跳过 | 避免无索引 LIKE |

#### 3.4.2 英文/其他（EN_OR_OTHER）降级链

| 优先级 | 策略 | 条件 |
|--------|------|------|
| 1 | 内置 FTS（english） | `hasEnglishFtsIndex` |
| 2 | pg_trgm 模糊搜索 | `hasPgTrgm && hasTrgmIndex` |
| 3 | 跳过 | 避免无索引 LIKE |

### 3.5 新增 Provider 实现

#### 3.5.1 `PgTrgmFulltextProvider`（新增）

```sql
-- pg_trgm 模糊搜索
SET pg_trgm.similarity_threshold = 0.1;  -- 低阈值，更多结果

SELECT id, chunk_text, document_id, chunk_index, metadata,
       similarity(chunk_text, ?) AS score_trgm
FROM rag_embeddings
WHERE chunk_text % ?  -- 使用 % 操作符，触发 trigram 索引
ORDER BY score_trgm DESC
LIMIT ?;
```

**注意**：
- 必须有 `gin_trgm_ops` 索引才创建此 Provider
- 设置低阈值（0.1）避免漏掉短词匹配
- 适合：短词、部分匹配、轻微拼写错误

#### 3.5.2 `PgEnglishFtsProvider`（新增）

```sql
-- 英文/内置 FTS（使用 'english' 配置和预建索引）
SELECT id, chunk_text, document_id, chunk_index, metadata,
       ts_rank_cd(search_vector_en, q) AS score_fts
FROM rag_embeddings,
     websearch_to_tsquery('english', ?) AS q
WHERE search_vector_en @@ q
ORDER BY score_fts DESC
LIMIT ?;
```

**注意**：
- 使用 `search_vector_en` GENERATED 列（有 GIN 索引）
- 使用 `websearch_to_tsquery` 替代 `plainto_tsquery`

### 3.6 `PgJiebaFulltextProvider` 改进

```java
// 改动1：使用预建的 search_vector_zh 列（有 GIN 索引）
// 改动2：使用 websearch_to_tsquery 替代 plainto_tsquery

String sql = """
    SELECT id, chunk_text, document_id, chunk_index, metadata,
           ts_rank_cd(search_vector_zh, q) AS score_fts
    FROM rag_embeddings,
         websearch_to_tsquery('jiebacfg', ?) AS q
    WHERE search_vector_zh @@ q
    ORDER BY score_fts DESC
    LIMIT ?
    """;

// 注意：search_vector_zh 可能为 NULL（如果 jieba 未安装），
// 需要在 WHERE 中过滤：AND search_vector_zh IS NOT NULL
```

### 3.7 `FulltextSearchProviderFactory` 改进

```java
public FulltextSearchProvider getProvider(QueryLang lang) {
    if (lang == QueryLang.ZH) {
        if (capabilities.enableChineseFts()) {
            return new PgJiebaFulltextProvider(jdbcTemplate);
        }
        if (capabilities.enableTrgm()) {
            return new PgTrgmFulltextProvider(jdbcTemplate);
        }
    } else {
        if (capabilities.enableEnglishFts()) {
            return new PgEnglishFtsProvider(jdbcTemplate);
        }
        if (capabilities.enableTrgm()) {
            return new PgTrgmFulltextProvider(jdbcTemplate);
        }
    }
    return new NoOpFulltextSearchProvider();
}
```

### 3.8 `HybridRetrieverService` 改动

```java
// 改动1：注入 SearchCapabilities
// 改动2：注入 FulltextSearchProviderFactory（已有）
// 改动3：在 fulltextSearch 前增加语言检测和策略选择

private List<RetrievalResult> fulltextSearch(String query, List<Long> documentIds,
                                             List<Long> excludeIds, int limit) {
    QueryLang lang = detectLang(query);
    FulltextSearchProvider provider = factory.getProvider(lang);
    
    if (provider instanceof NoOpFulltextSearchProvider) {
        log.debug("No fulltext search provider available for lang={}", lang);
        return Collections.emptyList();
    }
    
    return provider.search(query, documentIds, excludeIds, limit, 0.0);
}

private QueryLang detectLang(String text) {
    // 轻量级 CJK 检测
    // ...
}
```

### 3.9 数据库迁移

#### V15：添加 search_vector 列 + GIN 索引

```sql
-- ================================================================
-- V15: 添加全文检索 tsvector 列和 GIN 索引
-- ================================================================

-- 1. 添加中文 tsvector 列（使用 pg_jieba 分词配置）
--    如果 pg_jieba 未安装，该列为 NULL，查询时会自动降级
ALTER TABLE rag_embeddings 
ADD COLUMN search_vector_zh tsvector
GENERATED ALWAYS AS (to_tsvector('jiebacfg', chunk_text)) STORED;

-- 2. 添加英文 tsvector 列（使用内置 english 配置）
--    english 配置永远可用，无需扩展依赖
ALTER TABLE rag_embeddings 
ADD COLUMN search_vector_en tsvector
GENERATED ALWAYS AS (to_tsvector('english', chunk_text)) STORED;

-- 3. 创建中文 GIN 索引（只有在 jieba 可用时才有意义）
--    条件创建：如果 pg_jieba 未安装，索引仍会创建但不会用于查询计划
CREATE INDEX idx_rag_embeddings_search_vector_zh 
ON rag_embeddings USING gin (search_vector_zh);

-- 4. 创建英文 GIN 索引（无条件，始终有效）
CREATE INDEX idx_rag_embeddings_search_vector_en 
ON rag_embeddings USING gin (search_vector_en);

-- 5. 重建已有数据的中文 tsvector（如果 jieba 已安装）
--    注意：GENERATED 列不会自动填充已有行，需要手动 UPDATE
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba') THEN
        UPDATE rag_embeddings SET search_vector_zh = to_tsvector('jiebacfg', chunk_text)
        WHERE search_vector_zh IS NULL;
        RAISE NOTICE 'Rebuilt search_vector_zh for existing rows';
    ELSE
        RAISE NOTICE 'pg_jieba not installed, search_vector_zh left as NULL';
    END IF;
END $$;

-- 6. 验证结果
SELECT 
    COUNT(*) as total_rows,
    COUNT(search_vector_zh) as zh_vector_count,
    COUNT(search_vector_en) as en_vector_count,
    COUNT(search_vector_zh) FILTER (WHERE search_vector_zh IS NOT NULL) as zh_vector_populated
FROM rag_embeddings;
```

**说明**：
- `search_vector_zh`：用 jieba 分词，如果 jieba 未安装则为 NULL
- `search_vector_en`：用内置 english 配置，始终有效
- 已有数据会通过 DO $$ 块自动重建

#### V16：添加 trigram 索引

```sql
-- ================================================================
-- V16: 添加 trigram 模糊搜索索引
-- ================================================================

-- 只有在 pg_trgm 扩展已安装时才创建 trigram GIN 索引
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_rag_embeddings_trgm 
        ON rag_embeddings USING gin (chunk_text gin_trgm_ops);
        RAISE NOTICE 'Created trigram GIN index';
    ELSE
        RAISE NOTICE 'pg_trgm not installed, skipping trigram index creation';
    END IF;
END $$;

-- 注意：trigram 索引不需要重建列，只要扩展安装后创建索引即可
```

---

## 4. 实现步骤

### Phase 1：能力探测框架（基础）

| Step | 任务 | 文件变更 |
|------|------|---------|
| 1.1 | 新增 `SearchCapabilities` 类 | `SearchCapabilities.java` |
| 1.2 | 新增 `QueryLang` 枚举 | `QueryLang.java` |
| 1.3 | 新增 `PgTrgmFulltextProvider` | `PgTrgmFulltextProvider.java` |
| 1.4 | 新增 `PgEnglishFtsProvider` | `PgEnglishFtsProvider.java` |
| 1.5 | 改进 `FulltextSearchProviderFactory`，支持语言参数 | `FulltextSearchProviderFactory.java` |
| 1.6 | 改进 `HybridRetrieverService`，集成语言检测和策略选择 | `HybridRetrieverService.java` |

**预估代码量**：+500 行

### Phase 2：数据库迁移

| Step | 任务 | 文件变更 |
|------|------|---------|
| 2.1 | V15 迁移：添加 search_vector_zh + search_vector_en GENERATED 列和 GIN 索引 | `V15__add_search_vectors.sql` |
| 2.2 | V16 迁移：添加 trigram 索引（条件执行） | `V16__add_trgm_index.sql` |

**预估代码量**：+60 行 SQL（含 DO $$ 重建块）

### Phase 3：pg_jieba 改进

| Step | 任务 | 文件变更 |
|------|------|---------|
| 3.1 | `plainto_tsquery` → `websearch_to_tsquery` | `PgJiebaFulltextProvider.java` |
| 3.2 | 改用预建 `search_vector_zh` 列（有 GIN 索引） | `PgJiebaFulltextProvider.java` |
| 3.3 | 实测对比两种查询方式的效果 | 测试验证 |

**预估代码量**：+30 行

### Phase 4：测试

| Step | 任务 | 验证方式 |
|------|------|---------|
| 4.1 | 向量搜索回归测试 | 现有单元测试 |
| 4.2 | pg_jieba FTS 回归测试 | 现有单元测试 |
| 4.3 | pg_trgm 新增单元测试 | `PgTrgmFulltextProviderTest` |
| 4.4 | English FTS 新增单元测试 | `PgEnglishFtsProviderTest` |
| 4.5 | 语言检测新增单元测试 | `QueryLangTest` |
| 4.6 | 降级链集成测试 | `HybridRetrieverServiceIntegrationTest` |
| 4.7 | E2E 端到端测试 | `./scripts/e2e-test.sh` |

**预估代码量**：+150 行测试

---

## 5. 风险与注意事项

### 5.1 GENERATED 列的扩展依赖问题

**问题**：如果 `to_tsvector('jiebacfg', chunk_text)` 执行时 pg_jieba 未安装，会发生什么？

**分析**：
- PostgreSQL 会报错：`function to_tsvector(unknown, text) does not exist`
- 这会导致 INSERT/UPDATE 失败

**解决方案**：使用 **条件表达式** 避免报错

```sql
-- 方案 A：使用 COALESCE 降级到 simple 配置
GENERATED ALWAYS AS (
    COALESCE(
        NULLIF(to_tsvector('jiebacfg', chunk_text), to_tsvector('simple', '')),
        to_tsvector('simple', chunk_text)
    )
) STORED

-- 方案 B：在迁移中确保 jieba 已安装后再创建列
-- 推荐：V15 迁移依赖 V1（rag_embeddings 表已存在），
--      但不依赖 jieba 安装（如果 jieba 未安装，用户需要先安装扩展再重建）
```

**推荐方案**：方案 A 过于复杂，且会丢失 jieba 语义。**采用显式文档说明**：
- V15 迁移假设 pg_jieba 已安装（因为这是环境前提）
- 如果未安装，`search_vector_zh` 为 NULL，查询时自动降级

### 5.2 扩展安装后的数据重建

| 场景 | 已有数据 | 新数据 |
|------|---------|--------|
| 安装 pg_jieba 后 | `search_vector_zh = NULL`，需要重建 | 自动计算 |
| 安装 pg_trgm 后 | 无需重建（无 GENERATED 列） | 索引自动生效 |

**Flyway 自动重建**：在 V15 迁移中加入 `DO $$` 块，尝试重建已有数据。

### 5.3 `websearch_to_tsquery` 对中文的影响

`websearch_to_tsquery` 可能会改变查询解析方式，与 `plainto_tsquery` 的行为不同。建议：
- 两种方式都实测
- 如果 `websearch_to_tsquery` 对中文效果差，只在英文场景替换

### 5.4 pg_trgm 索引大小

trigram GIN 索引可能较大（尤其是长文本列），但对于 `chunk_text` 列（通常几百字符）应该可接受。

### 5.5 向量搜索 vs 文本搜索权重

当前 RRF 融合中，向量和文本的权重是隐式的（通过 rank 计算）。可以后续增加显式权重配置，但当前设计足够。

---

## 6. 测试计划

### 6.1 单元测试

| Provider | 测试场景 | 预期结果 |
|----------|---------|---------|
| PgJiebaFulltextProvider | "Spring AI" (EN) | ✅ 有分数 |
| PgJiebaFulltextProvider | "痘痘" (2字 ZH) | 取决于 jieba 分词 |
| PgJiebaFulltextProvider | "痘痘肌肤" (4字 ZH) | ✅ 有分数 |
| PgTrgmFulltextProvider | "痘痘" (2字 ZH) | ✅ 有分数（字符级匹配） |
| PgTrgmFulltextProvider | "Spring" (EN) | ✅ 有分数 |
| PgEnglishFtsProvider | "Spring AI" (EN) | ✅ 有分数 |
| PgEnglishFtsProvider | "痘痘" (ZH) | 可能无分数（english 配置不支持中文） |
| 降级链 | 所有 Provider 都不可用 | ✅ 返回空，不抛异常 |

### 6.2 E2E 测试

```bash
# 中文短词测试（向量 + FTS + TRGM 多通道）
curl -X POST "http://localhost:8081/api/v1/rag/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":"痘痘","topK":5}'

# 英文测试（向量 + English FTS）
curl -X POST "http://localhost:8081/api/v1/rag/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":"Spring AI","topK":5}'
```

**验证点**：
- 向量分数始终 > 0
- 有对应 Provider 时，FTS/trgm 分数 > 0
- 最终结果数 = topK

---

## 7. 时间估算

| Phase | 任务 | 预估时间 |
|-------|------|---------|
| Phase 1 | 能力探测框架 | 2-3 小时 |
| Phase 2 | 数据库迁移 | 1 小时 |
| Phase 3 | pg_jieba 改进 | 1 小时 |
| Phase 4 | 测试 | 2 小时 |
| **总计** | | **6-7 小时** |

---

## 8. 附录：参考文章关键设计

1. **能力驱动原则**：只有在满足"扩展已安装 + 索引存在"两个条件时才启用某策略
2. **websearch_to_tsquery**：支持 Google 风格搜索语法，比 plainto_tsquery 更适合用户输入
3. **pg_trgm.similarity_threshold**：设置低阈值（0.1）避免短词漏匹配
4. **RRF 融合公式**：`score(d) = Σ 1/(k + rank_s(d))`，k=60
5. **语言检测**：Unicode CJK 区块判断
6. **自动降级链**：向量（必走）→ 中文 FTS → 英文 FTS → trigram → 完全跳过
7. **GENERATED 列 + GIN 索引**：查询效率高，但需要一次性重建已有数据
8. **扩展依赖处理**：如果扩展未安装，GENERATED 列为 NULL，查询时自动降级
