# KNOWLEDGE 多查询证据合并优化进度

> 状态：实施与基本集成硬门槛完成，实现连续检查 `3/3` 完成，待 Git 交付
>
> 开始日期：2026-08-24
>
> 当前分支：`feat/knowledge-evidence-joiner-20260824`
>
> 当前 worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 代码基线：`7c7d846b`（`origin/main`）
>
> 实施规划：[2026-08-24_KNOWLEDGE_EVIDENCE_JOINER_PLAN.md](2026-08-24_KNOWLEDGE_EVIDENCE_JOINER_PLAN.md)

本文是跨会话恢复账本，不替代代码、测试或双语长青文档。每次取得关键进展时，先更新
本文，再进入下一阶段。只记录不含密钥的状态、决策、证据路径和恢复入口。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 合入上一轮 main 后完整复验 | 已完成 | 代码门槛、PostgreSQL、Maven、WebUI、Mock Playwright 和启动烟测通过；真实阶段已用特性 worktree 的 `.env` 重跑 |
| 最新 main 与 worktree 核对 | 已完成 | 新分支基于 `origin/main@7c7d846b`；当前只有本轮 plan/progress 未提交 |
| 代码与长青文档探索 | 已完成 | 已核对 Spring AI 默认 joiner、项目 mapper、KNOWLEDGE advisor、rerank、trace 和相关测试 |
| 下一批候选比较 | 已完成 | 选定 KNOWLEDGE 项目自有 evidence joiner |
| 规划编写 | 已完成 | 已冻结 identity、最佳 score、稳定排序、低基数摘要和模式边界 |
| 规划连续检查 | `3/3` | 预收敛修正后连续三轮固定范围检查无问题，规划 SHA-256 已冻结 |
| 生产实现 | 已完成 | joiner、trace、KNOWLEDGE wiring、一次性测试与真实 KNOWLEDGE JSON 验收资产已落下 |
| 基本集成硬门槛 | 已完成 | 完整 Chat capability runner `18/18` 通过；blank identity 测试补强后，聚焦测试、`mvn clean compile test-compile` 与全量 `mvn test` 再次通过 |
| 实现连续检查 | `3/3` | 修复后重新计数，连续三轮限定范围只读检查无问题、无修改 |
| Git 交付与 main 合入 | 未开始 | 特性验证完成后执行 |

## 2. 已冻结的关键决策

- 本轮只替换 KNOWLEDGE advisor 的文档 joiner。
- 同一非空 `Document.id` 只保留一个对象，项目生产 ID 为 `documentId:chunkIndex`。
- 重复项优先保留有限最高 score 对象；相同分数按 query 文本和列表位置建立的规范遍历
  顺序保留对象，不依赖 Spring AI 的 `HashMap` 遍历顺序。
- null/blank ID 的对象不互相合并。
- 输出先按有限 score 降序，再按 ID/匿名输入位置稳定排序；有限负分仍排在
  null/NaN/Infinity 前；不在 joiner 中截断最终结果。
- 只增加低基数 `documentJoin` trace 摘要，不写 query、正文、ID 或 metadata 值；
  Chat response 与持久化 attempt metadata 输出同一摘要。
- `ProjectDocumentJoiner` 无状态且由 factory 内部持有；不新增 Spring bean 或改变
  `ModeAwareChatClientFactory` 的 public 构造器。
- 不修改 Search、AGENT、JSON record、Evaluation、legacy advisor、权限、用量账本或数据库 schema。
- 不新增配置、前端控件、embedding/LLM/rerank provider 调用。
- 规划和进度文档单语；稳定事实完成后同步双语长青文档并归档本轮 draft。

## 3. 规划检查账本

固定检查范围：

1. 价值、范围、identity、最佳 score、排序和回滚；
2. Spring AI advisor、并发、授权、trace、模式边界、API 兼容和成本；
3. 测试矩阵、PostgreSQL/真实运行、前端共享契约、文档、Git 和恢复。

发现实质问题必须修改规划并把计数器归零；无问题轮次不修改规划正文。

| 连续轮次 | 时间 | 范围 | 发现/处理 | 结果 |
|---:|---|---|---|---|
| 0/3 | 2026-08-24 12:54 CST | 第一轮：价值、范围、identity、score、排序、Spring AI join API 与 trace | 发现父 session 持久化未纳入范围；`HashMap` 下同分“先遇”不稳定；负有限分与无效分排序矛盾；匿名/有 ID 同分顺序未定义 | 已扩展 `RetrievalTraceSession` 范围，冻结规范 query 遍历、完整 score 分类和输出顺序，并补齐 response/persisted trace 测试 | 有实质修改，计数重置为 `0/3` |
| 0/3 | 2026-08-24 12:56 CST | 可编码性复核：Spring AI `Query`/`Document` API、规范排序与复杂度 | 发现不存在可直接使用的 Query `context-free` 稳定描述；正常 Document 构造不允许空 ID；候选表和风险表仍残留 O(n)/非有限按 0 的旧表述 | 将生产前提冻结为项目 expander 保证 query 文本唯一，按 query 文本与原列表顺序规范遍历；空 ID 改为防御性边界；统一复杂度和分数分类 | 有实质修改，计数保持 `0/3` |
| 0/3 | 2026-08-24 12:57 CST | 算法分支完备性：同一 identity 的多个无效 score | null、NaN、Infinity 之间的重复候选选择和 replacement 计数未明确 | 冻结为保留规范首个对象，无效分之间不计 replacement；加入一次性单测矩阵 | 有实质修改，计数保持 `0/3` |
| 0/3 | 2026-08-24 12:58 CST | advisor wiring、依赖边界与兼容性 | 原规划的构造注入会改变 public factory 构造契约，而 joiner 无状态且不需要容器依赖 | 改为 factory 内部持有并复用 joiner，保持现有构造器与 Spring wiring 不变 | 有实质修改，计数重置为 `0/3` |

最终冻结规划 SHA-256：
`b8836537896dc0e0479c713ee0a44aae9c04266fb333ca2615dab5cc10228e67`。

连续无修改检查摘要：

| 轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|---|
| 1/3 | 2026-08-24 12:59 CST | 价值与范围、identity、query 唯一性、有限/无效 score 全分支、排序、复杂度与回滚 | 无 | 无修改 | 连续计数 `1/3` |
| 2/3 | 2026-08-24 13:00 CST | Spring AI advisor、attempt context、collector/session 并发与持久化、Chat metadata、模式隔离、public 构造兼容和成本 | 无 | 无修改 | 连续计数 `2/3` |
| 3/3 | 2026-08-24 13:00 CST | 后端/PostgreSQL/Maven、前端与 Mock Playwright、隔离真实 LLM、双语文档、归档、Git 与恢复入口 | 无 | 无修改 | 连续计数 `3/3`，规划检查完成 |

## 4. 预定实施文件与测试

生产代码：

- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentJoiner.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/RetrievalTraceCollector.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/diagnostics/RetrievalTraceSession.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ModeAwareChatClientFactory.java`

测试：

- 新增 `ProjectDocumentJoinerTest`
- 扩展 `RetrievalTraceCollectorTest`
- 扩展 `RetrievalTraceSession`/diagnostics 相关断言
- 扩展 `ModeAwareChatClientFactoryTest`
- 扩展多查询 Chat/检索集成测试
- 运行既有 `HybridRetrieverRrfPostgresIntegrationTest` 与 Chat capability runner

文档：

- 双语 `architecture`
- 双语 `chat-memory-rag-tool-calling`
- 双语 `rest-api`
- 双语 `testing-guide`
- 双语 `quality-defaults`

## 5. 验收证据位置

本轮计划使用独立 run ID 保存到：

```text
.verification/knowledge-evidence-joiner/<run-id>/
```

证据至少包含 focused backend、PostgreSQL、Maven、WebUI、Mock Playwright、isolated
startup/real Chat、文档门禁、lock gate、三轮实现检查和 Git 状态摘要。日志中不得出现
密钥。

## 6. 下一恢复入口

1. 读取本文和 `2026-08-24_KNOWLEDGE_EVIDENCE_JOINER_PLAN.md`；
2. 确认 `git status --short --branch` 仍为当前分支且无意外修改；
3. 规划检查已达到 `3/3`，按 plan Slice B/C 一次性实现生产代码与测试；
4. 每完成一个实施切片，先更新本文，再执行下一切片；
5. 基本硬门槛全部通过后，才执行实现三轮检查；
6. 任何实质修复后重置实现计数，并从受影响门槛开始重跑。

## 7. 实施记录

### 2026-08-24 核心实现与一次性测试

- 新增无状态 `ProjectDocumentJoiner`：
  - 按唯一 query 文本建立规范遍历顺序；
  - 以非空 `Document.id` 去重，匿名对象独立保留；
  - 同一 identity 保留最高有限 score；
  - 输出按有限 score、identity 与输入位置稳定排序；
  - 记录四个整数的低基数 join 摘要。
- `RetrievalTraceCollector` 与 `RetrievalTraceSession` 已同步
  `documentJoin` 到 Chat response summary 和持久化 attempt metadata。
- `ModeAwareChatClientFactory` 仅在 KNOWLEDGE advisor 显式使用项目 joiner，public
  构造器保持不变。
- 新增/扩展算法、trace、factory 多查询和 Chat result metadata 测试；新 joiner 测试已
  纳入 `verify-chat-capability.sh` focused 列表。
- 双语长青文档已同步架构、Chat、REST metadata、质量默认和测试指南。
- `spring-ai-rag-webui/e2e/chat-real.spec.ts` 已增加经真实 Vite 代理的 KNOWLEDGE
  JSON 验收，复用临时文档并断言答案、来源、检索执行、四整数 join 摘要、低基数
  不泄露和 citation 状态。
- 聚焦后端测试最近一次结果：`38` tests，`0` failures，`0` errors，`0` skipped。
- WebUI `npm run typecheck`、项目文档门禁 `10/10` 和 `git diff --check` 已通过。
- 完整 Chat capability runner 结果：`18` passed，`0` failed，`0` skipped；证据位于
  `.verification/knowledge-evidence-joiner/20260824-pre-review/summary.md`，包含
  PostgreSQL 集成矩阵、Maven、隔离启动、WebUI、Mock Playwright 和真实
  LLM/Playwright/provider 验收。
- 实现第 3 轮审查发现原测试只覆盖 null identity，没有明确证明相同 blank identity
  仍独立保留；已一次性补强 `ProjectDocumentJoinerTest`，实现代码未改变，审查计数
  重置为 `0/3`。
- 测试补强后，聚焦测试 `38/38`、`mvn clean compile test-compile` 和全量
  `mvn test` 均通过；全量测试中 `spring-ai-rag-core` 为 `2940` tests、
  `spring-ai-rag-starter` 为 `44` tests，均无失败或错误。

## 8. 实现检查账本

修复后的固定检查范围：

1. join 算法、identity、score 分类、稳定排序与一次性测试矩阵；
2. Spring AI advisor 接线、attempt trace、父级持久化、并发与模式隔离；
3. API/双语文档、真实 Playwright、项目门禁与 Git 交付边界。

| 连续轮次 | 时间 | 范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|---|
| 1/3 | 2026-08-24 13:28 CST | joiner 算法、稳定排序、null/blank identity、有限/无效 score、测试矩阵 | 无 | 无修改 | 连续计数 `1/3` |
| 2/3 | 2026-08-24 13:29 CST | Spring AI 1.1.8 join/advisor 源码、KNOWLEDGE wiring、trace 生命周期、父级 metadata、并发与构造兼容 | 无 | 无修改；聚焦测试再次通过 | 连续计数 `2/3` |
| 3/3 | 2026-08-24 13:30 CST | REST/双语长青文档、真实 Playwright JSON 断言、文档/锁/whitespace 门禁与工作区范围 | 无 | 无修改；文档门禁 `10/10`、锁检查与 whitespace 通过 | 连续计数 `3/3`，实现检查完成 |

下一步：提交全部修改，fetch/merge 最新 `origin/main`；若基线变化，按合并后代码重新
执行完整验收，再推送特性分支并合入 `main`。
