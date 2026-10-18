# 模型依赖治理与恢复交付进度

- 基线：`815e82ec`，专用分支 `feat/dependency-resilience-recovery`，开始时 main 干净。
- 2026-10-18：已读交付规范、文档 Skill、准入/Embedding/检索/Rerank/任务代码；规划已写，尚未修改生产代码。
- 全批范围：依赖治理；混合负载/故障/重启验收；真正备份恢复，全部实施后集中全面回归。
- 规划检查：2026-10-18 14:08，需求/自包含、代码/兼容/并发、验收/恢复/交付连续 3/3 无修改通过。
- 实现检查 0/3，开始实施依赖治理；进度约 15%。
- 不使用其他项目服务或数据库；测试凭据只从环境读取，不记录值。

## 2026-10-18 实施与早期验证

- 已实现三类依赖容量池、零排队执行器、真实结束释放许可、deadline/取消传播、primary 缓存外层保护、
  有界 transport/retry、后台文档分类和远程 rerank fallback；同步文档导入同样使用后台 embedding 容量。
- 补充 batch timeout/cancel/capacity 禁止 sequential fan-out，防止失败后继续无意义访问上游；这些失败按暂态归类，
  由既有 durable job 的重试/取消/提交栅栏处理，不重写任务调度。
- 备份 CLI、离线安全负例、受控 provider、真实 HTTP 混载/重启/dump-restore 演练与 k6 reliability profile 已编写。
- 初次 `mvn clean compile test-compile`、全量 `mvn test` 和原 project-tests 59 检查通过；后续补丁后仍须重跑最终门槛。
- 离线备份负例发现本机 Python 3.10 不支持 `hashlib.file_digest`，改为有界流式 SHA-256，6/6 通过。
- E2E 夹具修复：等待 PostgreSQL 正式 TCP readiness；测试 root key 满足 32 字符；处理 chunked request；
  sessionId 不超过 36 字符；不同样本使用不同内容避免触发正常的全局去重。它们不是生产回归。
- 第一轮受控故障已通过 8 种 embedding/rerank 故障、混载、SIGKILL lease 接管和真实 dump/restore 逐表相等；
  之后增强 scope ACL/主动 QA/删除样本断言，正在重新完整执行，尚未宣称最终验收。
- `.env` 只输出所需 provider 凭据是否配置，未显示值；真实服务验证待最终构建后运行。
- 证据：`/tmp/dependency-*.log`、`.verification/dependency-recovery/`；当前总体约 50%，实现检查仍 0/3。

## 2026-10-18 14:59 集成与真实模型验证

- 实现主体完成，整体约 75%，实现检查仍 0/3。正式双语配置/API/架构/测试/恢复文档已同步。
- 全 gated PostgreSQL：28 套件（Core 230 + Starter 1）通过；官方 SDK Core/Starter 双拓扑 20 步通过。
  前端 typecheck/build、Vitest 1030 项、核心 Mock Playwright 23 项通过；后续补丁后仍重跑最终门槛。
- 受控完整证据 `.verification/dependency-recovery/20261018-144205-3568e978/summary.json`：
  8 类 embedding/rerank 故障、24 次混载并发导入与主动 QA、实际 socket 断连、SIGKILL lease 接管、
  正常重启、真实 dump/restore、60 张表逐表 hash/count 相等、文件/权限/删除/JSON/SSE，以及 SQL 失败单事务回滚。
- 验收发现并修复：direct Search 未写 branchStages；advisor 异步线程丢失后台身份/deadline；
  ChunkingUpgradeService 后台升级需使用后台池。均增加针对性回归。
- `scripts/dev.sh` 首次私有环境只覆盖 POSTGRES_*，被 `.env` 的 SPRING_DATASOURCE_URL 盖过。
  已正常停服，在私有测试 env 显式指定完整 datasource，并通过启动日志确认自有 15596 数据库。
  首次错误环境结果不作为最终隔离验收；仅清理脚本自己的随机样本，未清空用户开发库。
- 隔离 dev 栈（后端 18197、前端 15197）：BGE-M3 + Grok-4.5 真实 smoke 13/13、真实浏览器 Agent SSE/
  KNOWLEDGE/历史恢复通过；远程 rerank success 指标已确认，不以 heuristic fallback 冒充真实调用。
- 真实 QA 首次 3 场景中 2 通过，正事实场景出现缺失引用并被安全回答替换，硬门槛如实 FAILED。
  同一授权范围直接请求确认 30天事实与有效引用可达；保留失败证据并做一次双跑交叉验证，不取消安全检查。
- 实际 k6 执行发现脚本原有 summary 作用域错误，以及测试选择了不存在的 rag-plain alias；已修复脚本，
  默认使用公开 rag-default，并保留成功率/成功延迟/明确拒绝/非预期错误独立指标，正在完整重跑。
- 真实 Client gate 初始使用不存在/禁止覆盖 mode 的 alias，均按契约拒绝；改用公开 rag-default 默认语义。
  这些测试配置错误不改生产 alias 安全规则。

## 2026-10-18 15:22 双模型与最后回归

- Grok-4.5：隔离 smoke 13/13，真实 Agent 工具/引用、Native/OpenAI JSON/SSE，以及真实浏览器代理/历史恢复通过。
  原始 QA 3 场景中一次正事实缺引用导致安全拒答；后续同一标准定义的双跑 6/6 通过，无环境漂移。
  合计观察 8/9 硬门槛通过，保留原始 FAILED，不把重复成功覆盖成“模型质量 100%”。
- MiniMax-M3：隔离 smoke 13/13（AGENT 按既有能力配置拒绝，未冒充为实际 Tool Calling 成功）；
  同一标准 QA 的正事实/证据不足/Collection 边界双跑 6/6 通过，无环境漂移。
  两个 provider 均通过真实 Native/OpenAI JSON/SSE、官方 Python/Node SDK quickstart。
- BGE-M3 写入/向量检索和真实远程 HTTP rerank 均已执行；MiniMax 服务的 success 操作计数分别为
  online embedding=11、backgroundEmbedding=16、rerank=5。它们是受管操作计数，不冒充精确 wire 请求数。
- API OpenAPI 基线检查 10 路径/9 schema、旧无效请求 400、Trace/隐私策略/审计查询通过。
  前端最终再跑 typecheck/build、87 文件 1030 个 Vitest、23 个核心 Mock Playwright，全部通过。
- 最新受控全链路 `.verification/dependency-recovery/20261018-151152-a792a045/summary.json` 全部通过；
  k6 126/126、非预期错误 0，成功请求 p95=158.93ms、p99=621.66ms（受控上游，不是生产 LLM SLA）。
  SSE 校验实际解析非空 delta/error/唯一 DONE，内含空流/损坏/error/终态后追加数据负例。
- 最终 gated 检查发现测试自身错误：在共享 Mockito thenAnswer 中断言后台身份，会在其他用例重设
  when 桩时提前执行。已把断言移到单个成功升级用例的 doAnswer；不移除实际后台身份与作用域清理断言。
  生产实现未因此改变；正在完整重跑，不把失败矩阵当成通过。
- 收敛前补齐 provider 内部抛出 timeout/cancelled 的低基数指标分类，并加入两例确定性回归；
  未改变 API/正常模型路径。已停止自有 dev 栈，重新 clean 编译/全量测试/60 门禁/完整 gated 与受控恢复。
  gated 改用本机已有原生 ARM `pgvector/pgvector:pg16`，避免 x64 模拟耗时，不修改生产镜像默认。
- Python 演练遵循已有 `verify-evidence-admission-e2e.py` 的独立运行模式，不加入只扫描 sh/mjs 的 registry；
  备份 7 个离线安全负例纳入默认 project-tests 链。最后实现检查仍 0/3，待全部最终硬门槛通过后开始。
- 最后一轮默认测试 Core 7967 项无失败，但 SDK 的并发 Maven install/repackage 使 Starter 编译读取了
  竞争构建产物；不是 API 测试成功，也不是生产修复依据。停止并发 Maven，等 SDK 全部退出后，再串行
  clean 编译→全量测试→60 门禁→完整 gated；该工作区约束已提升到双语开发者参考。

## 2026-10-18 15:43 最终验收结果与证据

实施与自动验收完成。后续为连续三轮只读实现审查和 Git 交付；无问题轮次仅输出会话总结，
不逐轮改写本文，最终结论随 Git 提交说明保留。

| 验证 | 实际结果 | 证据 |
|---|---|---|
| 串行 clean 编译/测试编译 | 五模块 BUILD SUCCESS | `/tmp/dependency-final-clean-build.log` |
| 全量 `mvn test` | 总计 8670，8455 通过，215 个条件跳过；0 failure/error | `/tmp/dependency-final-maven-test.log` |
| 仓库默认门禁 | 60/60，含 7 个备份安全负例、失败自测与可见性校验 | `/tmp/dependency-final-project-tests.log` |
| 完整 gated PostgreSQL | 28 套件、Core 230 + Starter 1，零失败/跳过；含修复后的全部 31 个分块升级场景 | `/tmp/dependency-final-gated-it.log` |
| SDK 双拓扑 | Core/外部 Starter 20 步，官方 Python/Node JSON/SSE、鉴权/幂等/错误/禁用负例 | `.verification/openai-sdk-e2e/dependency-recovery-delivery/summary.md` |
| 受控故障/混载/恢复 | 最新冻结实现 16 步全部通过；8 类依赖故障、导入/QA、客户端断连、SIGKILL、正常重启 | `.verification/dependency-recovery/20261018-153926-02bbc259/summary.json` |
| k6 真实运行 | 118/118 成功、非预期错误 0；成功延迟 p95 252.55ms、p99 798.83ms，仅受控上游 | 同目录 `k6-summary.json` |
| 真正备份/恢复 | 60 张表逐表行 hash/count 相等；原件/向量/历史/权限/删除/API；同库/非空/损坏拒绝、SQL 失败原子回滚 | 同目录 `backup.log`、`restore.log`、summary |
| 非 root 制品权限 | Docker 工具以调用者 UID/GID 执行，真实 dump 归属当前用户、0600，目录 0700 | 同目录 backup 与 corrupt-backup；未提交原始制品 |
| 真实依赖与质量 | BGE-M3、真实 HTTP rerank、Grok-4.5/MiniMax-M3；两模型 smoke 各 13/13，能力 gate 如上单列 | `/tmp/dependency-real-*.log` |
| 真实 QA | 5 个 run/15 case：14 PASSED、1 FAILED；最后两个 provider 各双跑 6/6，无环境漂移；保留初始安全拒答 | `.verification/dependency-real-delivery/` 的 runs/cases JSONL 与双跑汇总 |
| 对外 Client 与 OpenAPI | 两模型真实 Native/OpenAI JSON/SSE + Python/Node quickstart；10 路径/9 schema 基线与错误状态通过 | `/tmp/dependency-real-client*.log`、`/tmp/dependency-real-api-contract.log` |
| 前端与联合运行 | typecheck/build、Vitest 1030/1030、核心 Mock 23/23；Grok 真前后端 Agent/KNOWLEDGE/历史恢复 1/1 | `/tmp/dependency-release-ui-*.log`、`/tmp/dependency-real-playwright.log` |
| 开发脚本与清理 | `scripts/dev.sh` 隔离真实服务两次成功启停；自有 real/SDK/故障容器与服务已清理 | `/tmp/dependency-dev-*.log`；最终 status stopped |
| 文档/锁/隐私 | 双语正式文档提升；文档 16/16；无悲观锁；diff 检查通过；真实环境凭据值在暂存 diff 中匹配数为 0 | 最终检查日志和交付会话 |

### 交付边界

- 本批不改 API DTO、既有 URL/必填参数/成功 envelope，不改 schema，无需运维补配置或补 SQL。
  依赖保护默认生效；远程付费 rerank 不替原先未启用的部署强行开启；备份仍为显式敏感数据操作。
- 真实模型的引用遗漏被安全拒答，不能把后续重复通过说成从未失败；`SUPPORTED` 不是数学上的事实保证。
- 强制中断不合作 provider 不是 Java 的保证：调用方退出后仍持有实际任务许可，测试已覆盖该边界。
- 备份不包含外部资源和模型凭据，不提供 PITR/RPO=0；旧备份必须先核对最新删除/权限/保留策略再放流量。
- 远端 `origin/main` 在最终 fetch 时仍为 `815e82ec`，无待合并更新。最终 Git HEAD 与三轮只读审查结果
  以交付提交和会话输出为准；私有日志、备份、模型原始输出与 `.env` 不提交。
