# 分块自动升级：外部故障后的无人值守恢复规划

> 状态：已实施、全部基本验收及实现连续 3/3 通过，归档；2026-10-14。Git结果以实际仓库状态为准。
> 基线：`main` / `ba978753`，当前工作区干净。本批沿用用户指定的 main 工作区和分支。
> 工作流：[规划、实施与验收](../../delivery-workflow-zh-CN.md)；本批不修改 HTTP 路径或鉴权契约。

## 1. 目标、问题与边界

前三批与 V62 自动升级已交付，本批只收口外部服务长时间不可用后的自动恢复。当前升级每篇
文档/Profile 最多三次尝试，随后 FAILED 被同一 hash/target 的扫描永久排除；provider 恢复或
应用重启不能单独解锁。旧索引仍能服务，但不符合无人值守要求。EmbeddingBatchService 将
异常降为字符串结果，必须先补安全分类，不能把所有失败无限重试。

目标：临时故障耗尽后持久化冷却，时间到自动恢复；拒绝/未知 provider 故障低频恢复；确定性
校验失败终止；跨实例重试共享预算、公平扫描；自动迁移历史任务；保持原子切换与默认开启。
HTTP、ACL、正文、documentRevision、JSON_RECORD 语义不变。

非目标：检索上下文算法、路由授权重构、人工管理 API、供应商金额硬限额、全局 Embedding Job
策略替换、混合新旧二进制滚动发布。诊断沿用 Micrometer 和安全 SQL，不新增 HTTP/权限。

## 2. 已核对的实现入口

| 入口 | 事实 / 本批作用 |
|---|---|
| [ChunkingUpgradeService](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ChunkingUpgradeService.java) | 单 worker、30 秒扫描两篇、120 秒 lease/30 秒 heartbeat；provider 在事务外，短事务 CAS/fence/两索引切换 |
| [V62](../../../spring-ai-rag-core/src/main/resources/db/migration/V62__automatic_chunking_upgrade.sql) | 私有 serving identity；主键 document/Profile；四状态、attempt 1–3 |
| [EmbeddingBatchService](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingBatchService.java) | batch→逐项 fallback；结果保留旧 error；增加内部分类，不改成功/fallback 契约 |
| [RagChunkProperties](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChunkProperties.java) | v3 和自动升级默认开启；新增嵌套恢复配置并 validate |
| [PG 集成测试](../../../spring-ai-rag-core/src/test/java/com/springairag/core/integration/ChunkingUpgradePostgresIntegrationTest.java) | 16 个既有迁移、CAS、lease、回滚、inactive Profile 用例；直接扩展 |
| [Embedding 单测](../../../spring-ai-rag-core/src/test/java/com/springairag/core/retrieval/EmbeddingBatchServiceTest.java) | batch/fallback/熔断/结果兼容；增加分类透传、JSON 不扩张 |
| [配置参考](../../configuration-zh-CN.md#文档分块配置) | 更新恢复语义与边界，英文同步 |
| [门控脚本](../../../scripts/verify-gated-it.sh) | 已聚合升级套件；沿用，不能新增遗漏门控 |

复用已依赖的 Spring AI retry、Spring Web 和 Spring JDBC 异常，无新依赖。并发只用条件 DML、
唯一约束和 lease，禁止显式悲观锁。

## 3. 冻结的错误与恢复契约

### 3.1 分类及兼容

新增 EmbeddingFailureClassifier，有限枚举 NONE/TRANSIENT/REJECTED/UNKNOWN。最多检查 16 层
cause 并防循环；优先具体 HTTP/连接/超时，再识别 Spring AI 类型；不解析 provider message。
408/425/429/5xx、网络连接/超时、TransientAiException、熔断拒绝为 TRANSIENT；其他 4xx 或
NonTransientAiException 为 REJECTED；无法证明的异常为 UNKNOWN。

EmbeddingResult 保留三参数构造、现有 getter/equals/hashCode。增加内部分类，字段/getter
使用 @JsonIgnore，不保存 Throwable；旧构造 error 非空时为 UNKNOWN。batch/fallback 写入
分类，触及的失败日志只输出有限 code，不输出异常 message。缺失向量、维度/顺序错误、NaN
由升级侧判为 PERMANENT；含明确 provider error 的结果先按内部分类处理。

升级侧另有 CONFLICT（目标、snapshot CAS 或 lease 变化）、PERMANENT（ChunkingException /
非法向量）；使用 typed exception 与有限 code，不从 provider message 推断类别。

### 3.2 状态转移

| 情形 | 结果 |
|---|---|
| 新任务 / hash 或 target 变化 | attempt=1、recovery_cycles=0，现有 lease 执行 |
| TRANSIENT/CONFLICT 且 attempt<3 | RETRY，60 秒后继续 |
| TRANSIENT/CONFLICT 且 attempt=3 | COOLDOWN，首次 1 小时，恢复轮次倍增至最多 24 小时 |
| REJECTED/UNKNOWN | 直接 COOLDOWN，最长冷却默认 24 小时，不快速重复调用 |
| 确定性内容/向量校验失败 | FAILED/PERMANENT；同一 hash/target 不重试 |
| COOLDOWN 到期 claim | attempt 重置 1、recovery_cycles +1，hash/target 不变 |
| RUNNING lease 过期 | attempt<3 转 RETRY；否则带退避 COOLDOWN；清 lease |
| 共享重试预算不足 | COOLDOWN 至下一 UTC 小时，安全 code，不调用模型 |
| 成功 | 与索引切换同事务 COMPLETED，清租约和错误分类 |

attempt 是每轮执行尝试次数，包含提交冲突/预算延期，不是供应商 HTTP 次数。冷却轮次不设
生命周期总次数上限，因为 provider 可能任意一天恢复；资源由短期预算、冷却和小时配额限制。
永久校验失败只在正文/目标变化后自动重新评估，保留既有安全边界。

### 3.3 配置、默认与成本边界

新增 rag.chunk.upgrade-recovery：cooldown 默认 PT1H（1 分钟–24 小时）；max-cooldown 默认
PT24H（不小于 cooldown，最多 7 天）；retry-documents-per-hour 默认 12（1–1000）。使用
RAG_CHUNK_UPGRADE_COOLDOWN / MAX_COOLDOWN / RETRY_DOCUMENTS_PER_HOUR 环境变量应急覆盖，
正常发布不要求运维新增配置。沿用 auto-upgrade-enabled=true，不另设默认关闭开关。

共享配额为活动 Profile 每 UTC 小时跨实例最多 12 次重试文档 EmbeddingBatchService 入口，
包括失败尝试；首次升级维持原吞吐，keyword-only 不消耗配额。不是 token/金额/provider HTTP
次数硬上限：批处理、逐项 fallback 和模型内部 retry 仍可能产生多次调用。长青文档必须诚实
说明。关闭既有开关只是暂停，重新开启继续持久恢复进度。

## 4. V63 自动迁移

新增 V63__chunking_upgrade_recovery.sql，不改 V62：扩状态约束加入 COOLDOWN；增加 failure_kind
（NONE/TRANSIENT/REJECTED/UNKNOWN/CONFLICT/PERMANENT）及非负 recovery_cycles，默认 NONE/0，
保留 attempt 1–3。

历史 FAILED 的 CHUNK_UPGRADE_FAILED 无法证明来源：UNKNOWN/COOLDOWN，延后 24 小时；
CHUNK_UPGRADE_LEASE_EXHAUSTED 为 TRANSIENT/COOLDOWN，延后 1 小时。其他历史 FAILED 保留
FAILED/PERMANENT；通用历史 RETRY 也采用 UNKNOWN 冷却。COMPLETED、未过期 RUNNING 不抢占。

新增 rag_chunking_upgrade_retry_buckets（Profile 外键、UTC bucket_start、reserved_count，
复合主键）；无文本/secret/payload。条件 INSERT ON CONFLICT 增计数，成功预留才允许模型
重试；DB 故障 fail closed，已预留额度不退还，防止超发。每扫描最多删除 100 个超过两天的旧
桶，不删当前桶或任务。任务新增非 COMPLETED 的 status/available_at 部分索引。

迁移不修改索引、正文、revision、serving identity；模型调用不在 Flyway 或长事务中。旧
二进制不认识 COOLDOWN，不保证混合滚动；应急暂停用既有开关，不能伪造任务状态回退。

## 5. 调度、事务与诊断

扫描 LEFT JOIN 当前 hash/target/Profile 的任务，排除未到期、永久失败、活跃 Job、disabled/
deleted 文档；按 COALESCE(task.available_at, document.created_at), document.id 排序，每次
仍两篇。身份变更不受旧 task 的时间限制；claim 同时检查状态、到期和 lease。旧 worker 的
提交/失败更新都以 lease_owner + RUNNING fence 保护，不能覆盖新 owner。

claim 后 prepare/validate；已有向量且 attempt>1 或 recovery_cycles>0 时，原子预留小时
配额，再在事务外调用 provider；成功沿用短事务 document CAS 与两索引原子替换。缺额持久
延期并取消 heartbeat；非法向量阻止任何索引切换。lease 超时退避同样依据恢复轮次。

新增 ChunkingUpgradeMetrics：RUNNING/RETRY/COOLDOWN/FAILED gauge、有限 status/failure_kind
transition counter、距最后成功扫描秒数；扫描失败保留 gauge，通过 age 暴露陈旧，不能假零。
固定标签，不包含 ID/hash/owner/text/exception。统计不参与业务决定，观测故障不能破坏已
提交的索引事务。提供安全 SQL 查询示例，不新增权限或管理接口。

## 6. 实施切片与文档

1. V63、RagChunkUpgradeRecoveryProperties、RagChunkProperties、application.yml 与配置单测。
2. EmbeddingFailureClassifier、结果透传和单测，保持 JSON/构造/equals/hashCode 兼容。
3. ChunkingUpgradeService 的冷却、lease、配额、公平与安全 code；保持原子切换。
4. ChunkingUpgradeMetrics 与有限标签单测，扩展已有 PG 套件。
5. configuration/project-context/troubleshooting/testing-guide 双语同步；AGENTS/CLAUDE/index
   仅更新版本/地图。活动规划与进度加入导航，完成后归档。
6. 测试指南给出等效的显式门控命令，不能只有动态聚合脚本引用；在 scripts/gate-registry.mjs
   将已有 verify-chunking-quality.sh 归入 manual 聚合命令（同类均调用 Maven/PG），不是静态
   gate。不改变 checker、不新增豁免；最后一次默认全量 mvn test 后跑静态测试门禁。

## 7. 验收矩阵

| 层次 | 必须证明 |
|---|---|
| 单测 | 配置默认/校验；HTTP/AI/网络/cause 循环分类；结果透传及 JSON 兼容；固定指标标签 |
| 迁移 | 空库到最新版与 V62→V63；历史通用/lease/永久失败分流；旧正文/索引/身份不变 |
| PG 恢复 | 三次临时故障耗尽→冷却内不调用→到期同 hash/target 自动成功；重建 service 模拟重启 |
| PG 错误 | 拒绝/未知低频恢复；非法分块/向量终止；不持久 provider secret；CAS/lease 不覆盖新 owner |
| PG 配额/公平 | 多实例 quota 条件写不超发；缺额延期、下个 UTC 小时恢复；低 ID 不饿死新任务 |
| 原回归 | 既有 16 场景继续通过，包括事务中途回滚、inactive Profile、keyword-only、JSON_RECORD |
| 全量 | clean compile test-compile、mvn test、全部 gated IT、禁锁、文档、密钥、diff gate |
| 前端 | 无公开 HTTP/SSE/DTO 或前端变更，专项 Mock Playwright N/A；仍运行全量 Vitest 与生产构建 |
| dev.sh | 隔离 PG/端口/env 实际启动，readiness、WebUI HTTP/HMR/代理；不碰原服务/.env |
| 真实 Embedding | legacy 建真实旧索引→默认 v3；本机受控 proxy 先 503 后转发真实 provider；推进隔离 task 时间后自动升级；正文/revision 不变，检索命中 |

真实验收只调用 .env 已授权 Embedding，不增加 Chat 费用。proxy 不保存/打印请求头、正文或
secret，配置在内存；临时 root 凭据/env 在 /tmp 且 0600，结束清理。dev.sh/proxy 在同一个
存活 exec 会话启动、调用、停止，避免工具清理子进程误判。不停既有 18081/18084 服务。
压缩冷却只推进隔离测试 DB 时间，不修改发布默认，也不把人工 SQL 当作产品恢复机制。

## 8. 收敛、交付和风险

规划检查于 2026-10-14 Asia/Shanghai 的 19:23、19:24、19:24 连续完成：需求/默认/自包含、
schema/分类/并发/兼容、验收/真实依赖/发布回退。三轮未发现实质问题、无需修改；检查期间
未修改正文。详细轮次输出保留在执行会话，本记录在 3/3 后集中写入。

规划三轮：需求/默认/自包含；schema/分类/并发/兼容；验收/真实依赖/发布回退。实质问题修订
后计数归零，无问题轮次只在会话输出时间/范围/发现/处理/计数。通过后建立 progress。基本
硬门槛全通过后，实现同样完成连续三轮只读审查。

完成定义：冷却到期无需改正文/任务状态即可恢复；永久校验不重试；迁移/并发/原子切换真实
PG 验证；默认 dev.sh 可启动；真实 Embedding 验收；双语同步、归档；只提交本批改动并 push
main，工作区干净，然后停止。

风险：新增少量重试模型费用，由退避/共享入口配额控制，不承诺金额硬限额；最长冷却默认
24 小时，不保证 provider 恢复后立即迁移。历史通用失败可能是永久 shape 错误，首次低频
恢复时重新验证并转永久，这是避免要求运维人工识别历史异常的保守默认。

追加收敛原因：基本验收期间静态门禁发现动态脚本开关不可发现及既有分块聚合命令未注册。
补齐文件级范围和可执行验收入口，重置追加规划计数；恢复契约、schema、默认和成本边界不变。
追加三轮于 20:08、20:08、20:09 连续无修改通过，分别复核默认/需求、注册/schema/API、
验收/启动/回退；未发现其他问题，无需修改。之后才实施注册补齐。

实现基本门槛通过后，于20:25/20:25/20:28连续三轮只读检查恢复/schema/CAS/配额、
分类/JSON/观测/密钥、验收/默认/成本/文档/清理。三轮未发现问题，无需修改，计数3/3。
此记录在三轮结束后集中写入，再归档；未在无问题轮次之间修改正文。验收证据见同目录
[实施账本](2026-10-14_CHUNKING_UPGRADE_RECOVERY_PROGRESS.md)。归档后的冻结确认只在执行会话输出。
