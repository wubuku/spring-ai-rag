# 架构设计详解

> 📖 [English](architecture.md) · 📖 [中文](architecture-zh-CN.md)

> **spring-ai-rag** — 模型无关、领域解耦、组件化的通用 RAG 服务框架。
> 本文档面向核心开发者和架构评审者。
>
> 文档导航：[index-zh-CN.md](index-zh-CN.md) · 完成状态对照：[IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md)

---

## 1. 设计理念

| 原则 | 含义 | 实现方式 |
|------|------|---------|
| **模型无关** | 切换 LLM 只改配置，不改代码 | Spring AI ChatClient 抽象 + 三 Bean 模式 |
| **领域解耦** | 通用 RAG 核心与业务领域分离 | DomainRagExtension 接口 + SPI 注册 |
| **组件独立** | 每个 Advisor / Service 可单独使用 | 接口优先设计，Spring Bean 自动装配 |
| **可观测** | 每步 Pipeline 可追踪、可度量 | Micrometer 指标 + 检索日志 + A/B 实验 |

---

## 2. 模块结构

```
spring-ai-rag (parent pom)
├── spring-ai-rag-api          # 接口定义、DTO、DomainRagExtension 接口
├── spring-ai-rag-core         # 核心实现（所有业务逻辑）
│   ├── advisor/               # RAG Pipeline Advisors
│   ├── config/                # Spring 配置类
│   ├── controller/            # REST 端点
│   ├── entity/                # JPA 实体
│   ├── exception/             # 业务异常
│   ├── extension/             # 领域扩展机制
│   ├── filter/                # 认证过滤器
│   ├── metrics/               # 监控指标
│   ├── repository/            # 数据访问层
│   ├── retrieval/             # 检索服务（嵌入/改写/重排）
│   └── service/               # 业务服务层
├── spring-ai-rag-starter      # Spring Boot Starter 自动配置
├── spring-ai-rag-documents    # 文档处理组件（分块/清洗）
└── demos/
    ├── demo-basic-rag         # 基础 RAG 示例
    ├── demo-multi-model      # 多模型示例
    ├── demo-component-level   # 组件级集成示例
    └── demo-domain-extension  # 领域扩展示例
```

**依赖方向**：`api ← core ← starter`，`api ← documents`，`starter + documents ← demos`。

---

## 3. 核心设计模式

### 3.1 三 Bean ChatModel 模式

通过 `app.llm.provider` 配置切换模型，无需改代码：

```
                    ┌─────────────────────┐
                    │  app.llm.provider   │
                    │  openai | anthropic │
                    └─────────┬───────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼                               ▼
   ┌──────────────────┐            ┌──────────────────┐
   │ openAiChatModel  │            │ anthropicChatModel│
   │ @Conditional...  │            │ @Conditional...   │
   │ provider=openai  │            │ provider=anthropic│
   └────────┬─────────┘            └────────┬─────────┘
            │                               │
            └───────────────┬───────────────┘
                            ▼
                 ┌─────────────────────┐
     @Primary →  │    chatModel        │
                 │ 自动选择可用的 Bean  │
                 └──────────┬──────────┘
                            ▼
                 ┌─────────────────────┐
                 │ ChatClient.Builder  │
                 │  (Spring AI 抽象)   │
                 └─────────────────────┘
```

**关键实现**：`SpringAiConfig.java`
- 未选中的 provider 返回 `null`（非错误）
- `chatModel` 用 `@ConditionalOnMissingBean` 避免冲突
- `ApiAdapterFactory` 自动检测 API 兼容性（如 MiniMax 不支持多 system 消息）

### 3.2 Advisor 链式 RAG Pipeline

Spring AI 的 `BaseAdvisor` 机制串联检索流程，每个 Advisor 独立、可插拔：

```
用户查询
  │
  ▼
┌─────────────────────────┐  order=+10
│  QueryRewriteAdvisor    │  查询改写：同义词扩展 + 领域限定词 + LLM 辅助改写
│  输入: 原始 query        │
│  输出: 改写后 query      │
└───────────┬─────────────┘
            ▼
┌─────────────────────────┐  order=+20
│  HybridSearchAdvisor    │  混合检索：向量相似度 + 全文检索 + RRF 融合
│  输入: 改写后 query      │
│  输出: context attributes│  (hybrid.search.results)
└───────────┬─────────────┘
            ▼
┌─────────────────────────┐  order=+30
│  RerankAdvisor          │  重排 + 上下文注入：取 Top-K 结果注入 Prompt
│  输入: 检索结果          │
│  输出: 增强后的 Prompt   │  (通过 augmentUserMessage)
└───────────┬─────────────┘
            ▼
┌─────────────────────────┐
│  MessageChatMemoryAdvisor│ 对话记忆：多轮上下文
└───────────┬─────────────┘
            ▼
        ChatModel → 响应
```

**数据传递**：Advisors 之间通过 `ChatClientRequest.context().getAttributes()` 共享数据，避免方法签名耦合。

### 3.3 双表对话记忆

| 表 | 用途 | 管理方 |
|---|------|--------|
| `spring_ai_chat_memory` | LLM 上下文窗口 | Spring AI 自动管理 |
| `rag_chat_history` | 业务审计 + 历史查询 | 应用层写入 |

```
请求 → ChatClient → spring_ai_chat_memory（给 LLM 用）
                  ↘ rag_chat_history（业务审计，保留 user_message + ai_response）
```

### 3.4 领域扩展机制

通过 `DomainRagExtension` 接口实现领域定制，核心框架无需修改：

```java
public interface DomainRagExtension {
    String getDomainId();                           // 领域标识
    String customizeSystemPrompt(String base);      // 定制系统提示词
    Map<String, Double> getRetrievalWeights();      // 自定义检索权重
    List<RetrievalResult> postProcess(...);         // 后处理检索结果
}
```

**注册流程**：
1. 实现 `DomainRagExtension` 并标注 `@Component`
2. `DomainExtensionRegistry` 构造时自动发现所有实现
3. 请求携带 `domainId` 参数时，自动激活对应扩展
4. 无匹配时使用 `DefaultDomainRagExtension`（无修改透传）

---

## 4. 数据流

### 4.1 RAG 问答请求流

```
POST /api/v1/rag/chat/ask
  │
  ▼
RagChatController
  │ validate request
  ▼
ChatClient.prompt(query)
  │
  ├──→ QueryRewriteAdvisor    (改写查询)
  ├──→ HybridSearchAdvisor    (向量+全文检索)
  ├──→ RerankAdvisor          (重排+注入上下文)
  ├──→ MessageChatMemoryAdvisor (多轮记忆)
  │
  ▼
ChatModel (DeepSeek / Anthropic / ...)
  │
  ▼
响应 + rag_chat_history 持久化
```

### 4.2 Collection 范围检索

Collection 已经是实际检索边界，不只是管理页面中的文档分类。

```text
ChatRequest / SearchRequest
  collectionKeys? + deprecated collectionIds? + documentIds?
        │
        ▼
ApiKeyCollectionAccess.resolveCollectionIds
  + CollectionIdentityResolver
  - 按原值校验 1-128 个可见 ASCII 字符，不做归一化
  - ID/key 集合必须标识同一批活动 Collection
  - restricted：只在允许的内部 ID 中解析；未知/越界 key 返回 403
  - unrestricted 未知 key 返回 404
  - 输出内部 Long ID
        │
        ▼
CollectionDocumentResolver
  - collectionIds -> RagDocument IDs
  - 与显式 documentIds 取交集
  - 非空范围解析为零文档时保留 empty scope
        │
        ├── Chat -> RagChatService.RetrievalScope
        │           -> HybridSearchAdvisor
        └── Search -> RagSearchController
                    -> HybridRetrieverService
        │
        ▼
向量与全文 SQL
  WHERE document_id IN (...)
        │
        ▼
仅返回有效范围内的 chunks
```

请求语义：

| 输入 | 不受限调用方 | 受限 API Key |
|------|--------------|---------------|
| 同时省略两种身份字段 | 不按 Collection 过滤 | 自动使用 Key 的允许列表 |
| 显式空 `collectionKeys` 或 `collectionIds` | 返回 `400` | 返回 `400` |
| 非空 `collectionKeys` | 按大小写精确解析 key | 必须在允许列表中解析，否则 `403` |
| deprecated 非空 `collectionIds` | 检索一个或多个 Collection | 必须是允许列表的子集，否则 `403` |
| 同时提供 key 和 ID 集合 | 集合必须一致，忽略顺序 | 应用 ACL 后集合必须一致 |
| 同时给出 `documentIds` | 与 Collection 所属文档取交集 | 先应用 ACL，再取交集 |
| 非空范围解析后为零文档 | 返回空结果，不调用全库检索 | 返回空结果，不调用全库检索 |

数据模型和当前边界：

- `rag_collection.id` 继续作为内部 `BIGINT` 主键；
  `rag_collection.collection_key VARCHAR(128) COLLATE "C"` 是稳定外部身份。它全局唯一、
  不可变、区分大小写，软删除后仍保持占用。
- `rag_documents.collection_id` 是可空外键，所以一个文档最多属于一个 Collection；
  未归属文档不能通过 Collection 范围选中，但仍可出现在未限定 Collection 或显式
  `documentIds` 的检索中。数据库外键和检索 SQL 继续使用数字 ID。
- Collection 创建、导入和克隆必须由调用方提供 key。by-key CRUD、恢复、文档关联和导出
  使用 query parameter，不使用 path segment，因为合法 key 可以包含 URL 保留标点。
- API Key 管理对外使用 `allowedCollectionKeys`；V24 存储和运行时授权仍在
  `rag_api_key.allowed_collection_ids` 中保存内部 ID。
- 删除 Collection 会软删除集合并批量清空关联文档的 `collection_id`，不会删除文档或
  `rag_embeddings`；这些文档之后仍可能被全库检索命中。
- `rag_collection.embedding_model` 和 `dimensions` 尚未参与运行时模型路由；当前仍使用
  全局 EmbeddingModel，但每次写入和查询都会绑定到一个不可变 Embedding Profile。
- 向量和全文检索都要求活动 Profile、最新 content hash 对应的 `COMPLETED` 状态，以及启用的文档。
- Collection 先解析成完整 document ID 列表，再生成 `IN (...)`；这是可用的 MVP，
  但大集合应改为检索 SQL 直接 JOIN `rag_documents` 并按 `collection_id` 过滤。
- WebUI Chat 和 Search 当前单选 Collection 并发送其 key；后端 Chat/Search 契约支持多个 key。

源码锚点：

- [CollectionIdentityResolver](../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionIdentityResolver.java)
- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- [CollectionDocumentResolver](../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionDocumentResolver.java)
- [RagChatService](../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
- [HybridSearchAdvisor](../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java)
- [RagSearchController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java)
- [HybridRetrieverService](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)

回归测试锚点：

- [CollectionDocumentResolverTest](../spring-ai-rag-core/src/test/java/com/springairag/core/service/CollectionDocumentResolverTest.java)
- [RagSearchControllerTest](../spring-ai-rag-core/src/test/java/com/springairag/core/controller/RagSearchControllerTest.java)
- [HybridSearchAdvisorTest](../spring-ai-rag-core/src/test/java/com/springairag/core/advisor/HybridSearchAdvisorTest.java)
- [ApiKeyCollectionAccessTest](../spring-ai-rag-core/src/test/java/com/springairag/core/security/ApiKeyCollectionAccessTest.java)

### 4.3 文档嵌入流

```
POST /api/v1/rag/documents/{id}/embed
  │
  ▼
DocumentEmbedService
  │ 1. 读取 RagDocument.content
  ▼
HierarchicalTextChunker
  │ 2. 按 Markdown 结构分块
  ▼
TextCleaner
  │ 3. 清洗（去 HTML、规范化空白）
  ▼
EmbeddingBatchService
  │ 4. 按 batchSize 调用 EmbeddingModel
  │ 5. 校验全部结果后，在短事务内原子替换活动 Profile 的
  │    rag_embeddings.embedding_1024 VECTOR(1024) 行
  ▼
完成
```

活动 Profile 注册在 `rag_embedding_profiles`。按文档和 Profile 保存缓存及完成状态的
`rag_document_embedding_state`。模型调用在写事务外执行；短事务只替换该 Profile 的向量行。
批量调用失败或结果不完整时，旧的已完成向量保持可用。

### 4.4 JSON 结构化记录流程

```text
POST /api/v1/rag/json-records/upsert
  │
  ▼
JsonRecordService
  │ 保存 jsonbPayload + retrievalText
  │ 外部身份：collectionKey + externalId
  │ Resolver -> 内部 collectionId + json-record + externalId
  ▼
RagDocument.content = retrievalText
RagDocument.jsonbPayload = 业务 JSONB
  │
  ├──> DocumentEmbedService（只处理 retrievalText）
  │      ├──> 一个 record-level chunk
  │      └──> 活动 Profile embedding
  │
  └──> 版本快照（content + JSONB payload）

POST /api/v1/rag/json-records/search
  │
  ▼
HybridRetrieverService -> 排序后的 document IDs
  │
  ▼
从 rag_documents 批量补充 payload
  │
  ▼
排序后的 JSON record 响应
```

`jsonbPayload` 不会复制到 embedding metadata 或普通聊天上下文。JSON 与
`retrievalText` 的语义对应关系由调用者负责，服务只保存二者，不尝试互相校验。
仅更新 payload 会创建审计版本，但保留新鲜 embedding。这是一条明确的 JSONB 专用路径，
公开 upsert/search 请求优先使用 Collection key，响应在 deprecated 内部 ID 旁返回 key；
持久化、advisory lock 与检索候选仍使用内部 ID。
未来可以按相同的 retrieval-text 边界增加 `xmlPayload`，而不引入泛化的 `payload` 列。

---

## 5. 数据库设计

### 5.1 ER 关系

```
rag_collection (1) ──→ (N) rag_documents
rag_documents  (1) ──→ (N) rag_document_embedding_state
rag_documents  (1) ──→ (N) rag_embeddings
rag_embedding_profiles (1) ──→ (N) rag_document_embedding_state
rag_embedding_profiles (1) ──→ (N) rag_embeddings

rag_chat_history        # 对话历史（独立表）
rag_retrieval_logs      # 检索日志
rag_ab_experiments      # A/B 实验定义
rag_ab_results          # A/B 实验结果
rag_user_feedback       # 用户反馈
rag_alerts              # 告警记录
rag_slo_config          # SLO 配置
rag_retrieval_evaluations  # 检索质量评估
rag_audit_log           # 审计日志（集合操作）
```

### 5.2 关键表结构

| 表 | 关键列 | 说明 |
|---|--------|------|
| `rag_collection` | id, collection_key, name, description, embedding_model | 内部数字身份与稳定外部 Collection key |
| `rag_documents` | title, content, content_hash, collection_id, external_id, jsonb_payload | 文档元数据与结构化记录 payload |
| `rag_document_versions` | document_id, version_number, content_snapshot, jsonb_payload_snapshot | 文档与 JSONB 版本审计 |
| `rag_embedding_profiles` | profile_key, provider, model_name, dimensions, distance_metric | 不可变向量空间身份 |
| `rag_document_embedding_state` | document_id, embedding_profile_id, content_hash, status, chunk_count | Profile 级缓存与完成状态 |
| `rag_embeddings` | document_id, chunk_index, embedding_profile_id, embedding_1024 VECTOR(1024), content | Profile 级文本块与向量 |
| `rag_chat_history` | session_id, user_message, ai_response | 业务审计 |
| `rag_retrieval_logs` | query, strategy, result_count, latency_ms | 检索质量追踪 |

**索引策略**：
- `rag_collection.collection_key`：命名的全局唯一约束/B-Tree；可见 ASCII CHECK 和
  UPDATE trigger 共同保证字符契约与不可变性
- `rag_embeddings.embedding_1024`：Profile 专属 partial HNSW 索引（向量近邻搜索）
- `rag_documents.content_hash`：B-Tree（哈希去重）
- `rag_documents`：GIN 索引（全文检索，jiebacfg 中文分词）

### 5.3 全文检索配置

PostgreSQL 全文检索使用 `pg_jieba` 中文分词扩展：
- 搜索配置：`jiebacfg`（基于 jieba 分词器）
- 支持中文 tokenization + ranking (`ts_rank`)
- HybridRetrieverService 中向量检索和全文检索结果通过 RRF（Reciprocal Rank Fusion）融合

---

## 6. 配置体系

### 6.1 RagProperties 统一配置

`@ConfigurationProperties(prefix = "rag")` 统一管理所有业务配置：

```yaml
rag:
  retrieval:
    top-k: 10                    # 返回结果数
    min-score: 0.5               # 最低相似度
    hybrid-alpha: 0.7            # 向量/全文权重
    rerank-top-k: 5              # 重排后保留数
  chunk:
    max-size: 500                # 最大块大小（字符）
    overlap: 50                  # 重叠大小
  memory:
    max-messages: 20             # 对话记忆窗口
    window-size: 10              # 历史消息数
```

### 6.2 多环境配置

| 配置源 | 说明 |
|--------|------|
| `application.yml` | 默认配置 |
| `.env` | 环境变量（API Key、数据库密码） |
| `RagProperties` | 类型安全的业务配置 |
| `spring.ai.openai.*` / `spring.ai.anthropic.*` | LLM 配置 |
| `siliconflow.*` | 嵌入模型配置 |

---

## 7. 监控运维体系

### 7.1 指标采集（Micrometer）

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `rag.requests` | Counter | success/failure | 请求计数 |
| `rag.latency` | Timer | endpoint | 端到端延迟 |
| `rag.llm.tokens` | Counter | direction=in/out | Token 消耗 |
| `rag.retrieval.results` | Gauge | strategy | 检索结果数 |

### 7.2 告警机制

`AlertService` 基于 `rag_slo_config` 配置阈值：
- 延迟告警：P95 > 阈值触发 WARNING
- 错误率告警：错误率 > 阈值触发 CRITICAL
- 静默期：同一告警 60 分钟内不重复触发

### 7.3 A/B 实验

`AbTestService` 支持检索策略对比：
1. 定义实验（策略 A vs B + 流量分配比例）
2. 请求按比例分流执行不同策略
3. 收集结果（延迟、准确率、用户评分）
4. 统计分析效果差异

---

## 8. 关键设计决策

### 为什么用 Advisor 链而不是 Pipeline 模式？

**选择**：Spring AI `BaseAdvisor` 链式调用
**备选**：MaxKB4j 的 `PipelineManage + AbsStep` 模式
**理由**：
- Advisor 与 Spring AI 原生集成，无需额外抽象
- 通过 `Ordered` 接口控制执行顺序，声明式配置
- context attributes 机制足够传递中间结果
- 每个 Advisor 可独立测试、独立使用

### 为什么用双表对话记忆？

**选择**：`spring_ai_chat_memory` + `rag_chat_history` 共存
**理由**：
- Spring AI 自动管理的表只保留最近 N 条，给 LLM 上下文用
- 业务审计表保留完整历史，支持查询和分析
- 两个表职责分离，互不干扰

### 为什么嵌入模型与 Chat Model 配置分离？

**选择**：`siliconflow.*` 独立配置嵌入模型
**理由**：
- 嵌入模型和对话模型可能来自不同提供商
- 嵌入模型切换频率低（需重建所有向量），对话模型切换频繁
- 分离配置降低误操作风险

---

## 附录：技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 运行时 | Java | 21+ (LTS, 虚拟线程) |
| 框架 | Spring Boot | 3.5.x |
| AI 框架 | Spring AI | 1.1.x |
| 主数据库 | PostgreSQL + pgvector | 42.7.x / 0.7.x |
| ORM | Spring Data JPA | 3.3.x |
| 迁移工具 | Flyway | 10.x |
| 构建工具 | Maven | 3.9.x |
| 嵌入模型 | BGE-M3（via SiliconFlow） | 1024 维 |
| 分词 | pg_jieba | — |
| 缓存 | Caffeine | 3.x |
| 监控 | Micrometer + Actuator | — |
| API 文档 | SpringDoc OpenAPI | 2.x |
