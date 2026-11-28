# Scenemill（星幕 AI）STRICT 模式 provisioning 参考模板

> 目标：多产品共用同一套 `spring-ai-rag` 实例时，按「1 产品部署 = 1 RAG 租户（`scenemill-prod`）」
> 建立 STRICT 模式隔离边界。客户组织素材与平台共享素材同处 `scenemill-prod` 租户内，
> 用 6 个 Collection 与 4 个受限 `NORMAL` Key 划分访问面，不新增跨租户 RLS 后门。
> 权威规划：[SCENEMILL_SEAM_ALIGNMENT_AND_HARDENING_PLAN.md](SCENEMILL_SEAM_ALIGNMENT_AND_HARDENING_PLAN.md)
> 可执行模板：`scripts/provision-scenemill-strict.sh`

## 1. 前置条件

- 后端已设置 `RAG_TENANT_MODE=STRICT`，应用数据库角色为 `NOSUPERUSER NOBYPASSRLS`。
- 已配置 `RAG_ROOT_API_KEY`（至少 32 位可打印 ASCII，非占位值），仅通过环境变量传给脚本。
- 已完成 Flyway V1–V86 迁移与 `TenantRlsVerifier` 启动校验。

## 2. 资源编排（幂等）

| 资源 | 标识 | 用途 |
|---|---|---|
| 租户 | `scenemill-prod` | 星幕产品部署的 RAG 租户边界 |
| Collection ×6 | `tenant-{image,audio,video}` | 客户组织素材，按媒体类型隔离 |
| Collection ×6 | `platform-{image,audio,video}` | 平台共享素材，按媒体类型隔离 |
| Key ×4 | `scenemill-binding` / `scenemill-image` / `scenemill-audio` / `scenemill-video` | 受限 `NORMAL`，分别用于 preflight 与三类媒体数据面 |

脚本通过平台运营端点 `POST /api/v1/rag/operator/tenants` 创建租户，用环境 root 身份
通过 `POST /api/v1/rag/collections` 创建 6 个 Collection，通过
`POST /api/v1/rag/operator/tenants/{tenantId}/api-keys` 复用
`ApiKeyManagementService.issueKeyForTenant` 签发 4 个受限 Key；签发后通过
`PUT /api/v1/rag/api-keys/principals/{principalId}/policy` 绑定
`allowedCollectionKeys`（携带从 `GET /api/v1/rag/api-keys/principals` 读取的
`expectedPolicyVersion`），避免脚本自行猜测 CAS 版本或覆盖既有策略。
重复执行时遇到已存在资源返回冲突即跳过，不覆盖既有 Key 或 Collection。

## 3. 执行

```bash
export RAG_ROOT_API_KEY='<32+ char key>'
export RAG_BASE_URL='http://localhost:8081'
bash scripts/provision-scenemill-strict.sh
```

脚本只输出 Key 前缀与 `principal_id`，原始 Key 仅在签发响应中出现一次；
请按密钥管理流程立即转交并避免在终端历史、文档或 git 中留存明文。

## 4. 验收断言

1. `GET /api/v1/rag/integration-capabilities` 的 `principal.tenantId=scenemill-prod`、
   `tenantMode=STRICT`、`tenantStatus=ACTIVE`，与 `/auth/me` 一致。
2. 任意一个受限 Key 只能访问其 `allowedCollectionKeys` 内的 Collection；
   跨 Collection 查询返回 403/404，不泄漏其他租户或媒体类型数据。
3. 将租户置为 `SUSPENDED` 后，4 个 Key 访问任意数据面端点均返回
   `403 TENANT_SUSPENDED` 与 `X-RAG-Failure-Class: AUTHORIZATION`；恢复后回到 200。
4. Wire 契约由 `ScenemillSeamContractTest` 在 `SINGLE` / `STRICT` 双模式下常驻验证。
