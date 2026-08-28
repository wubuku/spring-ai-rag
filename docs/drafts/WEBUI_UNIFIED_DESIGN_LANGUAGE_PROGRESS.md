# WebUI 统一设计语言实施进度

> **对应规划**：[WEBUI_UNIFIED_DESIGN_LANGUAGE_PLAN.md](WEBUI_UNIFIED_DESIGN_LANGUAGE_PLAN.md)
> **日期**：2026-08-28
> **状态**：本批次实施完成，待 Git 交付
> **工作区**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> **分支**：`feature/webui-unified-design-language`
> **实施基线**：`origin/main@c36bd43e`

## 1. 当前约束

- 用户已授权规划完成后直接实施，不等待中途决策。
- 当前为串行开发，只使用主工作区，不创建额外 worktree。
- 不覆盖、不 stash、不丢弃工作区已有或并发产生的修改。
- 规划/进度属于单语过程文档；稳定规则在交付前提升到双语长青文档。
- 前端验收只使用 DOM、ARIA、URL、网络、JSON、computed style、contrast 和 geometry，
  不使用截图作为通过证据。
- Mock 门槛通过后执行真实前后端与必要的真实 LLM/Embedding 验收。
- 后端非本任务生产范围；仍必须通过 `mvn clean compile test-compile` 和服务启动门槛。
- 顶级页面之间不得泄漏滚动位置；路由 pathname 切换后，Layout 主滚动容器必须回到顶部，
  同一页面内仅 query/state 变化时不强制重置。

## 2. 已完成

| 阶段 | 状态 | 证据 |
|---|---|---|
| 上一轮 plan/progress 归档 | 完成 | `docs/drafts/archive/2026-08-28_NEXT_HIGH_VALUE_FEATURES_*` |
| 新规划与 drafts 索引 | 完成 | `main@495e7fce` |
| 并发规划漂移纠正 | 完成 | 保留 design debt、Tabs、token 导入等有效改进，恢复直接实施与单工作区边界 |
| 规划连续三轮无修改审查 | 完成 | `main@58dac02c`，项目文档门禁 11/11 |
| 特性分支同步 main | 完成 | `feature/webui-unified-design-language@58dac02c` |
| 页面滚动泄漏复现 | 完成 | API Keys 滚到底后进入 Files 会继承同一个 `<main>` scrollTop |
| 最新主线同步 | 完成 | 保留现有 WIP，将特性分支快进到 `origin/main@c36bd43e` |
| 滚动回归单元测试 | 完成 | Layout Vitest 3/3；覆盖 `<main>`、documentElement、body 的滚动清零 |
| 滚动回归 Mock Playwright | 完成 | API Keys 80 条真实 DOM 长列表，连续三次切换到 Files，滚动位置均为 0 |
| 前端基本门槛 | 完成 | typecheck、Vitest 251/251、lint/alignment/design-token、production build |
| 完整 Mock Playwright | 完成 | `BASE_URL=http://127.0.0.1:15173`，88/88 通过 |
| 仓库级交付门禁 | 完成 | 文档 11/11、禁悲观锁、shell 语法、diff whitespace、added-line secret scan 全部通过 |
| 后端编译门槛 | 完成 | `mvn clean compile test-compile` BUILD SUCCESS |

## 3. 实施切片

| 切片 | 状态 | 下一退出条件 |
|---|---|---|
| Slice 1：行为与设计验收基线 | 本批次完成 | 当前滚动泄漏行为已闭环；完整 Mock 与交付门禁通过 |
| Slice 2：Token、Theme 与机器门禁 | 待开始 | 生成幂等、主题合同和设计债务门禁通过 |
| Slice 3：Primitive 与 Shell | 待开始 | 公共组件、导航和主题在四视口可访问且稳定 |
| Slice 4：Chat/Search/Knowledge 页面 | 待开始 | 高频工作流迁移且行为测试无回归 |
| Slice 5：Operations/Quality/Admin 页面 | 待开始 | 13 个顶级 route 统一页面层级 |
| Slice 6：债务收口与双语长青文档 | 待开始 | 全量门禁、文档与交付材料完成 |

## 4. 验证基线

- 当前 WebUI：34 个 Vitest 文件、251 个测试；完整 Mock Playwright 88/88。
- 当前样式：28 个 CSS Module、约 6288 行 CSS、151 个 JSX button、约 400 行字面颜色。
- 当前 dev 栈：后端 `18082`、前端 `15173`，由 `scripts/dev.sh` 管理。
- 最终隔离验收会使用不同端口，避免复用日常服务状态。

## 5. 本批次实现

- `Layout` 为顶级路由 pathname 变化建立滚动边界：同步清零 `<main>`、`documentElement` 和 `body` 的纵横向位置，并在下一帧再次清零，以覆盖路由内容装载和浏览器滚动锚定时序。
- 同一页面内仅 query/state 变化时不触发该重置，因此不会破坏 Search、Files 等页面的 URL 状态恢复。
- Layout 单元测试验证主滚动容器及文档滚动容器；Navigation Mock Playwright 使用 80 条真实 DOM 长列表，连续三次从 API Keys 切换到 Files，断言 window/document/main 均回到顶部。
- 本批次未修改后端、API、数据库、Embedding 或 LLM 路径，真实 LLM/Embedding 验收对本批次不适用。

## 6. 最终验证证据

1. `npm run typecheck`：通过。
2. `npm run test:run`：251/251 通过。
3. `npm run lint`：ESLint、alignment、design-token 检查通过。
4. `npm run build`：生产构建通过。
5. `BASE_URL=http://127.0.0.1:15173 npm run test:e2e`：88/88 通过；包含三次滚动泄漏回归和全部核心 Mock 页面。
6. `mvn clean compile test-compile`：`BUILD SUCCESS`。
7. `./scripts/verify-project-docs.sh`：11/11 通过。
8. `./scripts/verify-no-pessimistic-locks.sh`：通过。
9. `find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n`、`git diff --check`、新增行密钥扫描：通过。

## 7. 恢复入口

下一步提交并推送当前特性分支，随后合并并推送 `main`，确认 `main == origin/main` 且工作区干净。本批次交付后暂停，不自动开启下一轮规划。
