# Chat 正向能力与异常/拒答处置分层验收

日期：2026-10-19；基线：`main@d8b525b6`；分支：`test/chat-outcome-acceptance`。
进度见[实施记录](2026-10-19_CHAT_OUTCOME_ACCEPTANCE_PROGRESS.md)，交付遵循[工作流](../../delivery-workflow-zh-CN.md)。

## 1. 问题、目标与非目标

`spring-ai-rag-webui/e2e/chat-real.spec.ts` 只有一个大用例，依次验证 Agent、历史恢复和
KNOWLEDGE。其材料把随机标记称为 release verification code，要求模型原样返回，且问题已经
包含答案。真实模型曾正常拒答，浏览器正常显示内容，但用例等待来源直到超时，未继续检查生命周期。
这不能证明拒答处置失败，也不能把拒答视作成功检索。

本批只改测试、夹具、运行器及双语测试文档，不改业务实现、API、schema、默认策略或模型安全行为。
不按关键词判断真实模型拒答，不放宽引用/工具/事实断言，不通过重试或切模型规避拒答。

## 2. 已核对事实与推荐方案

- 浏览器以 `/api/v1/rag/chat/stream` 发起 keyed SSE；响应有 `X-RAG-Turn-Id`，成功通过
  done 提供 sessionId，历史字段为 `aiResponse/status/sources/metadata`。
- `Chat.tsx` 正常完成后解除输入禁用；错误通过独立 error 处理；历史 API 真实持久化可独立断言。
- `ModelSafetyRefusalNormalizer` 只依据结构化 refusal/content_filter 归因；普通文本拒答不能
  仅凭文本标记为 MODEL_SAFETY_REFUSAL。
- `scripts/verify-quality-production-e2e.py` 已覆盖三种拒答、双实例、JSON/SSE、SDK及预算收尾；
  `scripts/verify-upstream-recovery-e2e.py` 已覆盖上游错误、deadline、断连、重试与恢复。
  本批复用这些外部API验收，不将浏览器Mock当作服务验收。

### 2.1 真实模型用例分层

拆为三个独立测试，删除 serial 依赖，单项失败不跳过其余项：

1. 生命周期：PLAIN 请求正常结束、界面可继续发送、历史为 COMPLETE、保存内容与返回一致、
   刷新后恢复。只证明交互/持久化正确，不宣称事实正确或检索成功。
2. 正向 Agent：使用临时公开观测站指南，问题不带答案；严格验证开馆时间、真实工具调用、来源、
   executionBudget 与历史。遇拒答仍不能通过正向能力；先检查终态和历史，再检查语义，避免等来源
   超时掩盖已经正确完成的请求。
3. 正向 KNOWLEDGE：单独通过真实 API 验证时间事实、来源、retrievalExecuted、documentJoin
   统计及引用；保留已有强断言，避免 Agent 未满足使其完全不执行。

共享 helper 只处理登录、SSE 完整读取/唯一 done/无 error、历史和页面恢复，不包含事实通过判断。
探针文本使用普通开馆时间09:40与无指令的资料；随机标记仅用于临时资源命名，不放进目标答案。
问题要求HH:mm用于确定性事实检查，不要求输出口令或验证码。所有临时session/document/collection
以既有 revision 删除契约清理；先取得SSE的sessionId，语义失败也能清理历史。

### 2.2 确定性拒答浏览器专项

新增 `chat-outcomes-real.spec.ts`，名称 `*-real` 保证默认Mock集不误执行。
新增 `scripts/verify-chat-outcomes-e2e.py` 复用现有隔离PG/真实后端/受控provider基座，启动自有
loopback Vite代理，不使用page.route/mock后端。控制地址必须由运行器显式注入且限定loopback，
未配置即失败，不对生产地址执行故障注入。

三个独立PLAIN浏览器用例分别注入普通文本拒答、结构化refusal、content_filter：
- 真实HTTP200、SSE恰好一个done且无error、非空正常展示、不出现错误气泡；
- 同一请求上游调用恰好一次，历史COMPLETE，内容与界面一致；
- 结构化/过滤拒答有明确归因，普通文本不假冒明确归因；
- 不要求拒答有来源或工具调用，不伪造检索成功；刷新恢复后，同会话恢复普通回答并确认可继续使用；
- 全部完成后等待真实reservation归零并验证无丢失usage；失败时保留私有日志，finally只清理自有资源。

测试标题明确区分 lifecycle / positive-answer / refusal-handling，结果分别报告。
拒答专项通过不覆盖或替换真实模型正向失败；不新增“拒答即跳过/通过”的分支。

## 3. 实施文件与兼容边界

1. `e2e/support/chat-lifecycle.ts`：公共生命周期断言；仅测试依赖。
2. `e2e/chat-real.spec.ts`：替换资料、三个独立测试与无损清理。
3. `e2e/chat-outcomes-real.spec.ts` + `scripts/verify-chat-outcomes-e2e.py`：拒答真实浏览器与记账验收。
4. `docs/testing-guide{,-zh-CN}.md`、`developer-reference{,-zh-CN}.md`：命令、分层与结果解释。
不更改生产API、WebUI交互或Java实现，因此无数据迁移；回滚仅回退测试/文档提交。

## 4. 一次性验收与检查

- 测试静态检查：Playwright list、测试文件ESLint、Python编译；前端typecheck/Vitest/build与Mock
  Playwright整套、现有设计门禁；有需要增加测试helper自测，不新增不必要的测试框架。
- 基本后端门槛：`mvn clean compile test-compile`、`mvn test`（严格串行，跳过项单列）。
- 真实服务：新增拒答浏览器专项；既有upstream/candidate四协议、SDK与双实例拒答/恢复脚本。
- 真实模型：`verify-dev-lifecycle-e2e.py --ack-paid-models`，验证dev.sh启动/停止、真实Embedding、
  LLM、三个拆分用例及API Key浏览器；不重跑与本批无关的96例内容矩阵掩盖历史质量差异。
- 文档、禁锁、diff/凭据门禁；只在自有隔离库运行，不打印.env或Maven凭据。
- 规划与实现分别连续3/3；实质问题修复后计数归零并复验受影响门槛。完成后归档、commit、
  fetch并必要时merge复验、合回并push main，确认干净后停止。
