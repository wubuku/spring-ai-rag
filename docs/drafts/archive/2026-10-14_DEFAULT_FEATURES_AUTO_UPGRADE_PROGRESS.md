# 前三批默认启用与自动升级进度

> 基线`f27a1d35`，2026-10-14，main。规划：[实施规划](2026-10-14_DEFAULT_FEATURES_AUTO_UPGRADE_PLAN.md)。

- 已检查第一批处理/housekeeping、第二批引用/SSE/幂等默认值及兼容边界，无需额外开关。
- 已确认第三批默认legacy以及手工重索引缺口；仅翻转开关将破坏旧索引可见性。
- 已探索分块、身份、SQL scope、完整性、缓存、CAS/lease和provider调用事务边界，冻结过渡真实身份+后台原子升级方案。
- 规划检查中修正了活动Profile再次启用时漏迁向量、任务Profile隔离和每实例并发措辞，计数重置。
- 规划3/3：2026-10-14 17:30需求/兼容、17:31事务/迁移、17:31验收/回滚，连续无修改通过。
- 已实现V62、有效身份查询、默认v3与后台原子升级；恢复先核对git status，不丢弃WIP。
- 实施交叉核对发现SKIP同时表示不生成关键词，已纠正规划相关一句并实现保留NOT_REQUESTED；新策略已完成初次编译。实现收敛检查尚未开始。
- 首次全量回归发现无数据库OpenAPI上下文被启动登记异常阻断，已改为安全code记录并允许后台恢复，增加单测；默认值变化的SQL/lifecycle夹具同步更新，不降低断言。
- V61旧Job没有索引时的身份回填已补齐；新增租约过期恢复/耗尽、关键词切换后事务失败回滚、永久预算错误目标变化后恢复、JSON_RECORD排除测试，迁移套件15/15及启动单测1/1通过。
- 交叉核对补齐集合Embedding readiness的有效身份，并新增inactive Profile激活迁移测试；首次全量PG门禁暴露3组硬编码旧默认夹具，已同步新默认，正在重新验收。
- 干净编译与全量后端首次修复后通过；WebUI全量87文件/1030用例、SSE专项34/34、构建通过；文档16/16、禁锁与脚本语法通过。最终证据以之后全量复验为准。

## 最终交付范围

- 第一批Processing Run/Stage与housekeeping已默认启用；第二批Citation Registry、SSE sequence/decoder、引用校验与Chat幂等已默认启用，保留旧JSON/无sequence兼容。本批没有批量开启这些批次以外的purge、通知、OpenAI入口等能力。
- 第三批默认改为bounded-v3。V62只迁移schema与真实过渡身份；后台在模型返回并验证后，以短事务原子切换关键词、活动Profile向量与任务状态。JSON_RECORD仍单块，HTTP路径、鉴权/Collection ACL及documentRevision语义不变。
- 超预算等永久失败及重试耗尽会保留真实旧身份和旧结果，不伪装为v3；正文/目标变化可重新尝试。已有向量升级有模型费用，关键词-only/SKIP/NOT_REQUESTED不新增模型调用；不保证混合旧/新二进制滚动发布。
- 有效身份覆盖descriptor/prepare、关键词与向量SQL scope、空检索诊断、完整性、集合readiness、缓存、落库和旧Job。显式legacy-v2只作应急回退，后台同样自动重建，不要求正常运维手工重索引。

## 固定验收证据

时间均为2026-10-14，Asia/Shanghai；日志为本机临时证据，不含提交的凭据。

| 门槛 | 结果 | 结束时间 / 证据 |
|------|------|----------------|
| `mvn clean compile test-compile` | 所有模块成功 | 18:24:38；`/tmp/rag-defaults-clean-final2.log` |
| `mvn test` | 总数8469；非门控8282通过，失败0、错误0；187个PG门控用例单独运行 | 18:27:19；`/tmp/rag-defaults-full-final.log` |
| `TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16 ./scripts/verify-gated-it.sh` | 全部24套件、187/187通过，无跳过 | 18:32:04；`/tmp/rag-defaults-gated-final.log` |
| 新升级专项 | PG16/16 + 启动恢复单测1/1通过，最终全量再次覆盖 | `/tmp/rag-defaults-upgrade-final.log`及上述全量日志 |
| WebUI全量 / SSE专项 | 87文件、1030/1030；SSE34/34 | `/tmp/rag-defaults-web-full.log`、`/tmp/rag-defaults-sse.log` |
| WebUI TypeScript与构建 | `npm run build`通过，包含`tsc -b` | `/tmp/rag-defaults-web-build.log` |
| 文档、禁锁、脚本、diff | 文档16/16、禁锁通过；`bash -n`与`git diff --check`通过 | `/tmp/rag-defaults-docs-review.log` |

PG专项覆盖V61→V62旧数据可检索、关键词/向量原子切换、失败保留、三次重试上限、租约过期恢复/耗尽、并发只调用一次provider、正文CAS、disabled/活跃Job、事务中途失败回滚、预算变化后恢复、inactive Profile激活、JSON_RECORD排除、旧Job快照和私有字段JSON隔离。第一次全量PG门禁失败的三组旧默认夹具已纠正，最终门禁不删除、跳过或放宽这些断言。

## `dev.sh` 与真实模型验收

使用`DEV_ENV_FILE`隔离配置：source本仓`.env`，仅覆盖新建PostgreSQL端口55496、数据库`rag_defaults_20261014`和临时root凭据；backend18082、WebUI15173。测试期间没有修改`.env`、没有启动开关要求留给正常运维，也没有停止原有18081/18084服务。

1. 造旧数据阶段显式legacy-v2且关闭自动升级，用`./scripts/dev.sh`实际启动；通过后端readiness、WebUI页面/HMR、代理root身份与管理写入检查。
2. API创建隔离Collection与SKIP文档，再显式embed构建真实BGE-M3向量。旧preview为legacy-v2、3块；旧检索命中3块。
3. 停止本次开发栈，撤掉两个chunk覆盖值，再用`./scripts/dev.sh`按新默认启动。Flyway版本62，后台任务自行COMPLETED，仅1次尝试；私有过渡身份清空，两索引均为真实bounded-v3派生物，检索命中3块。
4. 真实MiniMax-M3（`minimax/MiniMax-M3`）回答命中唯一验收码；3个sources均有`sourceHandle`与`citationId`。正文、documentRevision均与升级前相同，私有字段没有出现在HTTP JSON中。
5. 默认栈readiness为UP、WebUI为HTTP200。关闭本次开发栈，删除自建容器/数据库及临时root凭据与env文件；原有服务保持运行。

证据：`/tmp/rag-defaults-dev-legacy-start2.log`、`/tmp/rag-defaults-dev-default-start.log`、`/tmp/rag-defaults-real-seed2.log`、`/tmp/rag-defaults-real-upgrade.log`。执行工具会在调用结束时清理子进程，真实验收因此在同一存活执行会话内完成；不是应用启动崩溃，不需要为工具行为修改dev.sh。

## 收敛检查

- 规划初次3/3：17:30、17:31、17:31；见上文。增加实际dev.sh验收要求后，18:15默认/兼容、18:16迁移/事务、18:17验收/回退再次连续3/3，规划正文期间没有修改。
- 基本门槛全部通过后，实现连续3/3：18:33默认/HTTP与SQL有效身份；18:35迁移、provider边界、CAS/lease、回滚与恢复；18:36全量证据、真实启动、双语文档和资源清理。三轮均未发现问题，处理措施均为无需修改，计数达到3。
- 记录集中写入并归档，避免在无问题轮次之间改文档；冻结后的只读确认与Git交付状态继续在执行会话输出，不再追加文档循环。
- Git交付目标是同一main工作区、单个聚焦提交、推送origin/main并确认工作区干净；不执行reset/stash/clean，不提交密钥或本地状态。
