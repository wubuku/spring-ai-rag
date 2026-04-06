# 混合检索增强规划文档

> 基于 "PostgreSQL Hybrid RAG" 架构方案，改进 spring-ai-rag 的全文检索能力
> 
> 编写日期：2026-04-07

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
-- 英文/内置 FTS（使用 'simple' 或 'english' 配置）
-- 需要新增 search_vector 列和 GIN 索引

SELECT id, chunk_text, document_id, chunk_index, metadata,
       ts_rank_cd(search_vector, q) AS score_fts
FROM rag_embeddings,
     websearch_to_tsquery('english', ?) AS q
WHERE search_vector @@ q
ORDER BY score_fts DESC
LIMIT ?;
```

**注意**：
- 需要 Flyway 迁移添加 `search_vector tsvector GENERATED ALWAYS AS (...) STORED` 列
- 需要 Flyway 迁移添加 `USING gin (search_vector)` 索引
- 使用 `websearch_to_tsquery` 替代 `plainto_tsquery`

### 3.6 `PgJiebaFulltextProvider` 改进

```java
// 改动1：使用 websearch_to_tsquery 替代 plainto_tsquery
// 改动2：支持 prefix matching（用 :* 后缀）
String sql = """
    SELECT id, chunk_text, document_id, chunk_index, metadata,
           ts_rank_cd(search_vector, q) AS score_fts
    FROM rag_embeddings,
         websearch_to_tsquery('jiebacfg', ?) AS q
    WHERE search_vector @@ q
    ORDER BY score_fts DESC
    LIMIT ?
    """;
```

**注意**：`websearch_to_tsquery` 对中文支持可能不如 `plainto_tsquery` 精确，需要实测对比。

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

#### V15：添加 search_vector 列（英文 FTS）

```sql
-- 为英文内容添加 tsvector 列（GENERATED ALWAYS AS STORED）
ALTER TABLE rag_embeddings 
ADD COLUMN search_vector tsvector
GENERATED ALWAYS AS (
    to_tsvector('english', chunk_text)  -- 英文用 english 配置
) STORED;

-- 创建 GIN 索引
CREATE INDEX idx_rag_embeddings_search_vector_english 
ON rag_embeddings USING gin (search_vector);

-- 注意：中文内容用 simple 配置也能存，但 jieba 分词效果更好
-- 可以考虑添加 lang 字段，动态选择配置
```

#### V16：添加 trigram 索引

```sql
-- 只有在 pg_trgm 扩展已安装时才创建
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_rag_embeddings_trgm 
        ON rag_embeddings USING gin (chunk_text gin_trgm_ops);
    END IF;
END $$;
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
| 2.1 | V15 迁移：添加 search_vector 列和 GIN 索引 | `V15__add_search_vector.sql` |
| 2.2 | V16 迁移：添加 trigram 索引（条件执行） | `V16__add_trgm_index.sql` |

**预估代码量**：+40 行 SQL

### Phase 3：pg_jieba 改进

| Step | 任务 | 文件变更 |
|------|------|---------|
| 3.1 | `plainto_tsquery` → `websearch_to_tsquery` | `PgJiebaFulltextProvider.java` |
| 3.2 | 实测对比两种查询方式的效果 | 测试验证 |

**预估代码量**：+20 行

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

### 5.1 `websearch_to_tsquery` 对中文的影响

`websearch_to_tsquery` 可能会改变查询解析方式，与 `plainto_tsquery` 的行为不同。建议：
- 两种方式都实测
- 如果 `websearch_to_tsquery` 对中文效果差，只在英文场景替换

### 5.2 `search_vector` 列的生成策略

当前设计用 `to_tsvector('english', chunk_text)` 生成所有内容的 tsvector。对于中文内容，这会按字符分词，效果不如 jieba。

**备选方案**：使用 `lang` 字段动态选择配置

```sql
ALTER TABLE rag_embeddings ADD COLUMN lang varchar(8) DEFAULT 'other';

search_vector GENERATED ALWAYS AS (
    CASE 
        WHEN lang = 'zh' THEN to_tsvector('jiebacfg', chunk_text)
        WHEN lang = 'en' THEN to_tsvector('english', chunk_text)
        ELSE to_tsvector('simple', chunk_text)
    END
) STORED
```

**注意**：这需要较大的数据迁移，先按简单方案实现，后续可迭代。

### 5.3 pg_trgm 索引大小

trigram GIN 索引可能较大（尤其是长文本列），但对于 `chunk_text` 列（通常几百字符）应该可接受。

### 5.4 向量搜索 vs 文本搜索权重

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
