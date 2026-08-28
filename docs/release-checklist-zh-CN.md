# 1.0 发布清单

> 📖 [English](release-checklist.md) · 📖 [中文](release-checklist-zh-CN.md)
>
> 单项功能在进入本发布清单前，先按[规划、实施与验收工作流](delivery-workflow-zh-CN.md)
> 完成规划、基本集成门槛和连续收敛检查。

候选版本：`1.0.0`
发布日期：`2026-07-21`

## 元数据与产物

- [x] 根模块、子模块与独立 Demo 的 Maven 版本均为 `1.0.0`
- [x] OpenAPI 版本为 `1.0.0`
- [x] Helm `version` 与 `appVersion` 均为 `1.0.0`
- [x] Docker/Helm 默认镜像 tag 为 `1.0.0`
- [x] 本地、Docker 与 Helm 默认端口均为 `8081`
- [x] Flyway 迁移范围为 V1-V58
- [x] JSONB 结构化记录 API、payload 快照和 Collection 生命周期已覆盖
- [x] `scripts/verify-jsonb-records.sh` 固化后端/数据库/前端聚焦验证
- [x] 文档 PATCH/禁用/恢复/永久删除与外部三元身份已覆盖
- [x] `scripts/verify-document-lifecycle.sh` 固化 CRUD、派生索引、client 与 WebUI 验证
- [x] OpenAI 兼容 base URL 末尾不带 `/v1`
- [x] 中英文发布说明已补齐

## 产品门禁

- [x] 生产 query rewrite 与 heuristic rerank 默认开启
- [x] Goldenset 脚本输出 baseline/quality 的 Precision@K、MRR、nDCG
- [x] API Key collection ACL 已持久化并覆盖全部数据面
- [x] Chat 与 Settings 支持运行时选模
- [x] 显式未知或不可用模型返回 HTTP 400
- [x] 外部 `models.json` 加载成功后完整覆盖 YAML

## 验证门禁

- [x] `mvn clean test`
- [x] `mvn package -pl spring-ai-rag-core -am -Pwebui -DskipTests`
- [x] WebUI lint、全量 Vitest 与生产构建
- [x] 全量 Playwright
- [x] Helm lint/template 与 Docker 镜像构建
- [x] PostgreSQL profile 服务启动与 `scripts/e2e-test.sh`
- [x] Retrieval goldenset
- [x] 版本化真实检索回归可通过 `--with-quality-regression` 或 `--with-local-runtime` 执行
- [x] 本机 `.env` 凭据可用时执行真实 LLM 冒烟
- [x] 密钥扫描与 `git diff --check`
- [x] 连续三轮无改动收敛检查

### 2026-08-17 增量门禁

- [x] OpenAI 兼容专项脚本覆盖 alias、scope/ACL、JSON/SSE 与错误信封
- [x] Embedding jobs 专项脚本覆盖 V33、coalesce、lease 与原子条件 claim
- [x] `verify-no-pessimistic-locks.sh` 阻止显式悲观锁、`SKIP LOCKED` 与 advisory lock 回归
- [x] JSONB 专项脚本覆盖 `payloadContains` 与 V34 GIN planner
- [x] 真实检索数据集与 baseline 已提交，质量门禁对外部依赖失败返回非零

### 2026-08-19 文档生命周期门禁

- [x] V40/V41 增加业务 revision、完整快照、source namespace 和 generation fencing
- [x] V42 增加权威外部快照对账 run 和删除来源标记
- [x] V43 将与 Profile 无关的本地关键词 chunk 与远程向量状态分离
- [x] 正文变化使旧派生结果立即 stale；metadata/payload/Collection-only 更新不重嵌入
- [x] 外部 reference client 与中英文最佳实践已提交
- [x] PostgreSQL 生命周期验收显式要求 `skipped=0`

### 2026-08-21 迁移与派生完整性门禁

- [x] V44 提供跨 Collection 原子迁移、双 Collection ACL、精确幂等重放和永久旧地址保护
- [x] V45 提供统一的严格派生 freshness、分页/聚合诊断和 preview-first 受控修复账本
- [x] `verify-document-relocation.sh` 与 `verify-derivation-integrity.sh` 固化后端、数据库、
  前端 Mock 和文档门禁，PostgreSQL 验收显式要求 `skipped=0`

### 2026-08-22 Chat Turn 可靠性门禁

- [x] V47 提供按 principal 隔离的 Chat turn 幂等、不可变重放快照、有界 lease/接管、
  状态查询和 history turn identity。
- [x] 原生 JSON/SSE 与 OpenAI 兼容 JSON/SSE 共用持久化 operation 边界；keyed replay
  不会再次调用 provider。
- [x] Chat 能力门禁已记录 V47 PostgreSQL 矩阵以及 Mock Playwright 的重试、重放和 stop 证据。

### 2026-08-25 业务服务接入就绪门禁

- [x] `/api/v1/rag/auth/me` 显式返回 principal role、restricted/unrestricted 模式和当前
  credential 自身的稳定 Collection key allow-list，无法完整解析时 fail closed。
- [x] `verify-business-client-readiness.sh` 覆盖 PostgreSQL 集成矩阵、Maven/WebUI 门槛、
  真实 Spring Boot、包含已部署 binding preflight 的 160 项业务 credential HTTP 合同和
  真实 API Key Playwright。
- [x] 合同覆盖外部身份/revision 边界、双向全数据面 `403` 防枚举，以及 provider
  `503` 后 Record identity/revision/payload/enabled/documentRevision 保留。
- [x] clean-tree 最终门禁生成 `release-manifest.json`，锁定完整 Git SHA、项目/OpenAPI
  `1.0.0`、API base path、与仓库最新 migration 一致的运行时 Flyway、PostgreSQL image
  和 HTTP 合同计数，且不保存密钥或业务 payload。
- [x] `business-client-binding-preflight.sh` 默认只读，真实 canary/cleanup 报告通过
  schema 与 secret-safety 验证。
- [x] 双语业务服务接入指南固化 root/业务 credential、binding、CAS/tombstone/ASYNC、
  轮换、升级与回滚边界。

### 2026-08-26 操作级 API 能力门禁

- [x] V49 为 stable principal 增加受数据库约束的 `RAG_READ` / `RAG_WRITE` policy，
  V48 数据默认兼容为完整读写。
- [x] 中央 capability filter 在认证后、共享限流前执行；只读 principal 可 Search/Chat，
  但写请求返回 `403`，OpenAI 兼容错误码为 `insufficient_permissions`。
- [x] 创建、策略 CAS、轮换、`/auth/me`、WebUI 与 PostgreSQL/真实 HTTP 验收使用同一份
  权威能力，非法集合不持久化，ADMIN 不允许降级为只读。
- [x] 部署 binding preflight 精确区分 `READ_ONLY` / `READ_WRITE` credential 画像；
  release manifest 固定记录两个已验画像，HTTP 合同使用只读 query 与读写 dispatcher
  验证读成功、写 `403`、状态不变和 rotation 能力继承。
- [x] 真实 LLM 门禁使用显式 `RAG_READ` principal，写拒绝和幂等 replay 不增加 provider
  counter；原生/OpenAI-compatible JSON/SSE 加 staged complete/cancel/pending-family-revoke
  生命周期要求恰好 9 次成功真实 provider 调用。

### 2026-08-26 Provisioning 与能力发现门禁

- [x] V50 增加按 requester 隔离的 provisioning operation 账本，只保存
  key/fingerprint hash 与结果 metadata；raw credential 仍只展示一次，不为 replay
  持久化。
- [x] keyed create 返回 `201`；跨实例精确 replay 返回 `200`、
  `X-RAG-Idempotent-Replay: true` 和 `rawKey: null`；语义复用返回
  `409 IDEMPOTENCY_KEY_REUSED`。
- [x] PostgreSQL 与真实 HTTP 验收覆盖并发首次创建、requester 隔离、rotation/revoke 后
  replay 状态、有界清理，以及账本/配置故障 fail closed。
- [x] 认证的 `GET /api/v1/rag/integration-capabilities` 返回版本化协议、当前调用方
  policy/Collection 投影、数据面行为、可选特性和稳定上限，不返回 secret。
- [x] 统一 managed-principal 门禁覆盖 PostgreSQL、Maven、WebUI
  typecheck/Vitest/build/alignment、Mock Playwright、双实例 HTTP、真实浏览器 DOM/网络
  断言和可选真实 provider Chat。

### 2026-08-26 Sync Run Item Receipt 门禁

- [x] V51 为既有 Sync Run item ledger 增加未过滤与按状态过滤的 keyset 索引，不复制
  mutation 数据，也不保存正文、payload、metadata、credential 或 provider 信息。
- [x] `GET /api/v1/rag/document-sync-runs/{runId}/items` 要求 `RAG_READ` 与
  Collection/run/namespace binding，支持状态过滤、1–200 有界分页、绑定 run/status 的
  opaque cursor，并返回独立的当前 ledger 摘要。
- [x] 终态遍历稳定；active run 明确为最终一致观察，Client 必须按 `externalId` 去重并在
  终态后从头复扫。错误在写入和读取时脱敏并限制为 500 字符，响应使用 `no-store`。
- [x] capability discovery 通过
  `features.optional.documentSyncRunItemReceipts` 公布运行时可用性，当前 protocol 为 `1.1`
  的 additive 兼容。
- [x] PostgreSQL service 与认证 HTTP 验收覆盖 V1-V52、受限读写/只读 principal、
  ACL 防枚举、cursor binding、active/terminal 分页、FAILED 精确重放、missing
  reconciliation 和证据脱敏。

### 2026-08-26 Collection Provisioning 幂等门禁

- [x] V52 增加独立、按 owner 隔离的 Collection 创建 operation ledger，保存
  key/fingerprint hash 和受约束的 Collection 外键；不保存 raw key 或请求体。
- [x] 无 header 创建保持 `200`；keyed 首次创建返回 `201`；跨实例或重启后精确 replay
  返回 `200` 和 `X-RAG-Idempotent-Replay: true`；语义复用返回
  `409 IDEMPOTENCY_KEY_REUSED`。
- [x] replay 返回 Collection 当前状态和当前文档数，包括软删除状态；不恢复资源，也不
  写第二条创建审计。
- [x] 配置关闭或账本不可用时 keyed 请求 fail closed 返回 `503`；同 owner 并发竞争使用
  唯一约束和有界重读，不使用显式悲观锁。
- [x] `verify-collection-provisioning.sh` 覆盖聚焦合同、9 个 PostgreSQL 测试、两个真实
  后端实例、重启恢复、owner/ACL 隔离、数据库事实和证据脱敏；WebUI 测试证明 Axios
  retry 复用一次提交生成的 UUID。
- [x] capability discovery 通过 protocol `1.1` 的 additive 字段
  `features.provisioning.collectionCreateIdempotencyKey` 公布能力。

### 2026-08-26 模型调用级用量账本门禁

- [x] V53 增加 append-only 的 `rag_llm_usage_event` 表，包含有界字段、
  principal/session/trace 归因、调用开始时价格快照、规范化 usage、终态结果和耗时。
- [x] `BudgetedChatModel` 在 Chat、query transform/expand、summary、fallback、应用
  retry 和 AGENT 轮次中，为每次模型调用或流式订阅最多记录一条终态事件。
- [x] 记录 fail-open：非流式使用有界同步确认，流式使用有界异步记录，维护进程本地
  丢失计数并按有界批次清理；不保存 prompt、answer、工具 payload、credential 或异常正文。
- [x] `GET /api/v1/rag/usage` 提供包含首尾的 UTC 聚合窗口、稳定 breakdown、usage/pricing
  缺失计数和按 principal 隔离的授权。
- [x] `verify-llm-usage-ledger.sh` 覆盖后端聚焦测试、PostgreSQL V1-V53 集成、Maven、
  WebUI、Mock Playwright、禁锁、文档和空白门槛。
- [ ] 真实 LLM 生命周期验收记录 plain、knowledge、agent、fallback、summary、replay
  和用量账本证据，不保存 prompt、answer、密钥或工具 payload。

### 2026-08-27 外部集成可运维性门禁

- [x] V54 增加有界 UTC 小时级请求总量与已授权 Collection contribution，不保存请求/
  响应正文、query、payload、external ID、credential、动态 URL 或异常正文。
- [x] `/integration-capabilities` 报告 protocol `1.1`，以 additive 字段发布
  structured-record、Sync Run、observability 运行时上限和
  `features.optional.integrationObservability`。
- [x] `GET /api/v1/rag/integration-observability` 为 NORMAL principal 提供 self/当前
  ACL 视图，为 root/ADMIN 提供管理视图，并支持有界 HOUR/DAY 窗口、status/operation/
  Collection breakdown 和显式 best-effort 完整性说明。
- [x] 记录器使用异步有界 queue、分组 PostgreSQL upsert、有界 retention/停机 drain、
  fail-open 业务语义和固定低基数 Micrometer 标签。
- [x] 已部署 binding preflight 可要求最低 JSON batch item/payload 上限与 operation
  observability；其报告和 readiness release manifest 只保存非敏感运行时事实。
- [ ] 最终合并基线 readiness 门禁需记录 focused/PostgreSQL/Maven/WebUI/Mock/真实 HTTP、
  真实 LLM/Embedding 验收与连续三轮无修改审查证据。

### 2026-08-27 有界 staged credential 轮换门禁

- [x] V55 对每个 stable principal 最多允许一个 current、一个有 deadline 的 retiring
  credential，以及一个 PENDING rotation operation。
- [x] prepare 必须携带 `Idempotency-Key`，replacement secret 只展示一次，精确 replay
  不返回 secret，并通过 `features.credentialRotation` 发布运行时上限。
- [x] complete、cancel、deadline expiry、policy expiry clamp、即时兼容轮换和 family
  revoke 均可收敛，不复制 principal identity、ACL、capabilities、Chat owner、usage 或 quota。
- [x] WebUI 以 staged rotation 为推荐路径，secret 只保存在页面内存，支持 complete/cancel
  与响应丢失恢复，并把即时路径保留为明确的次级操作。
- [x] V55 合并前验收已记录 PostgreSQL 54/54、Maven/WebUI/Mock/真实 Playwright、
  双实例 HTTP 生命周期、9 次真实 provider 调用及拒绝/replay 零增量、真实 Embedding/RAG。
- [x] 合并最新 `origin/main` 后已从合并后基线重新执行同一完整矩阵：
  `v55-minimax-postmerge-20260828-r2` 为 13/13 通过；独立真实 RAG
  `v55-minimax-postmerge-20260827-r5` 为 `PASS=10 FAIL=0`，并完成带 revision 的
  永久删除及最终数据库事实确认。

### 2026-08-27 Collection 受保护清理与退役门禁

- [x] V56 增加永久 key tombstone、Chat commit fence、Chat/feedback 规范化文档引用与
  完整性标记，以及不保存正文/明文 token 的 durable purge preview。
- [x] preview/apply 使用 environment root、数据库 ADMIN 或显式 auth-disabled direct
  loopback 权限；caller-aware capability protocol 升为 `1.1`，并发布同步清理上限。
- [x] Collection-first 条件写入统一保护文档 mutation、同步、repair、feedback、Chat、
  restore/relocation/clone 和 purge；禁止显式悲观锁。
- [x] PostgreSQL 5 场景矩阵覆盖完整级联、无关数据/独立文件保留、活动 lease/run 阻断、
  坏历史引用 fail closed、权限、回滚、精确 replay、cleanup 与退役 scope。
- [x] WebUI 只为 environment root 且 capability 可见时显示入口；token 不渲染，必须精确
  输入 key，成功结果保持可访问并刷新 active-only 列表，409 不自动重试。
- [x] `verify-collection-purge.sh` 固化聚焦后端、PostgreSQL、Maven clean、WebUI、
  Mock Playwright、禁锁、文档、脚本和空白门槛。
- [x] 使用隔离 PostgreSQL 和真实 LLM/Embedding 完成退役前写入/检索/Chat、purge 与退役后
  显式拒绝/默认范围排除的生命周期验收；`real-provider-20260828-r8` 为 `12/12`，
  全局/Collection purge rollup 均为正，日志无 observation drop/Provider/数据库异常，
  持久化证据不含密钥、正文、明文 token 或完整模型回答。
- [x] 同步最新 `origin/main` 后从合并基线完整重跑：`post-merge-20260828-r1`
  专项门禁 **9/9**，含后端 **186/186**、PostgreSQL **11/11**、WebUI Vitest
  **233/233**、Mock Playwright **3/3** 和 Maven/前端/文档门槛；
  `real-provider-post-merge-20260828-r2` 真实生命周期 **12/12**，日志和脱敏证据复核通过。

### 2026-08-27 受管 API Principal 到期告警门禁

- [x] V57 增加 active managed alert partial unique index、阶段/通知版本、principal 公平
  扫描游标与检查约束，不保存 credential、名称、Collection allow-list、quota 或业务 payload。
- [x] principal 创建、expiry policy 更新和 family revoke 提交后通过 Spring Event 唤醒
  有界异步 worker；默认每小时 Scheduled 扫描只负责漏事件和时间跨阈值恢复。
- [x] Alerts 全路由收紧为 operator 管理面；WebUI 修正为 `firedAt`，并展示服务端阶段、
  principal 和 expiry，不在浏览器计算阈值。
- [x] focused 门禁覆盖后端 **218/218**、PostgreSQL V1-V58 生命周期 **6/6**、WebUI
  Vitest **234/234**、production build 和 Alerts Mock Playwright **1/1**。
- [x] 完整 Maven、WebUI、服务启动、禁锁、文档、diff、shell 与密钥门禁完成；最终证据由
  V58 联合门禁 `20260828-durable-final-precommit` 覆盖。
- [x] 隔离 PostgreSQL 与真实 LLM/Embedding principal/document/Chat/alert 生命周期验收
  完成；同一联合门禁 **13/13** 通过。
- [x] `git fetch origin --prune` 后确认 `HEAD == origin/main == 00341665`，没有上游变更
  需要合并；联合门禁在该相同代码基线上完整通过。

### 2026-08-28 告警通知 Durable Outbox 门禁

- [x] V58 增加 `rag_alert_notification_delivery`、alert/version/provider 唯一约束、
  eligible/expired-lease/query 索引和状态/lease/attempt 检查约束。
- [x] 告警与 delivery 同事务提交；after-commit Spring Event 准实时唤醒独立有界 worker，
  默认一分钟 Scheduled 只恢复漏事件、重启和过期 lease。
- [x] provider 调用位于事务外且单个 ledger attempt 只调用一次；Apache HttpClient 自动
  retry 已关闭，跨重启重试由 PostgreSQL attempt/lease/CAS 和有界退避统一管理。
- [x] Operator API 与 Alerts WebUI 支持低敏 receipt、过滤、游标和人工 retry，不返回
  payload、endpoint、recipient、secret、lease、错误正文或堆栈。
- [x] 专项真实生命周期门禁 `20260828-rerun2` 通过 **9/9**：Event 首投 `0s`、
  `503 -> DELIVERED` 恰好两次 attempt、阻塞调用中终止首实例后由第二实例使用同一 UUID
  回收 lease，并完成真实 WebUI DOM/network Playwright。
- [x] `mvn clean compile test-compile`、全量 Maven、WebUI Vitest **236/236**、
  typecheck、production build、alignment 与核心 Mock Playwright 已通过。
- [x] 真实 Chat LLM/Embedding/durable notification 联合门禁
  `20260828-durable-final-precommit` 通过 **13/13**；真实 Chat 触发 MiniMax provider
  **9** 次，真实 Embedding、Event-driven ASYNC、vector Search、KNOWLEDGE Chat、
  告警状态复用和 durable notification 全部通过。
- [x] 最终联合门禁同时通过 PostgreSQL integration matrix、`mvn clean compile
  test-compile`、全量 Maven（Core **3240**、Starter **44**）、WebUI Vitest
  **236/236**、typecheck、production build、alignment、核心 Mock Playwright、禁锁、
  文档和 diff 检查。
- [x] `git fetch origin --prune` 后确认 `HEAD == origin/main == 00341665`，没有上游变更
  需要合并；联合门禁在该相同代码基线上完整通过。

### 最终证据（2026-07-21）

- 一键命令：`VERIFY_RUN_ID=20260721-release-complete ./scripts/verify-release.sh --with-local-runtime`
- 归档：`target/release-verification/20260721-release-complete/summary.md`
- 发布门禁：19 passed、0 failed、0 skipped
- Maven：3213 tests（API 530、Documents 74、Core 2557、Starter 52）
- WebUI：lint、153 Vitest、生产构建、内嵌 bundle 完整性、37 Playwright
- 部署：Helm lint/template；DaoCloud 基础镜像 + 阿里云 Maven mirror 的 `linux/arm64` 非 root Docker 镜像
- 运行时：PostgreSQL profile 服务启动、HTTP E2E 66/66
- 检索：baseline/quality 均为 MRR 1.0、Precision@5 0.24、nDCG 1.0，`GOLDENSET_OK`
- 真实模型：MiniMax-M3 + SiliconFlow BGE-M3，ask/stream 与数据清理共 10/10

## 发布

- [x] 所有适用验证门禁通过后才提交 release commit
- [x] 将已验证 commit 推送到当前上游分支
- [ ] 由发布流水线创建不可变源码/镜像 tag `1.0.0`
