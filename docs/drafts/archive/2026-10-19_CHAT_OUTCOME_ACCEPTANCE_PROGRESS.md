# Chat 验收分层实施记录

日期：2026-10-19；基线 `d8b525b6`；分支 `test/chat-outcome-acceptance`。
规划：[Chat验收分层规划](2026-10-19_CHAT_OUTCOME_ACCEPTANCE_PLAN.md)。

- 初始工作区干净；已核对真实浏览器用例、SSE/历史接口、拒答归因、受控provider及已有外部API验收。
- 只修改测试和验收文档；不改业务逻辑，不放宽正向回答断言，不绕过模型安全拒答。
- 下一步：规划连续3/3后实施；证据保存在`.verification/chat-outcome-acceptance/`，不提交私有数据。
- 11:06:45/11:06:58/11:07:11 规划连续3/3：目标与分层、SSE/历史/API可行性、验收与隔离清理。
  三轮无问题且无修改，开始实施；不将普通文本拒答猜测为结构化安全归因。
- 11:12 三个真实用例、共享生命周期断言和三类拒答专项已实现，ESLint/测试文件TS编译/list通过。
  首次受控浏览器三项均在调用provider前HTTP403，定位为新隔离Vite端口未列入CORS允许来源；
  仅补运行器自有loopback来源，与dev.sh同样配置，不修改生产CORS或放宽HTTP断言。首次日志保留。
  WebUI完整门禁运行中；后续统一运行Maven、外部API/SDK/双实例与真实模型/dev.sh。
- 11:18 按既有Native SSE的choices[].delta.content修正测试读取（并非缺少content事件）。
  文本/结构化拒答已通过；过滤拒答进一步揭示Chromium DevTools response.text的无charset SSE
  中文解码差异，而真实页面与历史中文正确。改为被动克隆浏览器fetch响应，以浏览器Response.text
  读取真实字节，不拦截/伪造后端、不转换或放宽中文一致性断言；保留三次失败证据。
  WebUI typecheck、1030单测、lint/设计门禁、production build、93 Mock Playwright全部通过。
- 11:19 三类拒答真实浏览器3/3通过：每类拒答及同会话后续回答共6次调用全部SETTLED，
  无遗留RESERVED或丢失usage，普通文本不假冒安全归因；独立报告为BUSINESS_HANDLING。
  证据`.verification/chat-outcomes/20261019-111848-0e805628/`；不覆盖前述失败记录。
  进入严格串行Maven、四协议/SDK、双实例、真实模型及dev.sh最终验收，当前约60%。
- 11:27 clean compile/test-compile与全量Maven通过。首次最终执行器全局source .env导致
  RAG_TIMEOUT_CHATASKMS覆盖上游专项的1500ms，预期超时变成正常回答；修正私有执行器，
  受控验收不加载真实模型配置，仅dev-real阶段私下加载.env。保留失败，不改业务或超时断言。
  同时补探针创建中途失败与历史清理失败时的资源回收，保证独立用例不遗留自己的资料。
- 11:35 严格TS编译和测试文件ESLint复验通过；全量Maven统计8801项、226门控跳过、8575项实际执行，
  0失败/错误。受控环境隔离后四协议/两语言SDK与候选/回答契约专项通过，不修改原超时门槛。
  双实例已验证三类拒答、撤权/删除、幂等与72例混载，继续完成恢复和真实模型/dev.sh阶段。
- 11:40 最终执行器通过：clean compile、全量Maven、上游错误/超时/SDK、候选恢复、双实例/备份恢复、
  `dev.sh`真实启动/状态/停止、真实Embedding/LLM smoke、live OpenAPI兼容、拆分后的真实浏览器
  以及文档/禁锁门禁；dev-real记录生命周期、Agent正向、KNOWLEDGE正向和API Client四个独立结果。
  `final-summary.json`保留第一次错误运行及修正后成功运行，当前结论只采用修正后的成功记录。
- 最终真实证据`.verification/quality-dev/20261019-113659-a898e9db/`：API Key生命周期、PLAIN生命周期、
  Agent正向和KNOWLEDGE正向共4/4通过；真实Embedding/LLM API13/13；dev.sh自有启停和live
  OpenAPI旧Client基线通过。拒答专项另为3/3，并非以拒答替代本轮正向成功。
- 实现连续3/3：11:42:24检查测试分层、强断言、资源回收及运行证据；11:42:44检查双语文档、
  命令、API兼容边界；11:43:06–11:43:07检查全量证据、凭据和自有资源清理。均无新增问题、
  无实现修改，完成后一次性记录。生产Java、API schema、WebUI src和模型策略均无变更。
- 交付准备：长青双语测试/开发者文档已同步，规划与记录按生命周期归档；随后commit、fetch，
  上游若变化则merge并复验，再回合并并push main、确认干净。Git结果以实际命令输出为准，
  不在记录中预写未来提交号。当前功能与验收完成，不再扩展范围。
