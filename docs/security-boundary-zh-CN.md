# 安全边界与租户隔离

> 适用版本：`1.0.0`（Flyway `V71`）。  
> 相关：[REST API 认证](rest-api-zh-CN.md#认证)、[配置参考](configuration-zh-CN.md)、[测试指南](testing-guide-zh-CN.md)。

本文记录数据面授权边界、已修复缺口、输入攻击面结论与验收方式。目标不是罗列所有安全能力，而是把**真实存在过的越权路径**和**回归入口**固定下来。

---

## 1. 信任模型

| 调用方 | 判定 | 数据范围 |
|--------|------|----------|
| 未启用认证（`rag.security.enabled=false`） | 无 principal | 全量（仅限隔离/本地环境） |
| root API key / `ADMIN` / 无 Collection 限制的 key | `ApiKeyCollectionAccess.isUnrestricted` | 全量，行为与历史版本一致 |
| 受限 `NORMAL` key | 绑定了 `allowedCollectionIds` | 仅允许 Collection + 本 principal 产生的内容 |
| 同一 managed principal 的多个 key | 共享 `principalId` | 共享该 principal 的可见范围 |

“受限”的定义是**显式配置了非空 `allowedCollectionIds` 或 `allowedCollectionKeys`**。空值表示不受限，这是既有契约，不能为了收紧而静默改变。

---

## 2. 已修复缺口（V71）

### 2.1 文件子系统

**问题**：`fs_files` / `fs_import_batches` 原本没有归属列，`/files/tree`、`/files/raw`、`/files/preview` 只做认证，不校验 Collection ACL。任意 `RAG_READ` key 可以枚举并下载全部上传文件，也可以拿他人的 PDF 导入 UUID 触发 embedding。

**修复**：

- 新列 `fs_files.owner_principal_id`、`fs_files.collection_id`，以及 `fs_import_batches` 同名列。
- 上传（`POST /files/pdf`、`POST /files/pdf-to-rag`）写入当前 principal；PDF-to-RAG 写入目标 Collection。
- 所有读取入口改用 `listVisibleChildren` / `getVisibleFile` / `getVisibleImportBatch`：
  - 不受限：原查询路径不变；
  - 受限：`owner_principal_id = 当前 principal` **或** `collection_id ∈ 允许集合`；
  - 历史行两者都为 `null`，对受限 key 不可见（fail-closed，不做猜测回填）。
- `/{uuid}/embed` 与 SSE 版本在触发嵌入前校验导入批次可见性，阻断“拿他人 PDF 写入自己 Collection”。

### 2.2 Evaluation / Feedback

**问题**：`/evaluation/history`、`/evaluation/report`、`/evaluation/metrics`、`/feedback/{stats,history,type}` 是全局视图。任意受限 key 可以读到他人的 `query`、`comment`、`sessionId`。

**修复**：`rag_retrieval_evaluations.owner_principal_id` 与 `rag_user_feedback.owner_principal_id`。写入时记录当前 principal；读取时：

- 不受限：全局视图不变；
- 受限：只查询 `owner_principal_id = 当前 principal`。

历史数据没有归属，受限 key 不再可见；root 仍可审计全量。

### 2.3 向后兼容

- root / `ADMIN` / 未启用认证的部署：行为不变。
- 受限 key：只会**变少**可见数据，不会新增可见数据。
- 升级不需要手工开关；V71 可空迁移随启动自动应用。

---

## 3. 输入攻击面结论

| 向量 | 结论 | 处理 |
|------|------|------|
| 路径穿越（`../`、URL 编码、反斜杠、NUL） | 路径只用于数据库查询，不拼接真实文件系统路径；但仍显式拒绝 | `PdfImportService.validateVirtualPath` 解码后拒绝 `.`/`..`/控制字符，返回 400；临时文件名再做字符白名单清洗 |
| 超大文件 / 上传炸弹 | 服务端在读取前检查 `contentLength`，流式拷贝有上限，Spring multipart 先拒超大请求 | 保持 `rag.pdf.max-upload-bytes`（默认 20MB）与 multipart 上限；测试覆盖超限返回 400 |
| 非法 `collectionKey` | 受限 key 解析未知 key 时返回 403，不受限返回 404 | 不返回“存在/不存在”以外的数据，回归矩阵覆盖 |
| SSRF（外部文档同步 URL） | **不适用**：外部同步是 push 模型（`POST /documents/upsert` 接收内容），服务端不下载客户端提供的 URL | 仓库内不存在 `java.net.URL` / `RestClient` 型的外部拉取入口；如未来新增 URL 拉取，必须只允许 `http/https` 并在解析后校验内网地址（含 DNS rebinding） |

---

## 4. 配置误用防护

`rag.security.enabled` 默认 `false`。关闭时 `/api/**` 不校验 key，把该配置暴露到公网等于公开数据面。

实现（`SecurityPostureConfiguration`）：

1. 启动时输出 WARN 横幅，包含风险与修复方式；
2. 在 `prod` / `production` profile 且安全关闭时**拒绝启动**；
3. 健康检查 `ragSecurityPosture` 暴露：
   - 关闭：`securityEnabled=false`、`risk=HIGH`、`exposedWithoutAuthentication=true`、`warning=...`；
   - 开启：`securityEnabled=true`。
4. 健康状态保持 `UP`，避免编排系统误杀本身健康的实例；风险通过 detail 与日志告警。

生产部署至少需要：

```bash
RAG_SECURITY_ENABLED=true
RAG_ROOT_API_KEY=<随机长密钥>
```

---

## 5. 验收

| 层 | 内容 | 入口 |
|----|------|------|
| PR 快层 | 文件 ACL 判定表、路径穿越、反馈/评估 scoping、安全关闭防护 | `SecurityBoundaryAccessTest`、`SecurityPostureConfigurationTest`、`UserFeedbackServiceScopingTest`、`RetrievalEvaluationServiceScopingTest` |
| gated 层 | 真实 PostgreSQL + mock provider，全入口跨 Collection / 跨 principal 矩阵 | `scripts/verify-security-boundary.py`（nightly `soak` job） |

回归矩阵覆盖：`search`、`chat`、`collections`（详情/文档/导出）、`documents upload`、`files/pdf-to-rag`、`files tree/raw/preview`、`evaluation history/report/metrics`、`feedback stats/history/type`。

本地运行：

```bash
python3 scripts/verify-security-boundary.py
```

脚本使用临时数据库与 mock provider，结束时删除；断言失败不会放宽。

---

## 6. 已知残余风险

- 历史 `fs_files` / feedback / evaluation 行无归属，受限 key 不可见；如需让历史数据对特定 principal 可见，必须做受控回填，而不是放宽查询。
- 日志、审计导出（如 `logs/*.jsonl`）不在本文范围，仍按部署侧权限管理。
- 未启用认证时没有 principal 概念，插件/工具调用等内部路径按全量处理；这是隔离环境的既有行为。
