# Collection 表命名规范与迁移规划

> 状态：Draft / 阶段 A 已实施，阶段 B 仍待批准并延期
> 编写日期：2026-08-15
> 代码基线：`main` @ `65eaecb`
> 规划范围：JPA 实体 `RagCollection` 对应的数据库表命名、健康检查缺陷和可延期的 Flyway 迁移
> 重要说明：本文不代表当前代码已经完成物理表名重构。阶段 A 仅修复健康检查对当前真实表名的引用；阶段 B 的 `rag_collection` → `rag_collections` 迁移仍未实施。在获得明确批准前，不实施阶段 B。

## 1. 执行摘要

当前项目同时存在两种命名层次：

| 层次 | 当前命名 | 结论 |
|---|---|---|
| Java/JPA 实体 | `RagCollection` | 保留单数，符合领域实体命名习惯 |
| 数据库表 | `rag_collection` | 当前正式 schema，属于项目中的单数例外 |
| 同类数据库表 | `rag_documents`、`rag_embeddings`、`rag_alerts` 等 | 大多数使用复数 |
| 健康检查代码 | `rag_collection` | 阶段 A 已修复并与当前正式 schema 一致 |

长期命名目标应为：

```text
RagCollection       // Java/JPA 实体，单数
rag_collections     // PostgreSQL 表，复数
collection_id       // 外键列，单数
```

但这项表名重构目前**不具有紧迫性**，也不是修复健康检查错误的唯一方式。因此采用分阶段策略：

1. **短期修复，单独处理运行时缺陷**
   已将健康检查及其测试对当前 schema 的引用统一为 `rag_collection`。这一步不需要数据迁移，已作为低风险维护修复完成。

2. **长期迁移，延期到专门的数据库发布窗口**
   在确定可以接受数据库表名变更、应用与数据库需要协调发布后，通过新的 Flyway 迁移将 `rag_collection` 重命名为 `rag_collections`，并同步更新实体、原生 SQL、约束/索引/trigger 名称、测试、脚本和文档。

3. **本次不顺带重命名所有历史单数表**
   `rag_api_key`、`rag_audit_log`、`rag_client_error`、`rag_chat_history` 等命名是否调整，另行建立数据库命名规范清单和独立规划。不要为了一个集合表问题扩大为全库破坏性迁移。

这份文档的核心决策是：

> **目标命名采用 `rag_collections`，但当前只记录方案，不立即迁移；健康检查缺陷和物理表迁移必须拆开管理。**

当前实施状态：

- 阶段 A 已完成最小代码修复：`ComponentHealthService`、健康检查单元测试和 readiness fixture 统一使用当前真实表 `rag_collection`。
- 阶段 A 已通过相关单元测试、健康控制器测试、`mvn clean compile test-compile`、文档门禁和 `git diff --check`。
- 最终全 reactor `mvn clean compile test-compile`、健康/就绪相关测试和项目文档门禁均已通过。
- 阶段 B 未实施：没有新增 Flyway 迁移，没有修改 `RagCollection.@Table`，没有重命名任何数据库对象。

## 2. 当前状态与代码证据

### 2.1 工作区与版本基线

本规划基于 `main` 分支 `65eaecb`。开始编写本规划时，工作区已有一个未提交的文件变更：

```text
docs/drafts/2026-08-15_COLLECTION_KEY_IMPLEMENTATION_PROGRESS.md
```

该变更不属于本规划，实施过程中不得回滚、覆盖或 stash。最终交付时，用户已明确授权
将工作区中的并行成果一并提交；同一 commit 不改变各项修改原本的任务归属。

### 2.2 JPA 实体命名

[`RagCollection.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java) 的实体类名为 `RagCollection`，并明确指定：

```java
@Table(name = "rag_collection", ...)
```

实体类保持单数是正确的。Java 类型表示“一条 Collection 记录”，不表示数据库中的整张记录集合。将实体改成 `RagCollections` 反而会违背 Java 领域模型的常见约定，也会扩大无必要的类名、Repository、Service、Controller 和 API 文档变更。

### 2.3 当前数据库表命名分布

当前 JPA/迁移涉及的表名大致如下：

```text
fs_files
rag_ab_experiments
rag_ab_results
rag_alerts
rag_api_key
rag_audit_log
rag_chat_history
rag_client_error
rag_collection
rag_document_embedding_state
rag_document_versions
rag_documents
rag_embedding_profiles
rag_embeddings
rag_retrieval_evaluations
rag_retrieval_logs
rag_silence_schedules
rag_slo_configs
rag_user_feedback
```

可数资源表大多使用复数，例如：

- `rag_documents`
- `rag_embeddings`
- `rag_alerts`
- `rag_ab_experiments`
- `rag_ab_results`
- `rag_document_versions`
- `rag_embedding_profiles`
- `rag_retrieval_evaluations`
- `rag_retrieval_logs`
- `rag_silence_schedules`
- `rag_slo_configs`

`rag_collection` 是可数资源表中的命名例外。另一方面，`rag_chat_history`、`rag_user_feedback` 等不可数或概念性数据集，不应简单按词尾机械复数化。

### 2.4 正式 schema 使用单数表

[`V1__init_rag_schema.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V1__init_rag_schema.sql) 首次创建：

```sql
CREATE TABLE IF NOT EXISTS rag_collection (...)
```

同一迁移中，文档表通过以下外键引用该表：

```sql
collection_id BIGINT REFERENCES rag_collection(id)
```

后续迁移继续以 `rag_collection` 为事实来源：

- [`V13__add_performance_indexes.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V13__add_performance_indexes.sql)：集合名称索引。
- [`V17__add_optimistic_locking_version.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V17__add_optimistic_locking_version.sql)：`version` 字段和索引。
- [`V21__add_collection_soft_delete.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V21__add_collection_soft_delete.sql)：软删除字段和索引。
- [`V27__add_collection_key_expand.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V27__add_collection_key_expand.sql)：`collection_key`、唯一约束、不可变函数和 trigger。
- [`V28__require_collection_key.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V28__require_collection_key.sql)：Collection key 的最终非空约束。

因此，`rag_collection` 不是偶然的测试夹具名称，而是已经写入正式数据库迁移历史的真实表名。

### 2.5 修复前错误的实际影响

[`ComponentHealthService.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/metrics/ComponentHealthService.java) 修复前配置：

```java
String[] coreTables = {"rag_documents", "rag_embeddings", "rag_collections"};
```

这会使修复前的 `checkTables()` 执行：

```sql
SELECT COUNT(*) FROM rag_collections
```

但正式 schema 中没有 `rag_collections`，所以修复前的健康检查会把集合表标记为 `missing`，并将表组件标记为 `DEGRADED`。

[`ComponentHealthServiceTest.java`](../../spring-ai-rag-core/src/test/java/com/springairag/core/metrics/ComponentHealthServiceTest.java) 修复前同样 mock 了 `rag_collections`，因此该单元测试没有验证真实 schema，而是把错误 SQL 固化成了测试预期。`RagReadinessIndicatorTest` 中的手工 Map fixture 也已在阶段 A 同步修正。

这是一个健康检查实现与 schema 不一致的问题，不是数据迁移失败，也不是 Collection 业务数据损坏。

### 2.6 原生 SQL、约束和命名依赖

当前代码和迁移在以下位置直接或间接依赖 `rag_collection`：

- JPA `@Table(name = "rag_collection")`。
- `rag_documents.collection_id` 外键。
- Collection Key 唯一约束 `uk_rag_collection_collection_key`。
- Collection Key ASCII 约束 `ck_rag_collection_collection_key_ascii`。
- Collection Key 不可变函数 `fn_rag_collection_key_immutable()`。
- Collection Key 不可变 trigger `trg_rag_collection_key_immutable`。
- `idx_rag_col_enabled`、`idx_rag_col_name`、`idx_rag_collection_version`、`idx_rag_collection_deleted` 等索引。
- `RagCollectionService` 对唯一约束名称的异常转换。
- Collection Key、JSONB、Embedding Profile 相关 PostgreSQL 集成测试。
- 架构、配置、项目上下文和规划文档中的 schema 示例。
- `scripts/check-entity-migration-sync.sh` 的表清单。

因此，不能只把 `@Table` 改成 `rag_collections`，也不能只新增一个表名迁移而不修改应用代码。

### 2.7 Flyway 与 Hibernate 边界

[`application.yml`](../../spring-ai-rag-core/src/main/resources/application.yml) 配置：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
```

数据库 schema 的真相来源是 Flyway；Hibernate 只在启动时校验实体映射。因此：

- 不修改已执行的 `V1`、`V13`、`V17`、`V21`、`V27` 或 `V28`。
- 不把表名迁移藏在实体注解中。
- 使用新的版本迁移执行物理表重命名。
- 迁移后必须用 `mvn clean compile test-compile` 和 PostgreSQL 集成测试验证 `ddl-auto=validate`。

## 3. 命名规范决策

### 3.1 目标规则

本规划建议项目后续遵守以下规则：

| 对象 | 规范 | 示例 |
|---|---|---|
| Java Entity | 单数、PascalCase | `RagCollection` |
| Java Repository/Service | 单数领域名 | `RagCollectionRepository` |
| PostgreSQL 表 | 可数资源使用复数、snake_case | `rag_collections` |
| PostgreSQL 列 | 单数、snake_case | `collection_id` |
| 外部 API 资源 | 复数 | `/collections` |
| 索引/约束 | 包含规范化后的表名 | `uk_rag_collections_collection_key` |

### 3.2 不机械复数化

该规则不是“所有表名都强制加 `s`”：

- `rag_chat_history` 表示不可数的历史集合，可以保留。
- `rag_user_feedback` 表示不可数反馈数据，可以保留。
- `rag_audit_log` 是否改为 `rag_audit_logs` 需要单独评估。
- `rag_api_key` 是可数资源，但迁移它属于 API Key 安全/运维范围，不在本次 Collection 表迁移中处理。
- `rag_client_error` 是否改为 `rag_client_errors` 需要单独盘点引用和监控查询。

这样可以避免为了追求表名表面一致而一次性重命名大量已有表。

### 3.3 实体不改名

实施时保留以下 Java 名称：

```text
RagCollection
RagCollectionRepository
RagCollectionService
RagCollectionController
CollectionIdentityResolver
```

只修改它们对物理表、SQL 和约束名称的引用。对外 HTTP 路径当前已经是 `/collections`，无需改变。

## 4. 分阶段实施策略

### 阶段 A：低风险健康检查修复（已实施）

**目标**：修复当前运行时错误，不改变数据库 schema。

已实施内容：

1. 将 `ComponentHealthService.coreTables` 中的 `rag_collections` 改为当前真实表 `rag_collection`。
2. 将 `ComponentHealthServiceTest` 的 SQL mock 和断言同步为 `rag_collection`。
3. 审查 `RagReadinessIndicatorTest` 中的详情 fixture。

后续增强项，不阻断本次最小运行时缺陷修复：

1. 增加 PostgreSQL 集成测试，确认健康检查在真实 schema 上返回：
   - 表组件不是因为 Collection 表名错误而 `DEGRADED`。
   - 详情中包含 `rag_collection` 的数量。
   - 不会访问不存在的 `rag_collections`。
2. 若未来正式健康检查文档展示具体表名，同步使用当前真实表名。

阶段 A 不创建 Flyway 迁移，不修改任何业务数据，不改变 Collection API。本轮已完成表名代码和测试修复；PostgreSQL 专用集成测试作为后续增强项保留。

### 阶段 B：延期的物理表重命名

**目标**：在明确的数据库发布窗口中，把可数资源表的 Collection 物理名称规范化为 `rag_collections`。

实施前提：

- 阶段 A 已完成并验证。
- 已决定接受旧版本应用不能与迁移后的 schema 混合运行。
- 已确定部署窗口、数据库备份和回滚负责人。
- 已确认没有长时间运行的旧 worker、脚本或外部 SQL 仍只使用 `rag_collection`。
- 已获得本次代码实施批准。

## 5. 阶段 B 的目标 schema

### 5.1 核心对象

目标为：

```text
rag_collections
```

实体映射为：

```java
@Table(name = "rag_collections", ...)
```

内部关系仍然保持：

```text
rag_documents.collection_id BIGINT
    -> rag_collections.id BIGINT
```

不改变：

- Collection 的 `BIGINT` 主键。
- `rag_documents.collection_id` 类型和语义。
- Collection Key 的值、唯一性、不变性和软删除占用规则。
- API Key ACL 当前使用内部 Collection ID 的存储方式。
- 向量、全文检索、Embedding Profile 或 JSONB payload 数据。

### 5.2 关联对象命名

为使 schema 命名自洽，建议在同一个迁移中重命名 Collection 专属的数据库对象：

| 当前名称 | 目标名称 |
|---|---|
| `rag_collection_id_seq` | `rag_collections_id_seq` |
| `idx_rag_col_enabled` | `idx_rag_collections_enabled` |
| `idx_rag_col_name` | `idx_rag_collections_name` |
| `idx_rag_collection_version` | `idx_rag_collections_version` |
| `idx_rag_collection_deleted` | `idx_rag_collections_deleted` |
| `uk_rag_collection_collection_key` | `uk_rag_collections_collection_key` |
| `ck_rag_collection_collection_key_ascii` | `ck_rag_collections_collection_key_ascii` |
| `fn_rag_collection_key_immutable()` | `fn_rag_collections_key_immutable()` |
| `trg_rag_collection_key_immutable` | `trg_rag_collections_key_immutable` |

这些名称必须以实施时目标数据库中的实际对象为准。迁移不能假定所有历史数据库都完整执行了同一组可选索引或对象；需要先建立 PostgreSQL 集成夹具覆盖标准 schema。

如果实际部署需要最大限度保留数据库对象名称以兼容运维脚本，可以只重命名表而保留约束/索引名称；但这会留下 `rag_collection` 命名痕迹。默认建议采用上表的完整重命名，并在迁移说明中列出破坏性边界。

### 5.3 健康检查输出

在阶段 A 中，健康检查详情暂时使用真实旧表名：

```json
{
  "tables": {
    "status": "UP",
    "rag_collection": 10
  }
}
```

阶段 B 完成后，健康检查详情改为新表名：

```json
{
  "tables": {
    "status": "UP",
    "rag_collections": 10
  }
}
```

健康检查详情中的表名是动态诊断信息，不是 Collection 业务 API 的稳定身份字段。默认不长期同时返回两个名称，否则会使监控消费者误以为两个表都必须存在。

如果实施前确认已有外部监控依赖旧详情键，应在迁移前增加监控适配或提供明确的兼容窗口；不得在数据库中创建同名兼容 view 来掩盖应用代码未完全升级。

## 6. Flyway 迁移设计

### 6.1 版本与文件

按实施时实际 Flyway 版本选择下一个可用版本。当前规划预计使用：

```text
spring-ai-rag-core/src/main/resources/db/migration/V30__rename_rag_collection_to_rag_collections.sql
```

不得修改已有 `V1` 至 `V29`，也不得重复占用已经存在的版本号。

### 6.2 迁移性质

该迁移是 PostgreSQL 元数据重命名：

- 不复制行。
- 不重新生成 embedding。
- 不改变 Collection、Document、API Key ACL 或 JSONB 数据。
- 不改变主键值。
- 不改变外键列类型。
- 不创建第二份 Collection 数据。

表名重命名通常比新建表再复制数据风险低，但仍然是应用与数据库之间的破坏性契约变更。

### 6.3 迁移前检查

在执行生产迁移前，必须记录并确认：

1. `rag_collection` 存在。
2. `rag_collections` 不存在。
3. `rag_collection` 的行数、主键、列、默认值和约束符合当前应用预期。
4. `rag_documents.collection_id` 外键确实指向 Collection 表。
5. `collection_key` 无空值、无重复值，且软删除行仍保留。
6. Collection 专属索引、约束、序列、函数和 trigger 的实际名称。
7. 没有未完成的旧版本服务、脚本或后台任务继续写入旧表名。
8. 已完成 PostgreSQL 备份，并知道恢复验证结果。

如果旧表和新表同时存在，迁移必须失败并阻止应用启动，不能猜测哪一张表是事实来源。

### 6.4 迁移动作

实施时按实际对象存在性执行以下顺序：

1. 重命名表 `rag_collection` 为 `rag_collections`。
2. 重命名自增序列，并验证 `id` 默认值仍然使用正确序列。
3. 重命名 Collection 专属索引。
4. 重命名 Collection Key 唯一约束和 CHECK 约束。
5. 重命名 Collection Key 不可变函数和 trigger。
6. 更新应用层对命名约束的异常识别。
7. 在迁移结束前执行目录级验证：
   - 新表存在。
   - 旧表不存在。
   - 行数不变。
   - 主键/外键完整。
   - 唯一约束、CHECK 约束和不可变 trigger 仍有效。
   - 序列可以为新 Collection 生成不冲突的 ID。

迁移脚本应包含明确的前置状态保护。以下情况不能静默当作成功：

- 旧表不存在且新表也不存在。
- 旧表和新表同时存在。
- 目标对象已存在但结构不匹配。
- 迁移后对象数量或约束状态不符合预期。

### 6.5 事务和锁

表重命名和对象重命名属于短事务 DDL，但仍需获得相关锁。迁移前应评估：

- 当前连接池和长事务。
- 后台 embedding、导入、导出、Collection CRUD 任务。
- 数据库连接是否可能在迁移期间持续持有 Collection 表锁。

默认建议在受控维护窗口执行，不把“通常很快”作为免除停写协调的理由。

## 7. 应用代码实施清单

获得批准后，按以下顺序实施：

### 7.1 阶段 A 文件

- `spring-ai-rag-core/src/main/java/com/springairag/core/metrics/ComponentHealthService.java`
- `spring-ai-rag-core/src/test/java/com/springairag/core/metrics/ComponentHealthServiceTest.java`
- `spring-ai-rag-core/src/test/java/com/springairag/core/metrics/RagReadinessIndicatorTest.java`
- 新增或扩展 PostgreSQL 健康检查集成测试。
- 必要时更新 `docs/testing-guide*.md` 和故障排查文档。

### 7.2 阶段 B 文件

- `spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/RagCollectionService.java`
- 所有直接使用 `rag_collection` 的 Java 原生 SQL。
- `spring-ai-rag-core/src/main/resources/db/migration/V30__rename_rag_collection_to_rag_collections.sql`
- `spring-ai-rag-core/src/test/java/com/springairag/core/integration/`
- `spring-ai-rag-core/src/test/java/com/springairag/core/service/`
- `scripts/check-entity-migration-sync.sh`
- Collection Key、JSONB、Embedding Profile 相关集成夹具中的 SQL。
- `docs/architecture.md` / `docs/architecture-zh-CN.md`
- `docs/configuration.md` / `docs/configuration-zh-CN.md`
- `docs/project-context.md` / `docs/project-context-zh-CN.md`
- 必要时更新 `docs/rest-api*.md` 中健康检查详情示例。

实施时必须用 `git grep` 重新扫描，不仅搜索 `rag_collection`，还要搜索：

```text
rag_collections
uk_rag_collection
ck_rag_collection
idx_rag_collection
trg_rag_collection
fn_rag_collection
rag_collection_id_seq
```

不能只修改 `@Table` 和健康检查，而遗漏迁移脚本、异常映射或测试夹具。

## 8. 测试与验收规划

### 8.1 阶段 A 验收

阶段 A 当前验收：

1. `ComponentHealthServiceTest` 全部通过。
2. 单元测试中的健康检查详情包含 `rag_collection` 计数。
3. 生产代码和测试不再执行 `SELECT COUNT(*) FROM rag_collections`。
4. `mvn clean compile test-compile` 通过。
5. 相关 Core 测试和健康检查 HTTP 集成测试通过。

PostgreSQL 集成测试直接调用 `checkTables()` 属于推荐增强项；它应在阶段 B 前完成，但不阻断阶段 A 的最小缺陷修复交付。

### 8.2 阶段 B 迁移验收

必须覆盖以下端到端场景：

#### Schema 保真

- 迁移前后 Collection 行数相同。
- 所有 Collection ID 不变。
- 所有 Collection Key 不变。
- `rag_documents.collection_id` 仍能正确关联。
- API Key 的 Collection ACL 仍能解析到原 Collection。
- Collection 软删除状态、时间、版本和 metadata 不变。
- Embedding、JSONB payload、文档版本和检索日志不受影响。

#### 约束与并发

- 新表上的唯一约束仍阻止重复 Collection Key。
- `collection_key` 仍不可修改。
- `ABC` 与 `abc` 的大小写语义不变。
- 软删除 Collection 的 key 仍不可复用。
- 新建 Collection 能正确使用重命名后的序列生成 ID。
- 并发创建同一 key 的结果仍为一个成功、其余返回 409。

#### 应用启动与 API

- Flyway 从 V1 到最新版本可在空数据库执行。
- 从已有 V29 数据库升级可成功执行 V30。
- Hibernate `ddl-auto=validate` 通过。
- Collection CRUD、文档关联、导入导出、克隆和恢复通过。
- Collection Key ACL 读写路径通过。
- JSONB structured records 的 Collection 解析和检索通过。
- `/rag/health` 与 `/rag/health/components` 不再报告缺失表。
- OpenAPI 合约测试通过。

#### 构建和项目门禁

后端至少执行：

```bash
mvn clean compile test-compile
mvn test
```

并执行本次修改覆盖的 PostgreSQL 集成测试和 HTTP E2E。若 WebUI、正式文档或脚本未发生行为变化，不因本次表名迁移额外扩大前端验证范围；但包含表名显示的 WebUI/运维页面必须补充相应验证。

项目文档门禁：

```bash
./scripts/verify-project-docs.sh
git diff --check
```

## 9. 部署、回滚与兼容性

### 9.1 推荐部署方式

由于旧版本应用查询 `rag_collection`，新版本应用查询 `rag_collections`，两者不能在同一数据库上长期混合运行。推荐：

1. 先在预生产数据库执行完整升级演练。
2. 备份生产数据库并验证备份可恢复。
3. 停止会访问 Collection 表的旧应用实例、worker 和定时任务。
4. 执行 V30。
5. 启动包含新表名引用的新应用版本。
6. 运行健康检查、Collection API、ACL 和检索冒烟测试。
7. 恢复流量。

### 9.2 不推荐的兼容 view 方案

不建议将旧表重命名后再创建名为 `rag_collection` 的兼容 view，原因是：

- JPA 读写需要额外的 `INSTEAD OF` trigger。
- view 可能掩盖部分代码仍使用旧名称的问题。
- 运维人员可能误以为存在两份独立表。
- 后续 Flyway、Hibernate validate 和对象权限更难诊断。

如果未来必须支持零停机滚动升级，应单独设计 expand/contract 方案，不在本规划中临时引入 view 兼容层。

### 9.3 回滚

Flyway versioned migration 没有自动 down migration。回滚必须是经过演练的运维操作：

1. 停止新版本应用和写入任务。
2. 确认 V30 之后没有新版本专属 schema 变更依赖目标表名。
3. 按备份或反向 DDL 将对象恢复为旧名称。
4. 恢复旧版本应用。
5. 执行完整健康检查和 Collection/ACL 冒烟测试。

不能通过删除 `flyway_schema_history` 记录、修改已执行迁移文件或运行 `flyway repair` 伪造回滚。

## 10. 风险与控制措施

| 风险 | 影响 | 控制措施 |
|---|---|---|
| 旧应用仍查询旧表 | 启动失败或运行时 500 | 维护窗口部署；禁止混合版本 |
| 漏改原生 SQL | 特定流程失败 | 实施前后 `git grep` 多模式扫描 |
| 漏改约束名称映射 | 重复 key 被错误返回 500 | 对并发重复创建做 PostgreSQL E2E |
| 外部监控依赖健康详情键 | 监控告警失真 | 迁移前盘点消费者，明确详情键变更 |
| 表锁与长事务冲突 | 发布超时 | 预生产演练，维护窗口停写 |
| 直接编辑旧 Flyway | 新旧数据库分叉 | 只新增 V30 或实际下一个版本 |
| 只重命名表、不重命名关联对象 | 命名继续混乱 | 默认完整重命名，或明确记录保留旧对象名 |
| 为所有历史表名顺带迁移 | 变更面失控 | 本次只处理 Collection，其他表另立规划 |
| 误以为迁移会复制或重建向量 | 不必要停机和成本 | 明确这是元数据 rename，不涉及 embedding |
| 其他开发者工作区变更被覆盖 | 丢失协作成果 | 不使用 `stash`、reset、checkout，提交前审阅精确 diff |

## 11. 文档同步计划

阶段 B 的规划本身只新增本文，不修改正式架构文档，因为物理表重命名尚未实施。

阶段 A 已检查正式文档：现有架构、配置和项目上下文均已使用当前真实表名
`rag_collection`，且没有暴露错误健康检查详情的正式示例，因此无需新增平行说明。

阶段 B 实施后，必须同步中英文成对文档：

- `docs/architecture.md`
- `docs/architecture-zh-CN.md`
- `docs/configuration.md`
- `docs/configuration-zh-CN.md`
- `docs/project-context.md`
- `docs/project-context-zh-CN.md`
- 必要时 `docs/rest-api.md`、`docs/rest-api-zh-CN.md`
- `docs/index.md` 与 `docs/index-zh-CN.md` 仅在新增导航入口时修改

阶段 B 实施后的正式文档必须区分：

- `RagCollection` 是 Java 实体。
- `rag_collections` 是迁移后的当前数据库表。
- `collectionKey` 是外部业务身份。
- `id`/`collection_id` 仍是内部数字关系。

## 12. 明确的实施边界

### 已完成内容

- 阶段 A 健康检查 bugfix 已随当前工作区完成并验证。

### 需要另行批准的内容

需要明确批准后，才可以执行：

- 阶段 B 表重命名及相关代码、迁移、测试和文档修改。

### 本规划不授权的内容

本文不授权：

- 除已完成的阶段 A 修复外，修改 `RagCollection` 映射或其他 Java 业务代码。
- 修改 `V1` 至 `V29`。
- 新增 `V30`。
- 直接连接用户数据库执行 DDL。
- 重命名其他数据库表。
- 修改 API Key、Collection Key、Embedding Profile 或 JSONB 业务模型。
- 删除旧 API 字段或数字 ID 兼容路径。

## 13. 实施完成判定

只有同时满足以下条件，阶段 B 才能被标记为完成：

- [ ] 规划获得明确批准。
- [x] 阶段 A 健康检查错误已修复并验证。
- [ ] 新 Flyway 迁移只使用实施时可用的下一个版本号。
- [ ] 空数据库和已有 V29 数据库升级均通过。
- [ ] `rag_collection` 已安全重命名为 `rag_collections`。
- [ ] 行数、主键、外键、序列、约束、trigger 和索引验证通过。
- [ ] 所有原生 SQL、异常映射和测试 fixture 已更新。
- [ ] Collection、Document、ACL、JSONB 和检索 E2E 通过。
- [ ] `mvn clean compile test-compile` 和全量相关测试通过。
- [ ] 项目文档门禁和 `git diff --check` 通过。
- [ ] 迁移、部署、回滚记录已写入进度文档。
- [ ] 工作区中其他开发者的修改未被丢弃或覆盖。

在这些条件满足前，不能对用户宣称“表名重构已完成”。

## 14. 参考入口

- [项目文档 Skill](../../.agents/skills/project-docs/SKILL.md)
- [Collection Key 实施规划](2026-08-15_COLLECTION_KEY_IMPLEMENTATION_PLAN.md)
- [Collection Key 实施进度](2026-08-15_COLLECTION_KEY_IMPLEMENTATION_PROGRESS.md)
- [Embedding Profile / Vector 迁移规划](2026-08-15_EMBEDDING_PROFILE_VECTOR_MIGRATION_PLAN.md)
- [JSONB Structured Records 规划](2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md)
- [`RagCollection` 实体](../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java)
- [`ComponentHealthService`](../../spring-ai-rag-core/src/main/java/com/springairag/core/metrics/ComponentHealthService.java)
- [`V1__init_rag_schema.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V1__init_rag_schema.sql)
- [`application.yml`](../../spring-ai-rag-core/src/main/resources/application.yml)
