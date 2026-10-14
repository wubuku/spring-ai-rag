# WeKnora 第二批引用与流式安全实施进度

> 对应规划：[2026-10-14_WEKNORA_SECOND_BATCH_CITATION_STREAMING_PLAN.md](2026-10-14_WEKNORA_SECOND_BATCH_CITATION_STREAMING_PLAN.md)
>
> 状态：已完成；实现、测试、文档提升和实现复审均已收敛，准备归档。

## 1. 恢复入口

实施工作区固定为仓库当前 `main` 分支。恢复时先检查：

```bash
git status --short --branch
git log --oneline -5
```

然后阅读对应 plan 的第 1、3、4、6、7 节，再从 Slice A 开始。不要使用 `git reset --hard`、
`git stash`、`git clean`；不得覆盖工作区中的其他 WIP。

## 2. 已完成探索

- 已核对 `ChatSource`、`ChatEvent`、`ChatExecutionService`、`RetrievalTraceCollector`、
  `CitationQueryAugmenter`、全部三个内置检索 Agent 工具（`KnowledgeSearchTool`、
  `StaticKnowledgeSearchTool`、`JsonRecordSearchTool`）及 `CitationValidator`。
- 已核对原生 `RagChatController` 的普通流和 keyed snapshot 流、OpenAI 兼容 controller、
  `useSSE.ts` 及 Vitest 测试。
- 已核对 durable Chat history 与 turn response snapshot：已有 `sources` JSON 快照，不需要
  为本批增加迁移。
- 已核对 `docs/SSE-PROTOCOL.md`、双语 REST/API 文档和 `docs/delivery-workflow-zh-CN.md`。

## 3. 规划检查计数与记录

- 当前计数：`3/3`。
- 检查方式：每轮输出时间、范围、发现、处理和结果；若发现实质问题，修改 plan 并归零。
- 当前周期因实现交叉核验发现 Slice B 对“授权上下文存在但 handle 未解析”的兜底策略，与目标契约冲突而重启；
  已统一为 fail closed（跳过该资料），只有无授权上下文的兼容/独立调用保留 `[SN]`。
- 已有工作区改动不丢弃；规划复审已完成，继续执行实现、测试和实现后 `3/3` 检查。

### 2026-10-14 05:09–05:16 规划复审最终记录

1. **第 1 轮（05:09）**：核对目标契约、实现入口、双语长青文档和差异格式；无问题。
2. **第 2 轮（05:11）**：核对 DTO/JVM/JSON 兼容、身份 presence、授权回放、OpenAI 隔离和禁锁门禁；无问题。
3. **第 3 轮（05:13）**：核对测试矩阵、文档事实、脚本存在性、工作区范围和差异格式；无问题。

三轮之间未修改规划正文；最终计数 `3/3`。05:09 第 1 轮发现的长青文档遗漏已在此前修复，
因此重启后连续三轮均未发现问题。

### 2026-10-14 规划检查记录

1. **第 1 轮：需求闭环与自包含性**
   - 范围：目标/非目标、默认值、可逆边界、恢复入口、近距离代码索引和验收覆盖。
   - 发现：初版回归矩阵使用“必要时 gated IT”，验收门槛不够确定。
   - 处理：将 `./scripts/verify-gated-it.sh` 固定为本批回归门禁；规划计数重置为 `0`，随后完整重跑。
   - 结果：修正后的第 1 轮无问题通过。
2. **第 2 轮：代码/API/数据/安全可行性**
   - 范围：`ChatExecutionService`、`RetrievalTraceCollector`、授权上下文、history/turn snapshot、原生 SSE、OpenAI SSE、WebUI retry 和测试入口。
   - 发现：无实质问题；确认无需 Flyway 迁移，已有 sources JSON 快照可承载可选字段。
   - 处理：无。
   - 结果：无修改通过。
3. **第 3 轮：实施顺序、验收、发布与回滚**
   - 范围：Slice A-D 顺序、三层测试矩阵、文档双语入口、回滚/恢复、项目文档门禁和禁悲观锁门禁。
   - 发现：初次预检发现英文草稿导航未同步新规划。
   - 处理：补充 `docs/drafts/README.md` 对应入口，计数重置为 `0`，随后完整重跑三轮。
   - 结果：修正后的第 3 轮无问题通过；最终连续计数 `3/3`。

上述检查中的 shell 命令输出工具缺失（`rg`/`tail` 的 PATH 波动）仅修正了检查命令调用方式，
没有改变规划内容，不计为规划问题。

### 2026-10-14 扩展范围复审

- 发现：原规划只明确 `KnowledgeSearchTool`，遗漏 `StaticKnowledgeSearchTool` 与
  `JsonRecordSearchTool`；后两者也向 Agent 返回 `citationId`，若不同时输出请求级 handle，
  同一回答会混用新旧引用协议。
- 处理：规划新增全部内置 Agent 检索工具、模型提示、预算裁剪一致性和对应测试覆盖；补充
  不完整 source key 不得注册的断言，并明确 keyed snapshot 重试需验证序号确定性。
- 结果：此前 `3/3` 仅针对较窄范围，现标记为失效；本次扩展范围规划检查计数重置为 `0/3`。
- 当前保护措施：此前启动的代码改动保留在工作区；在新范围规划检查重新完成前，不再新增实现
  改动。

- 复审推进中再次发现：虽然目标契约要求更新 KNOWLEDGE/AGENT 提示，但文件级实施顺序未明确
  `ChatExecutionService.buildSystemPrompt()` 与对应 prompt 断言，可能只更新 augmenter 和 tool
  descriptions 而漏掉系统级指令。
- 已修正规划，明确 KNOWLEDGE 采用 `[cN]` 并兼容 `[SN]`、AGENT 仅引用工具真实返回 handle，
  并要求对应单测；本轮计数重新置为 `0/3`，后续三轮从第 1 轮重做。
- 本次重启后的第 1 轮又发现 keyed replay 的手动复制边界：
  `ChatTurnOperationService.stableSource()` 会丢弃新 DTO 字段，`stableMetadata()` 的 allowlist
  也会丢弃 `citationRegistry`。已将两个生产落点及 response snapshot/replay 回归测试补入规划，
  规划检查再次归零；修正后的三轮必须从第 1 轮重新开始。
- 随后核对授权路径时发现原非目标表述不够准确：现有 keyed replay 会通过
  `ChatAuthorizationService.verifyReplay()` 重新校验 principal、scope 和来源 ACL。已改为明确
  保留此 fail-closed 安全门槛，并把 `ChatAuthorizationSourceVerifyTest` 列为必跑回归；规划计数
  再次归零。
- 兼容/发布复核发现多副本滚动升级时，新 WebUI 可能重试到不带 sequence 的旧后端；按旧协议
  接受无序号事件虽然兼容，但此时无法去重。已将此作为明确保证边界，并冻结“后端全量升级后
  才依赖 retry 去重”的默认发布顺序；本轮计数再次归零。
- 工具预算复核发现静态知识 catalog 只限制正文累计字符数，序列化后的 tool result envelope
  可能越过 `maxToolResultCharacters`；新增 handle 会增大该风险。已要求在 tool 级按序列化输出
  裁剪来源/snippet、同步暴露集合和计数，并测试三种内置工具的最终字符上限；规划计数再次归零。
- 交付硬门槛复核发现验收矩阵漏列 WebUI 必须运行的 `npm run check:design-system`、核心 Mock
  Playwright、`mvn clean compile test-compile`/PostgreSQL profile 运行时门槛，以及本次涉及模型
  提示和 Tool Calling 时适用的真实 provider smoke。已将这些门槛和隔离 Agent 场景加入真实
  smoke harness 的规划；凭据不可用时作为环境限制如实记录，规划计数再次归零。
- 本轮代码/API/数据身份交叉检查发现 `RetrievalResult.chunkIndex` 是 primitive `int`，缺失值会
  默认成合法值 `0`，因此仅在 registry 校验 `documentId` 与数值索引不能满足“身份不完整则拒绝”
  的要求。已将可空索引、缺失值保留、全读取路径 null-safe 审计以及 `0` 合法的回归断言写入
  规划；本轮发现实质遗漏，检查计数重置为 `0/3`，尚不得恢复新增实现。
- 规划补全后的第一轮闭环复核又确认可空字段会触及 `RetrievalResult.equals/hashCode` 和 JSON
  缺失值序列化契约；已明确按 `Objects.equals` 保持对象语义，并对 null 字段使用
  `@JsonInclude(NON_NULL)`，维持缺失字段仍省略、有效数字不变；检查计数保持 `0/3`。
- 第一轮通读发现规划首页仍描述更早一次扩展范围导致的归零原因，可能使恢复者误判当前检查状态；
  已同步为最新的索引缺失值语义复核，检查计数保持 `0/3`。
- 第 2 轮生产路径交叉核验发现 `RetrievalDocumentMapper`、`ProjectRerankPostProcessor`、
  `StaticKnowledgeSearchTool` 都会把缺失索引补成 `0`，`RetrievalUtils` 还会用拼接 key 合并
  候选。已将各映射点 null 穿透、缺失 identity 不跨通道合并及四类现有回归测试写入 Slice A/验收；
  这是实质遗漏，规划检查计数重置为 `0/3`。
- 同轮继续核验发现 DTO 从 primitive 改为 nullable 后，现有 `DtoTest` 覆盖了 equals/hashCode，
  但验收矩阵未明确要求维持该契约及 Jackson 缺失字段行为；已把 DTO 测试与数值/省略序列化断言
  纳入矩阵，并同步当前复审原因，规划检查计数再次重置为 `0/3`。
- API 兼容性复核进一步确认 `RetrievalResult` 是公开 DTO，直接把 getter 从 primitive `int` 改为
  `Integer` 会破坏既有 JVM 方法签名。已改为保留现有 getter/setter 与旧 JSON 数值形状，用内部
  presence/nullable accessor 区分“未设置”和显式 `0`，并将回归断言同步到规划；检查计数再次归零。
- 紧接着检查 Jackson Bean 属性发现新增 public presence/nullable helper 若未明确忽略，可能泄露为
  新 JSON 属性；已要求 helper 使用 `@JsonIgnore` 并在 `DtoTest` 验证 JSON 字段集不变，规划检查
  计数维持 `0/3`。
- 交付复核发现项目通用流程要求大块功能使用专用分支，而本任务由用户明确指定直接在 `main` 工作区/
  分支实施。已在完成定义中记录为仅本批适用的一次性例外，保留无损处理工作区 WIP 和全量 diff
  审查规则；规划检查计数重置为 `0/3`。
- 验收矩阵将真实 provider smoke 列为必跑，但项目测试规范要求先获用户授权；已冻结“脚本必须补齐，
  真实调用仅在明确授权且凭据可用时执行，否则记录 `NOT RUN`”边界，避免无人值守时产生未授权
  外部调用或费用；规划计数重置为 `0/3`。
- 对照根 `AGENTS.md` 硬规则发现验收矩阵虽有 `mvn clean compile test-compile` 与受影响测试，
  但未明确要求完整 `mvn test`。已将全量 Maven 测试加入 Build/runtime 硬门槛；本轮发现实质漏项，
  规划检查计数重置为 `0/3`。

### 2026-10-14 扩展范围最终规划复审（连续 3/3）

以下三轮均为只读检查；三轮之间未修改规划或进度文档，最终达到 `3/3`。

1. **第 1 轮：目标闭环、自包含性和恢复路径**
   - 检查时间：`2026-10-14 04:43:28 CST`。
   - 范围：任务目标与非目标、引用/流式契约、默认值和可逆边界、当前基线、进度恢复入口、双语草稿导航。
   - 发现/处理：无实质问题；未修改文档。
   - 结果：通过，计数 `1/3`。
2. **第 2 轮：代码/API、身份数据、安全与兼容性**
   - 检查时间：`2026-10-14 04:43:51 CST`。
   - 范围：`RetrievalResult` presence API 与 JSON/二进制兼容、检索映射和去重、授权检索工具、snapshot/replay ACL、原生 SSE 与 OpenAI 隔离、WebUI sequence retry。
   - 发现/处理：无实质问题；未修改文档。
   - 结果：通过，计数 `2/3`。
3. **第 3 轮：实施顺序、验收、回滚和 Git 交付**
   - 检查时间：`2026-10-14 04:44:17 CST`。
   - 范围：Slices A-D、测试文件和脚本存在性、完整 Maven/WebUI/运行时门槛、真实 provider 授权、灰度兼容、回滚，以及用户指定 `main` 的任务级例外。
   - 发现/处理：无实质问题；未修改文档。
   - 结果：通过，计数 `3/3`；规划门槛完成。

## 4. 实施进度与结果

- [x] 规划连续 `3/3` 检查完成，且实施前发现的问题已按账本重置并重新收敛。
- [x] Slice A：`CitationRegistry`、collector 接入、`ChatSource`/WebUI DTO、索引 presence 语义。
- [x] Slice B：KNOWLEDGE/AGENT prompt、三种内置检索工具、引用校验、执行结果 metadata、snapshot/replay 保留。
- [x] Slice C：原生 Chat SSE `sequence`、SourcesAvailable registry metadata、WebUI 重试去重和 EOF 收尾。
- [x] Slice D：双语长青文档、测试门禁、实现后 `3/3` 复审、脚本补齐。

本批次不包含自适应分块、检索上下文组装、Route Policy self-check、Parser SPI；这些方向仍按第一批
路线保留为后续候选，不应从本账本推断为已实施。

### 4.1 自动化验证证据

以下命令均已在当前 `main` 工作区执行并通过：

- 后端：`mvn clean compile test-compile`、完整 `mvn test`（`7748` 项通过，`170` 项按环境跳过）。
- 后端专项：引用、工具、映射、SSE controller 与 keyed tail 测试均通过；`./scripts/verify-gated-it.sh` 的 gated integration `170` 项通过。
- WebUI：`npm run test:run -- src/hooks/useSSE.test.ts`（`34/34`）、`npm run typecheck`、`npm run check:alignment`、`npm run check:design-system`、`npm run build`、`npm run test:e2e:mock -- e2e/chat.spec.ts`（`11/11`）。
- 项目门禁：`./scripts/verify-project-docs.sh`、`./scripts/verify-no-pessimistic-locks.sh`、`git diff --check`。
- 真实模型：新增 Agent smoke 已写入 `scripts/real-llm-e2e-smoke.sh`，但当前无人值守且未获真实 provider 调用授权，因此记录为 `NOT RUN`；不得以 Mock 测试替代该证据。

### 4.2 实现后连续 `3/3` 复审

以下三轮均为只读检查，期间未修改实现、规划或进度正文；最终计数 `3/3`。

1. **第 1 轮：引用身份、授权边界和工具预算**（`2026-10-14 13:26 CST`）
   - 范围：`CitationRegistry`、`RetrievalResult` presence、mapper/rerank/retrieval identity、`CitationQueryAugmenter`、`CitationValidator`、三个内置检索工具及预算裁剪。
   - 发现/处理：无实质问题；专项 Maven 测试和 `git diff --check` 通过。
2. **第 2 轮：SSE、EOF、重试去重和协议隔离**（`2026-10-14 13:27 CST`）
   - 范围：原生 controller 序号、keyed snapshot replay、终态互斥、WebUI EOF tail/reader lock/sequence 去重、OpenAI 兼容端点扩展隔离。
   - 发现/处理：无实质问题；后端专项测试、`useSSE` `34/34` 与 TypeScript 检查通过。
3. **第 3 轮：测试、文档、发布与回滚**（`2026-10-14 13:31 CST`）
   - 范围：验收矩阵、双语长青文档、smoke 脚本、真实 provider 授权边界、回滚边界、工作区差异和归档路径。
   - 发现/处理：无实质问题；进入最终文档归档和 Git 交付。

### 4.3 交付收尾

- 已在当前 `main` 工作区保留并审阅全部既有改动；未使用 `git reset --hard`、`git stash` 或 `git clean`。
- 本进度账本与规划在长青文档提升完成后移入 `docs/drafts/archive/`；当前提交和推送结果以交付命令及最终 `git status --short --branch` 为准。
