# Collection Key 实施规划

> 状态：Draft / 待实施
> 编写日期：2026-08-15
> 代码基线：`main` @ `2fb37dedc5cd50c30ad76e62fafa525ab4cd36ea`
> 目标：为 Collection 增加调用方提供的、全局唯一、稳定且不可变的 128 ASCII 字符以内业务键，同时保留 `Long id` 作为内部数据库主键
> 重要说明：本文描述目标设计，不代表当前代码已经支持 `collectionKey`

## 1. 执行摘要

本改进采用双标识模型：

| 标识 | 类型 | 定位 | 是否对外推荐 | 是否可变 |
|---|---|---|---|---|
| `id` | Java `Long` / PostgreSQL `BIGINT` | 内部数据库主键、表间关系键、现有 ACL 存储键 | 否，仅兼容旧 API | 否 |
| `collectionKey` | Java `String` / PostgreSQL `VARCHAR(128)` | 调用方定义的全局唯一业务标识 | 是 | 否 |

核心结论如下：

1. 不替换 `rag_collection.id`，也不迁移 `rag_documents.collection_id` 等内部关系。
2. 在 `rag_collection` 增加最终为 `VARCHAR(128) COLLATE "C" NOT NULL` 的 `collection_key`；迁移 expand 阶段暂时允许 NULL。
3. 通过命名唯一约束保证全局唯一；软删除不释放 key，已删除 Collection 的 key 也不得复用。
4. key 精确区分大小写，不 trim、不转小写、不截断、不做 Unicode 归一化。
5. 接受 1 至 128 个可见非空白 ASCII 字符，即 Unicode `U+0021` 至 `U+007E`。
6. 新建、导入和克隆必须由调用方显式提供 key。调用方可以使用 UUID，也可以拼接有业务含义的多个部分。
7. WebUI 提供显式“生成 UUID”控件，但不得静默生成或在服务端代生成。
8. 因为合法 key 可以包含 `/`、`?`、`#`、`%`、`+` 等字符，新增 key 定位接口使用请求体或查询参数，不把任意 key 放入 URL path segment。
9. 旧 `collectionId` / `collectionIds` 输入和数字 ID 路由暂时保留并标记 deprecated；本次不删除旧契约。
10. 外部请求优先使用 `collectionKey` / `collectionKeys`，进入核心业务前统一解析为内部 `Long id`。
11. API Key 的数据库 ACL 暂时继续保存内部 ID；管理 API 和 WebUI 增加 `allowedCollectionKeys`，在创建或更新权限时解析成 ID。
12. 数据库唯一约束是并发正确性的最终保障。应用层可预检查以改善错误信息，但必须捕获特定约束冲突并返回 HTTP 409。
13. 数据库迁移采用 expand/contract 两阶段：过渡阶段不依赖自动生成业务 key 的 trigger；所有旧写入实例退出后才设置 `NOT NULL`。

本方案避免把字符串业务键传播到所有数据库外键和检索 SQL，降低迁移风险；同时让外部集成不再依赖数据库自增 ID。

## 2. 当前状态与代码证据

### 2.1 当前身份模型

- [`RagCollection`](../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java) 使用 `Long id`、`@Id` 和 `GenerationType.IDENTITY`。
- [`V1__init_rag_schema.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V1__init_rag_schema.sql) 将 `rag_collection.id` 定义为 `BIGSERIAL PRIMARY KEY`。
- `rag_documents.collection_id` 是 `BIGINT`，内部查询、统计、解绑、克隆和检索均按 `Long` 处理。
- 当前 17 个 JPA 实体中，16 个使用 `Long` 主键；例外是 [`FsFile`](../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/FsFile.java) 使用路径字符串作为主键。Collection 没有必要偏离项目的内部数值主键模式。
- [`application.yml`](../../spring-ai-rag-core/src/main/resources/application.yml) 配置 `spring.jpa.hibernate.ddl-auto=validate`，因此实体变更必须配套 Flyway 迁移，不能依赖 Hibernate 自动改表。

### 2.2 当前 Collection API 与服务边界

[`RagCollectionController`](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagCollectionController.java) 当前提供：

- `POST /rag/collections`
- `GET /rag/collections/{id}`
- `GET /rag/collections`
- `PUT /rag/collections/{id}`
- `DELETE /rag/collections/{id}`
- `POST /rag/collections/{id}/restore`
- `POST /rag/collections/{id}/clone`
- `GET /rag/collections/{id}/documents`
- `POST /rag/collections/{id}/documents`
- `GET /rag/collections/{id}/export`
- `POST /rag/collections/import`

控制器仍承担部分创建、更新、导入和导出编排；[`RagCollectionService`](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/RagCollectionService.java) 负责删除、恢复和克隆。实施时应把需要一致事务、唯一冲突转换和 key 解析的写操作收敛到 service 层，避免同一规则在控制器中重复。

### 2.3 数字 Collection ID 的影响面

当前数字 ID 不只出现在 Collection CRUD，还贯穿以下入口：

- [`ChatRequest`](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java) 的 `collectionIds`
- [`SearchRequest`](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/SearchRequest.java) 的 `collectionIds`
- [`DocumentRequest`](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentRequest.java) 的 `collectionId`
- 批量文档请求、文件上传和 PDF-to-RAG 的 `collectionId`
- [`CollectionDocumentResolver`](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionDocumentResolver.java)
- [`ApiKeyCollectionAccess`](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- Collection 恢复、克隆、导入、导出和文档挂接
- WebUI 的 Collection、Documents、Chat、Search、Files 和 API Keys 页面
- 普通 E2E、真实 LLM E2E、WebUI E2E 和 k6 脚本

因此只给实体加一个字段是不完整的。实施必须同时覆盖输入解析、输出映射、ACL、前端类型、脚本和契约测试。

### 2.4 当前 API Key ACL

- [`V24__api_key_allowed_collections.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V24__api_key_allowed_collections.sql) 增加 `rag_api_key.allowed_collection_ids VARCHAR(2048)`。
- [`RagApiKey`](../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java) 将允许访问的 Collection 保存为逗号分隔的内部 ID。
- [`ApiKeyCollectionAccess`](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java) 以 `Long` 集合执行子集检查、默认 scope 收窄和写入权限检查。

本次不改变 ACL 的底层存储格式，以免同时引入权限数据迁移和请求身份迁移。对外改用 key，对内解析成 ID 后继续复用现有授权模型。

### 2.5 当前错误处理

- [`ErrorCode`](../../spring-ai-rag-api/src/main/java/com/springairag/api/enums/ErrorCode.java) 已有 `VALIDATION_FAILED`、`BAD_REQUEST`、`FORBIDDEN`、`COLLECTION_NOT_FOUND` 和 `DUPLICATE_RESOURCE`。
- [`RagException`](../../spring-ai-rag-core/src/main/java/com/springairag/core/exception/RagException.java) 可携带统一错误码。
- [`GlobalExceptionHandler`](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/GlobalExceptionHandler.java) 会把一般数据库异常映射为 500。

如果只增加数据库唯一约束，不做特定异常转换，并发创建同一 key 时会错误地返回 500。因此必须识别命名约束并转换为 `DUPLICATE_RESOURCE` / HTTP 409。

### 2.6 当前并行工作

工作区存在未提交的 V25、V26 迁移和 embedding/retrieval 改动：

- `V25__embedding_profile_expand.sql`
- `V26__remove_unused_rag_vector_store.sql`
- [`2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md`](2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md)

Collection Key 迁移必须使用实施时的下一个可用 Flyway 版本；按当前工作区预期是 V27，不得占用或改写 V25/V26。JSONB 检索规划仍以 `collectionId` 描述部分外部身份，实施两个方案时必须统一为“外部优先 key、内部继续 ID”。

## 3. 目标与非目标

### 3.1 目标

- 为外部调用提供稳定、调用方可控制、有业务含义的 Collection 唯一键。
- 保证 key 在数据库层全局唯一，并正确处理并发竞争。
- 保证 key 创建后不可修改。
- 保持内部数据库关系和检索执行仍使用高效、成熟的 `Long id`。
- 让所有主要外部入口能够接受 `collectionKey` 或 `collectionKeys`。
- 保留数字 ID 契约作为兼容层，不在本次直接删除。
- 保持 API Key 限权请求 fail closed，且不因 key 查询暴露未授权 Collection 是否存在。
- 为已有数据和滚动部署提供可执行迁移路径，并明确旧二进制只能在 contract 阶段前回滚。
- 同步 WebUI、自动化测试、运维脚本和正式中英文文档。

### 3.2 非目标

- 不把其他实体的 `Long id` 全部替换为字符串。
- 不把 `rag_documents.collection_id` 或其他外键改为 `VARCHAR`。
- 不在本次把 API Key ACL 数据库列改成 key 数组或关系表。
- 不引入租户内唯一；本次定义为整个 `rag_collection` 表全局唯一。
- 不允许修改已有 key，也不提供 rename API。
- 不允许删除后复用 key。
- 业务 API 不由服务端自动生成新的业务 key；迁移阶段仅为已有历史行补充可追溯的 legacy 身份。
- 不在本次删除数字 ID 路由或 DTO 字段。
- 不顺带重构全部 Controller、Mapper 或 API 版本机制；只做完成该能力所需的收敛。

## 4. 已冻结的设计决策

### 4.1 双标识模型

`Long id` 是内部实现细节，继续用于：

- JPA 主键
- 数据库外键
- 文档归属
- 检索 SQL
- 审计中的内部关联
- API Key ACL 的当前持久化和运行时集合

`String collectionKey` 是外部业务身份，优先用于：

- Collection CRUD 和生命周期操作
- 创建文档、批量导入、上传、PDF-to-RAG
- Chat 和 Search 范围
- Collection 导入、导出和克隆
- WebUI 路由、选择器和状态
- API Key 权限配置

解析边界位于 Controller/Service 入口：外部 key 经统一 resolver 转成内部 ID，核心检索和持久化链路不改为字符串。

### 4.2 全局唯一和大小写

- 唯一范围：全表全局唯一，不按 `deleted`、名称、模型或未来租户字段分区。
- 比较方式：精确、区分大小写。
- `customer-a` 与 `Customer-A` 是两个不同 key。
- 应用和数据库都不得执行 trim、lowercase、uppercase、Unicode normalization 或截断。
- API 文档必须提醒调用方自行保存原始 key，并按原始大小写使用。

选择区分大小写，是因为用户明确要求可表达业务含义且只需保证唯一；隐式大小写折叠会改变调用方提供的身份，并给跨系统迁移带来不可见碰撞。

### 4.3 不可变和不复用

- 创建后不能通过 Update DTO 修改。
- JPA 字段标记 `updatable = false`。
- 数据库 UPDATE trigger 阻止直接 SQL、旧代码或未来遗漏绕过应用约束。
- 软删除只更新 `deleted` / `deleted_at`，不清空 key。
- 恢复沿用原 key。
- 删除后的 key 仍占用唯一约束，不允许新 Collection 复用。

此决策确保缓存键、外部引用、导出记录和审计记录不会因 key 重用指向另一个 Collection。

### 4.4 不在路径中放任意 key

合法 key 可包含所有可见 ASCII 标点。若直接用 `/rag/collections/{collectionKey}`：

- `/` 会改变路由层级。
- `?` 和 `#` 具有 URL 语义。
- `%` 可能触发二次解码问题。
- `+` 在查询编码中也必须正确转义。

因此新增定位接口使用 JSON body 或 query parameter。调用方必须按标准 URL 编码查询参数，例如 `+` 编码为 `%2B`、`#` 编码为 `%23`。服务端不得在解码后再做 trim 或归一化。

### 4.5 兼容策略

- 数字 ID 输入和路径继续可用，并在 OpenAPI 标记 deprecated。
- 新响应同时返回 `id` 和 `collectionKey`；旧字段暂不删除。
- 当同一请求同时提供 ID 和 key 时，两者必须解析为同一 Collection 或同一集合，否则返回 400。
- 兼容字段的最终删除必须是单独的 API 版本或明确破坏性版本，不在本次设定静默删除日期。
- 创建、克隆和导入的 key 必填是有意的契约收紧；服务端不为旧客户端生成正式业务 key。
- 数据库不为旧二进制长期自动生成业务 key；expand 阶段通过可空列承受旧写入，contract 阶段前必须排空旧写入实例。

## 5. `collectionKey` 精确契约

### 5.1 接受规则

| 规则 | 定义 |
|---|---|
| 类型 | JSON string / Java `String` |
| 最短长度 | 1 |
| 最长长度 | 128 个字符 |
| 字符集 | 可见非空白 ASCII，`U+0021` 至 `U+007E` |
| 大小写 | 敏感 |
| 空值 | 创建、导入、克隆目标 key 不允许 |
| 空字符串 | 不允许 |
| 空格 | 不允许，包括首尾和中间空格 |
| 控制字符 | 不允许 |
| Unicode | 不允许 |
| 规范化 | 不执行 |

因为只接受 ASCII，一个字符等于一个 UTF-8 字节，“最多 128 个字符”和数据库 `VARCHAR(128)`、`octet_length <= 128` 的含义一致。

合法示例：

```text
550e8400-e29b-41d4-a716-446655440000
customer-42:product-manual:v3
org/acme|region/cn|kb/support
crm.customer_42.case@2026-08
ABC
abc
```

其中 `ABC` 与 `abc` 不冲突。

非法示例：

```text
""
"customer 42"
" customer-42"
"客户-42"
"line\nbreak"
```

### 5.2 应用层校验

在 `spring-ai-rag-api` 增加可复用约束，推荐实现为自定义 Bean Validation 注解 `@ValidCollectionKey`，避免在多个 DTO、query parameter 和 multipart 入口复制正则。

约束逻辑：

```text
value != null
length in [1, 128]
every char in [0x21, 0x7E]
```

`@ValidCollectionKey` 对 null 返回 true，符合 Bean Validation 组合约束习惯；创建、导入、克隆目标 key 必须额外 `@NotNull`，可选输入允许 null。不要用 trim 后的值进行校验，也不要把不合法输入自动修复。

前端复用同一规则：

```text
/^[\x21-\x7E]{1,128}$/
```

前端校验只改善体验，后端和数据库约束仍是最终保障。

对 query parameter、multipart parameter 和 `List<String>` 元素使用该约束时，相关 Controller 必须启用 `@Validated`，方法参数显式标注 `@ValidCollectionKey`；只在 DTO 上声明注解不能覆盖 Spring 方法参数校验。列表参数同时校验每个元素和整体的非空/去重语义。

### 5.3 数据库校验

数据库列使用 C collation，并增加命名 CHECK：

```sql
collection_key VARCHAR(128) COLLATE "C"

CONSTRAINT ck_rag_collection_collection_key_ascii
CHECK (
    octet_length(collection_key) BETWEEN 1 AND 128
    AND collection_key !~ '[^!-~]'
)
```

`NOT NULL` 单独设置。V27 过渡 CHECK 名称为 `ck_rag_collection_collection_key_ascii_expand`；V28 最终 CHECK 名称为 `ck_rag_collection_collection_key_ascii`；唯一约束为 `uk_rag_collection_collection_key`；不可变 trigger/function 使用固定名称 `trg_rag_collection_key_immutable` / `fn_rag_collection_key_immutable`，供测试、异常转换和运维诊断使用。

## 6. 数据库迁移设计

### 6.1 迁移文件

新增连续的两个可用版本，当前预期：

```text
spring-ai-rag-core/src/main/resources/db/migration/
  V27__add_collection_key_expand.sql
  V28__require_collection_key.sql
```

开始实施前必须重新列出 migration 目录；如果 V27/V28 已被其他并行改动占用，则两个文件一起顺延到当时连续可用的版本，不重命名或修改已存在迁移。

### 6.2 迁移顺序

#### V27 expand

1. 增加暂时可空的 `collection_key VARCHAR(128) COLLATE "C"`。
2. 对所有当前已有行，包括软删除行，回填一个确定性且未占用的 `legacy-collection-<id>` 候选。
3. 如果候选已被调用方占用，按 `legacy-collection-<id>-<attempt>` 递增 attempt，直到找到未占用候选；任何候选超过 128 字符时直接使迁移失败，不截断。
4. 增加名为 `ck_rag_collection_collection_key_ascii_expand`、允许 NULL 过渡状态的 ASCII CHECK：
   `collection_key IS NULL OR (octet_length(collection_key) BETWEEN 1 AND 128 AND collection_key !~ '[^!-~]')`。
5. 增加命名唯一约束 `uk_rag_collection_collection_key`。PostgreSQL UNIQUE 对多个 NULL 不冲突，允许旧实例在过渡期间写 NULL。
6. 增加名为 `trg_rag_collection_key_immutable` 的 UPDATE 不可变 trigger，由 `fn_rag_collection_key_immutable` 实现；当 `NEW.collection_key IS DISTINCT FROM OLD.collection_key` 时拒绝更新。
7. 增加列注释，说明“外部稳定业务键、区分大小写、不可变、软删除后不复用”。

V27/V28 的回填必须使用按 `id` 排序的 PL/pgSQL 循环或等价的数据库内算法，并在每次候选选择时查询现有 `collection_key` 值；唯一约束负责最终并发保障。它不保留用户可见的命名空间：`legacy-collection-*` 仍是合法的调用方 key；碰撞由迁移算法解决，而不是通过禁止用户前缀解决。

#### V28 contract

V28 只能在所有旧应用写入实例退出后执行：

1. 再次回填任何 `collection_key IS NULL` 的行，使用与 V27 相同的未占用候选算法。
2. 查询并记录仍产生 NULL 的来源；若存在则失败，不继续收紧约束。
3. 删除 `ck_rag_collection_collection_key_ascii_expand`，增加最终严格 CHECK `ck_rag_collection_collection_key_ascii`。
4. 执行 `ALTER TABLE rag_collection ALTER COLUMN collection_key SET NOT NULL`。
5. 保持 `uk_rag_collection_collection_key` 和不可变 trigger。

V28 之后，数据库不再接受旧二进制省略 `collection_key` 的 INSERT。不能通过 trigger 为新 Collection 伪造业务身份。

交付边界必须与现有 Flyway 自动执行方式匹配：首个发布版本只携带并执行 V27；V28 不能与 V27 一起进入会自动执行全部 pending migrations 的同一应用包。待所有旧写入实例退出并完成 NULL 统计后，再在后续发布版本携带 V28，或使用项目已验证的受控 Flyway target 明确只执行 V27。禁止依赖“先启动应用、稍后人工阻止 V28”这类不可验证的时序。

### 6.3 唯一约束与索引

使用：

```sql
CONSTRAINT uk_rag_collection_collection_key UNIQUE (collection_key)
```

PostgreSQL 会为 UNIQUE constraint 创建唯一 B-tree index，不再额外创建重复的普通或唯一索引。应用捕获并发冲突时只识别该约束名。

默认在一个 Flyway 事务中创建约束。若生产表规模评估显示直接建唯一约束会超过允许锁窗口，则在实施 PR 中把 V27 的唯一索引拆为非事务的 `CREATE UNIQUE INDEX CONCURRENTLY` 加 `ALTER TABLE ... USING INDEX` 两步；这是只影响部署方式的可逆边界，不改变数据和 API 设计。实施前必须记录生产 `rag_collection` 行数和可接受锁时长，不能无依据改用并发迁移。

### 6.4 UPDATE 不可变 trigger

不可变 trigger 是长期约束，不随后续兼容清理删除。它应：

- 允许其他列正常更新。
- 允许 ORM 提交与旧值相同的 key。
- 使用 `IS DISTINCT FROM` 正确处理 NULL 过渡。
- 抛出固定约束名 `ck_rag_collection_collection_key_immutable`、SQLSTATE `23514` 和消息，便于定位直接 SQL 或代码缺陷。

JPA `@Column(..., updatable = false)` 是第一道防线，数据库 trigger 是最终防线。

### 6.6 迁移验证 SQL

部署后至少验证：

```sql
SELECT count(*) FROM rag_collection WHERE collection_key IS NULL;

SELECT collection_key, count(*)
FROM rag_collection
GROUP BY collection_key
HAVING count(*) > 1;

SELECT id, collection_key
FROM rag_collection
WHERE collection_key IS NOT NULL
  AND collection_key !~ '^[!-~]{1,128}$';

SELECT conname
FROM pg_constraint
WHERE conrelid = 'rag_collection'::regclass
  AND conname IN (
      'uk_rag_collection_collection_key',
      'ck_rag_collection_collection_key_ascii'
  );
```

V27 完成后，已有行的前三个查询应无问题，但在旧实例仍运行的窗口内第一条查询允许出现新写入的 NULL。V28 完成后第一条查询必须为 0，最后一个查询返回最终两个约束，且不可变 trigger/function 也存在。

## 7. 领域模型、Repository 与解析层

### 7.1 Entity

在 `RagCollection` 增加。expand 版本的兼容构建暂时使用 `nullable = true`，contract 版本和最终代码使用 `nullable = false`；两版都使用 `updatable = false`：

```java
@Column(
    name = "collection_key",
    nullable = false,
    length = 128,
    updatable = false,
    unique = true
)
private String collectionKey;
```

`unique = true` 只用于表达实体意图和 `ddl-auto=validate` 一致性，命名数据库约束仍由 Flyway 定义。增加 getter/setter；setter 仅供创建和 JPA hydration，不向更新 DTO 暴露。若 expand 构建把 `nullable` 设为 true，不能把该中间状态发布为最终实现。

### 7.2 Repository

在 `RagCollectionRepository` 增加并测试：

- `Optional<RagCollection> findByCollectionKey(String collectionKey)`
- `Optional<RagCollection> findByCollectionKeyAndDeletedFalse(String collectionKey)`
- `boolean existsByCollectionKey(String collectionKey)`
- 批量 key 查询，返回实体后由 resolver 检测遗漏和重复输入
- 受限 ACL 场景的 `collectionKey IN (...) AND id IN (...)` 查询
- 按 ID 批量映射 key 的查询，供响应和 API Key 展示使用

不要使用 `IgnoreCase`，不要在 JPQL 中调用 `LOWER`。

### 7.3 `CollectionIdentityResolver`

在 core service 层新增单一解析组件：

```text
CollectionIdentityResolver
```

职责：

- 单 key -> active Collection / internal ID
- 单 key -> including-deleted Collection，供 restore 和管理操作
- 多 key -> 去重后的有序 ID 集合
- ID -> key 和批量 ID -> key 映射
- 同时提供 ID/key 时校验两者一致
- 根据当前 API Key scope 执行受限解析
- 未解析项 fail closed

不应让每个 Controller 自己调用 repository 拼装规则。Resolver 输出内部 ID 或已加载实体，后续 `CollectionDocumentResolver`、检索服务和文档服务继续接收 `Long`。

### 7.4 集合语义

- 输入 key 列表先按精确字符串去重，并保留第一次出现顺序。
- 输入 ID 列表按数值去重。
- 同时提供两种列表时，比较解析后的 ID set，不比较列表顺序。
- 两种列表解析后集合完全相等才接受。
- 空数组视为“调用方显式提供了空 scope”，不能在解析后退化为不带过滤条件。
- 对检索类请求，显式空 scope 固定返回 400 `BAD_REQUEST`，避免误解；绝不能执行全局检索。
- 对未提供任何 scope 的请求，继续按现有 API Key 规则：不受限 key 可不限定；受限 key 强制使用其 allowed IDs。

## 8. ACL 与防枚举顺序

### 8.1 不受限调用方

解析顺序：

1. 校验 key 语法。
2. 全局精确查询 key。
3. 任一 key 不存在时返回 404 `COLLECTION_NOT_FOUND`。
4. 如果同时给出 ID，比较解析后的集合；不一致返回 400 `BAD_REQUEST`。
5. 将 ID 传入后续内部服务。

### 8.2 受 Collection 限制的 API Key

受限请求必须避免通过 404/403 差异探测其他 Collection 是否存在：

1. 解析当前 API Key 的 allowed internal IDs。
2. 先校验调用方提供的旧 `collectionId(s)` 是否为 allowed IDs 的子集；否则返回 403。
3. key 只在 `allowed IDs` 范围内查询。
4. 任一 key 无法在 allowed 范围内解析时统一返回 403，不区分“全局不存在”和“存在但无权访问”。
5. 如果 ID 和 key 都提供且各自已通过授权，再比较解析后的集合；不一致返回 400。
6. 无 scope 时继续使用现有 fail-closed 规则：读请求强制收窄到 allowed IDs；需要单一 Collection 的写请求在 allowed 集合只有一个元素时可沿用现有自动选择，否则要求调用方提供 key 或旧 ID。

不得先全局查 key、返回 404，再检查 ACL。

### 8.3 软删除与 enabled

Resolver 需要明确模式：

- `ACTIVE_USE`：排除软删除；适用于文档、Chat、Search、上传、克隆源。
- `INCLUDING_DELETED`：仅用于 restore 和明确的管理查询。
- `CREATE_TARGET`：检查全表唯一，包括软删除。

`enabled=false` 与 `deleted=true` 是不同语义。是否允许 disabled Collection 用于写入/检索应继续遵循现有行为，本次不借机改变；key resolver 只负责身份与删除状态。

## 9. API 契约与兼容矩阵

### 9.1 命名约定

- 单个外部业务键：`collectionKey`
- 多个外部业务键：`collectionKeys`
- API Key 允许范围：`allowedCollectionKeys`
- 旧字段：`collectionId`、`collectionIds`、`allowedCollectionIds`

旧字段在 OpenAPI 标记 `deprecated = true`。新响应暂时同时返回 key 和 ID，方便旧客户端迁移和排障。

### 9.2 Collection API

| 操作 | 首选新契约 | 旧契约 |
|---|---|---|
| 创建 | `POST /rag/collections`，body 必填 `collectionKey` | 同一路径；缺 key 返回 400 |
| 获取 | `GET /rag/collections/by-key?collectionKey=...` | `GET /rag/collections/{id}` 保留 |
| 列表 | `GET /rag/collections`，每项返回 key | 保留原分页参数 |
| 更新可变属性 | `PUT /rag/collections/by-key?collectionKey=...` | `PUT /rag/collections/{id}` 保留 |
| 软删除 | `DELETE /rag/collections/by-key?collectionKey=...` | `DELETE /rag/collections/{id}` 保留 |
| 恢复 | `POST /rag/collections/by-key/restore?collectionKey=...` | `POST /rag/collections/{id}/restore` 保留 |
| 克隆 | `POST /rag/collections/clone`，body 含 `sourceCollectionKey` 和新 `collectionKey` | `POST /rag/collections/{id}/clone` 保留，但 body 必须提供目标 `collectionKey` |
| 列文档 | `GET /rag/collections/by-key/documents?collectionKey=...` | `GET /rag/collections/{id}/documents` 保留 |
| 挂接文档 | `POST /rag/collections/by-key/documents?collectionKey=...` | `POST /rag/collections/{id}/documents` 保留 |
| 导出 | `GET /rag/collections/by-key/export?collectionKey=...` | `GET /rag/collections/{id}/export` 保留 |
| 导入 | `POST /rag/collections/import`，payload 必填 `collectionKey` | 同一路径；旧导出文件缺 key 返回 400 |

`by-key` 必须在 `/{id}` 路由之前或通过精确映射避免被数字 path handler 误接收。接口测试需覆盖 `GET /by-key` 不会尝试把 `by-key` 转为 `Long`。

### 9.3 创建与更新 DTO

拆分当前 [`CollectionRequest`](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/CollectionRequest.java)：

- `CollectionCreateRequest`：包含必填 `collectionKey` 和现有可创建字段。
- `CollectionUpdateRequest`：不包含 `collectionKey`。

不要继续使用一个 DTO 同时承担创建和更新。`CollectionUpdateRequest` 不暴露可写的 key；同时增加一个只用于拒绝输入的 `collectionKey` JSON setter（或等价的 DTO 级未知字段拦截），收到更新 payload 中的 `collectionKey` 时明确抛出 `IllegalArgumentException`，由现有异常处理返回 400 `BAD_REQUEST`，不能依赖 Jackson 全局未知字段配置，也不能静默忽略。若旧 `CollectionRequest` 被外部模块公开引用，可先保留并 deprecated，但 Controller 必须切换到语义明确的新 DTO。

### 9.4 克隆 DTO

新增 `CollectionCloneRequest`：

```json
{
  "sourceCollectionKey": "customer-42:manual:v1",
  "collectionKey": "customer-42:manual:v2"
}
```

key 路由使用两个字段。旧数字 ID 路由的 source 来自 path，body 仍要求目标 `collectionKey`；若 body 同时携带 source key，则必须与 path ID 一致。

克隆响应增加：

- `collectionKey`
- `sourceCollectionKey`
- 保留 deprecated 的 `collectionId` / `sourceCollectionId`

### 9.5 Import / Export

导出 payload 在顶层加入原始 `collectionKey`。导入规则：

- `collectionKey` 必填并按统一规则校验。
- 不接受服务端自动改名、追加后缀或生成 UUID。
- 目标数据库已有同 key，包括软删除记录时，返回 409。
- 同一数据库直接重新导入刚导出的文件会冲突；调用方必须先显式编辑导出文件中的 key。
- 导入文档内部继续保存新建 Collection 的 `Long id`。
- 导入事务中 Collection 和 documents 必须原子提交；唯一冲突不得留下孤立文档。

旧导出文件不含 key 时返回 400，并提供明确消息，提示调用方补充一个 1-128 字符的可见 ASCII key。

### 9.6 Chat、Search 与 Document API

以下 DTO/入口增加 key 形式，并暂时保留 ID：

- `ChatRequest.collectionKeys` + deprecated `collectionIds`
- `SearchRequest.collectionKeys` + deprecated `collectionIds`
- `DocumentRequest.collectionKey` + deprecated `collectionId`
- `BatchDocumentRequest.collectionKey`
- `BatchCreateAndEmbedRequest.collectionKey`
- 文件上传 multipart `collectionKey`
- PDF-to-RAG multipart/query `collectionKey`
- 文档列表、统计等过滤参数 `collectionKey` / `collectionKeys`

Controller 在调用 `CollectionDocumentResolver`、`BatchDocumentService`、`PdfToRagService`、`HybridRetrieverService` 前统一解析为 `Long`。内部 service 签名除身份解析边界外不需要全面改为 String。

`PdfImportController` 的原始 `/pdf` 上传参数 `collection` 是文件虚拟目录/子目录前缀，不是 RAG Collection 身份；本次保持其现有含义，不改名为 `collectionKey`。只有 PDF-to-RAG 归属 Collection 的入口增加 `collectionKey`。

同一请求同时提供 key 和 ID 时执行第 7、8 节的一致性和 ACL 规则。解析层必须保留“scope 字段是否显式出现”的信息，不能把显式空 `collectionKeys` / `collectionIds` 归一化为 null；任何显式但无法解析或为空的 key 列表都返回 400 `BAD_REQUEST`，不得继续全局检索。尤其要修正当前 `CollectionDocumentResolver.hasCollectionFilter` 仅按“非空列表”判断的行为。

SSE Chat 请求沿用同一 `ChatRequest` 解析规则：`collectionKeys` 优先、`collectionIds` 兼容，SSE-PROTOCOL 中的输入归一化示例和 WebUI `useSSE` 同步更新；若 SSE 事件或诊断 payload 当前返回 Collection 身份，则在保留 ID 的同时增加 key。

### 9.7 响应 DTO

至少更新：

- `CollectionResponse`
- `CollectionListResponse`
- `CollectionCreatedResponse`
- `CollectionDeleteResponse`
- `CollectionRestoreResponse`
- `CollectionCloneResponse`
- `CollectionExportResponse`
- `CollectionImportResponse`
- `CollectionDocumentListResponse`
- `DocumentAddedResponse`
- `DocumentSummary`
- `DocumentDetailResponse`
- 创建、批量和上传响应中所有出现 `collectionId` 的结构

原则：

- Collection 自身响应必须返回 `collectionKey`。
- 文档详情和摘要在有归属 Collection 时同时返回 `collectionKey`。
- 无 Collection 的文档，`collectionId` 和 `collectionKey` 都是 null。
- 不因 key 映射触发逐行 N+1 查询；Mapper 接收批量构建的 `Map<Long, CollectionIdentity>`。

当前 Controller 有部分 `Map<String, Object>` 返回值。涉及 key 的接口应优先迁移到 typed DTO，确保 OpenAPI、序列化和测试能发现字段遗漏。

## 10. 错误与状态码契约

| 场景 | HTTP | `ErrorCode` | 说明 |
|---|---:|---|---|
| 缺少创建/导入/克隆目标 key | 400 | `VALIDATION_FAILED` | Bean Validation |
| key 长度或字符不合法 | 400 | `VALIDATION_FAILED` | 不自动修复 |
| 同时提供 ID/key 但不一致 | 400 | `BAD_REQUEST` | 已授权、已解析后比较 |
| 不受限调用方查询未知 key | 404 | `COLLECTION_NOT_FOUND` | 与未知 ID 语义一致 |
| 受限调用方提交未知或未授权 key | 403 | `FORBIDDEN` | 防止存在性枚举 |
| 创建、导入、克隆目标 key 已存在 | 409 | `DUPLICATE_RESOURCE` | 包括软删除行 |
| 更新请求提交 `collectionKey` | 400 | `BAD_REQUEST` | DTO 显式拒绝不可变字段 |
| 内部代码或直接 SQL 修改 key | 无正常 HTTP 契约；数据库返回 SQLSTATE `23514` | 由不可变 trigger 拒绝并记录为缺陷 | 正常 API 不应走到该路径 |
| 数据库不可用或非目标约束异常 | 500 | `DATABASE_ERROR` | 不错误转换为重复 key |

新增 `DuplicateCollectionKeyException extends RagException`，绑定 `DUPLICATE_RESOURCE`；同时增加 `CollectionNotFoundException`，统一 ID/key 的 404 文本。

创建路径应：

1. `existsByCollectionKey` 预检查，提供友好错误。
2. `saveAndFlush`，确保唯一冲突在受控事务边界内抛出。
3. 检查异常链中的 PostgreSQL `PSQLException` / `ServerErrorMessage.getConstraint()`。
4. 只有 `uk_rag_collection_collection_key` 转换为 `DuplicateCollectionKeyException`。
5. 其他 `DataIntegrityViolationException` / `DataAccessException` 保持原有数据库错误处理。

不能仅依赖异常消息文本包含 `duplicate key`，因为消息可能受数据库版本和 locale 影响。

## 11. 后端实施清单

### 11.1 `spring-ai-rag-api`

新增或修改：

- `@ValidCollectionKey` 及 validator
- `CollectionCreateRequest`
- `CollectionUpdateRequest`
- `CollectionCloneRequest`
- Collection CRUD、clone、import/export 响应 DTO
- Chat、Search、Document、batch DTO 的 key 字段
- API Key DTO 的 `allowedCollectionKeys`
- OpenAPI `@Schema`：长度、字符规则、大小写、不可变、示例和 deprecated 标记

DTO 的 `equals`、`hashCode`、`toString` 和 record 构造参数必须同步更新。`toString` 可输出 key，因为控制字符已禁止，但不要把 key 作为 Micrometer tag。

### 11.2 `spring-ai-rag-core`

新增或修改：

- `RagCollection.collectionKey`
- `RagCollectionRepository` 的 key 查询
- `CollectionIdentityResolver`
- `DuplicateCollectionKeyException`
- `CollectionNotFoundException`
- `RagCollectionService`：create/update/delete/restore/clone/import/export 的事务边界和 key 逻辑
- `RagCollectionController`：薄化编排，增加 by-key 路由
- `CollectionMapper` / `DocumentMapper`：输出 key，批量映射
- `RagDocumentController`
- `RagChatController`
- `RagSearchController`
- `PdfImportController`
- `BatchDocumentService`
- `PdfToRagService`
- `ApiKeyCollectionAccess`
- `ApiKeyManagementService`
- `ApiKeyController`
- `GlobalExceptionHandler` 或受控 service 异常转换

内部 `RagDocument.collectionId`、repository 查询和 retrieval service 仍使用 `Long`。

### 11.3 审计和日志

- Collection 创建、删除、恢复、克隆和导入审计 metadata 增加 `collectionKey`。
- 保留内部 ID，方便数据库关联和故障排查。
- 不把 `collectionKey` 用作高基数 metrics label。
- key 不是认证密钥，但可能包含客户业务信息；日志遵循现有级别，不额外在每个检索请求重复记录完整列表。
- 在 V27/V28 发布验证中统计 `legacy-collection-%` 回填行和 contract 前仍为 NULL 的行；旧实例排空后，V28 必须以 0 个 NULL 作为硬门禁。

## 12. API Key ACL 桥接

### 12.1 外部契约

[`ApiKeyCreateRequest`](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyCreateRequest.java) 增加首选：

```json
{
  "allowedCollectionKeys": [
    "customer-42:manual",
    "customer-42:faq"
  ]
}
```

暂时接受 deprecated 的 `allowedCollectionIds`。`allowedCollectionKeys` 与旧 ID 字段保持现有最多 100 个 Collection 的限制；解析去重后必须确认序列化后的 ID 文本仍不超过 `allowed_collection_ids VARCHAR(2048)`，否则返回 400，不能截断或退化为 unrestricted。如果两者同时提供：

- 在 root/admin provisioning 权限检查通过后解析。
- 两者必须代表同一个内部 ID set，否则 400。
- 任一 key 不存在返回 404；API Key 管理是授权管理入口，不采用普通受限请求的防枚举 403 语义。

### 12.2 持久化

本阶段继续把解析后的 ID set 规范化为升序、去重、逗号分隔字符串，写入 `allowed_collection_ids`。

原因：

- 现有鉴权过滤器和静态 helper 都以 ID 工作。
- key 不可变，ID/key 映射稳定。
- 避免在同一发布中迁移权限表结构。

后续若 ACL 规模超过 `VARCHAR(2048)` 或需要数据库关系完整性，应单独规划关联表，不在本次实现中临时扩展字符串协议。

### 12.3 响应与轮换

- `ApiKeyResponse`、`ApiKeyCreatedResponse` 以及当前或未来任何已经暴露 allowed scope 的管理 DTO 返回 `allowedCollectionKeys`；当前 `ApiKeyIdentityResponse` 没有 scope 字段，不为它无依据新增 scope。
- 兼容期同时返回 deprecated 的 `allowedCollectionIds`。
- 批量 ID -> key 映射，避免 N+1。
- API Key rotation 继续复制原内部 ID scope，并在响应阶段重新映射 key。
- 若数据库中出现指向已不存在 Collection 的遗留 ID，响应不得静默扩大权限；保留 ID、对缺失 key 给出诊断日志，并按 fail-closed 处理请求。

## 13. WebUI 规划

### 13.1 类型和状态

更新以下 API 模块及其调用方：

- [`api/collections.ts`](../../spring-ai-rag-webui/src/api/collections.ts)
- [`api/documents.ts`](../../spring-ai-rag-webui/src/api/documents.ts)
- [`api/chat.ts`](../../spring-ai-rag-webui/src/api/chat.ts)
- [`api/search.ts`](../../spring-ai-rag-webui/src/api/search.ts)
- [`api/files.ts`](../../spring-ai-rag-webui/src/api/files.ts)

Collection 类型保留 `id: number` 仅用于兼容和展示诊断，业务状态、选择器 value、请求 payload 和导航使用 `collectionKey: string`。不要把 key 转成 number，也不要假设 UUID 格式。

需要更新页面：

- [`Collections.tsx`](../../spring-ai-rag-webui/src/pages/Collections.tsx)
- `Documents.tsx`
- `Chat.tsx`
- `Search.tsx`
- `ApiKeys.tsx`
- 文件上传相关 hook/component
- SSE 请求构造逻辑

### 13.2 创建表单

[`CreateCollectionModal`](../../spring-ai-rag-webui/src/components/CreateCollectionModal/CreateCollectionModal.tsx) 增加必填 key 输入：

- `maxLength=128`
- 前端执行可见 ASCII 校验
- 显示剩余/已用字符数
- 提供带图标和 tooltip 的“生成 UUID”按钮
- 使用浏览器 `crypto.randomUUID()`
- 生成值只填入输入框，用户可见、可编辑、提交时仍视为调用方提供
- 打开 modal 时不自动生成
- 不 trim 用户输入

服务端返回 409 时保留用户已填写内容，并将错误绑定到 key 字段。

### 13.3 显示与交互

- Collection 列表和详情明确显示 key，并提供复制图标按钮。
- 名称继续是面向人的可变显示名，key 是稳定技术身份。
- 删除/恢复/克隆操作按 key 调用新 API。
- 克隆对话框必须要求新的目标 key，并可使用同一 UUID 辅助控件。
- API Key 权限多选器使用 `collectionKey` 作为 value，名称作为主标签、key 作为辅助文本。
- 不把超长 key 强塞进固定宽度按钮；使用省略显示和 tooltip，但复制完整值。

### 13.4 WebUI 构建产物

WebUI 测试和构建通过后，按项目既有流程将静态产物同步到：

```text
spring-ai-rag-core/src/main/resources/static/webui/
```

只提交项目既有规则要求的产物，避免手工编辑构建后的 hash 文件。

## 14. 测试计划

### 14.1 校验单元测试

覆盖：

- 1 字符和 128 字符通过
- 0 字符和 129 字符失败
- ASCII 字母、数字和所有边界标点通过
- 空格、tab、换行、DEL、中文、emoji、全角符号失败
- `ABC` 与 `abc` 保持原样
- null 在 required 字段失败，在 optional 兼容字段按定义处理
- 不发生 trim 或截断

### 14.2 Entity / Repository 测试

扩展：

- `RagCollectionTest`
- `RagCollectionRepositoryTest`

覆盖：

- 保存、读取 key
- 精确大小写查询
- `ABC` 和 `abc` 可同时存在
- 完全相同 key 冲突
- 软删除后仍冲突
- active 查询排除软删除，including-deleted 查询可用于 restore
- 批量查询和 ID/key 映射

### 14.3 PostgreSQL / Flyway 集成测试

必须使用真实 PostgreSQL，不用 H2 推断以下行为：

- 旧数据回填格式正确；若调用方已占用 `legacy-collection-*` 候选，迁移循环选择下一个未占用候选
- V27 允许过渡 NULL，V28 后新列 `NOT NULL`
- CHECK 拒绝 Unicode、空格、控制字符和超长字节
- UNIQUE 精确区分大小写
- 直接 SQL 更新 key 被 trigger 拒绝
- V27 期间省略 `collection_key` 的旧式 INSERT 暂时可成功并留下 NULL；V28 后同类 INSERT 被 `NOT NULL` 拒绝
- 并发事务创建同一 key 只有一个成功
- Hibernate `ddl-auto=validate` 启动成功

如项目现有 Testcontainers 基础设施不足，新增最小 PostgreSQL integration test profile；不要用 mock 替代数据库约束验证。

### 14.4 Service / Controller 测试

扩展：

- `RagCollectionServiceTest`
- `RagCollectionControllerTest`
- `CollectionAclControllerTest`
- `CollectionMapperTest`
- `CollectionDocumentResolverTest`
- `RagControllerIntegrationTest`

覆盖：

- 创建必填 key
- duplicate 预检查 409
- 唯一约束竞态 409，而非 500
- 非目标数据库异常仍为 500
- by-key CRUD、restore、documents、export
- key 不可更新
- clone 必须提供新 key
- import 必须提供 key，冲突 409，事务回滚
- key 和 ID 一致时成功，不一致时 400
- 未知 key 的 unrestricted 404
- restricted key 的未知/未授权统一 403
- 显式空 scope 不会变成无范围检索
- 文档、Chat、Search、upload、PDF、batch 的 key 解析
- 响应同时含 key 和兼容 ID
- 批量映射不出现逐文档 repository 查询

### 14.5 API Key 测试

扩展：

- `ApiKeyCollectionAccessTest`
- `ApiKeyManagementServiceTest`
- `ApiKeyControllerTest`
- `ApiKeyIdentityControllerTest`
- `ApiKeyRootModeWebIntegrationTest`

覆盖：

- `allowedCollectionKeys` 创建和响应
- deprecated IDs 仍可用
- key/ID 双输入一致与不一致
- 未知 key 不能创建 ACL
- DB 仍保存规范化内部 ID
- rotation 保持 scope
- 受限 key 无法利用 404/403 枚举其他 Collection
- 旧 ACL 中悬空 ID fail closed

### 14.6 OpenAPI 契约测试

扩展 [`OpenApiContractTest`](../../spring-ai-rag-core/src/test/java/com/springairag/core/contract/OpenApiContractTest.java)，不能只检查 schema/path 存在：

- `collectionKey` 的 `required`、`minLength=1`、`maxLength=128`、pattern 和示例
- create/update DTO 分离
- update schema 不含可写 key
- key 路由存在且参数类型为 string
- 旧 ID 字段和路由标记 deprecated
- duplicate 409、not-found 404、forbidden 403 响应声明
- Chat/Search/Document/API Key DTO 的 key 字段

### 14.7 WebUI 测试

扩展：

- `CreateCollectionModal.test.tsx`
- `Collections.test.tsx`
- `Documents.test.tsx`
- `Chat.test.tsx`
- `Search.test.tsx`
- `ApiKeys.test.tsx`
- `useFileUpload.test.ts`
- `useSSE.test.ts`

覆盖：

- key 必填和边界字符
- UUID 按钮显式填充
- 不自动生成、不 trim
- 409 字段错误
- 所有 API payload 使用 key
- ACL 选择器提交 key
- 兼容响应中只有 ID 时的临时降级仅用于读取，不能继续作为新写入首选

### 14.8 E2E 与负载脚本

更新：

- [`scripts/e2e-test.sh`](../../scripts/e2e-test.sh)
- [`scripts/real-llm-e2e-smoke.sh`](../../scripts/real-llm-e2e-smoke.sh)
- [`scripts/demo-e2e.sh`](../../scripts/demo-e2e.sh)
- [`scripts/webui-e2e-test.js`](../../scripts/webui-e2e-test.js)
- [`scripts/k6-load-test.js`](../../scripts/k6-load-test.js)
- [`scripts/k6-session-stress.js`](../../scripts/k6-session-stress.js)
- [`scripts/k6-vector-search-stress.js`](../../scripts/k6-vector-search-stress.js)

脚本作为调用方显式生成每次运行唯一的 key，例如 UUID 或 `e2e-<run-id>-<case>`，并确保：

- 仅可见 ASCII
- 不超过 128 字符
- 并行 worker 不碰撞
- 后续请求从创建响应读取/核对 key
- 至少一个 case 使用有业务含义的拼接 key，不只测试 UUID

## 15. 验证命令

实施完成后按顺序执行，命令细节以 [`developer-reference-zh-CN.md`](../developer-reference-zh-CN.md) 和 [`testing-guide-zh-CN.md`](../testing-guide-zh-CN.md) 为准：

```bash
mvn -pl spring-ai-rag-api,spring-ai-rag-core -am test
```

```bash
cd spring-ai-rag-webui
npm test -- --run
npm run build
```

```bash
SPRING_PROFILES_ACTIVE=postgresql mvn -pl spring-ai-rag-core \
  -Dtest='*Collection*,*ApiKey*,OpenApiContractTest,RagControllerIntegrationTest' test
```

```bash
./scripts/e2e-test.sh
```

```bash
./scripts/start-real-e2e-server.sh
./scripts/real-llm-e2e-smoke.sh
```

真实 LLM 联调是最终 smoke，不替代单元、Controller、PostgreSQL 约束和 OpenAPI 测试。若测试命令因项目现有 profile 或并行 embedding 工作变化，实施者应使用正式开发/测试文档中的最新等价命令，并在 PR 中记录差异。

## 16. 正式文档更新

实施行为时同步更新中英文成对文档：

- [`rest-api-zh-CN.md`](../rest-api-zh-CN.md) / [`rest-api.md`](../rest-api.md)
- [`architecture-zh-CN.md`](../architecture-zh-CN.md) / [`architecture.md`](../architecture.md)
- [`project-context-zh-CN.md`](../project-context-zh-CN.md) / [`project-context.md`](../project-context.md)
- [`configuration-zh-CN.md`](../configuration-zh-CN.md) / [`configuration.md`](../configuration.md)
- [`SSE-PROTOCOL.md`](../SSE-PROTOCOL.md)
- [`api-versioning.md`](../api-versioning.md)

各文档职责：

- REST API：字段、路由、字符规则、状态码、双输入一致性和 deprecated 信息。
- Architecture：双标识边界、resolver、内部外键保持 ID。
- Project context：稳定能力和关键代码入口。
- Configuration：API Key ACL 对外 key、内部 ID 持久化的暂时桥接。
- SSE：Chat、PDF、embedding 请求中的 key 参数及 URL 编码。
- API versioning：数字 ID 兼容策略和未来删除条件。

实现完成并进入稳定能力后，再更新 `docs/index*.md` 的导航或迁移版本描述；本规划阶段不修改正式索引。

## 17. 分阶段实施顺序

### 阶段 0：基线和冲突检查

- 确认并行分支和 migration 最新编号。
- 记录现有 Collection 数量、最大 ID、软删除数量。
- 确认 PostgreSQL 版本和 migration 事务策略。
- 运行相关现有测试，区分基线失败与本改进回归。

退出条件：迁移编号无冲突，基线问题已记录。

### 阶段 1：数据库 expand

- 添加可空 column、冲突感知回填、过渡 CHECK、UNIQUE 和不可变 UPDATE trigger。
- 增加 PostgreSQL/Flyway 集成测试。
- 发布能识别 key 的兼容构建；在 V27 窗口内允许旧应用暂时写 NULL，但新 API 已要求 key。

退出条件：迁移验证 SQL 通过，V27 旧式 NULL INSERT 和新约束均有真实 PostgreSQL 测试，且所有写入实例已能升级到 key-aware 构建。

### 阶段 1b：数据库 contract

- 停止或排空所有仍可能省略 `collection_key` 的旧写入实例。
- 在后续应用发布版本中加入并运行 V28，补齐剩余 NULL、设置严格 CHECK 和 `NOT NULL`。
- 用 `ddl-auto=validate` 启动最终实体版本。

退出条件：NULL 数量为 0，旧二进制不再是可回滚目标，最终约束和不可变 trigger 均通过真实 PostgreSQL 测试。

### 阶段 2：领域模型和 resolver

- 更新 entity/repository。
- 增加 validation、exception、identity resolver。
- 完成大小写、软删除、双输入和 ACL 解析测试。

退出条件：核心身份规则集中实现，没有 Controller 自行复制解析逻辑。

### 阶段 3：Collection 生命周期 API

- create/update DTO 分离。
- 实现 by-key CRUD、restore、clone、documents、import/export。
- 所有写操作进入 service 事务。
- 完成特定唯一约束到 409 的转换。

退出条件：Collection 全生命周期可只用 key 完成；旧数字路由仍通过兼容测试。

### 阶段 4：跨域入口

- Document、batch、upload、PDF、Chat、Search 增加 key 输入。
- 响应 Mapper 增加批量 key 映射。
- 验证显式空 scope 和 unknown key 均 fail closed。

退出条件：主要外部业务调用不再必须知道数字 Collection ID。

### 阶段 5：API Key ACL

- 管理 DTO/WebUI 支持 `allowedCollectionKeys`。
- provisioning 时 key -> ID。
- 响应 ID -> key。
- 保持底层 ID ACL 和 rotation 行为。

退出条件：可以只用 key 创建、查看和轮换受限 API Key。

### 阶段 6：WebUI、脚本和文档

- WebUI 全链路切换到 key。
- 更新 Vitest、Playwright/E2E、真实 LLM 和 k6 脚本。
- 构建并同步静态资源。
- 同步正式中英文文档。

退出条件：WebUI 正常工作，脚本不会因 key 必填失败，正式文档与 OpenAPI 一致。

### 阶段 7：发布验证

- 先发布 V27 expand 和能识别 key 的兼容构建，再确认所有旧写入实例退出。
- 执行 V28 contract，然后发布/验证最终 `nullable=false` 实体构建。
- 观察 duplicate 409、resolver 404/403、NULL 回填和数据库异常。
- 执行 E2E 和真实 LLM smoke。

退出条件：无未解析 key 导致的 scope 扩大，无唯一冲突 500，V28 后没有 NULL 写入，无旧客户端写入中断超出已声明的 key 必填变化。

## 18. 部署、回滚与后续收缩

### 18.1 推荐发布顺序

1. 备份并记录 migration 前 Collection 统计。
2. 发布只包含 V27 的 expand 版本和临时 `nullable=true` 的 key-aware 应用构建。
3. 验证回填、唯一约束、过渡 CHECK 和 NULL 写入窗口。
4. 滚动替换所有仍可能省略 key 的旧实例，并确认没有旧写入者。
5. 在后续发布版本加入 V28 contract，验证严格 CHECK、`NOT NULL` 和最终实体。
6. 发布/验证最终 `nullable=false` 构建、WebUI 和外部脚本/客户端。
7. 监控至少一个完整发布周期。

### 18.2 应用回滚

V27 expand 窗口内可以回滚到仍认识“可空 `collection_key`”的兼容构建：

- 旧应用忽略额外列，`ddl-auto=validate` 允许数据库存在额外 column。
- 旧应用 INSERT 缺少 key 会留下 NULL，只能在 V27 的过渡窗口内接受。
- 新创建的业务 key 数据不会丢失。

V28 contract 完成后，原始未改造的旧二进制不再是可回滚目标；回滚下限是能够识别并写入 `collection_key` 的兼容构建。这样避免用数据库 trigger 伪造业务身份，也避免用户合法 key 与系统生成 key 碰撞。

### 18.3 数据库回滚

不推荐在已对外暴露 key 后删除 column，因为外部系统可能已经保存该身份。出现应用问题时优先回滚应用，不回滚数据模型。

只有在尚未对外发布、确认没有新 key 数据依赖时，才允许人工执行：

- 删除不可变 trigger/function
- 删除唯一和 CHECK 约束
- 删除 column

不得把 destructive rollback 自动写进常规部署流程。

### 18.4 后续收缩

后续独立迁移可做：

- 在新 API 版本移除数字 ID 输入和路由。
- 评估把 API Key ACL 从逗号分隔 ID 迁移到关系表。

长期不可删除：

- `collection_key` NOT NULL
- ASCII CHECK
- 全局 UNIQUE
- UPDATE 不可变 trigger
- 内部 `Long id` 主键和外键

## 19. 风险、默认值与可逆边界

| 风险/未知项 | 推荐默认 | 理由 | 可逆边界 |
|---|---|---|---|
| key 是否区分大小写 | 区分 | 保留调用方原始身份，C collation 可精确验证 | 改为不区分会产生碰撞，发布后不应更改 |
| 是否允许空格/Unicode | 不允许 | URL、日志、跨系统和字符计数更稳定 | 放宽可后续做；收紧会破坏已有 key |
| 是否允许删除后复用 | 不允许 | 防止历史引用指向新实体 | 发布后不应放宽 |
| 是否服务器生成 UUID | 不生成 | 用户明确要求由客户决定；UI 只提供显式辅助 | 可新增独立显式端点，但不能静默 |
| key 是否放 path | 不放 | 合法 ASCII 标点会引发路由/编码问题 | 可为受限字符子集另加别名路由，不改变主契约 |
| ACL 是否立刻改存 key | 否，继续存 ID | 降低权限迁移风险 | 后续可迁移关系表 |
| 唯一索引创建方式 | 默认事务内 UNIQUE constraint | Collection 表通常小，迁移简单且可原子回滚 | 大表时切换 concurrent 两阶段 |
| 兼容 ID 保留多久 | 本次不删除 | 删除需明确 API 版本决策 | 后续版本化删除 |
| 旧导出缺 key | 400，要求调用方补充 | 不替调用方创造业务身份 | 可提供离线迁移工具，但 API 不自动生成 |
| 显式空 key 列表 | 400 | 防止变成无过滤检索 | 可改为空结果，但必须继续 fail closed |

没有阻断实施的待决项。唯一需要实施前重新确认的是 Flyway 的下一个可用版本号和生产表规模；两者不改变目标数据模型或 API 契约。

## 20. 预计文件影响清单

### API 模块

```text
spring-ai-rag-api/src/main/java/com/springairag/api/dto/
spring-ai-rag-api/src/main/java/com/springairag/api/validation/   # 新增，名称按现有包规范
spring-ai-rag-api/src/main/java/com/springairag/api/enums/ErrorCode.java
```

`ErrorCode` 现有值已足够，默认不新增错误码；只有正式 API 规范要求区分 duplicate collection key 时才新增专用码。

### Core 模块

```text
spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java
spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagCollectionRepository.java
spring-ai-rag-core/src/main/java/com/springairag/core/service/RagCollectionService.java
spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionIdentityResolver.java
spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java
spring-ai-rag-core/src/main/java/com/springairag/core/controller/
spring-ai-rag-core/src/main/java/com/springairag/core/util/CollectionMapper.java
spring-ai-rag-core/src/main/java/com/springairag/core/util/DocumentMapper.java
spring-ai-rag-core/src/main/resources/db/migration/V27__add_collection_key_expand.sql
spring-ai-rag-core/src/main/resources/db/migration/V28__require_collection_key.sql
spring-ai-rag-core/src/test/
```

### WebUI、脚本和文档

```text
spring-ai-rag-webui/src/api/
spring-ai-rag-webui/src/pages/
spring-ai-rag-webui/src/components/CreateCollectionModal/
spring-ai-rag-webui/src/hooks/
spring-ai-rag-core/src/main/resources/static/webui/
scripts/
docs/rest-api*.md
docs/architecture*.md
docs/project-context*.md
docs/configuration*.md
docs/SSE-PROTOCOL.md
docs/api-versioning.md
```

## 21. 验收标准

以下条件全部满足才算实施完成：

- [ ] `rag_collection.id` 仍为 `BIGINT` 主键，现有内部外键未改为 String。
- [ ] 所有 Collection 都有非空、1-128 可见 ASCII 的 `collectionKey`。
- [ ] 数据库精确、全局、包含软删除行地保证唯一。
- [ ] `ABC` 与 `abc` 可共存，相同 key 并发创建只能成功一个。
- [ ] key 创建后通过 API 和直接 SQL 都不能修改。
- [ ] 创建、导入和克隆要求调用方显式提供 key。
- [ ] 服务端不静默生成、trim、归一化或截断 key。
- [ ] WebUI 只在用户点击时用 `crypto.randomUUID()` 填充输入。
- [ ] Collection 全生命周期可以只使用 key 完成。
- [ ] Document、Chat、Search、upload、PDF、batch 支持 key。
- [ ] 旧数字 ID 路由和字段仍通过兼容测试并标记 deprecated。
- [ ] ID/key 双输入不一致返回 400。
- [ ] 未知 key 对 unrestricted 调用方返回 404。
- [ ] restricted API Key 的未知/未授权 key 统一返回 403，不能枚举存在性。
- [ ] 任何解析失败或显式空 scope 都不会扩大为全局检索。
- [ ] duplicate key 的预检查和并发竞态都返回 409，不返回 500。
- [ ] 非目标数据库异常仍返回数据库错误，不误报 duplicate。
- [ ] API Key 管理可使用 `allowedCollectionKeys`，底层仍按 ID fail closed。
- [ ] 响应和导出包含 key，文档响应不产生 N+1 查询。
- [ ] PostgreSQL migrations、不可变 trigger、Controller、ACL、OpenAPI、WebUI 和 E2E 测试通过。
- [ ] WebUI 静态资源按项目流程更新。
- [ ] 正式中英文文档同步，API 示例不再只展示数字 Collection ID。
- [ ] V27/V28 分阶段滚动发布，以及 V28 后回滚到 key-aware 兼容构建的路径已在测试环境验证。

## 22. 与其他规划的协调

### 22.1 JSONB payload / retrieval 规划

[`2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md`](2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md) 中若有对外 `collectionId` 字段、唯一身份或 payload 示例，实施时统一调整为：

- 外部请求/响应优先 `collectionKey`
- 兼容期保留 `collectionId`
- 数据库 payload 和检索 SQL内部仍可保存/使用 `Long collection_id`
- resolver 必须在构建检索过滤条件前完成 key -> ID

### 22.2 Embedding profile 迁移

Collection Key 不改变 embedding profile、vector column 或维度策略。两个改动可能同时接触：

- `RagDocumentController`
- `RagControllerIntegrationTest`
- `OpenApiContractTest`
- migration 版本序列
- 正式 docs 索引

合并时必须保留双方行为和测试，不覆盖当前工作区改动。迁移版本以合并时序顺延。

## 23. 实施者快速入口

恢复任务时按以下顺序读取即可，不需要重新全库探索：

1. 本文第 4-10 节：冻结的数据、API、ACL 和错误契约。
2. [`RagCollection`](../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java)、[`RagCollectionRepository`](../../spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagCollectionRepository.java)、[`RagCollectionService`](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/RagCollectionService.java)。
3. [`RagCollectionController`](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagCollectionController.java) 和第 9 节 API 矩阵。
4. [`ApiKeyCollectionAccess`](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java) 和第 8、12 节。
5. 第 14、15 节测试与验证门禁。
6. 第 17 节按阶段实施，每阶段满足退出条件后再进入下一阶段。

若代码在实施前发生变化，以代码和正式 `docs/` 为事实来源；但不得在没有显式设计变更记录的情况下改变本文已经冻结的核心语义：内部 `Long id`、外部调用方提供的不可变全局唯一 `collectionKey`、最多 128 个可见非空白 ASCII 字符、软删除不复用、ACL fail closed。
