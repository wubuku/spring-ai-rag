# 质量稳定性与生产恢复闭环进度

基线：`f65edce1`；分支 `feat/quality-production-recovery-loop`。
规划见 [PLAN](2026-10-18_QUALITY_PRODUCTION_RECOVERY_LOOP_PLAN.md)。

## 2026-10-18 启动

- 用户批准三个切片同批交付，先规划、按1→2→3实施，最后统一真实模型/双实例/API/恢复验收。
- 工作区初始干净；fetch确认origin/main没有新增提交；未修改生产代码。
- 已探索轻量QA gate、Evaluation验证/执行、引用augmenter、实例准入、备份与故障恢复脚本、权限撤销和擦除。
- 当前：规划检查中；下一步完成连续3/3后实施。

## 规划检查修正

- 核对 DataErasureService 后确认：恢复库重新 preview/apply 会生成新 operation UUID。
  已将清单从“逐条匹配擦除操作”改为“阻断未完成擦除+核对实际数据记录”，防止合法对账永远无法通过。
  规划计数重置为0，生产实现仍未开始。
- 核对 compare 及QA metrics后补充：实际model/evidenceAssessment增量归因与客户端完整快照比较，
  不用只含code/profile/corpus的现有compare冒充模型一致性；规划计数仍为0。

## 2026-10-18 16:10 规划收敛与实施开始

- 连续三轮只读规划检查通过：需求/自包含；代码/API/安全；测试/成本/发布恢复；无问题轮次未改文档。
- 总体进度约15%。按切片一→二→三实施，完成后统一全量回归，不以受控模型代替真实质量。

## 2026-10-18 实施与定向验证

- 新增quality profile（默认三轮、1–200例），保留旧三例smoke；失败全程留痕、模型/配置指纹及case归因。
- 新增24例版本化合成语料，含120段无关背景；真实矩阵脚本只在显式付费确认下执行。
- 强化引用/不可信资料约束；QA metrics增量提供actualModel与evidenceAssessment。
- Java定向15项通过；Python质量门禁8项、恢复闸门11项、旧备份7项通过。
- 双实例演练已实际通过重放、CAS、公平性、即时吊销/删除、质量72例正例与48例反例、依赖恢复和强杀接管。
- 恢复第一次发现principal告警扫描时间导致合法对账误报；已从安全指纹排除expiry_alert_checked_at，
  增加回归，正在重跑。此前失败日志保留，不作为最终通过证据。
- 文档EN/ZH已同步到正式入口，门禁16/16。当前总体约60%，尚未完成完整回归/真实矩阵/最终审查。

## 2026-10-18 16:47 验收推进（实现检查0/3）

- 恢复闸门补齐Python 3.10对PostgreSQL非六位小数秒的兼容，自测12项；不接受无时区时间。
- 噪声语料加入后暴露夹具误取提示词中的引用示例：现只识别实际资料行，并去掉生产提示词中的
  虚构引用示例。新增离线回归，质量门禁自测现9项；旧失败制品保留。
- 证据删除竞态第一次误在查询扩展阶段触发；改为等待携带资料的最终生成调用，并保存响应。
  最新运行已通过跨实例答案409/删除fence，尚在执行强杀与恢复部分。
- 确认resolvedModel是路由别名而非真实型号，新增provider型号/配置回退/来源标记，旧resolvedModel不变。
  定向Java通过后追加了配置回退回归，纳入当前全量Maven验证。
- WebUI TypeScript、1030项Vitest、生产构建、41项核心Mock Playwright通过。
- 正在串行执行clean compile/test-compile、全量Maven、仓库门禁与完整PostgreSQL集成矩阵。
- 已启动双真实provider矩阵，使用自有隔离数据库及真实embedding；该首轮服务快照早于型号归因修正，
  仅作质量诊断，不能冒充最终修订的完整验收；待结果明确后复验最终代码。
- 总体约68%；未宣布真实质量、恢复闭环或Git交付完成。下一步继续检查实际失败、SDK和dev.sh联合验收。

## 2026-10-18 17:14 归因闭环与完整回归

- 前一轮完整Maven、仓库62门禁、28套件PostgreSQL、官方SDK Core/Starter20步及旧故障恢复演练通过；
  gated矩阵为Core230+Starter1，无失败/跳过。WebUI 1030单测与41核心Mock仍通过。
- Maven默认报告在gated执行后会被覆盖；一次跨目录汇总误包含历史隔离consumer报告，已识别为统计问题，
  不将该汇总中的历史失败/计数作为本轮结果。下一轮使用非quiet日志保存各模块正式总计。
- Grok-4.5真实24例的前两轮均24/24通过，实际型号来自provider报告；第三轮与MiniMax后续仍在运行。
- 进一步检查发现QA直接执行prepareForOperation没有创建检索诊断，case trace长期为空。
  已接回现有owner隔离/隐私投影/开关和fail-open持久化；provider失败也尽力保留trace，
  不调用额外模型、不提交Chat历史。新增6项回归通过，旧Java构造签名保持兼容。
- 新双实例演练现要求正反例trace齐全、owner可读取且他人不可读取，并等待实际embedding调用在途再SIGKILL。
  该增强版正在重新运行，之前通过不冒充最终版本。
- 真实144例矩阵的服务快照早于上述纯诊断修补。为避免重复付费运行完全相同的生成逻辑，另启动
  最终代码的8例×2轮×2真实provider（32例）专项，覆盖数字/单位/表格/否定/不可信资料/跨文档/不足/冲突，
  要求全部trace可归因且抽查实际读取；与144例结果分开报告，不冒充完整矩阵重新通过。
- 下一步：最终代码完整后端回归、最终双实例、32例归因、真实dev.sh/Playwright；实现检查仍0/3。

## 2026-10-18 17:34 最终验收推进与失败保留

- 最终双实例受控演练11项全部通过，包括owner隔离trace、实际embedding调用在途时强杀A、
  B接管lease、预算无悬挂和旧备份隐私对账；受控回答不作为真实模型质量证据。
- `scripts/dev.sh`隔离启停、真实embedding/LLM的PLAIN/KNOWLEDGE/AGENT/SSE、live OpenAPI、
  真实浏览器Chat及API principal生命周期全部通过，停止后无自有监听残留。
  测试进程显式声明已经实际确认的Grok streaming/tool-calling能力，Agent确实发生工具调用，
  不把未声明能力导致的测试跳过或降级算作工具验收通过，也不修改生产默认能力声明。
- Grok-4.5完整24×3为72/72通过；该快照早于纯诊断修补。最终代码的8×2专项16/16通过，
  trace齐全并可读取，Native/OpenAI JSON/SSE、Python/Node和真实HTTP rerank已通过。
- MiniMax-M3完整24×3为66/72，第二轮虽全绿也不能抹去第一、三轮失败；有依据却无引用而被安全拒答3例、
  资料不足声明未被硬门槛识别3例。仍在收集同语料的私有回答诊断，不能将后者直接判定为幻觉或误报。
  对该模型的质量验收保持失败，不能声称双模型质量全部通过。
- 真实执行器此前错误假定存在Prometheus端点，实际依赖调用已成功但验收末尾404；改为现有
  Actuator metrics端点读取rerank成功计数，并新增离线回归。旧失败制品保留，未为测试引入无关依赖。
- 最终后端clean编译已通过，正在串行执行全量Maven、62门禁、完整PostgreSQL、官方SDK和含真实k6的
  旧恢复演练。实现检查仍0/3，不以单测、浏览器或单轮模型成功代替剩余验收。

## 2026-10-18 17:36 冻结提示约束改进，重新验收

- 最终代码的MiniMax专项16例出现1例有资料但漏引用；私有合成回答诊断确认同语料能正确回答，
  也能明确拒绝猜测密码和未来价格。重复成功不能推翻原失败，未声称已经定位全部失败的唯一根因。
- 在资料和用户问题之后再强调真实引用，并要求证据不足时明确使用“无法从资料确认”说明，
  避免只输出有引用的无关背景；新增位置与内容回归，默认生效、不补写引用、不调用第二次模型。
- 冻结24例定义、来源、必要/禁止事实及全部硬断言，均未降低。后续为此实质提示修改新增有界验收运行，
  原MiniMax66/72和15/16保持失败，不以重跑覆盖。前一队列结果仅作为修改前回归证据。
- 实现检查计数仍0；接下来串行重新执行基本门槛，并对最终提示进行完整双模型矩阵与启停复验。

## 2026-10-18 17:47 安全拒答按正常业务结果验收

- 按用户补充要求：明确的模型安全对齐拒答不是异常，不忽略、不通过重试或切模型规避。
  保留质量硬门槛结果，但把模型质量、正常拒答的应用处理和真实服务故障分开解释。
- 默认提示明确引用要求不凌驾于安全规则；双实例演练新增12个组合，检查两节点的Native PLAIN/
  KNOWLEDGE和OpenAI PLAIN各自JSON/SSE安全拒答，成功信封、唯一终态、PLAIN不重试、预算无悬挂。
  KNOWLEDGE沿用现有fail-closed安全回答，不伪造引用或将拒答转换为服务异常。
- 该受控场景只证明应用层处理正确；MiniMax缺引用不被武断归因为上游安全政策。真实质量失败制品保留。

## 2026-10-18 17:50 结构化拒答补充规划

- 已交叉检查本地Spring AI 1.1.8的实际字节码：OpenAI适配器会把独立`refusal`字段投影到
  AssistantMessage metadata；本仓目前只读取正文，存在合法结构化拒答变成空白结果的边界。
- 先在规划补充最小归一化方案及验收，不扩展为内容审核平台，不猜测纯文本拒答的政策来源。
  最终验收编排已暂时阻止进入新一轮编译，待此补充检查收敛后恢复，避免混用代码快照。

## 2026-10-18 17:54 结构化拒答实现与定向验证

- 补充规划于17:50完成连续3/3：依赖及数据流、外部契约/安全/成本、验收及恢复范围分别交叉检查，
  三轮无方案修改；总结输出在会话，不用review替代实际测试。
- 增加内部归一化器，将明确的`refusal`/`content_filter`转换成正常可见响应；保留usage、context和
  finishReason，文本拒答不被猜测为结构化政策信号，未分类空正文及畸形响应保持旧规则。
- 新5项Java回归及既有Chat/evaluator/augmenter定向测试通过，含流式分片拒答被末尾空分片覆盖的回归。
  Python质量自测13项通过，明确`MODEL_SAFETY_REFUSAL`不等于`EXECUTION_FAILURE`。
- 双实例安全拒答专项扩展为36组合：文本、独立refusal、空正文content_filter，各覆盖两个节点、
  Native PLAIN/KNOWLEDGE及OpenAI PLAIN的JSON/SSE。正在执行真实HTTP/PG专项，随后恢复冻结代码全量验收。

## 2026-10-18 18:03 端到端拒答问题收敛

- 首次专项暴露真实问题：独立refusal的空正文在共享记忆提交时失败；修补持久化投影后，第二次专项又
  揭示KNOWLEDGE流的advisor内部聚合早于外层归一化，导致拒答归因丢失。两次失败制品原样保留。
- 将无调用副作用的归一化移到ChatModel边界，先于ChatClient advisor、内部流聚合及Memory；保留外层
  幂等保护和持久化投影。新增模型委托只调用一次、归一化幂等回归，7项归一化/边界单测通过。
- KNOWLEDGE对明确结构化拒答提供受控安全提示与`provider_safety_refusal`原因；显式拒答符合证据不足
  用例的拒答要求，但仍不能豁免必要/禁止事实及授权断言。文本拒答不靠关键词冒充结构化政策信号。
- 最终专项还加入Python/Node官方SDK的结构化拒答和过滤终态JSON/SSE验证。冻结实现后重做全套后端、
  双实例、真实模型和dev.sh验收；实现检查仍0/3，之前通过的快照不冒充最终组合。

## 2026-10-18 18:07 外部Client拒答投影补齐

- 前一专项的Native四种JSON/SSE组合已通过，但OpenAI JSON缺少新增的拒答标记；正文拒答与HTTP200
  均正确，此失败是新字段投影缺失，不是模型异常。已补齐`rag.modelSafetyRefusal`及终帧投影。
- 只增可选字段，保留OpenAiRagExtension四/五参数构造器，并新增序列化/兼容构造回归；普通结果不输出新字段。
  统一重做36组合及官方Python/Node SDK检查，未删除前次失败记录。

## 2026-10-18 18:13 最终拒答验收通过，完整回归继续

- 文本/结构化/过滤终态36个两节点协议组合全部HTTP200、唯一正常终态、PLAIN无模型重试、预算无悬挂；
  两种结构化形式各自Python/Node官方SDK的JSON/SSE均通过。证据目录：
  `.verification/quality-production/20261018-180706-8f2ec815`，全部响应与前次失败分开保存。
- 最终Java代码clean compile/test-compile和全量Maven通过：API566、documents89、core7985、starter49，
  共8689项，失败/错误0；其中215项条件跳过另由完整gated PostgreSQL矩阵验证，不能算成已执行。
- 正常结构化拒答另在`refusalOutcomes`及逐例`refusalType`记录，不列入`failureCategories`。
  Python质量自测13项通过；必要事实未回答的质量结果不被改写成服务执行异常，也不自动洗绿。
- 最终Grok/MiniMax各24×3有界矩阵即将使用冻结Java快照执行；之后的仓库门禁、完整PG、官方SDK、
  k6/旧恢复、最终双实例及dev.sh仍按串行Maven规则推进。实现检查仍0/3。

## 2026-10-18 18:40 最终运行证据与限定结论

- 冻结实现29个非文档文件，SHA256：
  `216520f21157fa3751345a7f1728b7c06bf17b573f3dc14c8e415447c8168524`；
  后续只调整文档、归档和Git交付，逐文件指纹复核无变化。测试时HEAD为`f65edce1`加本批工作树；
  不能把测试制品中的旧HEAD字符串理解为只测试了基线代码。
- `/tmp/quality-loop-certified-final.log`保留最终编排，18:09开始，18:38结束。
  **组合执行最终退出1，仅因MiniMax质量门槛未过；没有改判总结果为通过。**
- clean compile/test-compile通过；`mvn test`四模块总计8689项，失败/错误0、条件跳过215项，
  实际执行8474项。完整gated PostgreSQL的28套件另执行231项（Core230、Starter1），无失败/跳过；
  两组报告独立，不把跳过项或重复执行当成新增通过。
- 仓库62门禁、官方Python/Node Core/Starter SDK20步通过；前端TypeScript、生产构建、
  Vitest1030/1030、核心Mock Playwright41/41通过。最终真实前后端Playwright2/2通过；
  Mock结果没有替代真实后端、嵌入或LLM验证。
- 最终旧依赖/恢复演练（含真实k6、客户端断连、节点强杀、原子恢复及原件/向量/API）通过，
  制品：`.verification/dependency-recovery/20261018-182707-a9b4aad3/`。
- 最终双实例演练通过：重放/CAS、principal公平性、即时吊销删除、在途证据fence、72例受控正例、
  48例有合法引用但事实错误的反例、trace owner隔离、两节点依赖恢复、实际embedding在途强杀后lease接管、
  预算终态，以及旧快照的负闸门→现有API对账→正闸门与无残留核验。
  制品：`.verification/quality-production/20261018-182954-59fffda2/`。
- 同一演练覆盖文本/独立refusal/空正文content_filter三类、两节点的Native PLAIN/KNOWLEDGE与
  OpenAI PLAIN JSON/SSE，共36组合；HTTP成功、可见拒答、唯一终态、PLAIN不重试、预算无悬挂。
  结构化与过滤拒答另以官方Python/Node SDK各验证JSON/SSE。此受控证据不冒充真实provider安全政策。
- 两个真实provider各24例×3轮，共144例，全部使用真实嵌入，case trace均72/72齐全且owner可读；
  两边均通过真实Client API、Python/Node JSON/SSE及HTTP rerank成功指标检查。
  Grok实际报告`grok-4.5`，72/72通过，`acceptanceStatus=PASSED`；
  MiniMax实际报告`MiniMax-M3`，69/72通过，`acceptanceStatus=FAILED`。
  制品分别是`.verification/quality-real/20261018-181257-e3b8c5ca/`、
  `.verification/quality-real/20261018-181337-46e748b6/`。
- MiniMax失败：第2、3轮`untrusted-instructions`各命中一次禁止字串；第3轮`zero-loss`未保留
  必要事实和有效引用。没有结构化安全拒答信号，不能把这些失败统称“安全对齐正常拒答”，也不能
  仅凭禁止字串判断恶意指令已被执行。质量汇总另有2次`ANSWERABLE_REFUSAL`诊断，包含1例硬断言
  已通过的回答；它不是新增失败例数，也不是已证实的上游安全策略。未改低v1断言、删旧失败或重试到绿。
- 真实质量门槛是provider/配置的准入结果，不是API成功率。服务实现/兼容性/恢复验收通过；
  MiniMax在该配置下没有取得本矩阵质量准入，保留为明确的模型级限制，不能宣称所有模型全绿。
  正常安全拒答另作业务结果记录，不进入执行异常；必要/禁止事实及授权断言不因拒答而被豁免。
- 最终`dev.sh`在隔离PG和动态端口实际启动，真实embedding及PLAIN/KNOWLEDGE/AGENT/SSE、
  live OpenAPI旧契约、浏览器问答/历史/principal生命周期通过；实际执行`--stop`，两进程状态均stopped。
  制品：`.verification/quality-dev/20261018-183439-8fb51133/`。结束无本任务容器残留。
- 本批无schema变更，仍V65；新增正常拒答处理默认生效，不增加普通Chat评测成本或要求运维迁移。
  正式入口为[质量验收指南](../../quality-production-acceptance-zh-CN.md)。
- 规划与进度按生命周期归档；最终实现只读3/3与Git交付在所有上述实际运行完成后进行。
  无问题审查轮次只在会话输出，不再修改文档。
