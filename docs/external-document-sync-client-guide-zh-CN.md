# 外部文档同步 Client 指南

> [English](external-document-sync-client-guide.md) | [中文](external-document-sync-client-guide-zh-CN.md)

本文给出外部系统接入 spring-ai-rag 的推荐方式：外部系统拥有文档主数据，新增、修改和删除
安全地连带更新 RAG 服务中的分块、全文索引和 embedding。

当前覆盖 webhook/CDC 增量同步，以及来源权威全量快照对账 API。

## 1. 选择稳定的来源身份

当前 API 使用以下三元组作为一份 RAG 文档投放的稳定外部地址：

```text
collectionKey + sourceNamespace + externalId
```

| 字段 | 规则 |
|---|---|
| `collectionKey` | 目标 Collection 的稳定业务 key。普通外部文档端点必填，且必须指向真实存在的活动 Collection。JSON record upsert 仍兼容 deprecated `collectionId` 输入，但新 Client 和后续外部地址操作都应使用解析后的 key。 |
| `sourceNamespace` | 可选的稳定 connector/来源所有权空间，例如 `cms-main`、`erp-products`。省略或空白会规范化为 `default`；多个 connector 共用一个 Collection 或使用来源对账时，应选择并显式发送稳定值。 |
| `externalId` | 来源对象不可变 ID；不要从标题或正文生成。 |
| `sourceRevision` | 完整期望状态的 opaque 版本，例如 ETag、行版本、commit ID 或规范状态 hash。 |
| `expectedSourceRevision` | 更新和 tombstone 的 CAS 前置版本。 |

当前最大长度已经为调用方预留了足够空间，且后续不得缩短：
`collectionKey` 和 `sourceNamespace` 最多 128 个字符，`externalId` 最多 255 个字符。
这些是调用方管理的标识符，不是服务端生成的 hash。

`sourceNamespace=default` 是字段省略或空白时使用的兼容命名空间，不代表默认
Collection。`collectionKey` 是所有外部地址中规范的 Collection 组成部分；`null`
Collection 归属只用于本地/未归属文档，不能作为外部同步的默认目标。

必须区分：

- **外部地址**：`collectionKey + sourceNamespace + externalId`，用于每次查询、upsert 和删除；
- **来源对象 ID**：`sourceNamespace + externalId`，由 connector 从来源系统稳定地产生；
- **状态版本**：`sourceRevision`，表示该地址当前投放的完整期望状态；
- **内部 ID**：`documentId`，只用于服务端诊断和运维。

这里把 `collectionKey` 放入外部地址，是因为当前项目没有独立 tenant 资源，Collection
同时承担投放目标和 ACL 边界；同一个来源对象也可能被有意投放到不同 Collection。不要把
三元组拼成一个不可解析字符串，也不要认为 `externalId` 必须在整个服务中全局唯一。

`sourceNamespace` 是身份边界，不是授权边界。两个互不信任的 connector 应使用不同
Collection，因为对某 Collection 有写权限的 key 并不会被限制到某一个 namespace。

RAG 服务内部 `documentId` 只用于诊断。connector 必须持久化并使用上述外部身份。

### Collection 迁移边界

当前普通 upsert 按目标三元组定位文档，**不能**把已有外部文档原子移动到另一个
Collection。只修改请求中的 `collectionKey` 会寻址另一份投放，可能创建第二个文档；
它不是原文档的普通更新。

在新增显式迁移 API 前，需要移动时只能：

1. 用新 revision tombstone 旧三元地址；
2. 在目标 Collection 用新地址 upsert 完整状态；
3. 分别等待两个操作收敛。

该兼容流程不是原子的，会产生新的内部 `documentId`、独立版本历史和新的派生任务。
对不能接受短暂重复/空窗或必须保留历史的系统，应保持 Collection 归属不变，等待受控的
原子迁移能力，不要用普通 upsert 模拟移动。

## 2. 增量 CRUD 契约

### 创建或更新普通文本

调用 `POST /api/v1/rag/documents/upsert`。

upsert 表示**完整期望状态**，不是 merge patch。必须发送 `title`、`content`、来源字段、
metadata 和明确的 `embeddingPolicy`。不要依赖服务端保留旧 revision 中遗漏的可选字段。

新 identity 不发送 `expectedSourceRevision`；后续 revision 必须发送服务端最后接受的版本：

```json
{
  "collectionKey": "customer-42:manual:v3",
  "sourceNamespace": "cms-main",
  "externalId": "article:10001",
  "sourceRevision": "etag:8b4d9f",
  "expectedSourceRevision": "etag:7a3c21",
  "title": "退款政策",
  "content": "当前退款政策是……",
  "source": "cms",
  "documentType": "markdown",
  "metadata": {"locale": "zh-CN"},
  "embeddingPolicy": "ASYNC"
}
```

生产默认开启严格外部 CAS：已有 identity 的新 revision 未携带
`expectedSourceRevision` 时会被拒绝；已经接受的同 revision 精确重放仍保持幂等。

### 来源删除

调用 `DELETE /api/v1/rag/documents/by-external-id`，发送完整身份、新的删除 revision 和
当前预期 revision。

该操作创建 tombstone：文档立即退出检索，但稳定身份和审计历史保留。之后用新的 revision
UPSERT 会恢复同一个内部文档。这与本地文档的永久删除有意保持不同。

### JSON 结构化记录

JSON record 通过 `/api/v1/rag/json-records` 使用相同的身份、revision、CAS、精确重放和
tombstone 模型。调用者分别提供：

- `retrievalText`：用于分块和 embedding 的自然语言描述；
- `jsonbPayload`：按 JSONB 返回和过滤的结构化值。

只修改 payload 不调用 embedding provider；修改 `retrievalText` 才会调用。

## 3. CRUD 与派生索引如何联动

文档主记录是真相源，chunk 和 embedding 是派生状态。

| 变化 | 文档 revision | 旧检索结果 | 新 embedding 任务 |
|---|---:|---|---|
| 使用 `SYNC`/`ASYNC` 创建 | 增加 | 不适用 | 有 |
| 修改 `content` / `retrievalText` | 增加 | 立即 stale 并退出检索 | 有 |
| 只改标题/来源/metadata | 增加 | 当前文档属性立即生效 | 无 |
| 只改 `jsonbPayload` | 增加 | 当前 payload 立即生效 | 无 |
| 普通 upsert 改 `collectionKey` | 不是同一身份的更新 | 旧地址仍存在，除非显式 tombstone | 新地址按创建处理 |
| disable/tombstone | 增加 | 立即退出检索 | 无 |
| restore | 增加 | 已 fresh 则复用，否则按策略排队 | 仅需要时 |
| 本地永久删除 | 主记录删除 | 立即退出检索 | 待执行任务与派生数据被取消/清理 |

新正文索引期间，服务不会返回旧 content hash 的 chunk。在当前存储模型下，该文档会暂时同时
退出关键词和向量检索，直到新的派生状态进入 `READY`。

`embeddingPolicy` 控制调度与等待：

- `ASYNC`：connector 默认推荐；文档变更与持久化任务提交后立即返回；
- `SYNC`：调用方确实需要对同一持久化任务做有界等待时使用；
- `SKIP`：明确让新正文保持 `NOT_REQUESTED`。

## 4. 增量投递算法

对每个来源事件：

1. 构造完整期望状态。
2. 分配不可变的投递 `eventId`。
3. 发送来源对象的新 opaque revision，并把最后接受的 revision 作为
   `expectedSourceRevision`。
4. 成功后原子记录投递 checkpoint 和已接受 revision。
5. 网络超时后精确重放同一请求。
6. 遇到 `409` 时停止该 identity，重新读取来源和 RAG 当前状态，再生成新事件；禁止把旧事件
   当作 last-write-wins 覆盖。
7. 下游流程要求可检索时，另外等待 lifecycle/readiness 收敛。

单来源 Collection 可以省略 `sourceNamespace`，其语义等同于发送 `default`。如果一个
Collection 由多个 connector 共同写入，必须在第一次投递前选择稳定的显式 namespace，并在
该 connector 的 identity 生命周期内保持不变。

不要在 RAG client 中按字符串或数字比较 opaque revision 的新旧；顺序由来源系统负责。

## 5. 重试与错误分类

| 结果 | Client 行为 |
|---|---|
| HTTP 2xx | 记录成功 checkpoint；需要可检索时另查 lifecycle。 |
| 网络超时/断开 | 用有界退避精确重放同一请求。 |
| `408`、`425`、`429`、`5xx` | 指数退避加 jitter；在合理上限内遵守 `Retry-After`。 |
| `409` | CAS 或精确重放冲突；重读来源和 RAG 状态，禁止自动覆盖。 |
| `400` | 事件或契约错误；记录安全错误码后进入 dead letter。 |
| `401` / `403` | 停止并修复凭据或 Collection ACL。 |
| tombstone 返回 `404` | 对账来源/client 状态，禁止静默转成创建。 |

文档变更成功后的 embedding provider 失败不会回滚来源文档。lifecycle 会进入 `FAILED`，
旧 chunk 继续被排除；可以重放同一已接受 revision，或通过 embedding 运维端点重试。

## 6. 可检索就绪

mutation 成功表示来源状态已被接受，不一定表示已可检索。

使用文档 lifecycle 读模型：

- `READY`：当前正文可检索；
- `INDEXING`：持久化任务排队或执行中；
- `FAILED`：当前派生失败；响应会标明是否可重试；
- `NOT_REQUESTED`：调用方使用了 `SKIP`；
- `DISABLED`：已停用或 tombstone。

批量流程优先查询 Collection embedding readiness，不要逐文档高频轮询。

## 7. 来源权威全量快照对账

当来源系统可以生成完整且一致的视图时，应使用 Sync Run 协议，不要从一个可能不完整的
批次推断删除：

1. 调用 `POST /api/v1/rag/document-sync-runs`，发送稳定的 `clientRunId`、显式的
   `snapshotMode`、`missingPolicy`，以及 opaque 的 `X-RAG-Sync-Lease` header。
2. 使用有界的 `batch-upsert` 发送 manifest item。item 继承 run 的 Collection 和
   `sourceNamespace`，必须包含 `externalId` 与 `sourceRevision`。
3. 调用 `preview-missing`，保存返回的 opaque preview token，再调用 `complete`。
4. 只有来源能建立一致性 cut 时才使用 `ONLINE_CUT + TOMBSTONE`。静态 manifest 的安全
   默认是 `OFFLINE_MANIFEST + NONE`。
5. `EXCLUSIVE_OFFLINE + TOMBSTONE` 必须显式发送
   `confirmExclusiveOffline=true`，并且 connector 必须保证整个 run 期间来源独占写入。
   这是有破坏性的显式操作，不能作为默认值。

TOMBSTONE run 在完成前必须用相同 fingerprint 重试失败 item。服务会保护 snapshot 边界
之后被修改的文档，执行删除阈值保护，并且不会在 run ledger 中存储正文或 JSONB payload。
每个 run mutation 都会重新检查当前 API Key 的 Collection ACL；lease token 不是绕过 ACL
的凭据。具体字段、响应和错误码见[REST API 合同](rest-api-zh-CN.md#外部快照同步-run)。

## 8. Reference Client

只依赖 Python 标准库的参考实现位于 `examples/external-sync-client/`，已实现：

- 流式读取不可变 JSONL `UPSERT` / `TOMBSTONE`；
- 对同一请求执行有界退避和 jitter；
- SQLite byte offset 与事件 checkpoint；
- 重复 `eventId` 检测；
- 恢复前校验输入文件指纹；
- POSIX 系统上 checkpoint 权限为 `0600`；
- 结构化摘要不输出正文或 secret。

运行：

```bash
export RAG_BASE_URL=http://127.0.0.1:8081
export RAG_API_KEY='...'

python3 examples/external-sync-client/sync_client.py apply-events \
  --events examples/external-sync-client/sample-events.jsonl \
  --checkpoint .external-sync/catalog.sqlite3
```

API Key 只能从环境变量读取，不接受命令行参数，也不会写入 checkpoint。处理开始后应把一个
JSONL 文件视为不可变；下一批投递使用新的文件和 checkpoint。

## 9. 上线检查清单

- 使用只允许目标 Collection 的 API Key，不使用 environment root key。
- 首次导入前先稳定来源 identity 和 revision 规则。
- 首次导入前固定 Collection 投放规则；不要把改 `collectionKey` 当作普通更新。
- 普通批量/CDC 投递默认使用 `ASYNC`。
- HTTP 成功后才持久化 checkpoint 和已接受 revision。
- 日志不得记录 API Key、完整正文和敏感 payload。
- 永久 4xx 只用 identity 与安全错误码进入 dead letter，不保存完整正文。
- 对 lifecycle `FAILED`、任务排队时间和 Collection 未就绪数量告警。
- 上线前测试重复、乱序、提交后响应超时、删除、恢复和 client 重启。
- 不得根据一个不完整批次推断权威删除。当前增量同步只删除显式 `TOMBSTONE` 事件。

精确 HTTP 字段与响应结构以
[REST API](rest-api-zh-CN.md#external-documents-idempotent-synchronization) 为准。
