# 上游故障处理与 Client 可恢复性闭环

状态：2026-10-18 已实施并完成分层验收；规划与实现检查均为连续3/3。
服务/恢复/契约门槛通过；真实QA为117/120，具体质量边界见进度账本，不宣称模型质量全绿。
基线 `f346f3d0`，分支 `feat/upstream-client-recovery`，当前工作区。
实施顺序：1 分类 → 2 执行决策 → 3 Client 契约 → 4 真实失败闭环 → 统一验收。
遵循[交付工作流](../../delivery-workflow-zh-CN.md)，进度见同目录
[进度账本](2026-10-18_UPSTREAM_CLIENT_RECOVERY_PROGRESS.md)。不创建 worktree，不引入新的外部服务。
稳定行为见[上游故障与Client恢复](../../upstream-client-recovery-zh-CN.md)；本归档保留实施前事实与设计语境。

## 1. 目标与边界

服务面向外部 Client；服务故障、安全拒答、证据兜底和回答质量分开验收。
正常安全拒答不抛错、不重试、不切模型规避。不保证模型每次回答成功，也不把安全兜底当事实回答。
保留既有 HTTP 状态、错误码、Java 构造器、JSON 字段和 SSE 终态顺序；默认提供新增信息。
不做 WebUI 新页面、Parser SPI、模型管理平台、schema 迁移或自动改写引用。
没有数据结构变更，因此无需迁移或人工运维操作。

## 2. 已核对事实与落点

- `api/enums/FailureClass.java` 是稳定粗分类，优先由 HTTP 状态推断；不替换其既有语义。
- `core/controller/GlobalExceptionHandler.java` 的未知错误仍返回 500/INTERNAL_ERROR；OpenAI
  专属 handler 返回 503/service_unavailable。保留这一区别，新增详细信息解决辨因，不更改旧状态。
- `core/config/RetryConfig.java` 只按外层异常分类，当前指数退避未实现配置声称的抖动/Retry-After。
- `core/retrieval/EmbeddingFailureClassifier.java` 已有有界异常链分类；迁移共同的底层识别但保留
  其 NONE/TRANSIENT/REJECTED/UNKNOWN 调用契约及既有行为。
- `core/chat/ChatExecutionService.java` 有 candidate/model-call/deadline 预算和流首事件前 fallback；
  目前普通 RuntimeException 会进入候选回退。明确的永久性上游错误应停止回退，未知异常不擅自推断为网络问题。
- `core/config/RagChatService.java` 的熔断统计目前把业务异常也计为失败；需排除授权、预算、冲突、取消等。
- `core/chat/ChatTurnOperationService.java` 已有失败快照和失败 replay；快照不含正文，可增量存低敏分类。
- `api/dto/ChatResponse.java`、`api/openai/OpenAiRagExtension.java` 和 Native completed metadata
  已有证据/拒答字段；增加统一回答结果投影，不替换原字段。
- `core/evaluation/EvaluationDiagnosticCapture.java` 仅在双确认主动诊断期间采集，prompt/answer各32KiB、
  证据16×4KiB。当前 answer 是应用最终回答；需要同次调用的原始输出，不能另发模型请求冒充历史。
- `core/rag/CitationQueryAugmenter.java` 已要求逐字使用引用；进一步将实际可用标识显式列成白名单，
  不自动把 S1 映射为 c1，不放松 validator。单位规则随问题明确要求，不暗加断言。

上述路径中的 api/core 分别是 `spring-ai-rag-api/src/main/java/com/springairag/api/` 和
`spring-ai-rag-core/src/main/java/com/springairag/core/`。API对照见[Client Kit](../../client-contract-kit-zh-CN.md)
§5、[可信诊断](../../trustworthy-evaluation-admission-zh-CN.md)和[质量与恢复验收](../../quality-production-acceptance-zh-CN.md)。

## 3. 冻结设计

### 3.1 同源分类

新增 API `FailureDetails`：origin、reason、retryable、retryAfterMs、upstreamStatus（数值可空）。
origin 使用 UPSTREAM/REQUEST/SERVICE，reason 为固定低敏标识，不含异常正文/URL/凭据。
retryable 只表示故障可能暂时恢复，不表示请求可以无条件重放；终态 turn 同 key 仍精确重放失败。
新增 core `UpstreamFailureClassifier`，最多16层、identity防环；业务拒绝优先，结构化HTTP比包装异常优先。

| 信号 | reason | 同模型重试/首输出前fallback | 计依赖熔断失败 |
|---|---|---|---|
| HTTP401/403 | UPSTREAM_AUTHENTICATION | 否 | 否（配置需修正） |
| HTTP429 | UPSTREAM_RATE_LIMITED | 有界，服从原开关 | 是 |
| HTTP408/504、Socket/HTTP超时 | UPSTREAM_TIMEOUT | 有界 | 是 |
| HTTP其他5xx | UPSTREAM_UNAVAILABLE | 有界，503服从原开关 | 是 |
| HTTP其他4xx、NonTransientAiException | UPSTREAM_REJECTED | 否 | 否 |
| IO/ResourceAccessException | UPSTREAM_CONNECTION | 有界，服从原开关 | 是 |
| TransientAiException | UPSTREAM_UNAVAILABLE | 有界 | 是 |
| 取消/中断 | CANCELLED | 否 | 否 |
| Chat总期限耗尽 | REQUEST_DEADLINE | 否（已无预算） | 否 |
| RagException预算/权限/冲突等 | BUSINESS_REJECTED | 否 | 否 |
| 其他未知异常 | UNCLASSIFIED | 否；不伪装为上游 | 否 |

Native ErrorResponse、Native SSE error、OpenAI error对象增量加入 failureDetails；不修改旧error/code、
failureClass、type。保留OpenAI四参数Error构造器。失败快照增量保存相同低敏字段，旧快照可读。
错误对外信息脱敏；分类中不解析上游错误正文。429 Retry-After支持秒和HTTP日期，限制0–10秒，
只接受合法有界值；不能让provider任意延长请求。

### 3.2 重试与收尾

同一个分类器驱动重试、fallback和熔断。未知错误默认不自动重试/fallback；测试的故障夹具应使用
真实可识别的 transient 类型，不能继续用 IllegalStateException 冒充网络错误。
指数退避加入现有jitter配置；Retry-After作为最短等待，最终仍受配置maxBackoff和请求剩余期限限制。
通过 RetryContext 最近异常取得等待建议；不引入线程共享的最近错误状态。
deadline在每次调用前及重试等待前检查；等待不足则终止，不越过deadline发起新调用。
流已经有事件后不fallback；取消不fallback。保留现有纯空流的兼容边界，不伪造答案或成功终态。
现有预算reservation与lease实现继续使用；不承诺取消能撤销provider已接受的调用，也不伪造usage。

### 3.3 回答与恢复投影

在Native metadata和OpenAI rag中新增 answerOutcome：ANSWER、MODEL_SAFETY_REFUSAL、SAFE_FALLBACK、
OUTPUT_TRUNCATED、OUTPUT_INCOMPLETE（provider未返回结束原因）；优先级安全拒答→安全兜底→长度截断/不完整→正常回答。这里只描述已完成响应，
故障和取消走既有错误/取消路径，不把其伪装成业务回答。保留finishReason和所有旧证据字段。
普通文本没有明确安全信号时不猜测为安全拒答。引用校验通过不等于事实正确。
Client Kit增加恢复决策：不可重试/未知错误不自动重试；暂时故障仅在调用方能安全重放时建议重试；
keyed执行不确定时优先查询turn，绝不自动拼接部分SSE。示例默认不新增自动模型调用。

### 3.4 同次调用诊断与生成约束

只在已有显式付费+敏感内容双确认诊断中，新增 modelAnswer（安全替换前原始输出，最多32KiB），
以及answerOutcome和citationValidation摘要。原有answer仍是最终交付内容；complete判定加入新增字段的
截断/脱敏状态，最大文本总量增加32KiB，原有隐私/ACL/过期规则不变。不保存到metrics或共享日志。
模型输出在toResult阶段采集；当前诊断使用同步prepareForOperation且lease=null，不跨deadline executor，
不扩大ThreadLocal传播范围，不让超时后后台线程继续写入已关闭的私有capture。
引用prompt增加请求内可用ID列表与格式例子，例子从真实ID生成，不硬编码S1。
问题明确要求单位时输出数值+单位；不修改既有不可变评测定义/失败制品，不靠补引用洗绿。

### 3.5 真实HTTP核实后的传输边界

默认OpenAI/Anthropic/MiniMax及配置型OpenAI/Anthropic同步RestClient显式使用Simple请求工厂，保留既有代理、连接及读取超时。
Spring AI模型内部重试设为单次；Python/Node SDK示例禁用隐式重试。实际HTTP矩阵按每请求provider
调用数验收，防止默认Apache传输对429自动重试绕过应用预算。无需变更schema或运维配置。
half-open在同步流构造失败、业务拒绝和取消时释放占位；legacy先完成本地准备再获取probe。
Anthropic的SDK流式默认handler会把状态/正文拼进普通异常，默认与配置型工厂均在WebClient filter
中先提取状态和有界Retry-After、释放错误body并抛低敏typed异常；不通过解析错误正文找回状态。

## 4. 文件级顺序

1. API FailureDetails及classifier；分类边界单测、错误controller JSON/SSE和失败replay测试。
2. RetryConfig、自定义有界BackOffPolicy、ChatExecutionService fallback、RagChatService熔断；
   测试嵌套异常、永久拒绝、503开关、等待/抖动、deadline、取消与流首事件边界。
3. answerOutcome各协议投影、Consumer Contract Kit恢复决策和Python/Node示例；旧构造器/快照回归。
4. DiagnosticCapture同次原始输出、引用白名单prompt、私有重放脚本；诊断与正文不落库测试。
5. 提升到双语正式文档；执行下述统一验收，再实现3/3与Git交付。

## 5. 验收矩阵与证据

先窄范围测试，再统一全面验收，Maven在同工作区绝对串行。证据目录`.verification/upstream-recovery/`。

- 后端：clean compile/test-compile、全量mvn test、全部gated PostgreSQL套件、Flyway现有schema可启动。
- 黑盒：扩展现有受控真实HTTP/PG脚本，覆盖401/403/429/503/超时/包装异常/断流/拒答/诊断；
  断言旧状态/字段、增量原因、唯一终态、实际调用次数、失败replay与预算lease收敛。
- Client：原生/OpenAI JSON/SSE、旧consumer fixture与新增恢复fixture、Python/Node live SDK。
- 前端：共享API变更需TypeScript、Vitest、production build、核心Mock Playwright；不新增UI功能。
- 双实例恢复：现有quality-production验收，依赖故障恢复脚本；不以Mock替代。
- 真实模型：已有授权`.env`凭据（不打印），至少Grok/MiniMax及真实embedding/rerank；多场景矩阵有界运行，
  保留拒答、质量失败和上游故障，分别报告业务处置/服务执行/事实与格式质量，不无限重跑。
- dev：现有生命周期helper验证scripts/dev.sh启动、健康、真实API与停止清理，不冒用旧日志。
- 门禁：verify-project-tests、verify-project-docs、禁悲观锁、diff/secret检查；相关测试有红则不交付。

完成标准：四切片实现且默认启用；兼容性、隐私、成本和状态恢复有自动化证据；规划/实现各3/3；
已批准业务语义的拒答不计服务失败；保留仍未满足的质量目标及其根因证据，不用全绿话术掩盖。
遵循专用分支→合并上游→最终验收→main→push，无破坏性git操作。
