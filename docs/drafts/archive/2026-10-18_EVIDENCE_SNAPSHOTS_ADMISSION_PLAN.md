# 证据版本固定与在线容量隔离实施规划

> 2026-10-18：实施与基本验收完成，归档供设计追溯；结果见同主题进度记录。

## 1. 状态、目标与边界

- 基线：`1e7c2802`；当前工作区专用分支 `feat/evidence-snapshots-admission`，不创建 worktree。
- 用户已授权先规划、review 后实施。生产代码必须等规划连续三轮无修改检查通过。
- 目标：固定本仓数据库检索证据的文档版本与派生身份；让在线请求、模型调用和检索排队有界。
- 非目标：备份恢复（后续独立批次）、跨实例全局并发调度、语义 entailment judge、自动重生成答案。
- 不改现有 URL、必填参数和成功 envelope；新字段可空，旧历史不伪造版本；新安全能力默认启用。
- 无 schema 变化：复用 V65 的 revision、content hash、派生 generation、JSON 来源/历史；不需要运维迁移。

## 2. 已核实的实现上下文

| 入口 | 当前事实与本批修改点 |
|---|---|
| `core/retrieval/HybridRetrieverService` 与 `fulltext/Pg*Provider` | SQL 已联查权威文档及 freshness state；在同一 SQL 中采集证据身份，不能模型完成后补读 revision 冒充检索版本 |
| `RetrievalResultProvenance`、`RetrievalDocumentMapper` | 负责重排结果复制、Document 往返及来源 DTO；新身份必须贯穿这些路径 |
| `RetrievalContextAssemblyPostProcessor`、`RetrievalContextRepository` | neighbor 当前按 seed 文本和当前 generation 扩展；需同时匹配 seed revision，避免相同 anchor 文本跨更新拼接 |
| `ChatExecutionService.toResult` | JSON、stream、durable prepare 共用的最终来源提取处；在此做最终证据检查，避免成功事件先于检查 |
| `RagChatHistoryRepository.reserveDurableContentReferences` | 现有提交栅栏只比较提交窗口读取的 revision；新增检索快照对照，并保留原有 Collection/文档 CAS |
| `ChatExecutionBudget`、`BudgetedChatModel` | 已有 deadline、调用次数、成本预留及 stream cancellation；只增加调用前容量准入，不重写预算结算 |
| `PerformanceConfig.ragSearchExecutor` | 固定 4 worker 但排队无界；改有界 64 队列和明确拒绝 |
| `RagChatController`、`OpenAiCompatibilityController` | 已有 SSE dispose；在 MVC 准入层为 async 完成/超时/错误提供幂等释放 |

上述 `core/` 指 `spring-ai-rag-core/src/main/java/com/springairag/core/`。
API DTO 位于 `spring-ai-rag-api/src/main/java/com/springairag/api/`。
协议参见 [REST API](../../rest-api-zh-CN.md)、[SSE](../../SSE-PROTOCOL.md)；
执行标准参见 [交付工作流](../../delivery-workflow-zh-CN.md)。

## 3. 证据快照设计

新增不可变 `EvidenceSnapshot`，可选挂在 Search `RetrievalResult`、Native `ChatSource` 和
OpenAI `rag.sources` 上。字段：`documentRevision`、`contentHash`、`collectionId`（允许未归属）、
`indexKind`（VECTOR/KEYWORD）、`embeddingProfileId`（关键词为空）、`generation`、
`chunkerVersion`、`evidenceDigest`（最终来源完整文本 UTF-8 SHA-256，不是截断后的 UI 摘要）。

1. Vector 与三个全文 provider 从同一检索 SQL 投影 document/state 身份；不信任任意文档 metadata 中的同名字段。
2. fusion/rerank/copy 和 Spring AI Document 往返保留身份；最终 context 裁剪/扩展后重算 evidenceDigest。
3. neighbor seed 带 revision；权威文档 revision 不再匹配就不跨版本扩展。相同正文也不能掩盖文档更新。
4. 同一请求再次获取同一 document/chunk 的不同快照不能静默替换已注册证据，明确拒绝。
5. 最终结果检查 pinned 来源的 revision/hash/归属/启用状态、Collection 有效性和对应派生 generation。
   静态知识和旧自定义 retriever 没有快照时保持原行为，不声称被版本固定。
6. durable/history 提交在已有事务栅栏内再次检查 pinned 文档身份；跨实例文档 mutation 与提交竞争时，
   成功提交必须对应检索版本。继续只用条件更新/CAS，不引入显式悲观锁。
7. 发现变化默认返回增量错误码 `EVIDENCE_SNAPSHOT_CONFLICT`（409）；不自动重试模型，避免意外费用。
   JSON 不返回成功答案；SSE 返回既有错误事件且没有成功 complete；已发 token 无法撤回，不能当成已提交答案。
   不写成功历史/记忆/重放快照；已经发生的 provider 消耗照常结算。
8. 旧持久化来源没有 snapshot 时读回为空；不后台补写伪造历史。OpenAI 继续不暴露 chunkText/任意 metadata。

## 4. 容量隔离设计

### 4.1 HTTP 在线准入

- 新 `rag.admission` 配置默认启用；MVC interceptor 只匹配 Native Chat/Search 和 OpenAI completion，
  不限制 readiness、认证、状态读取和管理接口。认证后使用 stable principal，不使用 raw key。
- 默认每实例在线请求总量 64、单 principal 16；不等待、不建立额外排队，超限立即 429，
  复用 `TOO_MANY_REQUESTS` 并带 `Retry-After: 1`。这也意味着极端过载时重复 Chat 请求可能被拒绝，Client 可稍后重放。
- permit 同步 afterCompletion 或 async complete/error/timeout 幂等释放；异常初始化不得泄漏。
- HTTP 生命周期结束不保证阻塞 provider 已停止，因此模型层另有独立许可。

### 4.2 模型调用准入

- `BudgetedChatModel` 在预算次数/成本预留之前准入；失败不调用 provider、不产生本次 reservation。
- 在线每实例最多 32 调用、单 principal 8、单 provider 16；按 provider 前缀分组，而非每模型无限开槽。
- Evaluation 使用仅服务端可设置的 background scope；独立最多 2 个模型调用，不占用在线许可。
  标记在构建 execution budget 时捕获，之后线程切换/stream/fallback 沿用同一标记，不能信任 Client metadata。
- 每个副本独立计数；两副本总容量是两份实例容量，不声称跨实例共享。成本硬上限仍由既有 PostgreSQL 预算保证。
- sync 在 provider 真正返回/抛错时释放；stream 在完成、失败、取消及同步订阅异常时释放。
  权限不含高基数 metric 标签；只为固定 workload/kind 记录拒绝与 active 指标。
- 活动 principal/provider map 归零删除，生命周期幂等，不能无限积累用户键。

### 4.3 有界检索排队

`ragSearchExecutor` 保留 4 worker，改为 64 队列、AbortPolicy；拒绝转换成可识别的 429，不能
静默伪装成“无证据”。EACH_COLLECTION 既有独立有界池保持不变。排队策略不改变正常搜索排序。

## 5. 实施切片

1. API snapshot DTO、来源序列化兼容、错误码与 DTO 测试。
2. 权威 SQL、provenance/copy、Document/context/citation 身份传播及单测。
3. 最终快照 validator、事务提交检查、真实 PostgreSQL mutation race 测试。
4. admission 配置/许可服务、MVC 生命周期、模型预算前置准入、Evaluation 后台隔离、有界 executor。
5. 配置、REST API、测试指南双语更新；Client 契约保持增量；增加可重复的黑盒故障验收。
6. 基本硬门槛、最终回归、真实模型、连续实现 3/3、合并 main 和推送并核实工作区。

## 6. 一次性冻结的验收矩阵

| 层次 | 必测场景与通过条件 |
|---|---|
| DTO/metadata | 新旧来源 JSON 读写；旧 OpenAI source 构造器可用；digest 对完整最终文本稳定；无任意 metadata 泄露 |
| 检索 | Vector/English/trgm/Jieba 投影身份；fusion/rerank/Document 往返；neighbor 更新后不得混版；同 chunk 跨版本重复注册拒绝 |
| 证据冲突 | 模型生成期间更新/禁用/删除/迁移；JSON 409、SSE error 无成功终态，历史/记忆无半截提交 |
| PostgreSQL | 真实 V1–V65 建库；两事务/实例交错的文档更新与提交；原有未归属、purge、CAS 场景仍通过 |
| 准入 | 额度边界；单 principal 不能独占；在线/后台/provider 隔离；所有失败和重复释放无漏许可；active map 有界 |
| 预算 | 拒绝发生在 provider 和 reservation 之前；取消继续使用既有 UNKNOWN/消耗结算语义，不假定零费用 |
| HTTP/SDK | Native/OpenAI JSON/SSE 正常响应兼容；429/Retry-After；慢 provider、断连及恢复后下一请求成功 |
| 排队 | worker+queue 有界；拒绝可识别，不变为安全拒答或空检索；旧普通排序/coverage 回归 |
| 真实依赖 | 隔离 PostgreSQL + BGE-M3；Grok/MiniMax 正向事实/引用，QA 充分/不足/ACL；记录真实路由，不把能力 skip 当 pass |
| 运行拓扑 | 只操作本批所有的端口、容器和临时 DB；dev.sh start/readiness/stop；不终止其他项目进程 |
| 全量门禁 | clean compile test-compile、mvn test、gated IT、Client 契约、前端共享契约相关 typecheck/build/Mock Playwright、禁锁及双语文档门禁 |

真实调用先配置单候选、关闭应用重试/judge、固定用例，目标不超过 24 次 Chat、30 次 Embedding；
若遇确定的测试/实现缺陷，先用可控上游复现修复再重跑，额外费用需在进度记录中解释。
不输出密钥，不把 .env、真实 prompt/answer 或业务内容写入跟踪文档。

## 7. 审查、风险与完成定义

- 规划三轮分别核对需求/自包含、代码/并发/兼容、测试/成本/交付；实质修改归零。无问题轮次只在会话输出，保持正文不变。
- 实现基本门禁后同样连续 3/3；进度与失败修复记在独立 `EVIDENCE_SNAPSHOTS_ADMISSION_PROGRESS.md`。
- 主要风险：旧扩展没有 pin、JSON metadata 往返丢字段、同文档多版本、async 重复释放、后台标记跨线程丢失、
  admission 错误被 retry/fallback/安全回答吞掉。以上均进入测试矩阵，不留给实施时决定。
- 紧急关闭 admission 仅作为容量回退；默认启用。快照冲突默认开启，不提供默认 legacy 模式；旧来源兼容为空不伪造承诺。
- 最终汇报区分 Mock、真实 SQL、真实 provider、skip 和未覆盖边界；不能用 review 次数代替测试。
