# 后续改进 TODO

> 📖 [English](TODO.md) · [中文](TODO-zh-CN.md)
>
> 最后复核：2026-10-20。本文只记录当前代码和正式 API 之外的后续事项，不代表
> 已发布的 API 能力。

## 已交付的接入门禁

- [x] `business-client-binding-preflight.sh` 提供无需 root、默认只读的已部署实例检查，
  覆盖 readiness、OpenAPI、principal policy、Collection allow-list 精确相等和 Collection
  active 状态。
- [x] 显式启用的 canary 模式验证 ASYNC 持久化、精确重放、就绪、payload 检索、CAS 冲突、
  tombstone、恢复和最终 tombstone，并在失败时执行有界清理。
- [x] readiness 合同把 preflight 作为黑盒 client 执行，并验证只读成功、allow-list
  不匹配、Bearer canary 成功和 provider 失败清理场景的 secret-safe 报告。
- [x] preflight 精确验证 `READ_ONLY` / `READ_WRITE` credential 画像；真实 HTTP 合同拆分
  只读 query 与读写 dispatcher，并验证写拒绝后状态不变。
- [x] capability preflight 可要求最低运行时 JSON batch 上限与 operation observability；
  V54 增加按 principal/ACL 隔离的 best-effort 小时聚合、低基数指标与脱敏证据。
- [x] V55 为调用方滚动部署提供有界 staged credential rotation，包括不重放 secret 的
  幂等 prepare、complete/cancel/deadline 收敛、family revoke 与能力发现；即时轮换继续
  作为明确的兼容路径。

## 受管 API Principal 后续边界

| 项目 | 优先级 | 当前状态 |
|---|---|---|
| 稳定 principal、versioned credential、即时吊销 | V48 已交付 | owner/policy 与 credential 分离；每次认证权威联查，无正向 decision cache |
| PostgreSQL 共享 principal quota | V48 已交付 | 多实例按 stable principal 共用固定分钟 bucket，rotation 不重置 quota，DB 故障 fail closed |
| 明文 secret schema 禁写 | V48 已交付 | 迁移清空明文、移除索引并约束 `api_key IS NULL` |
| operation-scoped `RAG_READ` / `RAG_WRITE` 强制授权 | V49 已交付 | 中央 filter 在认证后、限流前按 operation 强制能力；只读 principal 可 Search/Chat，写请求 `403` |
| principal provisioning 幂等键 | V50 已交付 | 可选 keyed create 支持不返回一次性 secret 的精确重放；语义复用冲突和账本故障均 fail closed |
| Collection 创建幂等键 | V52 已交付 | 可选、按 owner 隔离的 keyed 创建支持跨实例/重启 replay 当前 Collection 状态；语义复用和账本故障均 fail closed |
| machine-readable 集成协议版本 / 能力发现 | V50 交付，V54 补全 | 认证、no-store endpoint 投影协议、调用方 policy、数据面行为、structured-record/Sync Run/observability 上限和可选特性 |
| 按 principal/Collection 的集成 operation 可观测性 | V54 已交付 | 有界 fail-open 小时 rollup 支持 self/当前 ACL 定位与 root/ADMIN 管理查询，不使用高基数 metric 标签 |
| 有界 staged credential rotation | V55 已交付 | 一个 current 与至多一个有 deadline 的 retiring credential 共享 stable principal 和 quota；prepare 幂等但不重放一次性 secret，complete/cancel/expiry/revoke 最终收敛到一个或零个 active credential |
| 受管 principal 到期预警 | V57 已交付 | after-commit Spring Event 准实时对账，默认每小时公平扫描兜底；PostgreSQL/CAS 收敛唯一 active 告警、阶段升级、通知 claim 与延期/吊销解决 |
| OAuth/OIDC 与独立租户层级 | 后续独立规划 | 当前仍是 environment root + 受管业务 principal，不提供第三方身份 federation |
| 模型 invocation 级 token/cost 持久用量账本 | V53 已交付 | `BudgetedChatModel` 为 Chat、query transform/expand、summary、fallback、应用 retry 和 AGENT 轮次记录有界的 principal/session/trace 归因；`GET /api/v1/rag/usage` 提供 UTC 聚合用量和配置成本估算 |
| token/cost hard limit、billing 与结算 | 后续独立规划 | 需要预授权、预留、结算、跨实例超额保护和崩溃恢复，不能直接建立在 best-effort 观测账本上 |
| 管理面 recovery 与彻底关闭 legacy 兼容 | 公网启用前评估 | legacy static/query 行为仍为兼容边界；operator recovery 依赖 environment root |
| 告警通知 durable outbox / 供应商投递回执 | V58 已交付 | 告警与 delivery 同事务入队；after-commit Event 准实时唤醒，默认一分钟 Scheduled 兜底；lease/CAS、有界退避、稳定 UUID、低敏 operator receipt 与人工 retry 提供跨实例/重启的 at-least-once 投递 |

V48 只对迁移时存在的每个历史 credential 做一对一 deterministic principal 回填；它不会
猜测旧 rotation rows 之间无法证明的 family 关系。模型 invocation 用量与配置成本可观测性
已由 V53 作为 fail-open 的 append-only 账本实现，不会被宣称为供应商账单或 hard-limit
结算源。OAuth/OIDC、租户层级、Redis、token/cost hard limit/billing 仍属独立后续事项；多
Embedding Profile 路由纳入[当前活跃批次规划](drafts/NEXT_BATCH_ASYNC_EMBEDDING_CLIENT_QUALITY_PLAN.md)。

## 文档生命周期与派生索引后续项

| 项目 | 优先级 | 当前缺口 |
|------|--------|----------|
| 权威来源全量快照对账 | 本批已交付 | V42 API 和 reference client 支持有界权威 run、preview fingerprint、删除保护和 reconciliation tombstone |
| Sync Run 持久化 item receipt | V51 已交付 | `RAG_READ` 查询支持状态过滤、有界 cursor、当前 ledger 摘要和脱敏错误；终态遍历稳定，active run 需最终一致去重并在终态后复扫 |
| 外部文档原子 Collection 迁移 | 本批已交付 | V44 提供双 ACL、幂等精确重放、Sync Run fencing 和永久 retired-address guard；默认由 feature flag 关闭 |
| Collection 派生索引完整性诊断与受控修复 | 本批已交付 | V45 提供共享物理 freshness、集合级有界诊断和最多 100 项的 durable preview/apply/status；有副作用的 repair 默认关闭 |
| 历史版本受控恢复 | 本批已交付 | 开启 feature flag 后，本地 `FULL` 快照可恢复为新 revision；外部文档仍由来源系统负责 |
| 本地 chunk/full-text 与远程向量解耦 | 本批已交付 | V43 保存与 Profile 无关的本地 chunk/state；provider 故障时当前正文仍以 `KEYWORD_ONLY` 可用，旧 generation 继续被排除 |
| 外部托管文档受保护 purge 与 Collection 退役 | V56 已交付 | 默认关闭的 preview/apply 使用 root/ADMIN 权限、token/fingerprint、Collection/Chat fence、完整引用索引和同步上限；保留永久 key tombstone，独立文件不按路径猜测删除 |

### 当前边界

- 外部同步支持 webhook/CDC 增量投递和权威 Sync Run。一次批次不完整时不得按
  missing 推断删除；只有显式声明的安全快照模式才能启用 tombstone。
- 当前外部地址仍是 `collectionKey + sourceNamespace + externalId`。在独立 tenant/
  connector 授权边界出现前，不把唯一约束降为全局 `sourceNamespace + externalId`；
  跨 Collection 移动使用同时校验源/目标 ACL 的显式 relocation，不能用普通 upsert 模拟。
- 版本恢复不能直接回退计数器或覆盖历史；当前操作会创建新 revision、新完整快照，并复用
  现有 mutation impact、持久化 job 和 commit fencing。
- 只有 `snapshotCompleteness=FULL` 的历史版本才具备完整恢复资格；旧兼容快照只用于审计。
- V43 本地全文派生保持“旧正文立即退出”，允许新正文先进入
  `KEYWORD_ONLY`，远程 embedding 成功后再将 lifecycle 提升为 `READY`。
- 所有并发协调继续使用条件 DML/CAS、唯一约束、lease 和有界重试，禁止显式悲观锁。
- 派生 repair 只重建/排队派生，不改正文；批次最多 100 项，明文 token 不落库，且不会
  在 HTTP 请求中同步循环调用 embedding provider。
- Collection purge 是整座知识库的有界同步运维能力，不是普通文档 retention 或对象存储
  清理。本批将为超过同步安全上限的请求增加持久化、可恢复、幂等的异步清理路径；同步
  路径仍保持原有结果和错误语义，绝不截断删除计划。

剩余实施范围和批次顺序以
[当前活跃规划](drafts/README-zh-CN.md) 为准；已发布契约仍以
[REST API](rest-api-zh-CN.md) 和
[外部文档同步 Client 指南](external-document-sync-client-guide-zh-CN.md) 为准。

## `EACH_COLLECTION` 召回覆盖模式（已交付）

显式请求 `collectionCoverageMode=EACH_COLLECTION` 时，服务在有界资源预算内为每个授权且有
检索命中的 selected Collection 提供结果机会；无相关候选的 Collection 可以返回零条，不会
为了覆盖而填充低质量结果。该模式仅适用于 `KNOWLEDGE + SELECTED_COLLECTIONS`，最多 20 个
Collection，最终结果最多 100 条。省略字段或使用 `GLOBAL` 时，旧全局 top-k 默认语义保持不变。

Search 通过可选 `X-RAG-Collection-Coverage` 返回覆盖摘要，Chat/OpenAI 通过增量 metadata
返回覆盖与降级信息。真实外部 API、JSON/SSE、SDK、PostgreSQL 与检索质量验收见
[已归档实施记录](drafts/archive/2026-10-17_NEXT_BATCH_COLLECTION_COVERAGE_PROGRESS-zh-CN.md)。
完整当前契约见 [REST API：Collection 检索范围语义](rest-api-zh-CN.md#collection-检索范围语义)
和 [项目上下文：当前 Collection 语义](project-context-zh-CN.md#collection-当前语义)。
