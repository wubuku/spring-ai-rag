# 候选隔离与回答契约——实施账本

当前状态：2026-10-19 03:56，实施、最终验收和实现检查均完成，待归档并Git交付。
基线`d95885f5`，分支`feat/candidate-contracts`；同一工作区，无额外worktree。
已探索候选路由/预算/熔断、查询增强、Native/OpenAI mapper、指纹/快照、流式buffer与私有诊断。
下一步：归档本计划/账本，完成最终文档与Git门禁，合回`main`并push，确认工作区干净后停止。

- 00:31 两轮compile通过；focused第二轮通过，新增诊断Python自测13项通过。
  首轮发现半开旧测试错误地允许第二个探测，修正后新32并发用例验证只有一个探测；保留失败日志。
- 00:33 开始真实HTTP专项，覆盖候选隔离、四协议契约、幂等及慢查询增强；新增严格JSON重复键拒绝。
- 00:42 HTTP专项7组通过（证据`http-fifth.log`）：四协议A故障B健康、全OPEN与恢复、
  TOKEN/JSON契约成功/失败/正常拒答/坏参数、四协议keyed重放与409零调用、PLAIN溢出、AGENT及KNOWLEDGE保护、
  扩展1秒降级而4秒provider仍运行、最终reservation归零。
  前四轮发现的是验收脚本假设与旧契约不符（Native显式模型仍允许fallback、Native流错误HTTP200、
  重放比较应排除JSON键顺序与请求追踪信封、滑动窗口应显式设1）；未修改生产HTTP/重放语义迁就断言。
- 00:44 前端TypeScript、1030单测、生产构建通过；核心Mock第一组66通过，待补剩余27。
- 00:46 并发核对发现迟到旧请求可能干扰新半开探测、既有窗口多原子字段不构成整体原子更新。
  已添加代次许可、短内存同步及旧请求/2000并发更新测试；因此早期测试仅为历史证据，最终门槛需重跑。
  同时明确增强内部取消必须传播，新增业务拒绝/取消测试。基本集成与实现3/3尚未完成。
- 00:52 全量后端第一轮暴露旧disabled夹具依赖旧缺省，已显式关闭并新增缺省开启断言，未删除测试。
  代次API首次重载使Java方法引用产生歧义，已改为独立命名，保留旧方法引用源兼容；最终第三轮完整门槛运行中。
  前端完整15 spec共93场景通过，TypeScript/1030单测/build均通过；文档16门禁、诊断13自测通过。
  后续仍须：最终PG/HTTP/SDK、双实例/恢复、dev.sh真实启停、真实双模型+Embedding/rerank及新增契约验收。
- 01:15 后端最终clean compile/test-compile、全量mvn test通过；默认XML证据为8777项、215跳过、0失败/错误，
  即8562执行通过。新增取消测试改用CompletableFuture.join模拟不可中断provider，避免手工吞中断；
  聚焦熔断/契约/预算及该夹具重新通过，随后63门禁通过。质量runner新增开关对应自测夹具亦已同步。
  全28套PostgreSQL门控矩阵成功（V1–V65升级、外部Starter、租约/预算/治理/恢复），耗时17分41秒。
  证据：`final3-compile.log`、`final3-maven.log`、`final-focused.log`、`maven-default-summary.json`、
  `final3-project-gates.log`、`final3-postgres.log`及`postgres-summary.json`。
  现按固定顺序重做前端门槛，再串行做HTTP/SDK/双实例/备份恢复/dev.sh/真实模型；不把历史专项冒充最终验收。
- 01:34 真实Grok质量24case全部执行失败，原因是两个迟到query扩展占满默认2个后台槽，
  主回答遭AdmissionRejected；不是模型安全拒答或上游错误。此证据完整保留，不宣称验收完成。
  前述API/SDK、取证、双实例72case/36拒答、k6/dump恢复、dev.sh真实13项+2浏览器均已通过，
  但修复影响共享准入后必须重跑最终序列。真实契约验收另发现脚本sessionId超旧36上限，改UUID，未放宽API约束。
- 01:40:24/01:40:41/01:40:56 补充规划连续3/3：默认容量/兼容、真实许可/成本/取消、验收/恢复，
  均无问题无修改；此后一次性记账。冻结同池可选准入与主答预留，默认不扩容、不关闭总限额、不修改事实断言。
- 01:59 容量修复的聚焦测试及HTTP八组通过；最终第四轮clean compile/test-compile、全量Maven和63门禁通过。
  默认XML为8781项、215跳过，实际8566执行通过，0失败/错误；PG矩阵运行中，不能将其余旧证据冒充新版通过。
  可选阶段策略已进入评测configurationSnapshot，逐例queryEnhancements显式记录SKIPPED_CAPACITY等实际结果。
  双语文档16门禁、禁悲观锁及诊断Python13自测亦通过；完整验收和实现3/3尚未完成。
- 02:16 最终第四轮28套PostgreSQL集成231项通过，V1–V65升级和恢复兼容已覆盖；耗时15分54秒。
  前端typecheck、1030单测、生产build与93个Mock Playwright通过；受控HTTP八组、上游四协议/SDK恢复和
  双确认诊断验收均通过。证据`postgres4-summary.json`及`after-capacity-fix/`；双实例/恢复/真实模型仍在执行。
- 02:41 双实例72case/36正常拒答处置、SIGKILL接管、撤权/删除跨节点生效、隐私备份恢复通过；
  依赖embedding/rerank故障、k6负载、断连/重启、真实dump/restore回滚与数据一致性全部通过。
  dev.sh第一次在直接上游预检60秒返回HTTP000，尚未进入问答；保留失败，不是应用启动失败。
  使用既有预检超时开关180秒重验，真实13项、2浏览器、旧OpenAPI与自有进程启停全部通过（dev-recheck.log）。
- 02:42 真实Grok 46/48、MiniMax 45/48，合计91/96；无执行错误、Trace缺失或本轮容量拒绝。
  Grok两次zero-loss数量词法断言未达标；MiniMax为conflicting-evidence禁用表达、decimal-threshold结果分类、
  missing-password不足声明断言。没有修改断言、删除失败或重跑完整矩阵洗绿，不能报告质量100%通过。
  两provider各四协议显式回答契约全部通过（8/8），真实Embedding/rerank、Client/SDK及双确认诊断/本地重放通过。
  后续单次诊断是新调用，不还原历史回答；前台定向验收单列，不替代这96条原始质量证据。
- 02:49 前台真实六契约中五个PASSED，Grok零数量为FAILED并正确返回SAFE_FALLBACK；两空scope均安全兜底。
  但部分迟到扩展线程带interrupt标志进入usage记录，出现repository_failure；测试还在旧provider退出前
  未等所有reservation归零，不能混同正常运行与强制停机。补充规划§3.5，检查归零，先确定性重现再修复。
- 02:49:18/02:49:31/02:49:47 中断记账补充规划连续3/3：取消/许可语义、真实PG与CAS、完整重验与停机边界；
  均无问题无修改，此后一次性记录。此前实现review尚未开始，不把规划review算作实现review。
- 02:57 单测BudgetedChatModel及真实PG预算/usage专项通过；修复BudgetedChatModel记账入口仅在结算/usage阶段
  临时清除既存interrupt并finally恢复，provider调用、原异常、取消和许可生命周期不变。
  首次全量Maven出现既有AdmissionRejected并发夹具抖动，单测复现通过；全量Maven重跑、63门禁和PG矩阵
  28套233项均通过（`final5-maven-rerun.log`、`final5-postgres.log`、`postgres5-summary.json`）。
- 03:54 最终运行时链路全部完成：前端/93 Mock、受控候选契约、上游四协议/SDK、诊断、双实例、依赖恢复、
  dev.sh真实13项+2浏览器全部通过；前台真实两provider契约、可选阶段执行/超时、空证据安全兜底及迟到记账
  收敛通过。真实矩阵MiniMax 48/48通过；Grok失败仍保留（最终一轮失败类别为CITATION_INVALID、
  STRUCTURED_ASSERTION_FAILED、ANSWERABLE_REFUSAL、OUTCOME_MISMATCH），不把模型质量失败归因于应用故障，
  也不宣称双模型质量100%通过。运行时汇总`after-interrupt-fix/runtime-summary.json`，真实证据私有保存。
- 03:56 实现检查连续3/3完成且未修改实现或文档：检查1外部API/兼容与重放；检查2候选并发/容量/取消/预算；
  检查3诊断隐私/安全拒答/契约失败/文档与Git安全，均无问题。规划补充§3.5后，规划检查也保持3/3。

- 00:09 规划交叉检查发现：PLAIN流buffer溢出不能依赖KNOWLEDGE证据兜底；已明确所有带契约mode
  溢出强制安全说明。参数校验也前移至fingerprint入口，确保坏契约不会claim。规划计数重置0/3。
- 00:10 构造兼容核对：budgetedModelFor公开返回BudgetedChatModel，不能改成任意ChatModel破坏Java调用。
  明确在现有包装器最外层增加候选许可，保持签名；规划仍0/3。
- 00:10:51 规划检查1：需求、默认值、构造/指纹兼容与流式安全，无问题、无修改，1/3。
- 00:11:02 规划检查2：实际许可/reservation收尾、不可取消provider、契约与诊断隐私，无问题、无修改，2/3。
- 00:11:15 规划检查3：现有集成/真实模型/dev验收入口、恢复与Git路径，无问题、无修改，3/3。
  三轮之间未修改规划正文；此后一次性记录结果，进入实施。
