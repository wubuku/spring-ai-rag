# 首个外部客户端接入历史进度

> 对应历史规划：[2026-08-21_FIRST_EXTERNAL_CLIENT_INTEGRATION_PLAN.md](2026-08-21_FIRST_EXTERNAL_CLIENT_INTEGRATION_PLAN.md)
>
> **状态：已归档。** 本文只保留对当前项目仍有参考价值的通用结论。

## 1. 当时完成的工作

- 探索了外部权威数据通过 outbox/dispatcher 投影到 RAG 的可靠交付模型。
- 冻结了稳定外部身份、opaque revision、严格 CAS、精确重放、tombstone 和恢复语义。
- 明确 Collection 同时承担投放目标和 ACL 边界，`sourceNamespace` 不是授权边界。
- 明确 query principal 与 dispatcher principal 分权，客户端数据面不得使用 root。
- 明确 RAG 只保存 allow-list 检索投影，私有媒体和内部协议字段保留在客户端。
- 明确搜索命中必须由客户端回源校验并生成浏览器安全 DTO。
- 规划了 Mock、真实 PostgreSQL、真实 HTTP、重启恢复和真实模型提供商的黑盒验收。

## 2. 对当前项目的长期影响

后续实现把上述原则沉淀为通用能力：

- 外部文档稳定地址和 revision CAS；
- 异步持久化 embedding job 与 keyword-first 生命周期；
- Collection allow-list、`RAG_READ` / `RAG_WRITE` capability；
- 业务 binding preflight、真实 HTTP 合同和 managed-principal 验收；
- 双 Collection、凭据轮换、浏览器 DTO 清洗和重启恢复测试。

当前契约和操作方法以以下长青文档为准：

- [业务服务接入指南（中文）](../../business-client-integration-zh-CN.md)
- [Business Service Integration Guide](../../business-client-integration.md)
- [REST API（中文）](../../rest-api-zh-CN.md)
- [REST API](../../rest-api.md)
- [测试指南（中文）](../../testing-guide-zh-CN.md)
- [Testing Guide](../../testing-guide.md)

## 3. 不再保留的历史细节

归档稿不再记录任何特定客户名称、外部仓库路径、业务实体或前端模块。那些信息不是当前 RAG
项目的维护前提，也不能成为通用 API 和架构的事实来源。
