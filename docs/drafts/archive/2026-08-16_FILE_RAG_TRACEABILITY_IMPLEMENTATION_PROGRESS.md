# 文件 RAG 来源追溯实施进度

> 状态：实施、验收与连续三轮无修改审查已完成，正在提交收尾
> 日期：2026-08-16
> 配套规划：[文件 RAG 来源追溯实施规划](2026-08-16_FILE_RAG_TRACEABILITY_IMPLEMENTATION_PLAN.md)

## 1. 执行约束

- 不使用 `git stash`，不回退、不覆盖、不丢弃其他开发者的工作区修改。
- 当前工作区已有文件目录排序、Search 分数展示和相关文档修改，实施必须在现状上增量完成。
- 规划连续三轮固定范围审查无实质问题后才修改生产代码。
- 基本测试门槛通过后再做实现连续三轮无修改审查。
- 任何凭据只从 `.env`/运行环境读取，不写入文档、命令输出或提交。
- 关键进展先更新本账本；连续无问题审查轮次只在会话中记录，避免破坏无修改条件。
- 使用 plan 工具维护阶段状态；恢复任务时以本账本、配套规划和当前 `git diff` 交叉确认进度。

## 2. 已确认基线

- `fs_files` 保存 PDF 原件、`default.md` 和转换资源。
- `PdfToRagService` 保存 `pdf-import:{uuid}/default.md` 来源及 PDF metadata。
- 当前检索结果未返回可消费的文件目录和原始 PDF 路径。
- Search WebUI 当前丢弃来源 metadata，没有追溯操作。
- PDF 注册当前按全局内容哈希复用，可能跨文件来源丢失追溯关系；规划已改为来源级复用。
- 添加到 RAG 已支持选择现有 `collectionKey`。
- 普通/PDF 文档可通过后端 Collection-document 接口重关联；external-managed 文档禁止迁移。
- 本任务不需要数据库迁移或重新嵌入历史数据。

## 3. 阶段状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| 代码与文档探索 | 已完成 | 已核对 PDF 入库、四类检索、fusion/rerank、Search/Files、Collection 关联和长青文档 |
| 规划与进度文档 | 已完成 | 已冻结 API 字段、路径规则、UI 交互、测试和非目标 |
| 规划三轮审查 | 已完成 | 连续三轮固定范围审查无实质问题，计数 `3/3` |
| 后端实施 | 已完成 | PDF 来源级身份、检索来源字段、四类 SQL、fusion/rerank 与契约测试已完成 |
| WebUI 实施 | 已完成 | Search 追溯操作、认证 Blob 打开原始 PDF、Files 深链接和测试已实现 |
| 长青文档同步 | 已完成 | 文件管理专题、REST API、项目上下文及入口已中英文同步 |
| 基本验证门槛 | 已完成 | 后端、前端、Mock Playwright、文档与差异门禁均通过；真实 HTTP 与浏览器链路已通过 |
| 实现三轮审查 | 已完成 | 修复来源校验后重新执行固定范围审查，连续三轮无修改，计数 `3/3` |
| 提交与推送 | 进行中 | staged diff 已完成最终复核，待停止临时资源后执行 |

## 4. 规划审查记录

| 时间 | 轮次与范围 | 发现问题 | 处理与结果 |
|---|---|---|---|
| 2026-08-16 | 初始第 1 轮：PDF 注册身份、历史兼容、核心追溯验收 | 全局内容哈希复用可能让新 PDF 复用普通文档或其他 PDF 文档，导致本次文件来源丢失并可能意外迁移 Collection | 改为按 `pdf-import:{uuid}/default.md` 来源级复用；内容哈希只用于 embedding 新鲜度；补充历史不可恢复边界和测试，计数归零 |
| 2026-08-16 | 重启后第 2 轮：原文件名、两阶段导入、UI 展示 | 两阶段路径把 `uuid/default.md` 保存为 `original_filename` 占位值，若原样返回会把转换文件误称为原始 PDF | 来源映射将该占位值视为未知，UI 回退显示 `original.pdf`；补测试，计数归零 |

## 5. 当前工作区边界

开始本任务时已有未提交修改，包括：

- 文件目录按导入时间排序；
- Search 结果排名/命中渠道展示；
- `RetrievalResult` 分数语义说明；
- 文件管理专题长青文档和导航链接。

这些修改视为当前基线，不得拆除。最终 diff 必须逐段确认追溯能力是在该基线上增量实现。

## 6. 下一步

停止临时服务与测试容器，提交并推送已完成验证和最终差异复核的变更；随后确认
工作区干净且本地 `HEAD` 与 `origin/main` 一致。

## 7. 验证记录

| 时间 | 验证 | 结果 |
|---|---|---|
| 2026-08-16 22:43 CST | `EmbeddingProfilePostgresIntegrationTest`，隔离 PostgreSQL 16 + pgvector，显式 JDBC | 7/7 通过，0 跳过；Flyway 从空库执行 V1–V31；Vector 与 English FTS 真实 SQL 均返回完整 PDF provenance |
| 2026-08-16 | 后端任务聚焦测试 | 158 项通过 |
| 2026-08-16 | WebUI 聚焦 Vitest | 3 个文件、17 项通过 |
| 2026-08-16 | WebUI TypeScript `tsc -b` | 通过 |
| 2026-08-16 | 项目文档门禁 | 10 项通过 |
| 2026-08-16 22:45 CST | 后端聚焦门禁 | 197 项通过，7 项因未带显式 JDBC 属性跳过；同一 PostgreSQL 类已独立 7/7 实跑 |
| 2026-08-16 22:46 CST | `mvn clean compile test-compile` | Reactor 5 个模块全部通过 |
| 2026-08-16 22:47 CST | WebUI 全量 Vitest | 27 个文件、185 项通过；同时稳定化 Settings 异步测试夹具 |
| 2026-08-16 22:48 CST | WebUI lint、对齐策略与生产构建 | 全部通过；对齐策略确认 12 处有意居中 |
| 2026-08-16 22:50 CST | Search/Files Mock Playwright | 11/11 通过；覆盖 Collection 选择、导入时间排序、深链接和认证 PDF 请求 |
| 2026-08-16 22:52 CST | 全仓 `mvn test` | API 538、documents 74、core 2690、starter 48 项通过；core 7 项显式 PostgreSQL测试按约定跳过 |
| 2026-08-16 22:50 CST | 文档、whitespace 与 added-line secret 门禁 | 文档 10 项通过，`git diff --check` 与密钥扫描通过 |
| 2026-08-16 23:00 CST | `scripts/dev.sh` 真实栈与 HTTP 冒烟 | 后端 18082、WebUI 15173 稳定启动；root 身份、readiness、文件树、真实 Search provenance、索引 Markdown 与原始 PDF 均返回 200 |
| 2026-08-16 23:12 CST | 真实 Chromium 端到端烟测 | root 解锁后通过 SPA 导航进入 Search；真实检索返回 provenance；认证原始 PDF 请求返回 200、1,281,916 bytes 并打开新页面；Files 深链接选中 `default.md` 且预览返回 200；页面异常 0 |
| 2026-08-16 23:18 CST | `RetrievalResultProvenanceTest` | 5/5 通过；覆盖非 `default.md` 的伪造 PDF source 必须失败关闭 |
| 2026-08-16 23:19 CST | 修复后 `mvn clean compile test-compile` | Reactor 5 个模块全部通过 |
| 2026-08-16 23:22 CST | 修复后 `scripts/dev.sh` 重启与真实 Chromium 复验 | 新编译后端和 Vite WebUI 稳定启动；Search provenance、认证原始 PDF 200/1,281,916 bytes、新页面打开、`default.md` 深链接预览 200、页面异常 0 |

## 8. 实现审查修复记录

| 时间 | 轮次与范围 | 发现问题 | 处理与结果 |
|---|---|---|---|
| 2026-08-16 23:17 CST | 初始第 3 轮：API、双语长青文档、发现路径和验收证据一致性 | 文档承诺只对 `pdf-import:{...}/default.md` 生成文件追溯路径，但校验器没有强制路径末段为 `default.md`，伪造的 `pdf-import:` 来源可能得到误导性文件操作 | 已收紧服务端来源校验并补回归测试；实现审查计数归零，真实栈复验后重新开始三轮 |
