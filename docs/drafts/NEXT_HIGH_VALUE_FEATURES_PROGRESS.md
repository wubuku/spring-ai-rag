# rerank 后文档级证据去冗余进度

> **状态**：规划连续审查和文档门禁已完成；待 Git 交付
>
> **开始日期**：2026-08-23
>
> **当前分支**：`docs/next-retrieval-quality-plan-20260823`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：本地 `main` / `origin/main` @ `0fd37b6d`
>
> **实施规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)

本文件是跨会话恢复账本。每次取得关键进展时，先更新本文件，再进入下一阶段。它不替代
当前代码、自动化测试或双语长青文档；实施完成后应与 plan 一起归档。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 最新 main 与文档生命周期核对 | 已完成 | `main`、`origin/main` 和上一轮交付均为 `0fd37b6d`；上一轮 plan/progress 已归档，稳定事实已进入双语长青文档 |
| 下一批候选探索 | 已完成 | 对比文档级去冗余、跨入口多查询扩展、`EACH_COLLECTION`、权限与用量账本 |
| 新规划编写 | 已完成 | 冻结 provider 完整排名、两阶段文档选择、默认 cap=2、回填和验收矩阵 |
| 规划连续审查 | `3/3` | 三轮固定范围、只读检查连续无实质问题；规划正文期间未修改 |
| 文档门禁、commit、push | 进行中 | 文档、并发规则和空白门禁已通过；待 commit、远端同步和 push |
| 生产代码实施 | 未开始 | 本阶段只规划，不修改生产代码 |

## 2. 已冻结的关键决策

- 本轮只实施 rerank 后文档级证据去冗余。
- 新配置 `rag.rerank.preferred-max-chunks-per-document`，默认 `2`、范围 `0..100`；
  `0` 明确关闭。
- provider 在已有有界候选池内返回完整有序排名；不增加 SQL、embedding 或 Chat LLM 调用。
- 配置值是多样化第一遍的软上限；候选文档不足时按 provider 原排名回填同文档 chunk，
  最终列表可以超过该值。
- 最终结果是 provider 排名的子序列，保持 score 顺序；数量为
  `min(finalLimit, providerRankedCount)`，不补齐 provider 自身欠量。
- null/blank `documentId` 不互相合并，避免错误丢弃未知身份候选。
- provider 异常的外层 degraded fallback 保持现有原候选截断，不执行新 cap。
- 只有全局 rerank、有效 provider、候选数和 candidate limit 都大于 final limit 且
  `0 < cap < finalLimit` 时才扩大 provider ranking depth。
- selector active 时，service 将 provider 输入和 ranking depth 一起限制到
  `candidate-limit <= 100`，避免直接调用方放大 HTTP 请求体。
- 设置 cap=`0` 可恢复当前 provider top N 行为。
- 跨入口 query expansion、`EACH_COLLECTION`、权限和用量账本不进入本轮。

## 3. 探索结论

1. 上一轮 candidate pool 已提供默认 20、最大 100 的安全候选上界。
2. 所有生产 rerank 调用方已经集中使用 `ReRankingService`，适合在共享层实现。
3. heuristic 的文本 diversity 是软分数且不知道 document identity；HTTP provider 不共享。
4. provider 当前按 final N 提前截断，因此必须先分离 ranking depth 与 final limit。
5. 两阶段选择后按原 index 输出，可同时保证优先文档覆盖、必要回填、结果数量和 score 顺序。
6. 现有 goldenset 每文档单 chunk，不能证明收益；需要真实 PostgreSQL 的确定性多 chunk
   集成夹具，并继续跑既有真实 embedding 回归防止退化。
7. 本轮不改前端，但 Search JSON/Chat sources 是共享契约，前端门槛仍适用。
8. 第一轮契约审查发现 `100` 不是所有调用方的绝对关闭值，且关闭/no-op 路径不应扩大
   provider 返回深度；已改为 `0` 关闭并冻结 selector 生效条件，检查计数保持 `0/3`。
9. 后续契约审查发现回填会突破绝对每文档上限，已将配置重命名为
   `preferred-max-chunks-per-document` 并明确为第一遍软上限，检查计数重置为 `0/3`。
10. provider/成本审查发现共享 service 不能假设所有直接调用方都传入不超过 100 项，
    已增加 selector active 时按 `candidate-limit` 同时限制 provider 输入和 ranking depth，
    检查计数重置为 `0/3`。
11. 数量语义审查发现 provider 自身可能少返回，已将选择器数量不变量精确为
    `min(finalLimit, providerRankedCount)`，不从未排名原候选伪造补齐，检查计数重置为 `0/3`。
12. 验收审查发现原方案没有冻结 `dev.sh` 多 chunk fixture 的可处置数据库边界，且
    `<1ms` 墙钟断言会形成脆弱门禁；已增加专用 acceptance runner、隔离 env/端口/数据库
    与清理规则，并改用确定性 O(n) 复杂度断言加运行时 p95/HTTP payload 观测，检查计数
    重置为 `0/3`。
13. runner 可实施性审查发现可处置数据库不能使用 goldenset `--skip-create`，临时 root key
    必须显式传给 real Playwright，且同一 worktree 的 `.dev` 状态不能覆盖已有运行栈；
    已冻结 fixture 创建、shell-only credential、启动前拒绝占用和多文档 KNOWLEDGE smoke
    规则，检查计数保持 `0/3`。
14. citation 审查发现 AGENT 多次检索会保留 attempt 内 first-seen citation ID，不能笼统
    声称每次最终列表都重新分配连续 `[S1]`、`[S2]`；已改为选择器不改 citation、首次检索
    按最终顺序分配、跨 tool call 保持稳定 ID，检查计数重置为 `0/3`。
15. 正式代码可行性审查发现外层异常策略并非所有调用链都降级：受管 Search、
    `ProjectRerankPostProcessor`、`KnowledgeSearchTool` 和 `JsonRecordService` 会回退，
    兼容 Search、Evaluation 与旧 Advisor 会传播异常；已按现状冻结分路径语义，并把
    `RetrievalTraceCollectorTest`、`RerankAdvisorTest` 加入聚焦矩阵，检查计数重置为
    `0/3`。
16. 重启后的代码可行性审查发现 selector 不能重新解释原始 provider 配置，否则 HTTP
    别名、未知名称回落 heuristic 和显式注入 no-op provider 会与实际执行对象分叉；已冻结
    使用规范化后的 `provider.getName()`，并增加相应测试矩阵，检查计数重置为 `0/3`。
17. 全范围预收敛发现当前 Search UI 不展示 `chunkIndex` 或原始数值 `score`，真实
    Playwright 不能要求 DOM 对这些字段逐项复现；已改为网络 JSON 断言完整检索字段，DOM
    断言标题、片段、展示顺序和结果计数，检查计数保持 `0/3`。
18. 算法预收敛发现“按原 index 输出”若保持无排序 O(n) 实现，清晰方案是第一遍选择、
    第二遍回填、第三遍按原顺序输出；已把过严的“两遍/每项最多两次”测试改为常数次线性
    扫描且总访问不超过 `3 * candidateCount`，检查计数保持 `0/3`。
19. 正式代码可行性审查发现独立 `demos/demo-component-level` 仍调用已移除的单参数
    rerank 构造器。实际执行 `mvn -f demos/demo-component-level/pom.xml clean
    test-compile -DskipTests` 证明基线共有 5 个 API 漂移编译错误，另外 4 个与本轮无关；
    该 demo 不在根 reactor。已把“所有生产调用方”限定为 core，并冻结本轮不修 demo、
    不恢复旧构造器、保持 SPI 签名不变，检查计数重置为 `0/3`。
20. 前端入口审查确认 WebUI Search 只调用 GET `/search`，而该入口明确
    `useRerank=false`；页面请求不能证明本功能。已改为 real Playwright 经 Vite proxy
    直接调用 POST Search 证明配置与 JSON，再用实际 Search 页面 GET 验证 DOM/代理兼容，
    检查计数重置为 `0/3`。
21. 验收 runner 认证审查发现 goldenset 只读取 `API_KEY/RAG_API_KEY`，不会自动读取
    临时 `RAG_ROOT_API_KEY`；root 模式下沿用 `.env` 的旧业务 key 可能被拒绝。已冻结
    goldenset 显式传 `API_KEY`、regression/LLM/Playwright 传 `RAG_ROOT_API_KEY`，均使用
    同一个 shell-only 临时 root key，检查计数重置为 `0/3`。
22. 最终测试矩阵核对发现 provider 别名和未知名称 fallback 已成为冻结契约，但聚焦清单
    未包含工厂测试；已把 `RerankProviderFactoryTest` 纳入一次性矩阵，检查计数保持
    `0/3`。

## 4. 规划检查规则

固定三轮范围：

1. 价值、目标/非目标、默认值、回填、score/order 和兼容语义；
2. provider、共享服务、调用链、identity、citation、异常降级、配置和成本；
3. PostgreSQL/质量/性能/前端/真实运行/LLM、文档、回滚和 Git 交付。

发现实质问题立即修改 plan 并把计数重置为 `0`。无问题轮次不修改 plan/progress。
达到连续 `3/3` 后一次性记录三轮摘要和 plan SHA-256。

## 5. 最终规划检查记录

冻结规划 SHA-256：
`3f82f3b25e5998a6f5d6e454f59b5f8b8bbe6eccd9412f1b360f90f2f55179db`。

| 轮次 | 检查时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1 | 2026-08-23 20:03 UTC | 价值、目标/非目标、默认值、回填、score/order 与兼容语义 | 无 | 无修改 | 连续计数 `1/3` |
| 2 | 2026-08-23 20:04 UTC | provider/SPI、共享服务、core 调用链、identity、citation、分路径失败语义、既有测试与 demo 边界 | 无 | 无修改 | 连续计数 `2/3` |
| 3 | 2026-08-23 20:07 UTC | PostgreSQL 夹具、Maven/前端门槛、真实 POST Search 与 GET DOM、可处置运行时、质量脚本、真实 LLM、双语文档、回滚与 Git 交付 | 无 | 无修改 | 连续计数 `3/3`，规划检查完成 |

## 6. 规划交付门禁

2026-08-23 20:07 UTC 已通过：

- `./scripts/verify-project-docs.sh`：10 项检查全部通过；
- `./scripts/verify-no-pessimistic-locks.sh`：生产源码未发现显式悲观锁或 advisory lock；
- `git diff --check`：通过；
- 活跃 plan/progress 尾随空白和文件末尾换行检查：通过；
- plan SHA-256 复核：仍为
  `3f82f3b25e5998a6f5d6e454f59b5f8b8bbe6eccd9412f1b360f90f2f55179db`。

## 7. 下一恢复入口

1. 读取 plan §4、§5、§6 和本文件；
2. 核对 `git status`、当前分支、HEAD 与 `origin/main`；
3. 规划审查已达到 `3/3`，不得重新修改冻结规划正文；如发现实质设计问题，记录原因并重置；
4. 文档门禁已经通过；完成 commit、fetch/merge remote、push；
5. 用户要求实施时，从最新 `main` 建立/调整专用实施分支，再按 plan 测试矩阵一次性推进。
