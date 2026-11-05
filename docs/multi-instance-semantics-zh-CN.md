# 多实例语义

项目默认按单实例运行，但 `postgresql` profile 支持多个应用副本连同一个 PostgreSQL。
本文说明哪些状态跨副本共享、哪些仍是进程本地，以及部署模板在 `replicas > 1` 时提供了什么保护。
配置项细节见[配置参考](configuration-zh-CN.md)；部署步骤见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 共享什么、不共享什么

| 能力 | 协调方式 | 多副本行为 |
|------|----------|------------|
| 嵌入任务 | 条件写入 claim + lease | 同一任务只被一个副本领取；过期 lease 可被接管；旧 owner 写入被 fencing |
| 外部文档同步 | 数据库唯一索引 | 同一 Collection + namespace 同时只有一个 `ACTIVE` run |
| 分块自动升级 | claim + lease + CAS | 同一文档只被一个副本升级；lease 过期后可恢复 |
| 聊天会话 | `rag_chat_session_lease` | 同一 session 同时只有一个请求提交；崩溃后过期 lease 可被接管 |
| SSE 跨实例续传 | `rag_chat_turn_operations` 快照 + `Last-Event-ID` | 带 `Idempotency-Key` 的流可被任意副本按事件 id 续传；无 key 实时流限定单实例 |
| API 限流 | PostgreSQL 共享桶 | 计数跨副本共享，配额不会被副本数放大 |
| LLM 预算 | 数据库条件写入 | 两副本共享同一窗口，不双花 |
| HNSW 索引启动 | 只读观测构建进度 | 只构建一次；不删除其他副本正在构建的索引 |
| 后台清理 | 有界幂等删除 + 会话 lease | 可能在多个副本同时触发，但每行只删除一次 |
| Admission 并发池 | 进程内信号量 | **每副本独立**，总并发约为副本数乘以配置上限 |

结论：持久任务、租约、配额和清理效果由 PostgreSQL 收敛；进程内缓存、Admission 并发池和
`rate-limit.backend=local` 不具备跨副本共享语义。

## 索引与文档处理

### 嵌入任务

`EmbeddingJobRepository.claim` 使用条件更新领取任务：只有一个副本能把状态从 `QUEUED`
推进到 `RUNNING`。运行期间持有带过期时间的 lease，心跳失败或副本崩溃后，其他副本可在
lease 过期后接管。旧 owner 的写入和心跳会被 owner token fencing 拒绝，不会覆盖新 owner 的进度。

### 外部文档同步

`rag_document_sync_runs` 上有部分唯一索引，同一 `collection_id + source_namespace`
只允许一个 `status = 'ACTIVE'` 的 run。第二个副本无法再插入一个 active run，只能复用或等待
已有 run，因此 upsert/删除不会在两个实例上重复执行。

### 分块升级

自动升级任务通过 `rag_chunking_upgrade_tasks` 的 claim 与 120 秒 lease 协调。
扫描可以发生在每个副本上，但同一 `document_id + embedding_profile_id` 只有一个副本能拿到
`RUNNING` 状态；lease 过期后其他副本可重试，旧 owner 的完成写入会被条件更新拒绝。

### HNSW 索引启动

每个副本启动时都会调用 `EmbeddingProfileIndexManager.ensureIndex`。旧实现遇到
`CREATE INDEX CONCURRENTLY` 冲突会立即 `DROP INDEX CONCURRENTLY` 重来，可能删掉另一个
副本正在构建的索引，甚至让两个副本互相打断。

当前实现不依赖锁：

- 索引已存在且有效：直接返回；
- 索引不存在：尝试 `CREATE INDEX CONCURRENTLY IF NOT EXISTS`；
- 索引存在但无效：连续四次（默认间隔 250 毫秒）观测到“无效且没有任何构建进度”才视为
  崩溃残骸，删除后重建；
- `pg_stat_progress_create_index` 显示其他副本正在构建：等待其完成，最多等待 60 秒。

因此滚动扩容或同时重启多个副本不会重复做全量 HNSW 构建，也不会删除在途索引。

## 后台清理

`DataGovernanceRetentionCoordinator` 在每个副本上按同一 cron 触发保留清理。项目没有
“只在一个副本运行调度器”的单例机制；多副本语义依赖每类清理本身的条件删除：

- 审计、客户端错误、评估、LLM 用量、检索诊断、集成观测、预算历史都按主键和有界批量删除，
  并发执行时同一行只会被一个事务删除，另一个事务得到 0 行；
- 聊天历史 TTL 清理按 session 获取 maintenance lease，再执行有界删除；活跃 session 会被跳过；
- 幂等记录清理同样使用有界条件删除；
- rate-limit 桶清理使用 `ctid` 定位与批量上限，重复执行是幂等的。

因此清理是“可能重复触发、效果幂等”，不是“全局只执行一次”。如果运维需要严格单例调度，
应在部署层只给一个副本启用调度（例如单独的 worker 部署），而不是依赖应用内互斥。

## 配额与限流

### API 限流

`rag.rate-limit.backend=postgresql` 使用 `rag_api_rate_limit_bucket` 的
`INSERT ... ON CONFLICT DO UPDATE ... WHERE request_count < limit RETURNING`：所有副本
共享同一个 principal 的固定 UTC 分钟窗口，接受次数不会变成副本数乘以 `requests-per-minute`。
store 故障时 fail closed 返回 `503`，不会降级成本地计数。

`rag.rate-limit.backend=local` 的计数在进程内，只适合明确的单实例环境。启用多实例声明后，
若同时满足 `rate-limit.enabled=true` 与 `backend=local`，应用会在启动期拒绝启动。

### LLM 预算

预算窗口与 reservation 都通过数据库条件写入更新，两个 `BudgetReservationService` 实例
共享同一窗口。并发预留、结算和过期回收不会双花；结算安全细节见
[LLM 预算结算安全](budget-settlement-safety-zh-CN.md)。

### Admission 并发

`AdmissionService` 是进程内并发池，不是共享配额。部署 N 个副本时，全局并发上限约为
`N × 配置上限`。需要严格全局并发时，应调低单副本上限，或在网关层统一控制；
本文不把它描述为跨实例共享能力。

## 聊天会话

同一 `owner_principal_id + session_id` 的聊天请求通过 `rag_chat_session_lease` 单飞：

- 第一个副本用条件写入获取 lease；另一个副本并发请求得到 `SESSION_BUSY`（HTTP 409），
  不会读取或写入该会话的历史；
- 正常完成时，history 与 memory 在同一事务提交，随后释放 lease；
- 副本崩溃后 lease 到期，另一副本可接管；旧副本的提交会因 lease token 不匹配而失败，
  返回 `CHAT_SESSION_LEASE_LOST`，不会把迟到结果写进接管者的历史。

因此同一 session 在两实例并发聊天时，历史不串、不丢；失败方得到明确的冲突错误。

## SSE 跨实例续传

带 `Idempotency-Key` 的聊天流（`/api/v1/rag/chat/stream`）**支持跨实例续传**：

- 服务端先把整轮结果与事件快照写入 `rag_chat_turn_operations`，再按事件 id 发送；
  客户端中途断开不会丢掉已生成的快照，也不会取消服务端的 keyed turn；
- 客户端带同一个 `Idempotency-Key` 与 `Last-Event-ID` 打到另一副本，副本从数据库读快照，
  只补发 id 大于游标的事件，并返回 `X-RAG-Idempotent-Replay: true`；
- 非法或缺失的 `Last-Event-ID` 降级为整轮重放，不会返回空流。

不带 `Idempotency-Key` 的实时流**限定单实例语义**：它依赖发起进程内的 emitter，
`Last-Event-ID` 会被忽略，请求在另一副本上按新 turn 整轮生成。这是明确行为，不是静默丢帧；
需要跨实例续传的客户端必须使用 keyed turn。发起副本在流中途崩溃时，无 key 的流无法续传。

`verify-multi-instance-e2e.py` 在 A 上断开 keyed 流后，用 B 带 `Last-Event-ID` 续传，
断言“首段 + 续传 = 完整回放”且 id 不重复；同时验证无 key 流在 B 上整轮新生成。

## 崩溃恢复

需要接管的持久状态都使用“过期即可接管”的 lease，而不是永久卡死的锁：

- 嵌入任务：lease 过期后其他副本重新 claim；
- 分块升级：`RUNNING` 且 `lease_expires_at < NOW()` 的任务被放回 `RETRY/COOLDOWN`；
- 聊天会话：过期 lease 被新请求接管；
- 失败副本留下的无效 HNSW 索引：其他副本确认无构建进度后删除重建；
- 告警投递、Collection purge、嵌入 Profile 回填等持久 worker 同样使用 claim/lease 与幂等重试。

不需要人工 SQL 解锁；恢复由下一次轮询或下一次请求自动完成。

## 部署模板

### Helm

`k8s/values.yaml` 默认 `replicaCount: 1`，但 `autoscaling.enabled: true`、最大 5 个副本；
`k8s/values-production.yaml` 使用 3 个副本和 3–10 的 HPA。模板已经存在 `replicas > 1` 路径，
因此不能把项目当成“只支持单实例”部署。

`k8s/templates/deployment.yaml` 在 `replicaCount > 1` 或 HPA 开启时自动注入：

```yaml
env:
  - name: RAG_MULTI_INSTANCE_ENABLED
    value: "true"
```

Deployment 使用 `Recreate` 策略：稳态可以多副本，但升级时所有旧 Pod 先停止，不提供滚动升级的
零停机语义。生产 values 同时配置 `podDisruptionBudget`（`minAvailable: 2`）保护节点驱逐时的可用性。

### Docker Compose

`docker/docker-compose.yml` 只定义一个应用容器，没有副本数或伸缩路径。使用 Compose 时多实例
语义仍然成立，但需要外部编排（多个容器 + 同一数据库 + 前面加负载均衡）；Compose 文件本身不提供。

## 启动断言

`rag.multi-instance.enabled` 声明“本进程与其他副本共享同一个 PostgreSQL”。开启后，
启动期会拒绝会被副本数放大的本地限流配置：

```yaml
rag:
  multi-instance:
    enabled: ${RAG_MULTI_INSTANCE_ENABLED:false}
```

被拒绝的组合是 `rag.rate-limit.enabled=true` 且 `rag.rate-limit.backend=local`；错误信息会
要求改为 `backend=postgresql`、`strategy=principal`，而不是静默把配额放大 N 倍。

## 验证

多实例语义由 gated PostgreSQL 套件验证，默认不进入 `mvn test`。这些套件在同一 JVM 内
创建两个独立的组件实例（两个 store、两个清理服务、两个聊天协调器、两个索引管理器），
直接验证数据库协调层；真实双进程、不同端口的 HTTP 验收由
`scripts/verify-multi-instance-e2e.py` 与 `scripts/verify-managed-api-principals.sh` 覆盖。

| 命令 | 覆盖 |
|------|------|
| `./scripts/verify-multi-instance-e2e.py` | 两个真实进程（默认 18086/18087）同一 PostgreSQL：并发 Flyway 启动只应用一次、keyed SSE 跨实例 `Last-Event-ID` 续传不重不漏、无 key 流整轮新生成、同幂等键并发只一次、两 worker 同批任务不双写、SIGKILL 后 lease 过期接管、A 写 B 立即可见、吊销凭据即时生效、共享限流不被副本数放大 |
| `./scripts/verify-gated-it.sh MultiInstancePostgresIntegrationTest` | 共享限流配额、并发 TTL 清理、并发 HNSW 启动、崩溃残骸修复 |
| `./scripts/verify-gated-it.sh ChatSessionPostgresIntegrationTest` | 两实例并发抢占同一 session、崩溃租约接管与旧 owner fencing |
| `./scripts/verify-gated-it.sh EmbeddingJobsPostgresIntegrationTest,DocumentSyncRunsPostgresIntegrationTest` | 嵌入任务 claim/lease 与同 namespace 单 active run |
| `./scripts/verify-gated-it.sh LlmBudgetAndGovernancePostgresIntegrationTest` | 两实例共享预算窗口、不双花 |
| `./scripts/verify-managed-api-principals.sh` | 两个真实后端（默认 18181/18182）共享一次性 PostgreSQL，验证跨实例配额与身份契约 |

nightly 的 gated 层会运行全部套件；门控开关与套件清单见[测试指南](testing-guide-zh-CN.md)。

## 边界与限制

- 清理调度会在每个副本触发；项目保证幂等，不保证单例执行。
- 无 `Idempotency-Key` 的 SSE 实时流不承诺跨实例续传：另一副本会把它当作新 turn 整轮生成。
- Admission 并发池是每副本行为，不是全局配额。
- 多副本启动时 HNSW 构建等待上限为 60 秒；超过上限会启动失败，避免无限等待。
- Helm 的 `Recreate` 策略不提供滚动升级零停机；需要零停机时应另行设计兼容的迁移与滚动策略。
- Flyway 在每个副本启动时执行迁移，由 Flyway 的迁移锁串行化；不要手工改写已执行的迁移。
- 以上语义以 `postgresql` profile 为前提；使用其它存储后端时不能假设跨副本共享。
