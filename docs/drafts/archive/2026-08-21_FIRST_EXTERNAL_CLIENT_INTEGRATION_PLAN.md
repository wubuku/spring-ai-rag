# 首个外部客户端接入历史规划

> **状态：历史归档。**
>
> 本文记录早期外部客户端接入所验证的通用设计问题，不描述任何特定客户、业务仓库或产品
> 工作流。当前事实以双语长青文档、公开 API 文档和代码为准。
>
> 历史进度：[2026-08-21_FIRST_EXTERNAL_CLIENT_INTEGRATION_PROGRESS.md](2026-08-21_FIRST_EXTERNAL_CLIENT_INTEGRATION_PROGRESS.md)

## 1. 当时要解决的问题

典型业务客户端已经拥有自己的权威数据、事务和权限模型，需要把其中一部分可检索投影可靠地
同步到独立 RAG 服务，并在搜索命中后回源生成业务 DTO。主要风险包括：

- 业务事务成功不能依赖 RAG 服务或模型提供商实时可用；
- 重试、超时和进程重启不能产生重复文档或乱序覆盖；
- RAG Collection 必须是明确的投放和授权边界；
- 私有媒体引用、凭据和内部事件字段不得进入可检索投影；
- RAG 命中不是业务权限或媒体事实，客户端必须回源校验；
- embedding 可以最终一致，但关键词检索和业务写入不能被远程模型故障阻断。

## 2. 推荐的通用接入形态

```text
权威业务对象
  -> immutable mutation event / outbox
  -> durable delivery receipt with lease and bounded retry
  -> stable external-document mutation
  -> keyword-first, vector-eventually retrieval
  -> client-side authoritative reload and DTO sanitization
```

服务端目标和 credential 由部署配置提供；业务绑定只引用预声明 target alias 和
Collection key，不能携带任意 URL 或 root credential。

## 3. 冻结的身份与版本原则

- 外部地址使用 `collectionKey + sourceNamespace + externalId`。
- `externalId` 来源于稳定业务身份，不使用临时 URL、任务 ID 或可变标题。
- `sourceRevision` 为 opaque、可精确重放的来源版本。
- 更新和 tombstone 使用 `expectedSourceRevision` 做严格 CAS。
- 请求超时后先精确重放；收到冲突时通过 GET 对账，不做 last-write-wins。
- 删除使用 tombstone；后续新 revision 可以恢复同一个 `documentId`。
- 新绑定或迁移必须显式读取远端当前状态，不能猜测 CAS 基线。

## 4. 投影与安全边界

RAG 只接收回答和检索所需的受控文本、稳定标量和 allow-list JSON payload。以下内容不得进入
投影、日志或浏览器 DTO：

- API key、token、secret、内部租户密钥；
- 公共、私有或签名 URL；
- 对象存储 key、内部媒体对象 ID；
- 内部事件、候选、去重和 fingerprint 材料；
- 提供商原始响应、隐藏提示词和未经筛选的请求载荷。

搜索结果只提供候选身份和相关性。客户端必须重新加载权威对象、执行自己的租户/项目权限和
状态校验，再生成最终 DTO。

## 5. 可靠投递原则

- 业务事务只追加不可变事件，不在数据库事务中执行 HTTP。
- 每个消费者维护独立 receipt、lease、attempt 和 terminal state。
- 同一稳定身份按 revision 串行；前序 terminal failure 阻断后序事件，等待显式 repair。
- HTTP 自动重试必须关闭，由 durable receipt 驱动有限退避。
- 凭据轮换不改变 principal 的 Collection allow-list 或 capability。
- 旧 binding generation 必须被 fencing，不能在切换后继续写入。

## 6. 查询和降级原则

- query principal 使用 `RAG_READ`，dispatcher 使用 `RAG_READ,RAG_WRITE`。
- 多 Collection 查询必须显式绑定 allow-list；数据面不能回退到 root。
- payload scope 分路查询后由客户端确定性合并。
- embedding 未完成或 provider 暂时失败时，当前正文仍可按公开 lifecycle 进入
  `KEYWORD_ONLY`；向量就绪后再提升为 `READY`。
- RAG 不可用时，客户端应保留普通业务浏览和写入能力，并明确降级状态。

## 7. 验收模型

接入验收应从黑盒客户端视角覆盖：

1. 预检 readiness、OpenAPI、principal capability 和精确 Collection allow-list。
2. 两个 Collection、只读 query principal 和相互隔离的读写 dispatcher。
3. 创建、精确重放、CAS 更新、冲突、tombstone、恢复和再次删除。
4. 查询/dispatcher credential 轮换以及旧 key 立即失效。
5. 异步 embedding 收敛、关键词/向量检索和 payload scope 隔离。
6. 服务重启后的 principal、外部身份、持久化 job 和精确重放连续性。
7. 清洗投影和浏览器 DTO 不泄露私有媒体或内部协议字段。
8. Mock 门槛通过后，使用真实 Chat/Embedding provider 验证 JSON 与 SSE 路径。

## 8. 后续演进

本轮历史探索还识别出以下独立演进方向：

- principal provisioning 的幂等创建；
- machine-readable capability discovery；
- 独立 tenant/connector 授权层级；
- 受保护的外部托管文档 purge 与 Collection 退役流程；
- 多 Collection 覆盖策略和更完整的质量评估。

这些事项必须分别规划，不能把客户侧业务模型引入通用 RAG 核心。
