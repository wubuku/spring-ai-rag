# 多 Collection 检索范围改进实施进度

> 状态：实施中
> 开始日期：2026-08-16
> 基线分支：`main`
> 基线提交：`722a645c757b113b8107c86b76bc9b6513c43c33`
> 实施规划：[多 Collection 检索范围改进实施规划](2026-08-16_MULTI_COLLECTION_RETRIEVAL_IMPLEMENTATION_PLAN.md)
> 关联调研：[多 Collection 检索范围与性能调研](2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md)

## 1. 不可变实施边界

- 实现 `CALLER_VISIBLE`、`ANY_COLLECTION`、`SELECTED_COLLECTIONS`。
- 本轮不实现 `EACH_COLLECTION`。
- restricted API Key 不得通过 scope mode 扩权。
- key 批量解析；deprecated 数字 ID 保持未知范围零命中兼容语义。
- Collection predicate 直接下推到 Vector 和全部 Full-text SQL。
- JSON record 保持显式 selected Collection，并下推 `document_type`。
- 不新增 Flyway migration，不自动修改 ANN session 参数。
- 不 stash、不回退、不丢弃其他开发者修改。

## 2. 规划门槛

规划草稿首轮事实校验发现并修复：

- deprecated 数字 ID 兼容语义不明确；
- GET 与 POST 列表元素校验边界不完整；
- 前端缺少独立 `tsc` 硬门槛；
- 内嵌 WebUI 静态资源同步缺少可执行、一致性可验证步骤。

修复后连续三轮无修改检查通过：

| 轮次 | 时间 | 范围 | 结果 |
|---|---|---|---|
| 1 | 2026-08-16 10:19:29 CST | API、兼容、ACL、防枚举、Chat 三入口 | 无问题，无修改 |
| 2 | 2026-08-16 10:19:54 CST | Scope、JDBC Array、Vector/FTS、JSON、PostgreSQL | 无问题，无修改 |
| 3 | 2026-08-16 10:20:33 CST | WebUI、测试、构建产物、启动、恢复入口 | 无问题，无修改 |

规划检查计数：`3/3`，已允许进入实施。

## 3. 当前阶段

| 阶段 | 状态 | 关键结果 |
|---|---|---|
| 0. 基线与进度记录 | 已完成 | HEAD/工作区已确认；Flyway V29；API/Documents/Core `test-compile` 成功 |
| 1. API 与 key 批量身份解析 | 已完成并通过目标测试 | enum、DTO 校验、batch key、ACL scope resolver 已通过 |
| 2. 共享 scope 与 SQL 下推 | 已完成并通过目标测试 | Vector 与三个 FTS 已使用共享 JDBC Array predicate；SQL 矩阵已覆盖 |
| 3. Chat/Search/JSON/Collection 端点接入 | 已完成并通过目标测试 | 生产构造器走新 scope；Controller/MockMvc/OpenAPI 契约已通过 |
| 4. 真实 PostgreSQL 集成测试 | 已完成 | pgvector 0.8.1、Flyway V1-V29、真实 JDBC Array 与范围隔离矩阵全部通过 |
| 5. WebUI 与 Mock 验收 | 已完成 | 共享范围选择器、Search/Chat/SSE object 契约完成；Vitest 177 项、`tsc -b`、production build、Mock Playwright 9/9 通过 |
| 6. 正式中英文文档 | 已完成，待最终门禁 | REST API、架构、项目上下文、测试指南、WebUI README、SSE 与实施计划已同步 |
| 7. 基本集成验证硬门槛 | 已完成 | 后端目标测试、PostgreSQL 矩阵、全仓库 clean compile、WebUI 资源一致性和服务启动均通过 |
| 8. 连续三轮实现检查 | 已完成 | 固定范围连续三轮检查无问题、无修改，计数 `3/3` |
| 9. Commit / Push / clean worktree | 未开始 | |

## 4. 硬门槛记录

| 门槛 | 状态 | 结果 |
|---|---|---|
| 后端本任务目标测试 | 已通过 | 当前工作区 API 全测 538 项、Core 目标组 275 项全部通过；包括生产链路、MockMvc 和 OpenAPI |
| 真实 PostgreSQL 集成测试 | 已通过 | Testcontainers `pgvector/pgvector:pg16`；空 schema 实际执行 V1-V30；1 个端到端矩阵测试通过 |
| `mvn clean compile test-compile` | 已通过 | 2026-08-16 11:33:27 CST；5 个 Maven 模块成功，含 API/Documents/Core/Starter 测试源编译 |
| WebUI Vitest | 已通过 | 27 个测试文件、177 项全部通过 |
| WebUI 独立 `tsc -b` | 已通过 | 无 TypeScript 错误 |
| WebUI production build | 已通过 | Vite production build 无错误 |
| 内嵌静态资源一致性 | 已通过 | 2026-08-16 11:33:43 CST；`mvn -pl spring-ai-rag-core -Pwebui generate-resources` 后 `diff -qr` 无差异 |
| 核心 Mock Playwright | 已通过 | 9/9；覆盖三模式、多选、搜索/分页、Chat SSE body 与移动视口 |
| PostgreSQL profile 服务启动 | 已通过 | 2026-08-16 11:39 CST；使用 `postgresql` profile 在 `18084` 启动，Flyway v30，`/actuator/health` 返回 `UP` |
| 文档门禁 / whitespace | 规划阶段通过 | 实施完成后重跑 |

阶段 0 基线命令：

```bash
mvn -pl spring-ai-rag-api,spring-ai-rag-core -am -DskipTests test-compile
```

结果：`BUILD SUCCESS`，2026-08-16 10:21:39 CST。

后端主体实现完成后再次执行相同 `test-compile`，结果：
`BUILD SUCCESS`，2026-08-16 10:30:26 CST。

首次目标测试命令在 Core `testCompile` 阶段失败，尚未执行 Core 测试。失败集中为：

- `HybridRetrieverService` 和 Full-text provider 同时暴露第二参数分别为
  `List<Long>` / `RetrievalScope` 的同名重载，旧测试传入 `null` 时产生编译歧义；
- `PgTrgmFulltextProviderTest` 的匿名覆盖仍使用旧方法签名。

处理原则：先检查生产与测试调用面，统一消除 API 层面的重载歧义，再一次性重跑目标测试，
不采用逐个测试点临时强转的方式掩盖问题。

已将新范围入口命名为 `searchInScope(...)`，保留旧
`search(..., List<Long>, ...)` 契约，消除 `null` 重载歧义并兼容旧调用方。
随后修正 scope resolver 测试中去重后参数及 allow-list `Set` 的 Mockito 匹配，
首批目标测试结果为 `Tests run: 58, Failures: 0, Errors: 0, Skipped: 0`。

扩展验收测试补齐后，于 2026-08-16 10:46:46 CST 再次执行：

```bash
mvn -pl spring-ai-rag-api,spring-ai-rag-core -am -DskipTests test-compile
```

结果：`BUILD SUCCESS`。API、Documents、Core 的生产代码和 201 个 Core 测试源文件均编译成功，
当前进入完整后端目标测试执行阶段。

完整后端目标测试于 2026-08-16 10:50:25 CST 通过（当时基线为 V29）：

- API DTO：`Tests run: 512, Failures: 0, Errors: 0, Skipped: 0`；
- Core：`Tests run: 278, Failures: 0, Errors: 0, Skipped: 0`。

首次 Core 执行发现 `CollectionScopeMode` 被 OpenAPI 内联，未形成独立组件 schema。
已在枚举类型上增加 `@Schema(enumAsRef = true)`，随后完整重跑 Core 目标组通过。

真实 PostgreSQL 集成测试于 2026-08-16 10:51:22 CST 通过。测试在
`pgvector/pgvector:pg16` 容器中从空 schema 执行 Flyway V1-V29，实际 pgvector
版本为 0.8.1，并覆盖：

- `CALLER_VISIBLE` 包含无 Collection 文档；
- `ANY_COLLECTION` 排除无 Collection 文档；
- selected A+B 隔离、空 Collection、Collection 与 document IDs 交集；
- JSON document type、disabled/stale/错误 embedding profile 排除；
- PostgreSQL JDBC `bigint[]` Array 的真实绑定和执行。

WebUI 已完成：

- 新增共享 `CollectionScopeSelector`，支持三种模式、query 搜索、每页 50、
  多选、跨页保留、100 上限、loading/error/empty/disabled 状态；
- Search query key 包含 mode 和排序后的 keys，selected 空范围禁止提交；
- Chat 复用同一组件，SSE 页面调用改为 options object，旧位置参数继续兼容；
- `CALLER_VISIBLE` / `ANY_COLLECTION` 永不发送 `collectionKeys`，
  `SELECTED_COLLECTIONS` 仅发送非空且排序后的 keys；
- 全量 Vitest 于 2026-08-16 10:59:24 CST 通过：
  `Test Files 27 passed`、`Tests 177 passed`；
- 独立 `npx tsc -b --pretty false` 通过。
- Vite production build 通过。
- 核心 Mock Playwright 9/9 通过。首次执行发现并修复 radio 点击命中区域、
  Search mock query-string 匹配和分页测试错误 reload 三个验收问题，修复后完整重跑通过。

正式文档于 2026-08-16 11:18:29 CST 完成首轮同步：

- `rest-api*` 记录三模式、兼容推导、ACL、上限、direct predicate 与 global top-k；
- `architecture*` / `project-context*` 删除“展开全部 document IDs”和 WebUI 单选旧描述；
- `testing-guide*` 增加真实 PostgreSQL 多 Collection 范围矩阵；
- WebUI README 与 `SSE-PROTOCOL.md` 更新共享选择器和 object request；
- 实施规划中的新 scope 方法名统一为 `searchInScope(...)`。

文档交叉检查发现并修复 SQL placeholder cast 表述和 SSE legacy ID 示例缺失；
该修复发生在基本硬门槛开始前，不计入实现代码三轮收敛检查。

进入硬门槛后发现并行外部文档同步 WIP 已新增 Flyway V30。处理边界：

- 保留阶段 0 和首次 PostgreSQL 测试运行时的 V29 历史记录；
- 不修改 V30 migration 或并行业务实现；
- 只把 AGENTS、双语长青文档、project-docs Skill/检查清单和文档门禁的当前版本事实
  从 V29 同步到 V30；
- 最终 PostgreSQL 矩阵与服务启动必须重新确认当前工作区从空 schema 执行到 V30。

重新执行当前工作区目标测试时，API 全测为 538 项、Core 目标组为 275 项，均通过。
首次重跑被并行外部文档 WIP 的 `DocumentSummary` / `DocumentDetailResponse` 旧构造器
签名阻塞；已补回兼容重载并让新增字段使用 `null/false` 默认值，未改变业务语义。
`MultiCollectionRetrievalPostgresIntegrationTest` 于 2026-08-16 11:29:46 CST 通过，
日志明确显示 Flyway 成功应用 30 个迁移并到达 v30。

全仓库 clean compile 和内嵌静态资源一致性已于 2026-08-16 11:33 完成；
服务启动验证完成后，所有基本硬门槛才算通过，之后开始实现代码三轮收敛检查。

服务启动验证的构建前置：执行 `clean` 后，先安装当前 API/Documents 产物到本地
Maven 仓库，再运行 Core 的 `spring-boot:run`，否则单模块启动会解析旧版上游 JAR。
该问题属于本地多模块启动方式，不改变业务接口。

启动过程中发现并修复一个实现问题：`JsonRecordService` 为保留测试兼容构造器而存在多个
构造器，Spring 无法自动选择生产构造器，导致上下文尝试无参实例化。已在生产构造器上
增加 `@Autowired`；相关目标测试 154 项全通过，随后服务启动成功。

## 5. 中断恢复步骤

1. 读取本文件的“当前阶段”和“硬门槛记录”。
2. 读取实施规划 §4-9、§13、§15。
3. 运行 `git status --short --branch`，识别并保留任何新增并行修改。
4. 从第一个“进行中”阶段继续。
5. 每次关键进展先更新本文，再进入下一阶段。
