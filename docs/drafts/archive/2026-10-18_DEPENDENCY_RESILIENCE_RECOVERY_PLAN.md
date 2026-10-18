# 模型依赖全链路治理、混合故障验收与备份恢复规划

状态：实施与自动验收完成，归档追溯。规划连续 3/3 已通过；实现只读审查结论记录于交付会话和 Git 提交说明。
基线 `815e82ec`，专用分支 `feat/dependency-resilience-recovery`；稳定事实以正式双语文档和代码为准。
范围：用户批准的三项一起交付；先规划、连续 3/3 检查，再改生产代码。

## 1. 目标与现状

本批服务外部 Client，不以 WebUI 可用替代 API 验收。保持 URL、既有必填参数、成功 envelope、JSON/SSE
契约；容量保护默认生效，新增诊断不暴露凭据、输入或响应正文。不做新的分布式调度平台、Parser SPI、云灾备。

已经具备请求/Chat/后台 Chat 实例准入、数据库费用 reservation、任务 CAS/lease、证据快照、Trace、
Consumer Contract Kit。不能重复实现，也不能把实例容量宣称为跨副本配额。

已核对事实（路径均相对仓库根）：
- `spring-ai-rag-core/.../admission/AdmissionService.java` 只有 request/model/background 三池。
- `config/EmbeddingModelConfig.java` 创建 OpenAI embedding，默认十次重试，尚无统一总时限。
- `config/PerformanceConfig.java` 的缓存包装器是 primary EmbeddingModel；缓存命中不该消耗调用许可。
- `retrieval/HybridRetrieverService.java` 使用有界 executor；CompletableFuture 超时不等于底层 HTTP 停止。
- `retrieval/rerank/HttpRerankProvider.java` 有网络 timeout 与 heuristic fallback，但无独立准入。
- `embeddingjob/EmbeddingJobExecutor.java` 已有 lease、heartbeat、版本提交栅栏，保留这些恢复语义。
- Flyway 当前 V1–V65；V20 的 `fs_files.content_bin BYTEA` 保存上传原件。不是需要另外备份的本地上传目录。
- `scripts/verify-recovery-readiness.sh` 仅检查迁移/治理，不是 backup/restore。

近距离参考：[交付标准](../../delivery-workflow-zh-CN.md)、[测试指南](../../testing-guide-zh-CN.md)、
[配置](../../configuration-zh-CN.md)、[API](../../rest-api-zh-CN.md)。代码路径前缀为
`spring-ai-rag-core/src/main/java/com/springairag/core/`，测试使用对应 `src/test/java`。

## 2. 冻结设计

### 2.1 Embedding 与远程 Rerank

复用 AdmissionService，增加 online embedding=16、background embedding=4、remote rerank=8 三个独立池。
不增加隐式排队：容量不足即时拒绝；embedding 的拒绝进入现有检索分支错误诊断，不能被当成没有证据；
远程 rerank 拒绝/超时按已有配置 heuristic/original fallback，保留证据来源和版本。

增加受管依赖调用执行器与作用域，默认单次在线 embedding/remote rerank 总预算 10 秒、后台 embedding 30 秒；
固定容量线程池且零等待队列。许可从提交前获得，到实际任务结束才释放，而不是调用方超时即释放。
调用方超时/中断停止等待，取消待运行或可中断任务；不声称一定能中断无视 interrupt 的第三方代码。
无视中断的调用仍占许可，避免超时风暴突破真实并发。执行器关闭时有界等待、发出中断。

作用域保存 deadline/取消信息和后台分类；跨检索 executor 显式传播，不用 InheritableThreadLocal。
检索入队时间计入依赖总时限，已超时的排队工作不得再打上游。后台文档执行与 Evaluation 保持独立容量；
后台标记必须进入 HybridRetriever 的异步分支。HTTP 网络 timeout、重试退避均受剩余预算约束。
embedding 默认最多 3 次瞬时故障重试（保留 1–10 配置范围）；只重试暂态/网络故障，不重试永久错误；
重试前检查 deadline/interrupt，退避不超过剩余预算。已有缓存位于治理包装之外，call/batch/document 不能绕过治理。

自定义 EmbeddingModel：primary 缓存入口也经过治理，保留原 bean 与既有构造器兼容。远程 rerank 仅实际
HTTP 受管，heuristic/noop 不消耗上游配额；不为了“默认新特性”把原来关闭的收费 rerank 强制开启。

复用低基数 Micrometer，记录 kind、success/error/timeout/cancelled/rejected，不记录 principal、query、URL/key。
检索沿用已有 branch diagnostics；远程重排降级有指标，不给现有返回 envelope 强加新结构。
无 schema 变化，无迁移。应急关闭使用已有 admission 开关（只关闭容量限制，时限仍生效），超时配置可调。

### 2.2 混合负载、故障与重启恢复验收

优先扩展已有黑盒工具及 k6，不重复建测试框架。受控 provider 支持 embedding/rerank/chat，按端点注入
慢响应、429、503、断连并统计实际 active/peak/calls；只在 loopback 运行，不作为生产组件。

外部 HTTP 同时执行 Search、Native/OpenAI JSON/SSE、文档导入/embedding、主动 Evaluation。
校验成功内容、证据、引用/终态、错误分类、容量上限、取消、最终 active=0、恢复后重新成功，
不能把快速 429 或空结果当成低延迟成功。分别记录成功请求 p95/p99、预期拒绝/非预期错误。
受控路径设有限 wall-clock 上限（测试预算允许启动和 lease 恢复），真实 LLM 不套 500ms 门槛。

重启覆盖正常关闭和 SIGKILL：同一隔离库重启实际服务；等待 lease 到期接管，检查持久任务不会重复提交、
旧 worker 不会越过 revision fence、预算终态不悬挂、重启后请求可用。复用已有 PostgreSQL IT 覆盖不易通过
单一外部接口准确注入的 commit race；只读 DB 断言作为 HTTP 证据补充，不用伪造成功状态。

### 2.3 真正的备份/恢复工具和隔离演练

新增 Python 标准库 CLI，复用本机 PostgreSQL 官方 `pg_dump -Fc` / `pg_restore` / `psql`。
数据库连接只通过 libpq 环境变量，不把密码放 argv、manifest、日志。backup 需要显式目标目录和
敏感数据确认；默认不自动周期性导出业务数据，不扫描或打包 `.env`、models.json、日志、外部 Resource URI。
DB 原件/向量/权限/任务/历史均在同一 PostgreSQL 一致性快照中；服务器配置、模型凭据是外部恢复前置条件，
不是数据备份的一部分。备份包含敏感业务数据，不宣称已脱敏；目录 0700、文件 0600，操作者负责加密和离线保管。

产物为 `database.dump`、version=1 manifest（schema version、SHA-256、大小、UTC 时间）；失败不发布完整 manifest。
不覆盖现有目录，不执行 SQL clean、不包含集群角色/密码。对调用方资源没有写操作。
恢复先验证 manifest、hash、archive，再强制目标为全新空库；禁止源库=目标库、禁止非空目标，禁止 truncate/drop。
用 `--no-owner --no-privileges --exit-on-error --single-transaction`，目标受控角色继承部署权限。
还原前必须暂停 worker/外部写入；恢复旧备份可能复活已删除数据，因此不能直接接到生产：先隔离、核对删除/保留
策略、恢复必要 tombstone 或重放删除，再放流量。工具不声称恢复 RPO=0，不自动清除预算或改写任务终态。

演练脚本只创建自有临时容器与两套隔离数据库：启动原服务/Flyway，通过 API 制造文档、文件、向量、引用、历史、
权限/删除样本，停止写服务后备份；还原到新空库，启动同一构建，校验哈希、Flyway、原件、索引/快照、权限隔离、
已删除样本、外部 API JSON/SSE。对损坏包、非空库、源目标相同做负例，失败必须无破坏。临时产物不提交。
服务重启可接管旧 lease 是任务恢复，不等于可以在没有删除审查的生产库随意恢复旧备份。

## 3. 文件级实施切片

1. `core/admission/*`：依赖作用域/执行器、三池配置和指标；`PerformanceConfig` 接入包装；测试并发、取消、超时。
2. `EmbeddingModelConfig`：有界 transport/retry；`HybridRetrieverService` 传播；`EmbeddingJobExecutor` 后台分类；
   `RerankProviderFactory` / `HttpRerankProvider` 受管 HTTP 与降级测试。
3. `scripts/rag-backup.py`、备份单元/黑盒负例；双语恢复操作说明；`scripts/verify-dependency-recovery-e2e.py` 合并隔离演练。
4. 受控 provider/故障负载脚本、k6 可选 reliability profile；串联原有契约和 PostgreSQL 恢复门禁。
5. 全面验收、正式配置/测试/开发者/发布文档双语更新、归档、实现 3/3、提交合回 main 并 push。

## 4. 验收矩阵与完成定义

| 层 | 必须证明 |
|---|---|
| 单元 | 三池隔离/许可恰好一次释放；真正结束前不释放；deadline/interrupt/retry；缓存不扣许可；rerank fallback/provenance |
| PostgreSQL | 全 gated IT；Flyway 从空库到 V65；lease/CAS、预算 reservation/finalize、删除与隔离 |
| HTTP | API compatibility/Consumer Kit/live OpenAPI；Native/OpenAI JSON/SSE；官方 Python/Node SDK 双拓扑 |
| 故障/混载 | 慢/429/503/断连/取消；Search+Chat+导入+QA；强杀与正常重启；实际资源上限与恢复 |
| 真实依赖 | BGE-M3 导入/检索；Grok/MiniMax 的事实/不足/边界/JSON/SSE；有界且记录真实失败，不循环到侥幸通过 |
| 恢复 | 真实 dump/restore 到全新库；文件/向量/历史/权限/删除/快照/API；损坏/非空/同库拒绝 |
| 前端 | typecheck、Vitest、build、核心 Mock Playwright；共享 API 有影响，不能标 N/A |
| 运行 | `scripts/dev.sh` 实际 start/readiness/代理/stop；不按端口盲杀其他项目 |
| 全局 | clean compile test-compile、mvn test、project-tests、无悲观锁、文档/diff/隐私门禁 |

所有检查必须实际执行，保留 `.verification/` 或 `/tmp` 的脱敏汇总；测试数据、密钥、原始备份不提交。
真实测试使用 `.env` 已授权服务；受控故障与真实模型分库，防止 Evaluation worker 抢任务。
测试费用以有界样例为原则，不关闭数据库 hard limit。第三方服务失败如实分类，不宣称零风险。

## 5. 检查、恢复与风险

规划和实现各连续三轮无实质修改；问题立即修复、计数归零，无问题轮只在会话输出。
进度与证据维护在同目录 `2026-10-18_DEPENDENCY_RESILIENCE_RECOVERY_PROGRESS.md`，不把聊天记录当唯一上下文。
无迁移；回退构建不改数据。备份工具是主动运维能力，默认不触发付费或导出，新增运行保护默认启用。
最大风险是 interrupt 不受控、跨 executor 分类丢失、恢复旧隐私数据；分别用持有许可、显式作用域传播、
隔离恢复+删除审查封边，不为排除风险而重写现有任务系统。
