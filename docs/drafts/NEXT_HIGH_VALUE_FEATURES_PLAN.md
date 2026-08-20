# 下一批高价值功能实施规划

> **状态：规划完成，尚未授权实施。**
>
> scenemill AI 是 spring-ai-rag 当前 RAG 服务的**第一个外部 Client**。本批次把 scenemill
> 已经产生的生成视频素材 mutation 事件真正投递到 spring-ai-rag，并在项目资产库形成可见、
> 可降级的语义检索闭环。规划为单语文档；实施完成后，稳定契约必须同步到两个仓库各自的双语
> 长青文档。
>
> 可恢复进度：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)

## 1. 执行结论

下一批按一条端到端纵向切片实施：

```text
scenemill VideoTask result
  -> generated Asset
  -> immutable asset_rag_outbox event
  -> durable per-consumer delivery state
  -> spring-ai-rag external document UPSERT/tombstone
  -> keyword first, vector eventually
  -> scenemill project-scoped video semantic search
  -> authoritative Asset reload
  -> WorldPage video tab results
```

优先级如下：

| 优先级 | 功能 | 为什么现在做 | 本批结论 |
|---|---|---|---|
| P0 | 可靠 RAG 投递与项目绑定 | 上游 durable event 已存在，但没有消费者；当前用户价值仍停在数据库里 | 必须完整交付 |
| P0 | 可检索文本快照与历史再投影 | 当前生成 Asset 默认描述为空，仅靠“场次/镜号”名称检索质量不足 | 必须完整交付 |
| P1 | 项目视频语义检索 API 与资产库 UI | 给可靠投影一个直接可观察的用户闭环，且不需要 LLM/Agent | 必须完整交付 |
| P2 | 候选审核后绑定到分镜/段 | 依赖候选冻结、作者态 CAS 与素材使用解析，范围显著更大 | 下一批之后再做 |
| P2 | 外部文档原子 Collection 迁移与派生完整性运营 | 仍有价值，但不解除当前“事件无人消费”的主阻塞 | 保留在 TODO，暂缓 |

这次不新增专用 spring-ai-rag mutation 协议。首期复用已经发布并有 PostgreSQL/E2E 证据的
外部文档 upsert、来源 tombstone、Collection ACL、metadata filter、持久化 embedding job
和 Search API。只有实现时发现现有公开契约无法满足本文冻结的不变量，才允许在
spring-ai-rag 做小型、增量、向后兼容的契约补强，并必须单独记录原因。

本文作为第一个外部 Client 接入规划，权威副本放在 spring-ai-rag 仓库；scenemill 仓库只维护
阶段进度和指向本文的短入口，不复制一份会独立漂移的完整规划。稳定 Client 契约在交付后提升到
两仓双语长青文档。

## 2. 成功标准

完成后，系统必须能够证明：

1. 一个符合资格的生成视频 Asset 创建后，即使进程在任意事务边界崩溃，最终仍会在绑定的
   spring-ai-rag Collection 中形成唯一外部文档。
2. Asset 名称、描述、状态、媒体身份或可检索资格变化会按 revision 顺序投递；软删除或停用
   会形成 tombstone，重新激活会恢复同一 RAG 文档身份。
3. RAG 服务不可用、限流或超时时，Asset CRUD 和视频结果完成仍成功；事件保留、有限退避，
   且不会在数据库事务中执行 HTTP。
4. 项目用户可以在资产库视频 Tab 输入自然语言，得到当前项目内的生成视频结果；返回媒体事实
   必须由 scenemill 重新加载 Asset 后产生，不能信任 RAG 返回的 URL 或权限。
5. 清空搜索后仍回到既有分页浏览；RAG 未配置、未就绪或暂时不可用时，页面明确降级但不破坏
   上传、筛选和普通资产浏览。
6. 整条验收不需要真实 LLM。跨服务 E2E 使用 deterministic embedding 或测试模型，避免把
   网络成本和模型波动当成功证据。

## 3. 当前事实与前置门槛

### 3.1 scenemill 当前事实

目标 worktree：
`/Users/yangjiefeng/.hermes/workspace/seedance-research-generated-video-asset-rag`。

父仓远端分支 `origin/feature/generated-video-asset-rag-20260820` 确实存在。以 2026-08-21
最新 `origin/main` 为基线，本地特性分支仍有 8 个独有提交，主线另有 3 个待合入提交；因此下列
能力尚未被 main 吸收。`web-studio` 同名分支目前仅存在本地，较其 `origin/main` 有 3 个提交，
必须先推送子模块分支，保证父仓 gitlink 可获取。

当前特性分支已经实现并在当时基线上完成最终三轮收敛：

- `VideoTaskResultDelivery.COMPLETED` 通过 `video_result_asset_outbox` 异步创建
  `Asset(type=VIDEO, origin_type=VIDEO_TASK_RESULT)`；
- `asset_rag_outbox` 是 RLS 保护、业务字段不可更新/删除的 immutable mutation log；
- payload 协议为 `material-rag-mutation-v1`，包含项目 scope、source identity、revision、
  incarnation、双 fingerprint、文档和不含裸 URL 的 `mediaRef`；
- 用户更新、系统媒体修复、停用/恢复和软删除已经接入 revision/CAS 与 UPSERT/DELETE append；
- 历史生成结果有一次性回填；视频资产库已有来源筛选和 50 条分页；
- 专项 PostgreSQL、视频结果回归、clean compile、前端 build/Playwright 和隔离 V435 dev smoke
  已通过；未提交进度账本记录调度池从 22 修正为 27 后重新验证，并完成连续 `3/3` 无实现修改
  检查。

因此本批实施的 Gate 0 不是重做旧基线审查，而是先保留并提交调度池/进度 WIP，再把父仓合入
最新 `origin/main`。合并后的最终组合必须重跑受影响门槛和三轮限定范围检查，才能形成可追溯
checkpoint；不能把旧基线的 `3/3` 直接当成最新主线组合的证据。

### 3.2 spring-ai-rag 当前事实

当前主线已经提供：

- 稳定外部地址 `collectionKey + sourceNamespace + externalId`；
- opaque `sourceRevision`、严格 `expectedSourceRevision` CAS、精确 revision replay；
- `POST /api/v1/rag/documents/upsert` 和来源 tombstone；
- `ASYNC` 持久化 embedding job；
- V43 keyword/vector 解耦，远程 embedding 未完成时可进入 `KEYWORD_ONLY`；
- Collection API Key allow-list、metadata containment、混合 Search 和检索 trace；
- 外部 CRUD、Sync Run、生命周期和 derivation 的真实 PostgreSQL/E2E 基础。

spring-ai-rag 当前没有独立 tenant 资源。Collection 同时承担投放目标和 ACL 边界，
`sourceNamespace` 不是授权边界。因此每个 scenemill 项目必须显式绑定一个活动 Collection，
不能只凭 payload 中的 `tenantKey` 动态拼任意目标。

### 3.3 两端之间的真实缺口

当前缺少：

- 项目到 RAG Collection、服务端 target 和 credential alias 的可信绑定；
- immutable outbox 对每个消费者的 mutable receipt/lease/retry 状态；
- 按 source revision 顺序调用外部 API 的 dispatcher；
- 可用于语义检索的生成提示词快照；
- 从 RAG 命中重新解析为项目内 Asset 的后端 API；
- 前端语义搜索入口、Mock 和跨服务 E2E。

`asset_rag_outbox` 只有 append/查询能力是有意设计，不应给它补 `status` 并把事件事实改造成
单消费者队列。

## 4. 范围与非目标

### 4.1 本批范围

- 只处理 `sourceKind=ASSET_VIDEO`、`sourceScope=PROJECT` 的生成视频 Asset。
- 每个 scenemill 项目最多一个活动 RAG binding。
- 只投递到配置中预先声明的 spring-ai-rag target alias。
- Collection 和受限 API Key 由运维预创建；scenemill 激活 binding 时验证可访问性。
- 只在 WorldPage 资产库视频 Tab 增加项目内生成视频语义搜索。
- 搜索结果只返回 authoritative Asset DTO；RAG 分数只用于排序，不作为权限或业务事实。

### 4.2 明确不做

- 不自动创建 spring-ai-rag Collection 或 API Key，不让 scenemill 保存 root credential。
- 不把 API Key、base URL、Collection key 或 target alias 暴露给普通前端。
- 不把 `tenantKey`、`sourceNamespace` 当作 spring-ai-rag ACL。
- 不索引原始视频、音轨、帧、OCR、ASR 或视觉 caption；首期只索引受控文本。
- 不引入 Kafka、Redis、Debezium、logical decoding 或第二套工作流引擎。
- 不做无用户确认的素材自动绑定，不触发视频/图片/LLM create。
- 不扩展到普通上传视频、ReferenceImage、角色、地点或平台共享素材。
- 不实现跨 Collection 原子迁移或派生完整性 repair；它们继续留在 spring-ai-rag TODO。

## 5. 身份、版本和文本契约

### 5.1 外部文档映射

固定映射：

| spring-ai-rag 字段 | scenemill 来源 | 规则 |
|---|---|---|
| `collectionKey` | 项目 RAG binding | 服务端解析；客户端不可提交 |
| `sourceNamespace` | 协议常量 | `scenemill.material.asset-video.v1` |
| `externalId` | Asset identity | `asset:<assetId>`，不使用 URL/hash/task ID |
| `sourceRevision` | event revision | `arm1-r<revision>-<sourceChangeId>` |
| `expectedSourceRevision` | 上一条已投递事件 | revision 1/远端不存在时省略；后续严格携带 |
| `title` | Asset name | 空值使用稳定、非敏感 fallback |
| `content` | 受控检索文本 | 见 5.2；不放 URL、key、token、隐藏 prompt |
| `source` | 常量 | `scenemill` |
| `documentType` | 常量 | `generated-video-asset` |
| `metadata` | mutation envelope 子集 | 只保存检索/回源所需的稳定标量 |
| `embeddingPolicy` | 常量 | `ASYNC` |

metadata 至少包含：

- `protocolVersion`、`documentSchemaVersion`、`targetIndexGeneration`；
- `sourceKind`、`sourceId`、`sourceRevision`、`sourceFingerprint`；
- `candidateKey`、`candidateIncarnation`、`scopeOwnerKey`；
- `mediaKind=VIDEO`、duration；
- `assetId`。

不得投递：公共/签名 URL、TOS key、managed object ID、Provider task ID、上游模型 ID、
完整 request payload、API Key 或用户不可见的系统指令。

### 5.2 可检索文本 v2

当前 v1 文档只有 Asset name/description，历史默认 description 为空。第二阶段新增
`documentSchemaVersion=2`，content 由以下 author-visible 字段确定性拼装：

1. Asset name；
2. Asset description；
3. `VideoTask.generation_params_json.prompt` 中结构化读取的 `prompt` 字段；
4. 设计来源类型、场次/镜号或段名称；
5. duration 和媒体类型等有限结构化标签。

实现约束：

- 只用 JSON parser 读取 `prompt`，不扫描 `request_payload_json`；
- 不读取 negative prompt、URL 数组、mention bindings 或 Provider response；
- prompt snapshot 最多 8,000 个字符，content 总 UTF-8 最大 16 KiB；
- 归一化只做空白和长度处理，不用正则/启发式改写自然语言；
- 不调用 LLM 做摘要；
- 将冻结的生成 prompt 保存到专用、非公开的 Asset 字段或等价结构化系统快照，后续用户修改
  name/description 时可重建文档，但不能从易变的当前 Storyboard/Segment prompt 猜历史 attempt；
- v1 已有事件保持不可变。历史升级通过显式 backfill 生成新的 Asset RAG revision 和 v2 UPSERT，
  不修改旧 event，不复用旧 revision。

### 5.3 顺序与重放

同一 `(tenantId, bindingGeneration, sourceKind, sourceId)` 必须串行：

- revision `n` 只有在同 binding generation 的所有较小 revision 已 `DELIVERED` 后才可 claim；旧
  generation 已 `SUPERSEDED` 的 receipt 不阻断新 generation；
- HTTP 超时后精确重放同一 sourceRevision 和完整请求；
- 远端返回同 revision 且内容一致，视为幂等成功；
- 409 时先读取当前外部文档。只有当前 revision 与本事件完全一致才能补记成功；其他情况进入
  `REMOTE_REVISION_DIVERGED`，不能 last-write-wins；
- 前序事件 terminal failure 会阻断后序事件，等待显式 replay/repair；不能自动跳过 DELETE；
- 不根据 created_at 推断版本；顺序以 sourceRevision 数字和稳定 source identity 为准。

新 binding generation 的首个 reproject event 没有本地“上一条已投递 revision”。worker 先按外部
identity GET：404 时省略 expected revision；远端已是本事件完整 desired state 时补记成功；远端
存在同一 scenemill identity 的旧 generation state 时，只有显式 admin reproject run 才可把读取到
的当前 revision 作为 CAS base 更新；其他来源、字段异常或普通实时事件一律按 divergence 失败。

## 6. 项目目标绑定与密钥边界

### 6.1 配置 target

应用配置声明有限 target alias，例如：

```yaml
scenemill:
  material-rag:
    targets:
      default:
        base-url: ${SCENEMILL_MATERIAL_RAG_DEFAULT_BASE_URL:}
        api-key: ${SCENEMILL_MATERIAL_RAG_DEFAULT_API_KEY:}
```

- base URL 和 secret 只能来自部署配置/环境变量；
- base URL 遵守 spring-ai-rag 公开配置契约，末尾不得包含 `/v1`；生产 target 默认要求 HTTPS，
  只有隔离测试 profile 可使用 loopback HTTP；
- target alias 的 host 必须在启动时校验，禁止由数据库或 HTTP 请求提供任意 URL；
- API Key 不写数据库、不写日志、不进响应、不进入 outbox payload；
- HTTP client 禁止对 POST/DELETE 做隐式自动重试，重试由 delivery state 显式驱动；
- connect/read/call timeout 必须有限。

### 6.2 项目 binding

新增 tenant/RLS 表，概念字段：

```text
material_rag_project_binding
  id
  tenant_id
  project_id
  target_alias
  collection_key
  source_namespace
  status = VERIFYING | ACTIVE | DISABLED | ERROR
  binding_revision
  verified_at
  last_error_code / last_error_summary
  created_at / updated_at / created_by / updated_by
```

不变量：

- `(tenant_id, project_id)` 最多一个 ACTIVE binding；
- binding 使用 `(tenant_id, project_id)` 复合外键或等价数据库约束绑定当前 tenant 的 Project，
  不能只依赖 Controller 预检查；
- project 必须真实属于当前 tenant；
- `source_namespace` 首期固定，不能由普通用户编辑；
- binding 激活分三段：短事务读取/预留 -> 无事务调用 RAG 验证 Collection 与 credential ->
  短事务 CAS 激活；
- 激活只证明该 key 能访问目标 Collection。运维仍应默认为每个 scenemill tenant 使用独立
  credential alias，并把 allow-list 限定在该 tenant 的项目 Collections；
- 首期 ACTIVE binding 的 target/collection 不允许原地改写。迁移使用“停用 + 新 generation +
  显式全量再投影”，不能把历史 delivery receipt 静默指向新目标。

普通项目成员只读取“已启用/准备中/异常”的脱敏 capability，不读取 collection/credential。
binding 管理由 ADMIN/运维入口承担，前端用户搜索不负责自动修复配置。

首期管理契约冻结为：

```http
GET    /api/admin/projects/{projectId}/material-rag-binding
PUT    /api/admin/projects/{projectId}/material-rag-binding
DELETE /api/admin/projects/{projectId}/material-rag-binding?expectedBindingRevision=...
POST   /api/admin/projects/{projectId}/material-rag-binding/reproject
GET    /api/admin/projects/{projectId}/material-rag-deliveries?status=...
POST   /api/admin/projects/{projectId}/material-rag-deliveries/{deliveryId}/replay
```

- 整个 Controller 使用现有 `/api/admin/**` + `hasRole('ADMIN')` 双重保护，并受当前 tenant RLS；
- `PUT` body 只接受 `targetAlias`、`collectionKey` 和 `expectedCurrentBindingRevision`；从未绑定时
  revision 必须为空，已有停用历史时必须等于最新 revision。操作总是创建下一 generation，不接受
  URL 或 secret；已有 ACTIVE binding 时返回 `409`，不能原地换目标；
- `DELETE` 是带 `expectedBindingRevision` 的 CAS 停用，不自动删除远端文档；
- `reproject` 只为当前 ACTIVE generation 建立有界 backfill run，返回 run ID，不同步执行全量扫描；
- delivery 列表只返回有限分页和脱敏诊断；`replay` 要求 `FAILED` 状态、expected attempt/version 与
  非空审计理由，不能跳过同 identity 的前序失败；
- GET/响应对 admin 可返回 target alias、collection key、revision 和脱敏错误码，但永不返回 secret。

## 7. Durable delivery state

### 7.1 数据模型

新增独立 mutable consumer state，而不是修改 immutable event：

```text
asset_rag_delivery_state
  id
  tenant_id
  event_id
  consumer_key = spring-ai-rag:v1
  source_kind / source_id / source_revision
  scope_owner_key
  status = BLOCKED | PENDING | PROCESSING | DELIVERED | FAILED | SUPERSEDED
  binding_id / binding_revision
  target_revision / expected_target_revision
  attempt_count / available_at
  claim_token / claimed_at / lease_until
  last_http_status / last_error_code / last_error_summary
  remote_document_id
  delivered_at / created_at / updated_at
```

约束：

- `(tenant_id, event_id, consumer_key)` 唯一；
- `event_id` 通过 `(tenant_id,event_id)` 复合外键引用 immutable log，`binding_id` 同样带 tenant
  约束；两者都不得级联删除事实；
- PROCESSING 必须有 token/lease，其他状态必须清空 claim 字段；
- `source_revision >= 1`；
- RLS/FORCE RLS；平台扫描只返回 `id + tenant_id`，实际 claim/apply 回到 tenant context；
- app role 只能更新 lease、状态、有限诊断字段；不能修改 source/event identity；
- 错误摘要有长度上限，不保存远端 body、stack trace 或 secret。

binding generation 变化时：

- 旧 generation 的 `BLOCKED/PENDING` receipt 通过条件 DML 转为 `SUPERSEDED`，不能改绑到新目标；
- 旧 generation 的 `PROCESSING` 由 token + binding revision fencing 禁止在 T2 写成新 generation 的
  成功，lease 到期后转为 `SUPERSEDED`；远端请求若已被旧目标接受，只能由显式、可审计 tombstone
  run 清理，不能假装从未发生；
- `DELIVERED/FAILED` 保留原事实和诊断；新 binding 必须由 reproject 产生新的 Asset revision/event，
  再创建绑定到新 generation 的 receipt；
- `SUPERSEDED` 是终态，不阻断新 generation 的同 source 投递，也不得被普通 replay 恢复。

`asset_rag_outbox` 增加只服务于发现扫描的 `(created_at,id)` 索引；是否冗余增加
`scope_owner_key` 列应以 PostgreSQL `EXPLAIN` 和 payload 查询成本决定，不能仅为避免解析 JSON
破坏 immutable 事件兼容。

### 7.2 发现、唤醒与补偿

- event append 提交后可以发布只携带 event ID/tenant ID 的 Spring Event 作为低延迟唤醒；
- listener 只创建/推进 delivery row，不做 HTTP；
- Scheduled reconciler 负责发现没有 receipt 的历史事件、恢复过期 lease、唤醒 retry；
- 无 binding 时创建 `BLOCKED/PROJECT_BINDING_UNAVAILABLE`，binding 激活后有界重排为 PENDING；
- 每 tick 有 event 上限、tenant 上限和耗时预算，正常新事件优先于历史 v2 backfill；
- executor 使用独立 bounded pool，不能挤占视频结果、billing 或通用 async executor。

### 7.3 worker 事务边界

固定三段：

```text
T1 short tx: claim + load immutable request snapshot
outside tx: spring-ai-rag HTTP
T2 short tx: token/lease fenced apply success or retry/failure
```

HTTP 429/5xx/timeout 为 retryable，尊重有界 `Retry-After`；401/403、binding 不匹配、payload
协议错误和 remote revision divergence 默认 terminal。任何失败都不能回滚 Asset 或 event。

## 8. spring-ai-rag 调用与可检索就绪

### 8.1 UPSERT/DELETE

- UPSERT 调用普通 external document endpoint，发送完整 desired state 和 `ASYNC`；
- DELETE 调用来源 tombstone endpoint，使用本事件 target revision 和上一成功 revision；
- remote `documentId` 只用于诊断，不成为 scenemill 素材身份；
- 返回 `KEYWORD_ONLY` 或 embedding job 尚未完成仍可视为投递成功，因为文档主状态已提交；
- `DELIVERED` 只表示 RAG mutation 已接受，不等于 vector READY。

### 8.2 readiness

项目 capability 至少区分：

- `UNBOUND`：无活动 binding；
- `DELIVERY_PENDING`：存在未终态 delivery；
- `AVAILABLE`：至少有已接受 projection，可发起搜索；不声称全部向量已经 ready；
- `DEGRADED`：目标不可用或存在 terminal delivery failure。

capability 只基于本地 binding/delivery 聚合，不新增一个虚假的 scenemill vector READY 布尔值，
也不在页面加载时调用远端。实际 search response 再根据本次 RAG 结果的 vector/full-text 分支返回
`HYBRID`、`KEYWORD_ONLY`、`EMPTY` 或 `DISABLED`；运维和跨服务验收直接查询 spring-ai-rag
Collection readiness 证明向量最终收敛。查询失败返回 DEGRADED/UNKNOWN，不影响普通资产列表。

## 9. 项目内生成视频语义搜索

### 9.1 后端 API

新增项目授权端点，契约冻结为：

```http
GET /api/projects/{projectId}/assets/videos/search?query=...&limit=20
GET /api/projects/{projectId}/assets/videos/search-capability
```

首期语义：

- `query` trim 后 2-500 字符；`limit` 默认 20、最大 50；
- 只检索当前项目 ACTIVE binding 的 Collection；
- RAG 请求固定 `SELECTED_COLLECTIONS` 和当前 collectionKey，并下推
  `metadataContains={"sourceKind":"ASSET_VIDEO","scopeOwnerKey":"<projectId>"}`；Collection 即使
  被多个项目共享，也不能依赖回源过滤才隔离候选；
- 不接受客户端传 collection、namespace、target alias、API Key、tenant 或 metadata filter；
- 解析命中 metadata 中的 `assetId`，去重后批量从当前 tenant/project 的 Asset repository
  重新读取；
- 只返回仍是 generated VIDEO、未删除、ACTIVE 且媒体当前可用的 Asset；
- 保持 RAG rank，过滤后不拿其他项目或低权限结果补位；
- 两个端点均使用现有 `@ProjectScoped(projectId = "#projectId")`；未授权项目沿用统一 `404`；
- search response 固定返回 `items: AssetResponse[]`、`searchMode`、`readiness`、`partial` 和可选的
  脱敏 `unavailableReason`；不返回原始 RAG score、RAG documentId、collectionKey、
  sourceNamespace、target alias 或远端错误 body；
- RAG score 是排序信号，不展示为“匹配概率”。

这个端点必须使用与普通视频列表相同的项目授权。列表过滤不能替代直接资源校验；RAG 命中也不是
Asset 权限证明。

capability 只读取 binding/delivery 聚合，不在页面加载时调用远端 RAG。search 对
`UNBOUND/DISABLED/DEGRADED` 等预期不可用状态返回 HTTP 200 + 空 `items` 和稳定 reason enum，
保证资产库降级；参数非法返回 `400`，并发 binding generation 已变化返回 `409`。远端超时/5xx
记录指标和脱敏日志后映射为 `DEGRADED/RAG_UNAVAILABLE`，不把原始响应透传给浏览器。

### 9.2 前端 UX

WorldPage 视频 Tab：

- 复用现有视频来源筛选；语义搜索首期只支持 `GENERATED`，用户在 `ALL/IMPORTED` 下输入时
  明确切换或提示范围，不静默混入普通视频；
- 输入框 300ms debounce、最少 2 字；提交与 URL query state 保持一致，刷新/前进/后退可恢复；
- 搜索态是 bounded top-k，不伪装成可无限翻页；清空 query 恢复现有 50 条分页浏览；
- 搜索期间保留稳定布局；loading/error/empty/result 通过 DOM/ARIA 表达；
- RAG unavailable 时显示非阻断降级状态，上传、来源筛选、已有列表和详情继续工作；
- 视频卡片继续复用 `videoCover.ts`，用户点击后才挂载真实 video；
- API 的 `fetchClient.ts`、mock-aware `client.ts`、`mock/handlers.ts` 三处同步。

## 10. 历史 v2 再投影

历史升级不能修改旧 immutable event。新增显式、默认关闭、一次性 runner：

1. 扫描当前 eligible generated Assets；
2. 加载 origin VideoTask 并结构化读取冻结 prompt；
3. 若 v2 检索文本与当前快照相同则 no-op；
4. 通过 `GeneratedVideoAssetMutationService` 的系统字段 CAS 写入 prompt snapshot、递增
   RAG revision，并在同事务 append v2 UPSERT；
5. dispatcher 按正常顺序投递；
6. 保存 scanned/enriched/skipped/failed/remaining 摘要；
7. 同一进程只运行一次，重启可幂等继续；正常新 mutation 优先。

无 origin task、prompt 非法或项目 binding 缺失必须分类。无 prompt 的 Asset 仍可以 name/description
投影，不应阻断全批。runner 不调用 LLM、embedding provider 或视频 Provider；embedding 只由
spring-ai-rag 接收 UPSERT 后的普通持久化 job 触发。

## 11. 实施切片

开始修改生产代码前，一次性冻结本批验收矩阵，并先提交能证明缺口的失败测试/fixture；随后按
Slice 顺序让同一组测试转绿。不能以代码 review 代替红绿证据，也不能在收敛审查阶段采用“发现
一个问题再补一个测试”的方式临时扩展验收范围。

### Gate 0：上一阶段收口

- 先保存父仓现有未提交配置与进度账本，不丢弃、不覆盖；
- `web-studio` 先 merge 最新远端 main、复验并推送可获取的专用分支；
- 父仓再 merge 最新远端 main，恢复已保存修改并解决冲突；
- 保留并提交调度池修复与记录旧基线 `3/3` 的进度账本；合并最新 main 后重跑受影响门槛和三轮
  连续无实现修改检查；
- 提交最新进度账本并推送 feature branch；
- 固定 phase-1 event fixture 和 migration 基线；
- 未合 main 前，第二阶段继续在同一隔离 worktree/专用分支实施，不切换旧分支。

### Slice A：契约 fixture 与 v2 文本

- 在两个仓库固定 external upsert/delete/Search JSON fixture；
- 新增 prompt snapshot 和 v2 event factory；
- 补普通更新/删除/恢复和历史 backfill PostgreSQL 测试；
- 不先写 HTTP worker。

### Slice B：binding 与 delivery state

- Flyway、RLS、grants、配置 validation；
- binding verify 三段事务；
- immutable event discovery、receipt、lease、ordering、retry/reconcile；
- WireMock HTTP contract tests。

### Slice C：跨服务投递验收

- 启动隔离 spring-ai-rag + PostgreSQL/pgvector；
- 预创建 Collection 和 restricted API Key；
- 启动隔离 scenemill + 专属 PostgreSQL；
- 验证 create/update/delete/restore、timeout replay、服务重启和 keyword/vector readiness。

### Slice D：搜索 API 与 UI

- 后端 project-scoped semantic search；
- authoritative Asset reload 与 rank preservation；
- 前端 query state、Mock、DOM/网络断言；
- 清空搜索恢复普通分页。

### Slice E：历史再投影、rollout 与文档

- 有界 v2 backfill；
- 指标、日志、运维只读统计；
- 两仓双语长青文档同步；
- 自动化硬门槛和三轮实现收敛。

## 12. 自动化验收矩阵

### 12.1 scenemill PostgreSQL 集成

- 空库执行当前全部 Flyway；
- binding tenant/project RLS、唯一约束、状态 CAS、无 secret 持久化；
- binding 停用时旧 generation receipt 的 `SUPERSEDED`、在途 lease fencing 和新 generation 全量
  再投影；
- event -> receipt discovery，BLOCKED -> PENDING；
- 同 source revision 严格顺序、不同 source 可并行；
- lease 过期接管、claim token fencing、bounded retry；
- 前序 terminal failure 阻断后序，显式 replay 后恢复；
- admin replay 的状态/version/reason 审计与 `SUPERSEDED` 禁止 replay；
- UPSERT/DELETE/恢复的 expected revision；
- timeout 后精确重放；409 exact-current 补记成功、diverged fail-closed；
- v1 兼容、v2 prompt snapshot、8KiB/16KiB 边界；
- 历史 backfill 幂等、缺 prompt 分类、不重复 revision；
- dispatcher 关闭时 Asset CRUD 和 outbox append 继续成功；
- HTTP 调用不在事务内；
- API Key、URL、raw response 不进入表/日志。

### 12.2 spring-ai-rag 契约

- external create/update/tombstone/replay 与严格 CAS；
- metadata containment 同时限制 `ASSET_VIDEO` 与当前 `scopeOwnerKey`，共享 Collection 时也不
  返回其他项目候选；
- `ASYNC` 返回后 keyword current、vector eventually current；
- restricted API Key 不能读写其他 Collection；
- Search 结果包含 connector 所需 metadata，但不包含媒体 URL；
- 现有 external document E2E 全量回归。

### 12.3 跨服务 E2E

- 两个独立 PostgreSQL 数据库、独立端口、deterministic embedding；
- 真实 HTTP 创建 RAG Collection/API Key、激活 binding；
- 真实生成 Asset fixture append event；
- 等待 delivery、查询 RAG 文档和 Search；
- 更新描述后旧 query 不再命中/新 query 命中；
- 软删除后 tombstone，恢复后同一 external identity 回归；
- RAG 服务停机时积压，恢复后自动收敛；
- 数据库只读查询证明 receipt、revision 和 document identity；
- 不调用真实 LLM、视频、图片或音乐 Provider。

### 12.4 前端

- `npx tsc --noEmit`、生产 build、设计系统和颜色/alignment 门禁；
- Mock Playwright 断言 URL query、请求参数、source filter、result rows、empty/error/degraded；
- 非 Mock fetch/跨服务 Playwright 断言网络 JSON 和 DOM；
- 不使用截图作为验收证据。

### 12.5 基本硬门槛

scenemill：

```bash
cd api-server
mvn clean compile test-compile
mvn validate
# 本任务 PostgreSQL 集成脚本

cd ../web-studio
npx tsc --noEmit
npm run build
npm run test:design-system
npm run check:colors:strict
npx playwright test --config=playwright.mock.config.ts \
  e2e/worldpage-video-pagination.mock.spec.ts
npx playwright test --config=playwright.world-page-fetch.config.ts \
  e2e/worldpage-video-pagination.fetch.spec.ts \
  e2e/worldpage-character-location-pagination.fetch.spec.ts
```

spring-ai-rag：

```bash
mvn clean compile test-compile
mvn test
./scripts/external-documents-e2e.sh
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh

cd spring-ai-rag-webui
npm run build
```

两仓均执行 `git diff --check`。任何合并远端 main 后都要对最终组合重跑受影响门槛。

## 13. 可观测性、发布与恢复

### 13.1 指标

- immutable events 总量与 oldest undiscovered age；
- delivery pending/processing/blocked/failed/delivered；
- oldest pending age、retry count、lease recovery；
- remote HTTP latency/status/error code；
- per-project binding state；
- semantic search success/degraded/latency/result count 与实际 `searchMode`；
- v2 backfill scanned/enriched/skipped/failed/remaining。

### 13.2 开关顺序

推荐独立开关：

```text
capture v2 text
delivery discovery
delivery worker
semantic search API
semantic search UI
history backfill
```

上线顺序：

1. schema + 代码，所有副作用开关关闭；
2. 配置 target，创建受限 Collection/API Key，激活一个测试项目 binding；
3. 开 discovery，观察 BLOCKED/PENDING；
4. 开 worker，验证 keyword；
5. 验证 vector readiness 后开搜索 API/UI；
6. 最后小批运行历史 backfill。

### 13.3 回滚

- 关闭 worker/search/backfill 不影响 Asset CRUD，immutable event 和 receipt 保留；
- 远端错误文档通过来源 tombstone 修复，不直接删 RAG 内部表；
- 已执行 Flyway 不修改、不 repair checksum；
- binding 停用后停止新投递和搜索，但不自动删除远端文档；需要撤销时使用显式、可审计 tombstone
  run；
- 不自动跳过失败事件，不把数据库状态手工改成 DELIVERED。

## 14. 风险与默认处理

| 风险 | 默认处理 |
|---|---|
| RAG target 凭据越权 | target 只来自配置；每 tenant 独立 restricted key；激活时验证 Collection |
| 事件乱序 | per-source revision claim gate；409 divergence fail-closed |
| HTTP 超时未知结果 | 同 sourceRevision 完整重放；读取远端对账 |
| event log 被改成队列 | delivery state 独立；event 继续 immutable |
| prompt 泄漏内部事实 | 只读 generation params 的 author-visible prompt；字段/字节 allow-list |
| RAG 返回越权 Asset | 只取 assetId；scenemill 按 tenant/project/status 重新加载 |
| embedding 服务波动 | `ASYNC` + keyword-first；搜索返回 readiness/degraded |
| 搜索造成无界成本 | min query、debounce、top-k<=50、无自动 Agent/LLM |
| 历史 backfill 压垮正常事件 | 单独开关/预算，正常 mutation 优先，幂等 checkpoint |
| binding 改目标造成重复 | ACTIVE binding 不原地改；新 generation 必须显式全量再投影 |
| 停用时旧 worker 已完成 HTTP | T2 由 binding revision fence；记录旧目标可能已接受，后续只走显式 tombstone run |

## 15. 实施后的三轮收敛检查

全部基本集成验证通过后才开始：

1. **事件与一致性**：事务边界、immutable log、receipt、ordering、lease、CAS、tombstone、
   backfill、RLS。
2. **跨服务与安全**：target/credential、Collection ACL、HTTP replay、payload 隐私、Asset
   authoritative reload、降级。
3. **API/UX/交付**：Search 契约、URL state、Mock/非 Mock DOM 与网络断言、指标、开关、
   双语长青文档和回滚。

任一轮发现影响正确性、成本安全、兼容性、权限或数据一致性的问题：立即修复、重跑受影响硬
门槛，计数归零。只有连续三轮无实现修改才可结束。

## 16. 完成定义

本批完成必须同时满足：

1. 上一阶段 Gate 0 已闭合；
2. v2 文本、binding、delivery、跨服务 projection、Search API/UI 和历史 backfill 全部交付；
3. 两仓专项 PostgreSQL/HTTP、clean compile、前端 build、Mock 与跨服务 E2E 通过；
4. 不依赖人工截图或用户首次手测发现主问题；
5. 连续三轮实现检查达到 3/3；
6. 稳定事实进入两个仓库对应的双语长青文档；
7. 本 plan/progress 归档；
8. 子模块先提交/推送，父仓与 spring-ai-rag 分别 merge 最新远端 main、复验、提交和 push；
9. 最终相关 worktree 干净，若有后来出现的外部 WIP则保留并明确报告。

## 17. 相关索引

spring-ai-rag：

- [外部文档同步 Client 指南](../external-document-sync-client-guide-zh-CN.md)
- [REST API](../rest-api-zh-CN.md#external-documents-idempotent-synchronization)
- [项目上下文](../project-context-zh-CN.md)
- [后续 TODO](../TODO-zh-CN.md)

scenemill：

- `docs/drafts/video-generation/生成视频自动资产化与RAG可靠事件发布-第一阶段实施规划-2026-08-19.md`
- `docs/drafts/video-generation/生成视频自动资产化与RAG可靠事件发布-第一阶段实施进度-2026-08-19.md`
- `docs/drafts/video-generation/素材RAG检索投影异步构建与事务性发件箱设计-2026-08-15.md`
- `docs/drafts/video-generation/素材RAG-M1项目级Metadata关键词MVP-实施规划-2026-08-16.md`
