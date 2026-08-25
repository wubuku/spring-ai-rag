# Chat 资源知识、运行时 Skill 与记忆演进实施进度

> **状态**：缓存隔离修复后的硬门槛已通过，待同步最新主线并完整复验
>
> **对应规划**：[CHAT_RESOURCE_SKILL_MEMORY_EVOLUTION_PLAN.md](CHAT_RESOURCE_SKILL_MEMORY_EVOLUTION_PLAN.md)
>
> **更新时间**：2026-08-25

## 1. 恢复基线

- 特性分支：`feat/chat-resource-skill-memory-evolution-20260825`
- 特性 worktree：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-chat-resource-skill-memory`
- 实施起点：`72f57a94`
- 实施起点的 `origin/main`：`72f57a94`
- 主 worktree：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
- 主 worktree 状态：规划提交已推送，开始实施前干净
- 目标数据库：PostgreSQL profile；集成测试使用一次性数据库或 Testcontainers
- 目标端口：实施 worktree 使用独立端口，不占用日常 `8081`、`18082` 或真实 LLM `18081`

## 2. Slice 状态

| Slice | 内容 | 状态 |
|---|---|---|
| 0 | Spring AI 契约探针、资源 fixture、工具/记忆边界测试 | 完成：确认 Spring AI JDBC 不可恢复原生工具消息，采用 JDBC-compatible projection |
| A | 无 embedding 静态知识目录、索引、KNOWLEDGE/AGENT 接入 | 完成：后端、packaged JAR、全栈与真实 LLM 正负向场景通过 |
| B | Runtime Skill catalog、Level 1/2/3 和后端加载门禁 | 完成：后端、packaged JAR、全栈与真实 LLM 门禁场景通过 |
| C | allowlist HTTP Tool、SSRF/redirect/结果预算 | 完成：专门测试及真实正常、超限、超时、重复调用场景通过 |
| D | Tool-aware Memory、summary projection、cleanup/metrics | 完成：真实 PostgreSQL `16/16`，真实 JSON/SSE/history/PLAIN 记忆通过 |
| E | 领域 SQL provider 示例 | 本轮不实施，保留 SPI/契约边界 |

## 3. 已冻结的实施决策

- 静态资源和 Skill 只接受服务端配置的 `classpath:`、`classpath*:`、filesystem 和
  受限 JAR source；模型输入不能指定路径。
- 静态知识默认关闭，UTF-8 Markdown/text，启动构建 immutable、有界 lexical snapshot，
  不调用 embedding，不写 `rag_document`，P0 不做热加载。
- `KNOWLEDGE` 使用单一 `RetrievalAugmentationAdvisor` 和组合 Retriever；静态 source
  按 `STATIC_KNOWLEDGE` 分区，不进入外部 rerank，不改写成 `DOCUMENT`。
- `AGENT` 只在 snapshot 健康且功能启用时注册 `searchStaticKnowledge`；`PLAIN` 不读取
  静态知识、不注入 Skill catalog、不注册相关 Tool。
- Skill 是不可信操作说明；`loadSkill`/`readSkillReference` 为 attempt-local，真正的
  HTTP/SQL 权限由 server-owned Tool registry、principal、domain 和预算决定。
- HTTP Tool 初期仅配置 allowlist 的只读 GET/HEAD；禁止任意 URL、任意凭据/header、未校验
  redirect、private/metadata IP 和超预算响应。
- SQL 不提供任意 `executeSql(sql)`；领域查询通过固定 schema 的 `RagChatToolProvider`
  扩展。
- 保留现有 JDBC Chat Memory、`rag_chat_history`、session lease、idempotency、summary
  CAS 和共享执行预算；Spring AI `1.1.8` JDBC 只持久化可恢复的用户/助手消息，完整配对
  的工具交换只以有界 `toolTranscript` 进入 history metadata 和摘要输入。
- 不新增 Flyway migration，除非验证中发现已冻结契约无法实现；此时暂停并另建规划。

## 4. 已执行验证

### 规划阶段

- 规划连续三轮实质审查：`3/3`，最后一轮修复了 executable JAR 验收载体不明确问题，
  并重新完成连续三轮。
- `./scripts/verify-project-docs.sh`：通过。
- `./scripts/verify-no-pessimistic-locks.sh`：通过。
- `git diff --check`：通过。

### 实施阶段

| 检查项 | 状态 | 证据 |
|---|---|---|
| Slice 0 契约探针 | 已完成 | ResourceCatalog/静态 UTF-8 fixture 与普通 JAR、classpath、filesystem、限制测试通过；真实 PostgreSQL 证明 Spring AI 1.1.8 JDBC 不可恢复原生 tool call/result，已采用兼容投影 |
| 相关后端 focused tests | 已通过 | 当前固定门槛 `85/85`，覆盖配置、资源、静态检索、Skill、allowlist HTTP、工具预算、Chat 执行、Memory 投影、summary、rerank 与日志 profile |
| PostgreSQL Chat integration | 已通过 | 最终固定门槛使用外部一次性 PostgreSQL 16 数据库真实执行 `16/16`，0 失败、0 错误、0 跳过 |
| `mvn clean compile test-compile` | 已通过 | 最终固定门槛五模块 reactor `BUILD SUCCESS` |
| 双语长青文档 | 已通过 | 配置、架构、项目上下文和 Chat Memory 专题已同步；项目文档门禁 `10/10` 与 `git diff --check` 通过 |
| 前端 tsc/build/Mock Playwright | 已通过 | `typecheck` 通过；Vitest `218/218`；生产 build 通过；Chat Mock Playwright `10/10` |
| 可执行 JAR nested-resource smoke | 已通过 | 最终重打 standalone demo；独立端口 `18183`、一次性 PostgreSQL；健康 `200/UP`、Flyway `48`；包内静态知识 `entries=1/chunks=1`、Skill `entries=1/skills=1`；退出后端口释放且数据库删除 |
| 隔离端口真实全栈 | 已通过 | `dev.sh` 后端 `18184`/前端 `15176`、一次性 PostgreSQL；真实 API-key 与 Chat Playwright 各 `1/1`，无截图 |
| 真实 LLM | 已通过 | 覆盖 PLAIN 记忆、静态知识命中/无命中、Skill 加载门禁、HTTP 正常/超限/超时/重复调用预算、JSON/SSE transcript/history、provider 计数和 summary-disabled 数据库基线 |
| 实现收敛审查 | `3/3` | IPv6 SSRF 修复后重新完成三轮固定范围只读审查，连续三轮无修改 |

## 5. 当前恢复记录

- 2026-08-25：恢复到特性 worktree，确认基线为 `72f57a94`，工作区仅含本轮未提交实施改动。
- 2026-08-25：完成静态知识和 Runtime Skill 初版代码交叉检查；确认下一步先收敛
  `ResourceCatalog`/Skill 测试语义，再实施 allowlist HTTP Tool。
- 2026-08-25：开始 Slice C。HTTP Tool 的实现边界冻结为 server-owned endpoint、
  Skill capability gate、只读 GET/HEAD、禁止 redirect、解析后 IP 校验、响应大小/
  内容类型/JSON 深度与共享执行预算限制。
- 2026-08-25：完成 allowlist HTTP Tool 初版：固定 endpoint 动态 schema、Skill capability
  gate、服务端 credential 注入、禁止 redirect、解析后地址分类、单次/请求级响应预算和
  JSON 结构限制；`AllowlistedHttpToolProviderTest` 与配置校验测试通过（12/12）。
- 2026-08-25：综合后端测试完成，资源、Skill、allowlist HTTP、工具预算和 Memory 投影共
  `18/18` 通过；`origin/main` 仍为 `72f57a94`，无需先合并主线。
- 2026-08-25：完成第一轮实现收敛中的资源边界修正：资源流改为真正有界读取；静态知识
  强制遵守自身检索结果/字符上限；资源发现 degraded 时不发布部分静态知识或 Skill
  快照；补充 `searchStaticKnowledge` 的 source/citation/trace 测试和工具 transcript
  严格字符预算测试。相关后端测试 `20/20` 通过。
- 2026-08-25：重新核对实现与规划矩阵，确认当前 focused 相关测试为 `25/25` 通过；Slice D
  已有 tool-aware Memory/summary/cleanup 初版，不应继续标记为“待开始”。下一步补齐
  Spring AI JDBC 工具消息 round-trip、request-local isolation、KNOWLEDGE/AGENT
  组合接线和 executable JAR fixture，之后再进入完整后端/前端/运行时门槛。
- 2026-08-25：完成五模块 `mvn clean compile test-compile`；项目文档门禁 `10/10`
  和禁止悲观锁检查通过。
- 2026-08-25：PostgreSQL 集成矩阵首次真正启用后执行 `16` 个测试，发现 Spring AI
  `1.1.8` JDBC repository 只保存 message text/type，不能恢复 assistant tool calls 或
  tool result payload。已按规划冻结的 fallback 修正：JDBC Memory 丢弃原生工具消息，
  完整配对工具交换继续以 bounded `toolTranscript` 写入 history metadata/summary。
  此次实质修复后实现审查计数保持 `0/3`。
- 2026-08-25：修复后的 clean focused suite `39/39` 通过；随后使用外部一次性 PostgreSQL
  16 数据库重跑 `ChatSessionPostgresIntegrationTest`，真实执行 `16/16`，0 失败、0 错误、
  0 跳过，验证 JDBC-compatible projection、history metadata、summary、清理和隔离契约。
  修复后五模块 `mvn clean compile test-compile` 也已重跑并通过。下一步进入双语长青文档
  和前端门槛。
- 2026-08-25：完成双语长青文档同步，覆盖静态知识资源目录、Runtime Skill、allowlisted
  HTTP Tool、KNOWLEDGE/AGENT 接线和 Spring AI 1.1.8 JDBC Memory 的可恢复消息边界；
  `./scripts/verify-project-docs.sh` 10/10 与 `git diff --check` 通过。下一步执行前端
  tsc、Vitest、生产构建和核心 Chat Mock Playwright。
- 2026-08-25：隔离 worktree 执行 `npm ci` 后，前端 `npm run typecheck` 通过，
  Vitest `29` 个文件共 `218/218` 通过，`npm run build` 通过；在隔离 Vite 端口运行
  `e2e/chat.spec.ts`，Mock Playwright `10/10` 通过，仅使用 DOM、可访问状态、请求体/SSE
  和自动化断言。下一步验证 `demo-basic-rag` executable JAR 的 nested resources。
- 2026-08-25：当前 feature artifacts 安装成功，`demo-basic-rag` executable JAR 打包成功，
  且归档内确认包含 verification 静态知识、Skill 和 nested core/starter JAR。首次启动发现
  `.env` 的完整 datasource URL 优先于数据库名，已立即停止并改为显式一次性 JDBC URL；
  第二次启动暴露 `verification` profile 未配置 Logback root appender，导致失败堆栈不可见。
  下一步修复 profile 日志接线并重新执行 packaged-JAR 验收；实现审查计数保持 `0/3`。
- 2026-08-25：已把 `verification` 纳入本地 console Logback profile，并新增
  `LogbackProfileConfigurationTest` 锁定可执行 JAR 验证 profile 的 root appender；
  聚焦测试 `1/1` 通过，随后重新安装五模块 feature artifacts 并成功重打
  `demo-basic-rag` executable JAR。下一步使用显式一次性 JDBC URL 和独立端口 `18183`
  验证 packaged JAR 启动、nested 静态知识/Skill snapshot、健康端点、进程退出和数据库清理。
- 2026-08-25：packaged JAR 使用一次性数据库启动时，nested 静态知识已成功加载
  `entries=1/chunks=1`，但应用上下文在创建 `AllowlistedHttpToolProvider` 时失败：
  Spring 扫描到该组件后尝试使用默认构造器，暴露生产构造器自动装配契约缺失。进程和
  一次性数据库已自动清理；这是本任务内启动正确性缺陷，下一步修复构造器接线、补充
  上下文级回归并重跑 packaged JAR，故实现审查计数维持 `0/3`。
- 2026-08-25：`AllowlistedHttpToolProvider` 生产构造器已显式自动装配，
  `ApplicationContextRunner` 回归与日志 profile 测试共 `6/6` 通过。重新安装到 starter
  的完整 reactor 并对 standalone demo 执行 `clean package` 后，已确认 executable JAR
  内嵌 core 校验和与本轮 core JAR 一致；构造器问题消失。继续启动时发现第二个预算契约
  缺陷：Skill 默认 load/reference 次数高于 AGENT 全局单工具调用上限，registry 正确以
  `Invalid policy for chat tool: loadSkill` 拒绝启动。下一步将 Skill policy 有效次数钳制
  到全局上限并增加默认配置 registry 启动回归；实现审查计数仍为 `0/3`。
- 2026-08-25：Runtime Skill 的 load/reference policy 现按自身上限与 AGENT 全局
  `max-tool-calls-per-name` 的最小值生效，并新增使用默认预算实际构造
  `RagChatToolRegistry` 的回归。相关 Skill、registry、allowlisted HTTP 构造器和日志
  profile 聚焦测试 `13/13` 通过。下一步重新安装最新 reactor、clean 打包 standalone
  demo 并重复 packaged JAR 冒烟。
- 2026-08-25：重新安装五模块 feature artifacts 并对 standalone demo 执行
  `clean package` 后，packaged JAR nested-resource smoke 通过：独立端口 `18183`、
  显式一次性 PostgreSQL datasource，健康端点返回 `200/UP`；日志确认静态知识
  `entries=1/chunks=1`、Runtime Skill `entries=1/skills=1`，且应用完整启动。退出后确认
  `18183` 无监听，一次性数据库不存在。下一步进入隔离端口 `scripts/dev.sh` 真实全栈
  Playwright 验收。
- 2026-08-25：使用临时环境覆盖和一次性数据库，通过 `scripts/dev.sh` 在后端 `18184`、
  WebUI `15176` 启动真实开发栈；启动日志确认 filesystem 静态知识
  `entries=2/chunks=3`、Runtime Skill `entries=3/skills=2`，健康为 `UP`。执行无截图真实
  `api-key-real.spec.ts`，Playwright `1/1` 通过，覆盖 DOM、Vite 代理、真实 API/数据库和
  凭据不落浏览器存储。由于命令执行环境会回收脱离会话的 `nohup` 子进程，验收期间由一个
  长期命令会话持有 dev stack；项目 `dev.sh` 本身未修改。下一步执行真实 Chat provider
  Playwright 和本轮专项真实 LLM JSON/SSE/历史/预算断言。
- 2026-08-25：真实 `chat-real.spec.ts` Playwright `1/1` 通过，覆盖真实 embedding、
  AGENT `searchKnowledge` 工具循环、SSE、来源 DOM、持久历史恢复和 KNOWLEDGE 检索；
  `real-llm-chat-idempotency-smoke.sh` 也通过，PLAIN JSON/SSE 首次执行、重放、冲突、
  turn status 与 provider 计数均符合预期。专项真实 LLM 中，PLAIN 两轮记忆和静态条款
  exact hit 已通过；“火星航班行李额度”无命中问题却召回不相关售后条款，暴露 CJK 弱字符
  重叠缺少有效相关性下限。下一步新增确定性负向测试并修正静态词法评分，再重启真实栈
  重跑受影响场景；实现审查计数维持 `0/3`。
- 2026-08-25：恢复隔离 worktree 后确认后端 `18184`、前端 `15176` 和未提交实施改动
  均保持完好。静态知识相关性门槛冻结为：完整短语命中、拉丁/数字词命中，或至少两个
  不同 CJK 字符且查询覆盖率不低于 `25%`；该门槛在排序前执行。将用同一 fixture 同时
  锁定 `X-200 电池保修期` 正向命中和火星航班行李问题负向不召回，再重启真实栈复验。
- 2026-08-25：静态知识相关性门槛和负向回归已实现；`StaticKnowledgeCatalogTest` 与
  `StaticKnowledgeSearchToolTest` 共 `4/4` 通过。下一步停止旧字节码的隔离 dev 栈，
  重新构建启动后复验真实 LLM 静态命中/不命中，并继续 Skill/HTTP 工具专项场景。
- 2026-08-25：新字节码的隔离 dev 栈已重新启动，健康 `UP`，日志确认静态知识
  `entries=2/chunks=3`、Runtime Skill `entries=3/skills=2`。真实 LLM 的 X-200 条款命中
  返回一个 `STATIC_KNOWLEDGE` source；火星航班问题返回零 source，修复得到真实复验。
  随后的 AGENT 天气调用实际完成 `loadSkill=1`、`getWeather=1`、`modelCalls=3`，但响应
  和持久 history 均缺少 `toolTranscript`。这暴露当前投影从 request-local ChatMemory
  读取不到 Spring AI ToolCallAdvisor 中间工具消息；下一步修复配对工具交换的捕获点并补
  回归，再重跑全部 AGENT 场景。实现审查计数仍为 `0/3`。
- 2026-08-25：新增 request-local `ToolTranscriptCollector`，由
  `BudgetedToolCallAdvisor` 在 Spring AI 每个非流式/流式工具轮次捕获 assistant tool
  call 与预算裁剪后的 tool result，并通过 advisor context 传给最终响应；
  `ChatExecutionService` 从该 context 生成有界 transcript，JDBC Memory 投影边界不变。
  `ModeAwareChatClientFactoryTest`、`ChatExecutionServiceTest` 和
  `ChatMemoryMessageProjectorTest` 共 `30/30` 通过。下一步重启隔离栈并真实复验响应与
  PostgreSQL history 的工具 transcript。
- 2026-08-25：重启后真实 AGENT 专项全部通过：正常天气为
  `loadSkill -> getWeather`、`modelCalls=3`；超大响应返回
  `response_too_large`；延迟 endpoint 返回有界 `tool_timeout`；同一单次上限 endpoint
  重复两次时第二次返回 `tool_call_policy_exhausted`，总计 `toolRounds=3`、
  `modelCalls=4`；AGENT SSE 产生内容并在 done metadata/history 中保留
  `loadSkill -> getWeather` transcript。各场景工具调用、轮次和模型调用均未超过全局
  `6/3/8` 上限。下一步补跑 PLAIN 两轮持久记忆并查询 summary-disabled 数据库基线。
- 2026-08-25：真实 LLM 专项收口。PLAIN 两轮在同一 session 正确恢复一次性代号，
  history 为两条 COMPLETE 记录，provider counter 精确增加 `2`，数据库
  `rag_chat_memory_summary` 为 `0`；未加载 Skill 直接调用 `getWeather` 返回
  `skill_not_loaded`；AGENT `searchStaticKnowledge` 命中 X-200 条款并返回一个
  `STATIC_KNOWLEDGE` source。结合前述场景，本轮真实 provider 已覆盖静态命中/无命中、
  Skill/HTTP 正常与失败路径、重复调用预算、JSON/SSE、持久 history 和 Memory 基线。
  下一步停止真实栈并执行实现审查前的完整后端、前端、文档和并发硬门槛。
- 2026-08-25：真实栈停止后，使用同一隔离 PostgreSQL 数据库重新执行
  `ChatSessionPostgresIntegrationTest`，真实运行 `16/16`，0 失败、0 错误、0 跳过，
  Reactor `BUILD SUCCESS`。现开始集中执行合并前固定验收门槛：clean focused backend
  suite、五模块 `mvn clean compile test-compile`、并发/文档/diff 检查、前端
  typecheck/Vitest/build/核心 Mock Playwright，以及 packaged executable JAR smoke；
  门槛全部通过前不进入三轮只读审查。
- 2026-08-25：合并前固定验收门槛全部通过：后端 focused `69/69`、PostgreSQL
  integration `16/16`、五模块 clean compile/test-compile、禁悲观锁、文档 `10/10`、
  `git diff --check`、前端 typecheck/Vitest `218/218`/生产 build、隔离 Vite 的 Chat
  Mock Playwright `10/10`。重新安装 feature artifacts 并 clean 打包 executable JAR，
  显式移除 filesystem location 覆盖后，确认包内静态知识 `entries=1/chunks=1`、
  Skill `entries=1/skills=1`，健康 `UP`、Flyway `48`；退出后端口释放且一次性数据库
  删除。现在开始三轮固定范围只读实现审查，任何实质修复都将把计数重置为 `0/3`。
- 2026-08-25：实现审查第 1 轮（安全、资源、HTTP、预算和并发边界）发现实质问题，
  因而不计入连续无修改轮次并保持 `0/3`：`classpath*` 中不同物理资源根的同名相对
  路径可能按 resolver 顺序丢失，Skill reference 选择未显式限制到所属 root；静态知识
  文件以超长首段开始时可能尝试生成空 chunk；HTTP 配置对 encoded dot 的拒绝表达式不
  完整，生产 transport 的 DNS 解析结果也未钉住到实际连接；累计 HTTP 响应字节只在下载
  后结算，不能在并发或重复调用前限制网络读取。下一步集中修复上述边界并补齐确定性
  回归，然后重跑受影响后端、Maven、packaged JAR、真实 HTTP/LLM 和文档门槛。
- 2026-08-25：第 1 轮审查问题已集中修复，审查计数继续保持 `0/3`：
  `classpath*` 按物理 container 派生 root identity，Runtime Skill reference 只接受同
  root 资源，静态知识超长首段不再产生空 chunk；allowlisted HTTP 固定 path 禁止百分号
  编码，解析后的全部公网地址通过 Apache HttpClient 自定义 DNS resolver 钉扎到实际 TLS
  连接，redirect/retry/cookie/compression 均关闭；逻辑请求的累计响应容量在读取前同步
  预留，transport 最多读取该预留额度外加一个超限探测字节，失败释放、成功按实际字节
  结算。聚焦资源、Skill、HTTP、registry 和 Chat 执行测试重新运行 `54/54`，0 失败、
  0 错误、0 跳过。下一步同步双语长青文档并重跑完整后端硬门槛。
- 2026-08-25：修复后的完整后端硬门槛通过：本任务 focused 集合扩展为 `71/71`，
  真实 PostgreSQL 16 专用数据库执行 `ChatSessionPostgresIntegrationTest` 为 `16/16`，
  每个用例从空 schema 迁移到 Flyway `V48`；五模块 `mvn clean compile test-compile`
  为 `BUILD SUCCESS`。双语文档门禁 `10/10`、禁悲观锁检查和 `git diff --check` 同时
  通过。下一步重跑前端 typecheck、Vitest、生产 build 和隔离端口 Chat Mock Playwright。
- 2026-08-25：修复后的前端硬门槛通过：`npm run typecheck`、Vitest `218/218`、
  `npm run build` 均成功；使用严格 Vite preview 端口运行 `e2e/chat.spec.ts`，
  Mock Playwright `10/10`。Playwright 继续关闭截图，仅使用 DOM、可访问状态、URL、
  请求体/SSE 和自动化断言。下一步重装 feature artifacts、重打 executable JAR 并验证
  nested resources，随后重跑隔离 `dev.sh` 的真实 HTTP/LLM 全栈场景。
- 2026-08-25：审查修复后的 executable JAR smoke 再次通过：standalone demo 在独立
  端口 `18183` 和空白一次性 PostgreSQL 数据库上完成 Flyway `V48`，日志确认包内静态
  知识 `entries=1/chunks=1`、Runtime Skill `entries=1/skills=1`，健康端点返回
  `200/UP`。进程已退出、端口已释放、一次性数据库已删除。下一步启动隔离
  `scripts/dev.sh` 栈，重跑 DNS 钉扎与预读容量预留修复所影响的真实 HTTP/LLM 场景。
- 2026-08-25：审查修复后的隔离真实全栈与真实 LLM 矩阵通过。`dev.sh` 在后端
  `18184`、WebUI `15176` 和空白一次性 PostgreSQL 上启动，日志确认 filesystem 静态
  知识 `entries=2/chunks=3`、Runtime Skill `entries=3/skills=2`；真实 API-key 和 Chat
  Playwright 各 `1/1` 通过，原生 JSON/SSE 幂等 smoke 确认首次调用、重放、冲突、
  turn status 和 provider counter。专项 AGENT 场景均保留 response/history 的有界
  transcript：正常路径为 `loadSkill -> getWeather`，预算 `toolCalls=2/toolRounds=2/
  modelCalls=3`；超大响应返回 `response_too_large`；延迟响应返回 `tool_timeout`；
  同一单次上限 endpoint 的两次调用依次返回 `response_too_large` 和
  `tool_call_policy_exhausted`，总预算 `3/2/3`；AGENT SSE 的 done metadata 与持久
  history 均保留 `loadSkill -> getWeather`，无 error 事件。KNOWLEDGE 的 X-200 查询
  返回一个 `STATIC_KNOWLEDGE` source，火星航班问题返回零 source；PLAIN 同会话两轮
  正确恢复随机验证码，provider counter 精确增加 `2`，持久 history `2` 条、JDBC
  Memory `4` 条、summary `0` 条。真实调用期间持续检查后端日志，未出现未预期错误。
  下一步停止隔离栈并执行实现审查前的完整后端、前端、文档和并发硬门槛。
- 2026-08-25：真实栈停止后的最终审查前硬门槛全部通过：本任务 focused 后端测试
  `71/71`，外部 PostgreSQL 集成 `16/16`，五模块 `mvn clean compile test-compile`
  `BUILD SUCCESS`；禁悲观锁、项目文档 `10/10` 和 `git diff --check` 通过。前端
  `npm run typecheck`、Vitest `218/218`、生产 build 及严格隔离 preview 端口的 Chat
  Mock Playwright `10/10` 全部通过，Playwright 无截图。现在重新开始连续三轮固定范围
  只读实现审查，计数为 `0/3`。
- 2026-08-25：重新开始的第 1 轮固定范围审查发现逻辑请求累计预算仍有实质缺口，因此
  不计入连续无修改轮次并保持 `0/3`：每个模型候选/回退 attempt 会新建
  `HttpToolExecutionState`，失败候选已消耗的 HTTP response bytes 不会约束后续候选。
  已把唯一 HTTP byte-budget state 提升到共享 `ChatExecutionBudget`，所有候选、重试和
  回退通过同一逻辑请求状态预留、结算响应字节；execution-budget metadata 同步暴露已用
  和剩余字节。新增预算对象和真实候选回退接线回归后，`ChatExecutionBudgetTest`、
  `ChatExecutionServiceTest`、`AllowlistedHttpToolProviderTest` 共 `32/32` 通过，
  Maven reactor `BUILD SUCCESS`。下一步重跑完整受影响后端门槛，并在通过后再次从
  `0/3` 开始固定范围审查。
- 2026-08-25：累计 HTTP 字节预算修复后的完整基本硬门槛通过。扩大的 focused 后端集合
  `85/85`；外部一次性 PostgreSQL 16 数据库执行 `ChatSessionPostgresIntegrationTest`
  为 `16/16`，0 失败、0 错误、0 跳过，并从空 schema 迁移至 Flyway `V48`；五模块
  `mvn clean compile test-compile` 为 `BUILD SUCCESS`。项目文档门禁 `10/10`、禁悲观
  锁和 `git diff --check` 通过。前端 `npm run typecheck`、Vitest `218/218`、生产 build
  和严格隔离 preview 端口的 Chat Mock Playwright `10/10` 全部通过，Playwright 无截图。
  现在按固定范围重新开始连续三轮只读实现审查，计数为 `0/3`。
- 2026-08-25：重新开始的第 1 轮安全/资源/HTTP/预算审查发现 IPv6 SSRF 判定仍有实质
  缺口，因此本轮不计数并保持 `0/3`：NAT64、6to4、Teredo、discard-only 等特殊地址不被
  `InetAddress` 的 loopback/link-local/site-local 判定覆盖，可能把私网或 metadata IPv4
  封装/转换为表面上的 IPv6 公网目标。下一步收紧 IPv6 全局单播判定，显式拒绝转换、
  隧道、文档和特殊用途前缀，并补齐不触发 transport 的确定性回归；之后重跑所有受影响
  门槛并再次从 `0/3` 开始审查。
- 2026-08-25：IPv6 SSRF 缺口已修复：普通全局单播继续允许；NAT64、6to4、Teredo、
  discard-only、文档和其他特殊用途前缀在 transport 前拒绝。新增 8 类负向地址和普通
  全局 IPv6 正向回归，聚焦 HTTP/配置/逻辑请求预算测试 `42/42` 通过；扩大的本任务
  focused 后端集合为 `87/87`，五模块 `mvn clean compile test-compile` 为
  `BUILD SUCCESS`。双语 Chat 长青文档已同步，项目文档 `10/10`、禁悲观锁和
  `git diff --check` 通过；前端 typecheck、Vitest `218/218`、生产 build、Chat Mock
  Playwright `10/10` 再次通过且无截图。该修复不触及数据库持久化路径，最近一次真实
  PostgreSQL `16/16` 结果仍覆盖本轮 Memory/history 变更。现在再次从 `0/3` 开始固定范围
  只读审查。
- 2026-08-25：IPv6 SSRF 修复后的合并前实现收敛审查完成，连续 `3/3` 无修改：
  第 1 轮检查资源、Skill、HTTP、SSRF/DNS、预算和并发；第 2 轮检查 Chat mode、
  Advisor/Tool loop、Memory/summary、JSON/SSE/history 和静态知识来源；第 3 轮检查
  测试/runtime/config/docs/startup/rollback/Git 交付。三轮均未发现影响正确性、成本
  安全、兼容性或数据一致性的实质问题。第 3 轮同时确认依赖解析成功、双语标题结构一致、
  文档门禁 `10/10`、禁悲观锁、密钥扫描和 `git diff --check` 通过。下一步获取并合并
  最新 `origin/main`，记录合并后基线并按固定顺序重新执行完整验收。
- 2026-08-25：合并前实现与文档已保存为本地提交 `fa315230`。随后执行
  `git fetch origin --prune`，确认最新 `origin/main` 仍为实施起点 `72f57a94`；
  显式执行 `git merge origin/main` 返回 `Already up to date`，没有主线冲突或新增
  merge commit。合并后验证基线为特性提交 `fa315230` + `origin/main@72f57a94`。
  现在开始按固定顺序执行 PostgreSQL 集成矩阵、Maven 门槛、前端门槛、packaged JAR、
  隔离真实全栈、真实 LLM 和合并后三轮只读审查；不沿用合并前结果作为最终结论。
- 2026-08-25：合并后后端硬门槛通过。一次性 PostgreSQL 16 数据库执行
  `ChatSessionPostgresIntegrationTest` 为 `16/16`，0 失败、0 错误、0 跳过，
  每个测试 schema 均从空库迁移至 Flyway `V48`；本任务 focused 后端集合为
  `87/87`，其中主命令 `85/85`，另行执行 `ProjectDocumentRetrieverTest` 为 `2/2`。
  五模块 `mvn clean compile test-compile` 为 `BUILD SUCCESS`。所有测试和服务进程均
  已退出。下一步执行前端 typecheck、Vitest、生产 build 和隔离端口 Chat Mock
  Playwright，不沿用合并前的前端结果。
- 2026-08-25：合并后前端硬门槛通过。`npm run typecheck` 无错误，Vitest
  `218/218`，`npm run build` 成功；在严格隔离的 Vite preview 端口运行
  `e2e/chat.spec.ts`，Mock Playwright `10/10`。测试仅使用 DOM、可访问状态、URL、
  请求体、SSE 和自动化断言，Playwright 配置保持 `screenshot: off`。preview 进程已
  停止。下一步执行项目文档、禁悲观锁和 diff 静态门禁，再验证 packaged JAR。
- 2026-08-25：合并后静态门禁通过：`verify-project-docs.sh` 为 `10/10`，
  `verify-no-pessimistic-locks.sh` 未发现显式悲观锁或 advisory lock，
  `git diff --check` 无输出。当前仍只有本进度文档存在未提交修改。下一步重装 feature
  artifacts、打包并在隔离 PostgreSQL/端口上验证 executable demo JAR。
- 2026-08-25：合并后 packaged JAR 验收通过。重新安装五模块 feature artifacts，
  standalone `demo-basic-rag` 从干净 `target` 成功打包；归档中的 verification 静态知识、
  Skill、nested core 和 starter 条目 `4/4` 存在。可执行 JAR 在独立端口 `18183` 和空白
  一次性 PostgreSQL 数据库上启动，Flyway 执行 `48` 个迁移至 `V48`，健康为 `UP`；
  日志确认包内静态知识 `entries=1/chunks=1`、Runtime Skill `entries=1/skills=1`。
  进程停止后端口已释放，一次性数据库已删除。下一步启动隔离 `dev.sh` 真实全栈并执行
  真实 API-key、Playwright、JSON/SSE、Memory、静态知识、Skill/HTTP 和预算验证。
- 2026-08-25：合并后隔离真实全栈已在后端 `18184`、WebUI `15176` 和空白一次性
  PostgreSQL 上启动。`dev.sh` 的健康、Vite 代理 root identity 与管理写探针通过；日志
  确认 Flyway `V48`、filesystem 静态知识 `entries=2/chunks=3`、Runtime Skill
  `entries=3/skills=2`，真实 provider 为 OpenAI-compatible `grok-4.5`。无截图真实
  API-key Playwright `1/1`、Chat Playwright `1/1` 通过，覆盖真实 embedding、AGENT
  SSE、来源 DOM、持久 history 恢复、KNOWLEDGE JSON 和执行预算。原生真实 LLM
  幂等 smoke 也通过 JSON/SSE 首次执行、重放、冲突、turn status 和 provider counter
  断言，重放未增加 provider 调用。下一步一次性执行本特性专项真实 LLM 矩阵。
- 2026-08-25：合并后真实 LLM 专项矩阵全部通过。PLAIN 同会话两轮恢复一次性验证码，
  数据库只读断言为 history `2`、JDBC Memory `4`、summary `0`；KNOWLEDGE 的 X-200
  查询返回一个 `STATIC_KNOWLEDGE` source，火星航班问题返回零 source。AGENT 未加载
  Skill 直接调用 `getWeather` 返回 `skill_not_loaded`；正常路径 transcript 为
  `loadSkill -> getWeather`，预算精确为 `toolCalls=2/toolRounds=2/modelCalls=3`；
  超大响应为 `response_too_large`，延迟响应为 `tool_timeout`；同一单次上限 endpoint
  的两次调用依次为 `response_too_large`、`tool_call_policy_exhausted`，预算精确为
  `3/2/3`。AGENT SSE 无 error 事件，done metadata 与持久 history 均保留
  `loadSkill -> getWeather`，预算 `2/2/3`。随后项目标准真实 E2E 独立交叉验证
  `10 PASS / 0 FAIL`，覆盖真实 embedding、隔离 search、KNOWLEDGE JSON 和 SSE，
  两条响应均包含唯一探针且引用唯一来源。真实调用期间持续检查后端日志，除幂等冲突
  用例预期 WARN 外没有未预期异常。下一步停止隔离栈、删除一次性数据库并重跑审查前
  后端、前端、文档和并发硬门槛。
- 2026-08-25：隔离真实全栈、辅助 HTTP 服务和 disposable PostgreSQL 数据库已停止并
  清理，相关端口已释放。随后使用新的空白一次性 PostgreSQL 数据库执行最终
  `ChatSessionPostgresIntegrationTest`，结果为 `16/16`，0 失败、0 错误、0 跳过；
  每个 schema 均完整迁移至 Flyway `V48`，Maven `BUILD SUCCESS`，退出钩子已删除测试
  数据库。下一步执行最终五模块 `mvn clean compile test-compile`，然后顺序重跑前端和
  静态门禁，全部通过后才进入合并后连续三轮限定范围只读审查。
- 2026-08-25：最终编译和前端硬门槛通过。五模块
  `mvn clean compile test-compile` reactor 全部 `SUCCESS`；WebUI `npm run typecheck`
  无错误、Vitest `218/218`、`npm run build` 成功。在严格隔离的 preview 端口执行
  `e2e/chat.spec.ts`，Chat Mock Playwright `10/10`，只使用 DOM、可访问状态、请求体、
  URL、SSE 和自动化断言，配置保持 `screenshot: off`；preview 已停止。下一步执行文档、
  禁悲观锁、diff 和密钥静态门禁，通过后开始连续三轮限定范围只读审查。
- 2026-08-25：最终静态门禁通过：项目文档验证 `10/10`，生产源码未发现显式悲观锁或
  PostgreSQL advisory lock，`git diff --check`、新增行密钥扫描均无问题，隔离 preview
  端口确认已释放。基本集成硬门槛至此全部满足。现在从 `0/3` 开始合并后连续三轮限定
  范围只读审查；任一轮发现需要实质修改的问题都会重置计数并重跑受影响门槛。
- 2026-08-25：合并后收敛审查第 1 轮未发现问题，计数曾达到 `1/3`；第 2 轮发现静态
  知识与项目文档共用了 query-result cache key：`searchStaticKnowledge` 或组合 Retriever
  记录同 query 后，后续 `searchKnowledge` 可能错误复用静态结果。该问题影响工具语义和
  检索正确性，因此审查计数重置为 `0/3`。修复策略是静态结果继续进入同一 citation/source
  trace，但不写入仅供项目检索复用的 query cache；同时补齐 Tool 和组合 Retriever 回归，
  然后重跑受影响测试与全部基本硬门槛。
- 2026-08-25：静态知识缓存隔离缺陷已修复。`StaticKnowledgeSearchTool` 和
  `StaticKnowledgeDocumentRetriever` 继续把结果记录到 citation/source trace，但不再写入
  项目文档 query-result cache；Tool 和组合 Retriever 的回归断言同时确认
  `STATIC_KNOWLEDGE` 结果正常返回且同 query 的项目缓存保持为空。修复后的定向测试
  `17/17`、固定后端特性集合 `87/87` 均通过；一次性空白 PostgreSQL 16 数据库执行
  `ChatSessionPostgresIntegrationTest` 为 `16/16`，每个用例迁移至 Flyway `V48`，
  数据库已删除；五模块 `mvn clean compile test-compile` 为 `BUILD SUCCESS`。
  WebUI typecheck、Vitest `218/218`、生产 build 和严格隔离 preview 端口的 Chat Mock
  Playwright `10/10` 全部通过，Playwright 保持截图关闭。项目文档门禁 `10/10`、禁悲观
  锁检查、`git diff --check`、新增行密钥扫描和隔离端口清理均通过。基本集成硬门槛重新
  满足。
- 2026-08-25：按最新交付指示取消后续三轮代码审查，不再以 review 作为正确性证明；
  最终信心继续由合并后重新执行的自动化集成、编译、前端、运行时和静态门禁提供。下一步
  保存当前修复提交，获取并合并最新 `origin/main`，然后从合并后基线重新执行完整验收。

## 6. 恢复顺序

```text
读取 plan + progress
  -> git status --short --branch
  -> git worktree list
  -> git fetch origin
  -> 检查 origin/main 是否前进
  -> 补读受影响代码/测试
  -> 更新本文件
  -> 继续当前 Slice
```

每个 Slice 在开始和完成关键步骤前更新本文件。不得写入 API key、Token、密码、完整工具
输入、完整对话、响应正文或外部文件绝对路径。

## 7. 交付前顺序

```text
实现完成
  -> 如 origin/main 前进，merge origin/main
  -> 记录 merge 后基线
  -> PostgreSQL 集成矩阵 + mvn clean compile test-compile
  -> 前端 tsc/build/核心 Mock Playwright
  -> 可执行 JAR smoke
  -> 隔离端口 scripts/dev.sh + 全栈 Playwright
  -> Mock 通过后执行有界真实 LLM
  -> 连续三轮实现审查
  -> commit/push 特性分支
  -> merge feature -> main
  -> main 上重新执行 merge 后完整验收并 push
```
