# 可信评测、失败取证与变更准入规划

基线 `3f5f22fa`；分支 `feat/trustworthy-evaluation-admission`；2026-10-18。
状态：已实施，验收证据已冻结并归档。用户已授权规划后实施、真实模型验证与最终Git交付。
真实模型严格质量未全部通过，不批准为生产基线；事实、限制和最终交付检查入口见进度记录。

## 1. 目标与代码事实

本批按“断言可信化→失败取证→变更准入”实施，不新增模型编排平台，不绕过安全拒答。
正式约束见[交付工作流](../../delivery-workflow-zh-CN.md)、[质量验收](../../quality-production-acceptance-zh-CN.md)、
[REST API](../../rest-api-zh-CN.md)、[测试指南](../../testing-guide-zh-CN.md)。

- `EvaluationSuiteDefinitionValidator`把不可变定义规范化并校验SHA，已有最多200 case、4 variant。
  `CaseDef`已有11参数及旧6参数构造器；新增字段必须保留两者。
- `KnowledgeQaEvaluator`对required/forbiddenFacts作NFKC、去空白、小写后的contains；旧语义不能偷偷更改。
  Citation VALID/SUPPORTED只证明引用协议，不证明事实蕴含；本批不宣称实现通用事实证明。
- `EvaluationSuiteService`的executeCase统一处理身份、scope、STATELESS KNOWLEDGE、费用、trace、断言。
  case metrics持久化，但不保存答案。诊断不能把原始正文塞入现有metrics破坏隐私默认。
- `SafetyRefusalChatModel`在ChatClient advisor前处理结构化拒答，可作为仅显式诊断时的调用取证边界。
  KNOWLEDGE最终来源从实际advisor DOCUMENT_CONTEXT提取；与最终可见安全回答一起保存才能解释替换。
- Evaluation API已有owner隔离及运行时ACL复核。新接口复用同一执行器，且返回前再校验当前权限。
- QA runner已有完整逐轮run/summary、定义与环境指纹；release已有检索回归及旧QA入口，但尚未直接
  接入新的多轮quality矩阵与候选/已审核基线比较。
- 当前V65，无本批需要持久化的新数据；不改schema、不回填或修改旧v1案例与历史结果。

## 2. 结构化断言与结果语义

case增量可选`assertions`（最多32）和`expectedOutcome`；RETRIEVAL不能使用它们。
旧requiredFacts/forbiddenFacts原样执行，新assertions与旧断言是AND，不是替代或豁免。
所有新字段严格类型/长度/未知字段校验，不接受用户正则表达式或可执行表达式。

assertion字段：唯一id（1–64），type，forbidden（布尔，默认false），按类型使用value/unit/min/max/alternatives。

- `TOKEN`：NFKC后按ASCII标识符边界匹配精确字面量，避免0匹配10、SAFE-42匹配SAFE-420。
- `TEXT_ANY`：1–8个非空短语的明确字面量择一匹配，不声称支持语义同义推理。
- `QUANTITY`：有限十进制value与紧邻unit匹配，支持规范的千位分隔、小数和中文整数；不能把不同单位
  或另一个句子中的数字拼起来。unit为显式字面量，不隐式单位换算。
- `NUMBER_RANGE`：min/max闭区间及同一unit，校验min≤max、数值精度/长度有界。
- 每条结果给出matched/passed与匹配位置；纯本地诊断重放另提供有界匹配文本。持久metrics不保存匹配文本，
  防止短答案恰好全文命中而意外落库。命中禁止字串不自动等于已执行注入，报告只承诺词法事实。

`expectedOutcome`可为ANSWER/INSUFFICIENT_EVIDENCE/MODEL_SAFETY_REFUSAL。默认缺省保持旧判断。
明确结构化安全拒答为正常业务结果；只有上游明确refusal/content_filter信号才标MODEL_SAFETY_REFUSAL，
不猜测纯文本的政策来源。期待该结果时允许没有引用，但必要/禁止事实与授权断言仍执行。
ANSWER要求没有结构化拒答或不足声明，但本身不证明答案正确。指标增量提供observedOutcome、assertions。

## 3. 有界、主动、不落正文库的诊断

新增POST `/api/v1/rag/evaluation/suites/{suiteKey}/versions/{version}/diagnose`：
请求`caseId`、`variantKey`、`ackSensitiveContent=true`、`ackPaidModels=true`；两项确认缺失均在调用模型前拒绝。
这是一次新的、明确收费的case执行，不是读取或重建历史run，不自动对失败无限重试。
已有路由集中策略显式登记、默认拒绝未知路由，能力与现有Evaluation执行一致。

服务复用executeCase；仅同步诊断线程启用可关闭ThreadLocal scope，正常Chat/后台run不采集正文。
模型边界记录有界的最后一次实际调用消息；在同一执行器记录最终可见answer、实际输入evidence、usage、
模型归因、source身份、断言和trace。prompt和evidence分别标识，不能用拼接资料冒充完整最终prompt。
采集范围：prompt总32KiB UTF-8、answer32KiB，实际DOCUMENT_CONTEXT与来源投影各最多16项/每个4KiB；
文本总量最多192KiB（JSON转义后可能更大），CLI对响应再设1MiB硬上限；任何截断/脱敏均显式标记。
复用SensitiveDataMaskingConverter处理已知敏感模式；不声称能识别所有PII，原始客户资料须调用者明确授权。
HTTP `Cache-Control: no-store`；owner及全部scope权限在执行前和返回前检查；不写case metrics、Chat历史或新DB表。
正常trace和usage仍由已有保留/擦除机制管理。下载后的私有制品与其他导出一样，不可由服务端撤回。

新增本地CLI record/replay/purge：record需两项显式确认，API Key仅来自环境，不输出请求/正文/凭据；
目录0700、文件0600、不覆盖、拒绝符号链接；manifest含版本、文件SHA、24小时到期时间和调用性质。
record/purge清理本工具自己生成且通过manifest识别的过期目录，有界扫描，不碰任意目录/用户文件。
replay纯本地、无HTTP/模型调用，校验SHA/版本/到期/完整性后重新检查结构化断言；截断或脱敏不能声称完整重放，
返回INCONCLUSIVE/非零。保留原始硬门槛失败，绝不靠重放把既有FAILED改成PASSED。
Java与Python匹配器共享版本化边界夹具，防止离线重放与服务端算法漂移。诊断不承担通用事实蕴含/越狱判定。

## 4. 版本化质量基线及发布准入

新增离线准入脚本，读取quality输出目录及显式提供的已审核baseline；无网络/LLM副作用。
默认要求两边均为完整、可信、有文件摘要的至少两轮quality结果，case/variant/定义SHA/语料内容身份一致，
每轮内部模型/配置一致、所有case确定性通过；拒绝缺失/重复case、旧失败baseline、截断或篡改制品。
模型、embedding、配置、代码变更是可比较的候选变化，报告差异而不是把变更本身当作退化。

新增按Collection的内容SHA摘要到configurationSnapshot：只在主动评测生成，最多100000条记录，
按原件内容/metadata及稳定外部身份生成稳定摘要，不输出正文，不依赖重新导入时变化的数据库ID或时间。
每次查询使用全请求剩余额度LIMIT，最多返回100001个指纹；完整集合在Java排序后摘要，避免SQL先对超大集合全量摘要排序。
超过100000文档的受影响摘要为null，离线准入失败关闭，原有评测Run API不因新准入条件而被阻断。
旧collectionSnapshot和compare语义保留。过大集合拒绝完整性声明，不截断冒充成功。
runner保存完整定义及制品摘要manifest；v1历史无manifest不自动获得新准入，新制品另行生成，不篡改历史。

报告逐case改善/退化、正常安全拒答、事实失败、执行失败、token与延迟变化；不能用平均分掩盖失败。
延迟/token比率默认仅报告，需要硬限制时由可选显式阈值启用，避免把测试环境波动当模型事实退化。
baseline不自动接受，不自动切模型，不要求每个provider全绿才能解释应用协议，但指定候选未过就非零退出。
`verify-release.sh`增加独立`--with-knowledge-quality`，调用显式指定的候选与baseline准入；缺文件必须失败。
收费执行与离线发布验收分离，不让普通Chat、默认构建或每次发布偷偷发起付费调用。

## 5. 实施文件与切片

1. CaseDef/validator、StructuredAnswerAssertions、KnowledgeQaEvaluator；共享边界fixture和Java/Python负例。
2. EvaluationDiagnosticCapture、SafetyRefusalChatModel、EvaluationSuiteService/Controller、可选请求DTO、
   路由策略、诊断CLI和离线重放；权限/截断/脱敏/ThreadLocal清理/不持久化测试。
3. corpus snapshot、QA artifact manifest与离线baseline gate、release flag；兼容旧runner并扩展quality自测。
4. 在新的v2 fixture使用结构化断言；v1文件保持字节不变。新增受控正反例、诊断及基线黑盒验收。
5. 同步quality-production-acceptance、rest-api、testing/developer-reference、release-checklist双语文档；
   正式事实提升后归档plan/progress，提交、fetch/merge、合回并push main，清理自有资源。

## 6. 一次性验收矩阵

- Java定向：旧构造器/旧definition、未知字段/类型/界限、数字包含/零/小数/千分位/中文整数/错单位/范围、
  TOKEN边界、forbidden词法而非意图、结果语义、合法引用错误事实、未知文本拒答、所有采集边界。
- Python离线：共享夹具一致、旧三例/新24例/结构化结果、artifact篡改/缺失/过期/路径越界/权限、重放零网络、
  基线fail-closed、明确改善退化、实际型号/配置变化报告；原失败不得被重放洗绿。
- 基本门槛：串行clean compile/test-compile、全量mvn test、62+仓库门禁、完整28套件gated PostgreSQL、
  文档16、禁锁与diff/密钥扫描；同工作区不得Maven并发。
- API：旧Native/OpenAI JSON/SSE、官方Python/Node SDK、旧required/optional参数与响应、live OpenAPI；
  新诊断两次确认、owner/当前ACL、正常/拒答/provider失败、无正文DB残留，基线正负门槛；双实例旧恢复演练。
- 前端共享：TypeScript、Vitest、build、核心Mock Playwright；隔离dev.sh启动/停止及真实前后端联合。
- 真实：Grok/MiniMax各24例×3轮并使用真实embedding；有限次数、不重试到绿；额外各1个明确标记为新调用的
  诊断case及本地重放。保留v1失败；新v2断言报告独立，不把正常安全拒答当服务异常。
- 实际硬门槛完成后实现只读3/3；任何实质修复重跑受影响门槛并重置。模型质量限制如实单列，不改判组合结果。

## 7. 风险、默认与恢复

能力默认可用；诊断正文采集与付费测试因隐私/成本只在显式请求触发，不是遗留实现默认。
无schema迁移、无运维开关/手工重索引。失败响应不暴露正文或key；数据规模、响应和清理均有界。
所有新增定义字段可选、旧CaseDef构造器保留；旧URL/成功信封/必填参数不变。回退可普通提交移除新入口，
不触碰既有数据。用户客户语料没有提供，合成goldenset不能冒充客户真实分布；该领域缺口不阻断本批通用能力。
进度记录见同目录[验收与限制](2026-10-18_TRUSTWORTHY_EVALUATION_ADMISSION_PROGRESS.md)；
稳定能力见[正式说明](../../trustworthy-evaluation-admission-zh-CN.md)。最终只读检查与Git交付结论记录在会话输出和提交说明中。
