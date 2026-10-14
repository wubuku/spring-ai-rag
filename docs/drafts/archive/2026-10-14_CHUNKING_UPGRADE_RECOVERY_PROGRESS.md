# 分块自动升级无人值守恢复：实施进度

> 2026-10-14；main 工作区；基线 ba978753。
> [自包含实施规划](2026-10-14_CHUNKING_UPGRADE_RECOVERY_PLAN.md)；规划已连续 3/3，通过后开始代码修改。

## 切片

| 切片 | 状态 | 恢复入口 |
|---|---|---|
| V63、恢复配置 | 已实现 | migration、RagChunkUpgradeRecoveryProperties、application.yml |
| provider 安全分类 | 已实现 | EmbeddingFailureClassifier、EmbeddingBatchService |
| 自动恢复与共享预算 | 已实现 | ChunkingUpgradeService |
| 低基数诊断与 PG 覆盖 | 已实现并专项通过 | ChunkingUpgradeMetrics、现有 PG 升级套件 |
| 全量/迁移/启动/真实模型验收 | 全部通过 | 规划 §7及下方证据 |
| 实现只读 3/3、归档 | 已完成 | 工作流 §7–8及下方收敛记录 |
| Git 交付 | main提交/推送，实际结果以仓库和交付会话为准 | 不预填未执行的Git结果 |

## 冻结决策

默认恢复开启；临时故障三次后 1h→24h 冷却，未知/拒绝默认 24h；确定性内容/形状错误终止。
重试批处理按 Profile 跨实例共用每 UTC 小时 12 次配额，不宣称 token/金额硬限额。旧索引原子
切换和 serving identity 保留，外部 HTTP 不变。本批不处理其余检索/授权架构。

## 验证和检查

规划期间文档门禁 16/16 通过。规划 3/3 时间和范围见 plan §8；实现验收尚未完成，不提前
宣称成功。不打印 .env；不操作他人服务、数据库或 WIP；禁止 reset/stash/clean。

19:42 专项单测 53/53、19:44 PostgreSQL 升级/恢复套件 31/31，无失败/错误/跳过；日志为
`/tmp/rag-recovery-unit.log`、`/tmp/rag-recovery-upgrade.log`。包括 V62 历史失败分流、重启后
同一正文恢复、跨实例配额/下小时继续、永久校验、拒绝/未知分类与回滚。最初编译发现测试
helper 尚未补齐及 MeterRegistry 非 AutoCloseable，已在运行专项前补齐/修正；未绕过断言。
双语长青文档与 V63 导航已同步，文档门禁 16/16；全量与真实启动尚待完成。

19:55 全量 PG 门禁 25 套件、202/202 通过；clean 编译全部模块通过；两次全量 mvn test 均
通过（总8500，默认执行8303，197个门控报告跳过，真实门控202项独立通过）。WebUI 87文件、
1030/1030与生产构建通过。静态测试门禁发现动态聚合脚本没有被开关发现器识别，已在双语
测试指南补充真实可运行的显式 `-Dchunking-upgrade.it.enabled=true` 命令；不修改或豁免门禁。
按指南顺序，最后一次 mvn test 后执行静态门禁，避免 PG 运行覆盖 report 导致错误证据。

后续 gate-wiring 自测发现既有 verify-chunking-quality.sh 未注册；它包含 Maven/PG，是专项
聚合命令，应按相邻脚本登记 manual，不能把 checker 静默放宽。追加 plan 的文件范围与
验收入口后，追加规划计数归零并重新三轮；核心恢复实现和已通过的测试不改动。
追加规划在20:08/20:08/20:09连续3/3通过；按已有规则登记manual聚合命令，不改变checker。

## 固定验收证据

时间为2026-10-14 Asia/Shanghai。本机/tmp日志不是架构真相源；实施和正式契约以代码、迁移、
自动化测试及长青文档为准。

| 硬门槛 | 结果 | 证据 |
|---|---|---|
| clean compile test-compile | 全部模块成功，19:46 | /tmp/rag-recovery-clean.log |
| 全量 mvn test | 两次通过，最后20:00；默认执行8303、失败/错误0 | /tmp/rag-recovery-full-final.log |
| 全部 PG 门禁 | 25套件、202/202，无跳过，19:55 | /tmp/rag-recovery-gated.log |
| 新恢复 PG 专项 | 31/31，无跳过，最终全量再次覆盖 | /tmp/rag-recovery-upgrade.log |
| 聚焦单测 | 53/53，无跳过，最终全量再次覆盖 | /tmp/rag-recovery-unit.log |
| WebUI | 87文件、1030/1030；TypeScript/生产构建通过 | /tmp/rag-recovery-web.log、/tmp/rag-recovery-web-build.log |
| 静态测试链 | 57/57；最后默认全量测试之后执行 | /tmp/rag-recovery-test-gates.log |
| 文档/禁锁/差异/密钥 | 文档16/16；禁锁、diff、配置secret值扫描通过，归档后再确认 | /tmp/rag-recovery-docs-final.log及会话 |
| dev.sh与真实Embedding | legacy→默认v3、受控503→冷却→自动恢复；两次启动和WebUI/代理/HMR校验通过 | /tmp/rag-recovery-real.log、/tmp/rag-recovery-dev-*-start.log |

默认运行报告总8500，方法级门控skip197；PG参数化展开独立执行202项。不能用197代替实际
PG执行数。当前构建默认执行8303，加实际门控202，共8505项已执行测试断言入口。

真实验收使用隔离容器 rag-chunk-recovery-20261014-pg、PG端口55497、后端18082、WebUI15173、
本机受控proxy19087。先按legacy创建真实BGE-M3向量，再撤除chunk配置覆盖值按发布默认重启。
受控HTTP503使同一文档三次尝试后进入TRANSIENT/COOLDOWN；冷却内不继续，旧索引完整保留。
恢复proxy转发，并只推进隔离DB的available_at模拟时间流逝，不改变status/hash/target；后台
自行完成一次恢复轮次，关键词/向量均为bounded-v3，私有身份清空，检索命中唯一验收码。
正文、contentHash、documentRevision不变。真实provider成功3次，受控503为24个HTTP请求，
实际upstream失败0；这也验证批处理入口次数不等于供应商HTTP次数，不能伪称金额硬限额。

首次真实验收因临时proxy只读Content-Length、不支持Spring的chunked请求而向上游发送空正文，
得到400。直接调用真实provider确认1024维成功，补齐测试proxy的有界chunked解码后全链通过；
生产代码没有为迁就夹具作修改。临时root/db凭据与env、flags、proxy进程、开发栈和测试容器
均按所有权清理；未修改.env，未停止其他服务。代理不保存/打印请求正文或授权头。

## 收敛入口

基本硬门槛已完成，接下来冻结实现，连续三轮只读检查：恢复/schema/CAS；分类/兼容/安全；
验收/默认/成本/文档/资源清理。此时计数0/3，不提前宣称收敛成功；每轮总结在执行会话输出，
无问题轮次不修改正文。收敛后集中记录、归档，再冻结确认与Git交付。

### 实现收敛完成

20:25、20:25、20:28连续三轮分别检查恢复/schema/CAS/配额、分类/JSON/观测/密钥、
验收/默认/成本/文档/清理；三轮均未发现问题、处理为无需修改，计数达到3/3。期间没有修改
实现或文档。此总结集中记录后归档，随后只读冻结确认，不在归档文档中循环追加日志。
双语稳定契约已提炼至配置/排障/测试指南和project-context；Git交付仍需实际commit/push，
不能用本账本代替执行证据。未改.env，不提交临时proxy/env、凭据或本地运行状态。
