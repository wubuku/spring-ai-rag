# 删除语义与数据保留

本文定义永久删除、软删除、孤儿判定与保留清理的边界。命令与接口细节见
[REST API](rest-api-zh-CN.md)；Collection 清理见[Collection 永久清理](configuration-zh-CN.md#collection-永久清理与退役)。

## 孤儿定义

孤儿指“逻辑所有者已不存在、但按语义仍必须消失”的行。按这个定义：

- **是孤儿**：文档永久删除后仍指向它的 chunks、向量、版本快照、会话来源引用、
  反馈引用。
- **不是孤儿**：聊天历史与反馈本体、评估快照、`fs_files` 文件产物。它们记录的是
  历史事实或独立内容，不随文档删除而失去意义；由各自的保留策略治理。
- 幂等记录也不是孤儿：删除文档会把 `result_document_id` 置空，操作行按 TTL 保留。

## 文档删除

- **禁用**：软删除，保留内容与历史，检索立即不再命中（`enabled = false`）。
- **外部来源删除**：写入 `source_deleted_at` 墓碑并把 `enabled` 置为 false；
  内容不物理删除，同步任务可重放。
- **永久删除**（`DELETE /api/v1/rag/documents/{id}`，要求 `expectedRevision`）：
  单事务内删除向量、显式清理 `rag_chat_history_source_document` 与
  `rag_user_feedback_document`，再删除文档行；chunks、版本、任务、处理记录由
  外键级联清理。删除后立即检索不会命中。
- 只有本地文档可永久删除；外部托管文档必须先停用来源或走集合级 purge。

## Collection 删除

存在两种语义，必须区分：

- **软删除/退役**（数字 ID 旧入口）：有外部托管文档时返回 409 拒绝；否则解除
  本地文档与集合的归属并标记集合已删除。被解除归属的文档仍可检索——这是保留
  的既有语义，不代表级联删除。
- **永久清理（purge）**：按 collection key 级联删除文档、chunks、向量、版本、
  来源/反馈引用以及相关会话与反馈，并保留 key 防止重名。预览、确认 token、
  lease 与批处理见配置文档。生产客户端应使用 purge，而不是旧数字 ID 删除。

## 会话与上传文件

- 删除会话按 `session_id` 级联删除历史行及其
  `rag_chat_history_source_document` 引用；其他会话的来源引用不受影响。
- `fs_files` 是独立的内容产物，不属于任何文档：删除文档**不会**删除导入或转换
  文件。因此文件行不是孤儿；目前也没有文件删除 API。需要清理时应先设计文件级
  生命周期，而不是在文档删除里顺手删共享产物。

## 检索与评估过滤

- 向量与关键词检索都要求 `enabled = true`，并且召回上下文额外要求
  `source_deleted_at IS NULL`，因此禁用、墓碑与永久删除的文档不会产生幽灵结果。
- 评估用例的内容指纹包含启用与禁用文档，用于检测集合内容漂移；这是评估快照，
  不是在线检索入口。已删除集合的 key 会被集合解析器拒绝，评估不会继续跑。

## 并发删除

删除与检索/导入不靠显式悲观锁：删除用 revision 条件写入，集合清理用 owner
lease、幂等键与 reservation。并发删除 vs 检索允许读事务在删除提交前看到旧行，
但提交后不得再出现；并发 purge vs 导入允许外键干净拒绝或 purge 回滚重试，
不允许“文档还指向已删集合”的中间态。门控测试会同时检查无死锁与无脏读。

## 保留与清理（决策记录）

已有清理能力覆盖：聊天历史 `rag.memory.message-ttl-days`（默认 30 天）、
聊天/文档幂等记录 24 小时、检索诊断 7 天、用量账本 400 天、审计/评估 400 天、
客户端错误 30 天、集成观测 90 天、Collection purge 预览与结果清理，以及终态
任务/预算记录的有界清理。

**决策**：本轮不新增定时清理任务。`fs_files` 产物与永久删除后的历史/评估快照
没有明确的保留所有者；在缺少文件删除 API 与产品保留策略前，凭空加定时任务会
误删可追溯证据。待文件生命周期或合规保留需求出现时，再以独立迁移与配置加入。

## 门控验证

真实 PostgreSQL 验收：

```bash
./scripts/verify-gated-it.sh DeletionConsistencyPostgresIntegrationTest
```

对应开关 `-Ddeletion-consistency.it.enabled=true`。覆盖永久删除后的
chunks/向量/版本/引用/幂等一致性、会话删除引用、删除后检索不命中，以及并发
删除 vs 检索、并发 purge vs 导入的无孤儿/无死锁终态。nightly 的 gated 套件
会与其余纯数据库套件一起运行。
