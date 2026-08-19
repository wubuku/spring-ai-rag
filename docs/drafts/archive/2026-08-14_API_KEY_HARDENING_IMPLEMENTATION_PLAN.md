# API Key 加固独立实施规划

> 状态：Phase 0 + Phase M0 已实施并通过验收；Milestone A 及后续完整加固尚未实施
> 起草与最近复核日期：2026-08-15
> 当前代码基线：`main` / commit `3b61b26`；MVP 实施提交：`ccc0e42`
> 当前数据库基线：Flyway V1-V24；实施时从下一个可用版本开始，本文按 V25 expand 描述
> 后续实施约束：Phase 1+ 扩展必须重新核对当前代码和迁移号；不得把已完成的 Phase M0
> 误标为完整 API Key hardening 或公网 production-ready
> 当前进度：[API Key WebUI MVP 实施进度](2026-08-14_API_KEY_WEBUI_MVP_PROGRESS.md)
> 上层消费者：[OpenAI Chat Completions 兼容 RAG 服务实施规划](2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md)
> 当前代码事实：[OpenAI 兼容服务就绪度与代码库上下文](../../openai-compatibility-readiness-zh-CN.md)
> 历史来源：[API Key 管理重构规划](API-KEY-MANAGEMENT-PLAN.md) 记录了早期
> bootstrap、ADMIN/NORMAL 和 Collection ACL 的来源；其 NORMAL 自助创建、日志输出
> ADMIN secret 等结论不适用于本规划的外部生产服务目标。

---

## 1. 决策摘要

### 1.1 建议

**建议把 API Key 加固作为独立安全工程先实施，再开放任何面向外部调用方的新协议。**

本工程不是简单增加 Bearer Header，而是把当前“内部共享 Key + 粗粒度角色”升级成：

> 面向机器调用方的、可归属、可最小授权、可安全轮换、可全局吊销、可审计并可执行配额的
> 稳定 credential principal。

OpenAI Chat Completions 兼容接口是第一个明确消费者，但目标能力不应耦合 OpenAI DTO、
Controller 或 SSE。未来 MCP、Responses API、批处理和其他外部 RAG API 应复用同一
credential、principal、policy、quota 和 lifecycle 基础。

### 1.2 当前状态

当前项目已经完成独立 RAG 服务 MVP-0（Phase 0 + Phase M0）：

- `RAG_ROOT_API_KEY` 可通过环境变量注入，并作为独立 environment-root principal。
- root 可解锁 `/webui/unlock`，创建、列出、轮换和吊销业务 API Key。
- root 创建的业务 Key 固定为 `FULL_RAG` 数据面能力，expiry 必填、必须在未来且不设
  固定最长有效期。
- 外部调用方不需要 WebUI，只需携带业务 Key 访问现有 RAG 数据面。
- root 模式下 WebUI 凭据只保存在页面内存；管理和数据面只接受 Header credential。
- 后端 root principal、root-only 管理 API、业务 Key 数据面隔离和 WebUI 流程均已验收。

MVP 实施证据和测试结果见
[API Key WebUI MVP 实施进度](2026-08-14_API_KEY_WEBUI_MVP_PROGRESS.md)；
功能实现提交为 `ccc0e42`。

MVP 复用了以下已有基础：

- raw secret 使用 `rag_sk_` 前缀，公开 key ID 使用 `rag_k_` 前缀。
- 认证按 SHA-256 hash 和唯一索引查询。
- 支持创建、列表、吊销、轮换、过期时间和 `last_used_at`。
- 已有 `ADMIN` / `NORMAL` 角色。
- V24 已有 Collection ACL，并接入 Chat、Search、Collection、Document、上传和
  PDF-to-RAG 等数据路径。
- 已有敏感日志脱敏、trace ID、审计表和 Testcontainers PostgreSQL 依赖。

MVP-0 可以按单实例、TLS、受控管理网络作为独立 RAG 服务交付；但完整 API Key
体系仍不是公网、多实例 production-ready 的外部身份系统：

- schema 和 entity 仍保留 plaintext `api_key`。
- legacy 模式仍允许 NORMAL 创建 child Key；null/static caller 在部分兼容管理和 ACL
  逻辑中等同 unrestricted。
- rotation 创建独立 Key，丢失稳定 role、owner、policy 和 quota 身份。
- 没有事务化最后 ADMIN 保护。
- 未配置 root 的 legacy bootstrap 仍可能把 raw ADMIN secret 写入启动日志；root MVP
  模式已禁用该路径。
- 30 秒 JVM positive cache 延迟多实例吊销。
- 每次认证同步更新 `last_used_at`。
- 当前 limiter 本进程内计数；认证后优先使用稳定 principal，但 legacy/未认证 fallback
  仍可能直接使用 raw header。
- 完整 family/policy/quota hardening 尚未完成；当前 MVP 只承诺单实例、TLS 和受控管理网络。
- V25+ schema、细粒度 action policy、多实例吊销一致性、共享 quota 和 lifecycle audit
  仍属于后续 Milestone A。

### 1.3 硬门槛

在以下条件全部满足前：

- 不得把 `/v1/**`、MCP 或其他外部数据面标记为 production-ready。
- 除本规划明确定义的单实例 MVP-0 外，不得向外部客户签发 unrestricted 或永不过期的
  NORMAL Key；MVP-0 业务 Key必须有 expiry，并受其单实例、TLS、受控管理网络声明约束。
- 不得把现有 static key 当作外部 principal。
- 不得用“网关之后再补”替代应用内可验证的身份和授权边界。

必须先具备：

1. family/version 分层的稳定 principal。
2. 明确 owner、expiry、action、deployment、Collection 和 limits 的 policy。
3. 安全 create/revoke/rotate/bootstrap/recovery。
4. 事务化 lifecycle audit。
5. standalone 与 starter 两种拓扑一致的认证链。
6. 单实例和多实例模式下被明确验证的 revoke 与 quota 语义。

### 1.4 最短可用路径：独立 RAG 服务 MVP

本文区分“独立服务最小可用”与“面向公网、多实例的 production-ready”。在不建设传统
用户名/密码登录的前提下，最短可用产品定义为：

```text
独立运行的 RAG API 数据面
  + 由专用 root API Key 解锁的 Web 管理控制台
  + 可由控制台创建、展示一次、轮换和吊销的业务 API Key
```

调用方分为两类：

- 管理员/运维人员访问 WebUI，输入环境变量提供的 root API Key 解锁控制台并管理 Key。
- 外部用户或系统不访问 WebUI，只携带分配到的业务 API Key 调用 RAG API。

MVP 首版只签发 `FULL_RAG` 业务 Key：可访问当前 RAG 数据面的完整读写能力，并可选限制
Collection；业务 Key不能创建、列出、轮换或吊销其他 Key。root 是唯一 Key 管理主体，
同时具有完整 RAG 数据面能力。API 创建操作永远不能创建或提升出新的 root。

该 MVP 可以先按**单实例、TLS、受控管理网络**交付，并复用现有 `rag_api_key` hash、
过期、吊销和 Collection ACL 能力，不以 family/version、shared quota、IAP/OIDC 或
传统账号系统为前置条件。它不能宣称完成多实例吊销、细粒度最小权限、租户隔离或公网
管理面加固；这些仍由 Milestone A 完成。

配置有效 `RAG_ROOT_API_KEY` 即显式启用 MVP 安全模式：management 和现有 RAG 数据面
都必须携带 root 或有效数据库业务 Key，不能再被 legacy `rag.security.enabled=false`
绕过。未配置 root 时保持现有兼容行为，不启用本 MVP；配置了空白、弱值或 placeholder
则 fail startup。

---

## 2. 目标、范围与非目标

### 2.1 本规划目标

1. 清除 raw secret 的持久化能力和日志分发路径。
2. 将“调用方身份”从可轮换 secret version 中分离出来。
3. 引入显式、typed、可版本化并默认最小权限的 policy。
4. 修复管理面的创建、委派、轮换、吊销和 ADMIN 并发保护。
5. 支持 `Authorization: Bearer`，同时保留受控的 legacy `/api/**` 兼容。
6. 统一 core standalone 和 starter consumer 的 Filter/Interceptor 装配。
7. 提供 stable family ID 维度的 RPM、并发和后续 budget 承载点。
8. 建立多实例吊销、policy 变更和 shared quota 的可验证语义。
9. 将 lifecycle audit 变成管理事务的一部分，而不是 best-effort 日志。
10. 提供 expand/cutover/contract 和受控回滚路径。

### 2.2 本规划范围

- `spring-ai-rag-api`：管理 DTO 和可复用的 policy/value object。
- `spring-ai-rag-core`：schema、entity、repository、credential resolver、principal、
  authorization、lifecycle、audit、bootstrap、quota、shared web security configuration。
- `spring-ai-rag-starter`：导入共享配置，避免重复 Bean。
- `spring-ai-rag-webui`：管理凭据安全边界、管理页面 DTO、streaming header 修复。
- `scripts/`、`docker/`、`k8s/`：bootstrap、Secret Manager/Kubernetes Secret、
  mixed-version 门禁、shared quota 和回滚 runbook。
- `docs/`：API、配置、部署、测试和迁移文档。

### 2.3 明确非目标

- 不建设完整的人类用户目录、注册登录、密码重置或 SaaS 计费系统。
- 不把请求体中的 `user`、`tenantId` 或任意 Header 当作可信身份。
- 不在本工程实现 OpenAI Chat Completions DTO、SSE 或 Models API。
- 不在本工程完成每一个现有 `/api/v1/rag/**` endpoint 的细粒度 action 迁移。
- 不承诺没有可信 usage 时的硬 token/cost budget。
- 不把浏览器 `sessionStorage` 当作防 XSS 的安全凭据库。
- 不在第一阶段允许公网 NORMAL 用户自助创建下级 Key。
- MVP 不建设传统用户名/密码、人类用户目录或服务端登录 session；WebUI 的 root API Key
  输入是“控制台解锁”，不是账号登录。
- IAP/OIDC 是后续公网或企业管理面的增强项，不阻塞单实例、受控管理网络中的 root-key
  WebUI MVP。

---

## 3. 当前代码基线

### 3.1 模块和代码入口

| 职责 | 当前文件 | 关键事实 |
|---|---|---|
| API DTO | [`ApiKeyCreateRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyCreateRequest.java) | 只有 name、无时区 expiry、Collection IDs |
| 创建响应 | [`ApiKeyCreatedResponse`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyCreatedResponse.java) | raw key 只在 create/rotate response 返回 |
| 元数据响应 | [`ApiKeyResponse`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyResponse.java) | version/family 尚未区分 |
| legacy entity | [`RagApiKey`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java) | 映射 key hash、role、ACL 和 plaintext `api_key` |
| repository | [`RagApiKeyRepository`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagApiKeyRepository.java) | hash lookup、disable、每请求 last-used update |
| lifecycle service | [`ApiKeyManagementService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java) | create/validate/revoke/rotate 和 30 秒 positive cache |
| bootstrap | [`ApiKeyBootstrapService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyBootstrapService.java) | root 模式禁用空表 ADMIN/raw bootstrap；未配置 root 时保留 legacy 行为 |
| 管理 API | [`ApiKeyController`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java) | root 模式仅 environment root 可 create/list/revoke/rotate；legacy 模式保留旧语义 |
| 认证 | [`ApiKeyAuthFilter`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java) | root 模式支持 Bearer/X-API-Key、拒绝 query；legacy 模式保留兼容 fallback |
| Collection ACL | [`ApiKeyCollectionAccess`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java) | null/static/ADMIN/空 ACL 被视为 unrestricted |
| 限流 | [`RateLimitFilter`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/RateLimitFilter.java) | JVM fixed window；认证后优先使用稳定 key ID，未认证/legacy fallback 仍可能使用 raw header |
| starter/core 装配 | [`RagWebSecurityConfiguration`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagWebSecurityConfiguration.java)、[`GeneralRagAutoConfiguration`](../../../spring-ai-rag-starter/src/main/java/com/springairag/starter/GeneralRagAutoConfiguration.java) | core/starter 共用认证；auth order -10、limit order 0，当前均覆盖 `/api/*`，尚未覆盖 `/v1/*` |
| 审计 | [`AuditLogService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/AuditLogService.java) | optional + catch-and-continue，不适合安全 lifecycle |
| WebUI storage | [`credentialStore.ts`](../../../spring-ai-rag-webui/src/auth/credentialStore.ts) | MVP 已改为页面内存，并在启动时清理旧 localStorage 项 |
| WebUI stream | [`useSSE.ts`](../../../spring-ai-rag-webui/src/hooks/useSSE.ts) | MVP 已将 streaming credential 改为 Header transport |
| WebUI 路由 | [`App.tsx`](../../../spring-ai-rag-webui/src/App.tsx) | MVP 已提供 `/webui/unlock`、protected route 和显式退出 |
| 传统登录 | core、starter、WebUI 和 Flyway | 没有 username/password、用户表、`formLogin`、`UserDetailsService` 或登录 session |

### 3.2 当前数据库

相关迁移：

| 版本 | 内容 | 加固影响 |
|---|---|---|
| V18 | `rag_api_key` | 初始 version-centric 表，TIMESTAMP 无时区 |
| V19 | `key_hash` 唯一索引 | 可复用的索引验证方向 |
| V22 | `role` | 最早 Key 被直接提升为 ADMIN |
| V23 | plaintext `api_key` + index | 必须先禁止写入，再随 legacy table 删除 |
| V24 | 逗号串 `allowed_collection_ids` | 迁移为 typed explicit scope |
| V10 | `rag_audit_log` | 可承载 lifecycle audit，但需强事务写入服务，并扩容 ID 列 |

当前 `spring.jpa.hibernate.ddl-auto=validate`。因此 expand 阶段不能先删除
`rag_api_key.api_key` 或整个 legacy table，否则旧应用和回滚版本会启动失败。

V10 的 `rag_audit_log.entity_id` 只有 64 字符，`operator` 只有 128 字符；目标
family/key public ID 最长 80 字符，可信 human operator ID 最长 160 字符。V25 必须把
前者扩到至少 80、后者扩到至少 192，并同步 `RagAuditLog` JPA 映射，否则强事务
lifecycle audit 会让合法管理操作因列宽失败而整体回滚。

### 3.3 当前运行拓扑

1. core 自带 runnable `SpringAiRagApplication`，组件扫描 `com.springairag`。
2. starter consumer 通过 `GeneralRagAutoConfiguration` 注册 auth/rate-limit。
3. core POM 不依赖 starter。

`ApiKeyAuthFilter` 和 `RateLimitFilter` 本身不是 `@Component`；core standalone 不会因为
扫描 core package 自动注册它们。共享安全配置是本工程必须解决的现存缺口。

### 3.4 当前测试事实

已有 unit/MVC tests 覆盖：

- create/revoke/rotate 和 SHA-256。
- 30 秒 positive cache。
- X-API-Key/query/static filter。
- Collection ACL。
- starter URL pattern 和当前错误 Filter 顺序。
- WebUI create/list/revoke/rotate 基本行为。
- 日志中 query API Key 和 Bearer token 的脱敏。

缺失：

- 真实 PostgreSQL family/version repository 和 migration 测试。
- mixed-version 和回滚测试。
- 两个实例之间的 revoke/policy 一致性测试。
- 最后 ADMIN、并发 rotation、幂等 create/rotate 测试。
- lifecycle audit 与管理事务原子性测试。
- shared quota 和 stream concurrency 测试。
- core standalone 的 auth/rate-limit 集成测试。

---

## 4. 当前矛盾和威胁模型

### 4.1 文档与代码矛盾

实施前 characterization tests 必须锁定而不是忽略以下矛盾：

| 声明 | 实际代码 |
|---|---|
| raw key 永不存储 | entity、V23 column 和 index 仍允许存储 |
| 最后一个 ADMIN 不可删除 | controller/service 没有事务保护 |
| static key 是 ADMIN / 全管理权限 | controller 得不到 entity，list 为 NORMAL；create/rotate 又有 null caller 放行缺口 |
| ADMIN 可创建任意角色 | create DTO 没有 role，service 总是默认 NORMAL |
| rotate 保留身份 | rotate 新建独立 NORMAL Key，仅复制 name/expiry/Collection |
| 滑动窗口限流 | 当前是本地固定 60 秒窗口 |
| API Key limiter 可用于多租户 | raw key 可能成为配置 key，且多副本独立计数 |

### 4.2 需要防御的攻击和故障

1. 数据库、日志、代理 access log 或浏览器持久化泄漏 raw secret。
2. NORMAL 或 static/null caller 创建 unrestricted credential。
3. 通过 rotation 重置配额、提升权限或摆脱 parent 限制。
4. 通过多个 child family 放大 parent RPM、并发或 budget。
5. 节点 A 吊销后，节点 B 继续接受 positive cache。
6. 认证数据库故障时降级到 static key 或匿名路径。
7. 通过假 `X-Forwarded-For` 绕过 pre-auth IP limiter。
8. 并发 revoke/policy update 使系统失去最后 ADMIN。
9. create/rotate response 丢失后重试产生多个有效 secret。
10. mixed-version 期间旧实例把新 external Key 当作 legacy unrestricted Key。
11. 无时区 `expires_at` 在不同 JVM/节点上解释不一致。
12. malformed policy 或 malformed legacy ACL 被错误解释成 ALL。
13. WebUI XSS 读取 localStorage ADMIN key，或 query secret 被浏览器/代理记录。
14. lifecycle audit 失败但管理操作仍返回成功。

### 4.3 信任边界

- **可信**：应用进程、PostgreSQL、明确配置的 Redis/shared quota backend、受控 Secret
  Manager、经过验证的 IAP/mTLS operator assertion。
- **不可信**：所有 HTTP Header、query 参数、OpenAI `user` 字段、浏览器 localStorage、
  直接客户端的 forwarded header、自由文本 owner/tenant/project。
- `tenantId`、`projectId`、`ownerId` 在本阶段是 opaque 归属和审计字段，不自动构成
  数据库行级租户隔离。
- 真实数据权限只能由服务端保存的 effective policy 决定。

---

## 5. 不可破坏的安全不变量

实现和 code review 必须逐项维持：

1. raw secret 只在 create/rotate/bootstrap 输入或成功响应中出现一次。
2. 数据库、entity、普通日志、audit、metrics、MDC、URL 和 exception 不保存 raw secret。
3. rotation 只新增 version，不改变 stable family 身份。
4. family policy 同时约束所有 active/overlap versions。
5. family revoke 覆盖全部 versions；存在 delegation 时覆盖 descendants。
6. version revoke 不等价于 family revoke，两者使用不同 endpoint/action。
7. role 是管理能力上限，不是数据面 scope bypass。
8. external NORMAL 默认无委派、无 ALL、无无限 expiry。
9. child effective policy 始终与全部 active ancestors 取交集。
10. child 请求同时消耗 child 和全部 ancestors 的 quota。
11. policy/store/quota 无法可靠求值时 fail closed。
12. management endpoint 永远不能因 `rag.security.enabled=false` 变成匿名接口。
13. mixed-version 期间新 external credential 不进入 legacy table。
14. malformed/unknown policy 不得降级为 unrestricted。
15. 最后一个可用 ADMIN family 不能被 revoke、级联、过期更新或 policy 收窄移除。

---

## 6. 术语与标识

### 6.1 Credential Family

稳定机器身份和 policy 容器。建议公开 ID：

```text
rag_f_<128-bit-or-more-public-random-id>
```

owner、role、expiry、policy、delegation 和 quota 都属于 family。

### 6.2 Credential Version

family 下某一次可用 secret。公开 key ID：

```text
rag_k_<128-bit-or-more-public-random-id>
```

version 只承载 hash 和 lifecycle，不复制 owner/policy。

### 6.3 Raw Secret

调用方持有的认证材料：

```text
rag_sk_<base64url-encoded-256-bit-random-secret>
```

保留 `rag_sk_` 以兼容现有识别，但从 UUID 文本改为 `SecureRandom` 生成至少 32 bytes。

### 6.4 Principal

认证成功后形成的不可变、请求级快照：

```java
public record ApiKeyPrincipal(
        String keyId,
        String credentialFamilyId,
        ApiKeyRole role,
        String tenantId,
        String projectId,
        String ownerId,
        long policyVersion,
        ApiKeyPolicy directPolicy,
        ApiKeyPolicy effectivePolicy,
        List<String> ancestorFamilyIds) {
}
```

下游不得依赖可变 JPA entity，也不得保存 raw key。

### 6.5 Operator

执行 create/revoke/rotate/policy update 的可信主体。第一阶段生产管理面只要求：

- database-backed ADMIN family，且具有对应 `keys.*` action；或
- 平台提供并由应用验证的 IAP/OIDC/mTLS operator assertion。

本项目当前没有 Spring Security/OIDC 依赖，因此“内建公网人类登录”不是本阶段交付项。

---

## 7. 目标数据模型

### 7.1 `rag_api_key_family`

稳定 principal 与 policy：

| 列 | 建议类型 | 约束/说明 |
|---|---|---|
| `id` | BIGSERIAL | 内部 PK |
| `family_id` | VARCHAR(80) | unique，公开稳定 ID |
| `status` | VARCHAR(20) | `ACTIVE` / `REVOKED` |
| `name` | VARCHAR(255) | 非空 |
| `tenant_id` | VARCHAR(128) | nullable opaque string |
| `project_id` | VARCHAR(128) | nullable opaque string |
| `owner_id` | VARCHAR(128) | 非空；legacy backfill 使用明确 legacy owner |
| `role` | VARCHAR(20) | `ADMIN` / `NORMAL` |
| `parent_family_id` | BIGINT FK | immutable nullable parent |
| `delegation_depth` | INTEGER | 非负且有全局上限 |
| `created_by_principal_type` | VARCHAR(32) | `API_KEY` / `HUMAN` / `BOOTSTRAP` |
| `created_by_principal_id` | VARCHAR(160) | 稳定 operator ID，只用于审计 |
| `policy` | JSONB | 非空 typed policy |
| `policy_version` | BIGINT | optimistic concurrency revision |
| `expires_at` | TIMESTAMPTZ | external NORMAL 必填 |
| `created_at` | TIMESTAMPTZ | 非空 |
| `updated_at` | TIMESTAMPTZ | 非空 |
| `revoked_at` | TIMESTAMPTZ | nullable |
| `revocation_reason` | VARCHAR(512) | nullable |
| `lock_version` | BIGINT | JPA optimistic locking |

索引：

- unique `family_id`
- `(status, expires_at)`
- `parent_family_id`
- `owner_id`
- `tenant_id, project_id`

### 7.2 `rag_api_key_version`

可轮换 secret：

| 列 | 建议类型 | 约束/说明 |
|---|---|---|
| `id` | BIGSERIAL | PK |
| `key_id` | VARCHAR(80) | unique public version ID |
| `key_hash` | CHAR(64) | unique SHA-256 hex |
| `family_id` | BIGINT FK | 非空 |
| `status` | VARCHAR(20) | `ACTIVE` / `ROTATED` / `REVOKED` |
| `created_at` | TIMESTAMPTZ | 非空 |
| `last_used_at` | TIMESTAMPTZ | nullable |
| `retire_at` | TIMESTAMPTZ | overlap 硬截止时间 |
| `rotated_from_key_id` | VARCHAR(80) | nullable |
| `revoked_at` | TIMESTAMPTZ | nullable |
| `revocation_reason` | VARCHAR(512) | nullable |

认证不只检查 status：

```text
status = ACTIVE
AND (retire_at IS NULL OR now < retire_at)
AND family.status = ACTIVE
AND family 未过期
```

异步清理未把 version 改为 ROTATED 时，`retire_at` 仍必须硬拒绝。

### 7.3 `rag_api_key_operation`

管理操作幂等记录：

| 列 | 建议类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL | PK |
| `operator_type` | VARCHAR(32) | `API_KEY` / `HUMAN` |
| `operator_id` | VARCHAR(160) | familyId 或受验证的人类 operator ID |
| `operation_type` | VARCHAR(32) | CREATE / ROTATE 等 |
| `request_scope_hash` | CHAR(64) | method + canonical path/target |
| `request_fingerprint` | CHAR(64) | canonical validated request 的 hash |
| `idempotency_key_hash` | CHAR(64) | 不保存原始 header |
| `status` | VARCHAR(20) | IN_PROGRESS / SUCCEEDED |
| `target_family_id` | VARCHAR(80) | nullable |
| `created_key_id` | VARCHAR(80) | nullable |
| `created_at` | TIMESTAMPTZ | 非空 |
| `expires_at` | TIMESTAMPTZ | retention 到期 |

唯一键：

```text
(operator_type, operator_id, operation_type, request_scope_hash, idempotency_key_hash)
```

不得保存 raw secret、完整 request body 或可重放的成功 response。`request_fingerprint`
只覆盖已经标准化并通过校验的业务字段，用于检测同一 Idempotency-Key 被不同 request
复用；不得包含 Header credential 或之后生成的 raw secret。

### 7.4 `rag_api_key_security_state`

单行安全状态/并发锁：

| 列 | 建议类型 | 说明 |
|---|---|---|
| `singleton_id` | SMALLINT | PK + CHECK = 1 |
| `bootstrap_completed_at` | TIMESTAMPTZ | nullable |
| `bootstrap_family_id` | VARCHAR(80) | nullable |
| `revision` | BIGINT | 变更 revision |
| `updated_at` | TIMESTAMPTZ | 非空 |

bootstrap、ADMIN create/revoke/policy/expiry、可能级联到 ADMIN 的 family revoke 都先
`SELECT ... FOR UPDATE` 锁定该行，再检查全局 ADMIN invariant。

### 7.5 为什么不用单表

单表无法同时正确表达：

- rotation 中两个短期有效 secret，共享一个 policy。
- owner/policy 更新立即约束 overlap 中所有 secret。
- stable parent-child delegation。
- rotation 不重置 quota。
- 从任意旧 keyId 撤销完整 credential。

因此 family/version 分层是实现安全 rotation 的必要模型，不是过度抽象。

---

## 8. Policy 模型

### 8.1 建议 JSON

```json
{
  "schemaVersion": 1,
  "actions": [
    "models.read",
    "chat.completions.invoke"
  ],
  "deploymentIds": [
    "rag-default"
  ],
  "collectionScope": {
    "mode": "LIST",
    "ids": [1, 2]
  },
  "limits": {
    "requestsPerMinute": 60,
    "maxConcurrentRequests": 4,
    "monthlyTokenBudget": null
  },
  "maxContentAuditMode": "METADATA_ONLY",
  "allowDelegation": false
}
```

### 8.2 两类版本号

- `schemaVersion`：JSON 结构版本。
- family `policyVersion`：同一 principal 的并发修改 revision。

两者不能合并。未知 `schemaVersion` 必须 fail closed。

### 8.3 Typed policy

建议使用项目已有 `@JdbcTypeCode(SqlTypes.JSON)` 模式，将 JSONB 映射为不可变
`ApiKeyPolicy`，并由独立 validator 执行：

- action 必须来自注册表。
- deployment ID 格式、数量和长度受限。
- Collection mode 只能是 `ALL` / `LIST` / `NONE`。
- `LIST` 必须有正整数 ID；`NONE` 不得带 IDs。
- limit 必须为正数且不超过全局上限。
- external expiry 和 delegation 规则不能只靠 DTO validation，必须在事务服务重验。
- JSON unknown fields 和 future schemaVersion 拒绝，不静默忽略。

### 8.4 Secure defaults

新 external NORMAL family：

- `role=NORMAL`
- `actions` 显式、默认空
- `deploymentIds` 显式、默认空
- `collectionScope=NONE` 或明确 LIST
- `allowDelegation=false`
- expiry 必填且在未来
- deployment/policy 可选 max TTL 默认关闭；如启用则必须显式配置并可审计
- RPM 与 concurrency 必填或使用受控平台默认值
- `maxContentAuditMode=METADATA_ONLY`

空列表/空对象绝不能解释为 ALL。

### 8.5 Role 语义

- `ADMIN` 只表示 family 有资格获得跨主体 `keys.*` 管理 action。
- ADMIN 调用数据面仍必须通过 action、deployment 和 Collection policy。
- legacy `/api/**` 在迁移窗口可通过明确 adapter 保留“ADMIN unrestricted”旧语义；
  该语义不能传播到新 external Key 或 `/v1/**`。

### 8.6 Action 注册

第一阶段至少注册：

```text
keys.create
keys.read
keys.read.self
keys.revoke
keys.rotate.self
keys.version.revoke.self
keys.policy.write
models.read
chat.completions.invoke
```

后续现有 REST API 可增量增加：

```text
rag.chat.invoke
rag.search.invoke
collections.read
collections.write
documents.read
documents.write
evaluation.read
evaluation.write
```

action 使用语义名，不保存 URL 字符串。

---

## 9. 认证与 Principal 构建

### 9.1 Credential transport

新 external 数据面：

- 默认只接受 `Authorization: Bearer <credential>`。
- 可通过显式 compatibility flag 接受 `X-API-Key`。
- 不接受 query `apiKey`。
- Bearer 与 X-API-Key 同时存在且不同：401。
- 两者相同：可接受，但只记录 deprecated transport metric，不记录 secret。

legacy `/api/**`：

- 迁移期保留 X-API-Key。
- fetch-based streaming 改为 Header 后进入 query key 弃用路线。
- 可增加 Bearer，保持旧客户端不受影响。
- 未配置 root 的 legacy mode 可在兼容窗口继续接受 query `apiKey`；一旦配置 root 进入
  MVP 安全模式，所有 management 和数据面都拒绝 query credential，只接受 Header。

management endpoint：

- MVP-0 接受专用 environment-root principal；完整加固后接受 database-backed family
  principal 或经过验证的 operator session。
- 不接受 legacy static key、query key 或 anonymous-dev caller。

### 9.2 Credential resolver

建议新增：

```text
ApiKeyCredentialExtractor
  -> ApiKeyCredentialResolver
  -> ApiKeyPrincipalFactory
  -> ApiKeyAuthorizationService
```

固定解析顺序：

```text
提取 credential
  -> 识别 transport/path policy
  -> SHA-256
  -> key_hash unique lookup
  -> version status/retireAt
  -> family status/expiry
  -> 递归加载 ancestor chain
  -> 严格解析 direct policies
  -> 计算 effective policy
  -> 生成 immutable principal
```

### 9.3 Legacy static key

`rag.security.api-key`：

- 只允许旧 `/api/**` 数据面。
- 不允许管理 Key。
- 不允许新 external path。
- 不产生 database family、owner 或 quota。
- 文档标记 deprecated。

为避免“数据库故障后 static fallback”：

- legacy path 只有在 credential 与已配置 static secret 精确匹配时，才形成明确
  `LEGACY_STATIC` caller。
- 未匹配 static 后若 database credential store 故障，返回 503。
- 不得把 database lookup error 当作“无匹配”继续放行。
- 启动时如 static secret hash 与 active DB version 冲突，fail fast，避免身份歧义。

### 9.4 Environment root key

MVP 新增专用 `RAG_ROOT_API_KEY`，不得复用 legacy `RAG_API_KEY` /
`rag.security.api-key`：

- 只从环境变量或等价 Secret file 读取，不写数据库、不通过 API 返回、不打印日志。
- 启动时只保留用于 constant-time 验证的内存派生值，并建立固定
  `ENVIRONMENT_ROOT` principal。
- root 固定拥有 Key 管理和完整 RAG 数据面能力；该能力不能通过 create/policy API
  委派。
- root 轮换通过更新 Secret、滚动或重启实例完成；旧值在实例退出后失效。
- 未配置 root 时不启用 MVP 安全模式并保持现有兼容；一旦配置，则空白、少于 32 个
  ASCII 字符或使用已知示例/placeholder 值时 fail startup。
- 有效 root 自动要求 `/api/**` management 和数据面鉴权，不能被 legacy global auth
  disabled 配置降级为匿名访问。
- 多实例必须由同一 Secret source 注入相同 root；MVP-0 本身仍只承诺单实例外部数据面。

该 root 是受约束的部署根信任，不是 legacy static data-plane key。完整 family hardening
实施后，可保留它作为 break-glass operator，或由明确迁移步骤转换为 bootstrap ADMIN；
不得同时存在两个语义不清的 root。

### 9.5 Failure semantics

| 情况 | 内部结果 | HTTP 基线 |
|---|---|---|
| 缺失、未知、过期、吊销 | INVALID_CREDENTIAL | 401，不区分细因 |
| action/scope 越权 | POLICY_DENIED | 403 |
| quota/并发超限 | QUOTA_EXCEEDED | 429 + Retry-After |
| DB/policy/schema 不可用 | CREDENTIAL_SERVICE_UNAVAILABLE | 503 |
| shared quota backend 不可用 | QUOTA_SERVICE_UNAVAILABLE | 503 |
| management optimistic conflict | POLICY_VERSION_CONFLICT | 409 |
| 幂等成功但 raw 已签发 | SECRET_ALREADY_ISSUED | 409 + public IDs |

上层协议负责映射 error envelope；API Key 模块不依赖 OpenAI DTO。

### 9.6 Request context

新增单一 request attribute，例如：

```text
rag.auth.apiKeyPrincipal
```

旧 `AUTHENTICATED_KEY_ATTRIBUTE` 和 `AUTHENTICATED_API_KEY_ENTITY` 在 expand release
仅为 legacy adapter 保留并标记 deprecated。新 limiter、controller 和 ACL 不得继续依赖
raw header 或可变 entity。

---

## 10. 授权和 Scope 合成

### 10.1 固定求值顺序

```text
principal valid
  -> semantic action
  -> target deployment/resource visibility
  -> Collection effective scope
  -> quota
  -> business operation
```

Controller 不得各自重写公式。

### 10.2 Annotation + Interceptor

建议新增：

```java
@RequiresApiAction("keys.create")
@RequiresApiAction("models.read")
```

由 `ApiKeyAuthorizationInterceptor` 读取 immutable principal 并调用统一 service。

对新 policy family：

- 标注 endpoint 按 action 检查。
- management 与新 external endpoint 未声明 action/policy metadata 时默认拒绝。
- 新增启动期 `ApiAuthorizationCoverageValidator`，扫描 `RequestMappingHandlerMapping`：
  `/api/v1/rag/api-keys/**`、`/api/v1/rag/api-key-versions/**` 和已注册的 `/v1/**`
  handler 必须具有可解析的授权策略，否则 application context 启动失败。
- 需要动态 any-of/self-vs-admin 语义的 handler 使用显式 policy metadata，由统一
  authorization service 求值；不得退回 Controller 内零散 role 判断。

对现有 `/api/**`：

- expand 期只有显式 allowlist 的 handler 可通过 `LegacyApiAuthorizationAdapter`
  保持当前数据面契约。
- Key 管理 endpoint 不进入宽松 adapter。
- 后续逐 endpoint 迁移 action，不能一次大爆炸式重写全部 controller。

### 10.3 Collection scope

目标使用 explicit `ALL|LIST|NONE`：

```text
effective = family policy
          ∩ all ancestor policies
          ∩ endpoint/deployment scope
          ∩ request override
          ∩ document ownership-derived scope
```

结果为空必须生成显式 empty filter/deny，绝不能退化为“未提供过滤条件 = 全库”。

### 10.4 Legacy ACL 迁移

- legacy null/blank `allowed_collection_ids` 映射为 legacy `ALL`。
- 非空逗号串严格解析为 LIST。
- malformed、空 token、0、负数和超长值使 migration fail。
- existing ADMIN 的 legacy unrestricted 只在 legacy adapter 生效。
- existing Key 不自动获得新 `/v1` action 或 deployment。

### 10.5 Delegation

MVP 默认关闭 NORMAL self-service create。

未来显式启用 delegation 时必须同时满足：

1. parent `allowDelegation=true`。
2. child role 不提升。
3. child actions/deployments/Collections 是 parent 的子集或相等。
4. child expiry 不晚于 parent，也不晚于 production max TTL。
5. child RPM/concurrency/budget 不高于 parent。
6. parent family immutable relation，不绑定可轮换 keyId。
7. 限制最大 depth 和每 family active child 数量。
8. 每次 child 请求与全部 active ancestors 重新取 policy 交集。
9. 任一 ancestor revoked/expired/malformed 都 fail closed。
10. child 请求同时消耗所有 ancestor quota bucket。

不能只在创建时复制 parent policy 后永久信任。

---

## 11. 管理 API 设计

### 11.1 路径策略

管理面继续位于：

```text
/api/v1/rag/api-keys
```

不在 `/v1/**` 提供 Key 管理 endpoint。

第一阶段保留现有路径，扩展 response 字段和语义，避免立即破坏 WebUI：

| Endpoint | 目标语义 |
|---|---|
| `POST /api-keys` | 创建 family + 初始 version |
| `GET /api-keys` | legacy-compatible family summary 数组；不返回 hash/raw |
| `DELETE /api-keys/{keyId}` | 从任意 version keyId 解析并撤销完整 family |
| `POST /api-keys/{keyId}/rotate` | 同 family 新建 version |
| `DELETE /api-key-versions/{keyId}` | 只撤销指定 version |
| `PATCH /api-keys/{familyId}/policy` | policyVersion 乐观锁更新 |

大规模客户管理再新增分页 endpoint；不要在同一变更中强制所有旧客户端切换分页 envelope。

### 11.2 Create request

新 request 至少包含：

```text
name
ownerId
tenantId/projectId (optional)
role
expiresAt (RFC3339 offset)
policy
parentFamilyId (only when explicit delegation is enabled)
```

服务层必须重新校验，不信任 Controller DTO 已校验的假设。

### 11.3 Create response

成功 `201`：

```json
{
  "familyId": "rag_f_<public-id>",
  "keyId": "rag_k_<public-id>",
  "rawKey": "rag_sk_<shown-once>",
  "name": "Production client",
  "expiresAt": "2026-11-12T00:00:00Z",
  "policyVersion": 1,
  "warning": "Save this key now; it will not be shown again."
}
```

响应要求：

- `Cache-Control: no-store`
- `Pragma: no-cache`
- 只经 TLS 暴露
- raw 不进入 `toString()`、access log body、analytics 或 error snapshot

文档示例必须使用占位符，不能写真实 secret。

### 11.4 List response

- 一条 family summary 携带 current active keyId、status、owner、role、expiry、policy
  摘要、active version 数。
- 不返回 hash。
- 不返回 raw。
- restricted operator 只能查看自己的 family。
- ADMIN list 需要 `keys.read`。

### 11.5 Management authorization

| 操作 | 默认允许 |
|---|---|
| MVP-0 创建、列出、轮换、吊销业务 Key | environment root |
| 创建任意 external family | ADMIN + `keys.create` |
| 列出全部 | ADMIN + `keys.read` |
| 查看自己 | `keys.read.self` |
| 撤销任意 family | ADMIN + `keys.revoke` |
| 自己 rotation | `keys.rotate.self` |
| 自己 version revoke | `keys.version.revoke.self` |
| policy update | ADMIN + `keys.policy.write` |

role 只是 action 可授予上限，不能跳过 action 检查。
API-created Key 永远不能获得 environment-root 身份；MVP-0 的 `FULL_RAG` Key只拥有数据面
权限，不能调用管理 endpoint。

---

## 12. Secret 生成、存储和传输

### 12.1 生成

- 使用单例 `SecureRandom`。
- raw secret 至少 256 bit。
- Base64 URL-safe、无 padding，避免 header/CLI 转义问题。
- public familyId/keyId 至少 128 bit。
- 保持 `rag_sk_`、`rag_k_`；新增 `rag_f_`。
- key/hash collision 依靠 unique constraint 检测后有限重试。

### 12.2 Hash

高熵随机 secret 使用 SHA-256 索引查找是合理设计：

- 不需要密码型慢 hash。
- 数据库只存 64-char lowercase hex。
- raw 进入 hash 方法后不放入 exception。

### 12.3 持久化禁止项

禁止：

- `RagApiKeyVersion.rawKey` 字段。
- database plaintext/encrypted raw secret。
- operation response snapshot 中的 raw。
- audit details 中的 raw/hash。
- metrics tag 中的 familyId/keyId/raw。
- URL query 中的新 external secret。

### 12.4 日志防线

扩展现有 `SensitiveDataMaskingConverter` 和测试：

- Authorization Bearer。
- X-API-Key。
- query `apiKey`。
- JSON `rawKey`、`apiKey`、`api_key`。
- nested JSON 和 escaped JSON。
- exception message。
- reverse proxy access log 示例。

日志脱敏是纵深防御，不能作为“允许先记录 raw”的理由。

---

## 13. Create 与幂等

### 13.1 事务

一个 create 事务内完成：

1. 解析可信 operator；API Key operator 锁定 family，人类 operator 验证稳定 external
   principal 和 `keys.create` 映射。
2. 校验 `keys.create`、role 上限和 secure defaults。
3. 如 delegation，锁定 parent 并检查 depth/child count/policy subset。
4. 创建 idempotency operation。
5. 生成 family、version 和 secret hash。
6. 写 lifecycle audit。
7. operation 标记 SUCCEEDED 并记录 public IDs。
8. commit 后才返回 raw secret。

audit 写失败则整个 create 回滚。

### 13.2 Idempotency-Key

- create/rotate 要求或强烈建议 `Idempotency-Key`。
- 至少 128 bit 随机，限制总长度。
- 保存 hash，不保存 header 原文。
- scope 包含 operator type/ID、operation type、canonical target path。
- 保存 canonical validated request fingerprint；同一 scope/key 的 fingerprint 不同时
  返回 409 `idempotency_key_reused`，不能返回旧结果或执行新请求。
- 其他 operator 使用相同值不能读取结果。

### 13.3 Response 丢失

若事务已 commit 但响应丢失：

- 重试不得创建第二个 family/version。
- 服务端不得重放 raw secret。
- 返回 `409 secret_already_issued` 和已创建 familyId/keyId。
- operator 可撤销未知 family，再使用新 Idempotency-Key 重建。

若事务未 commit，operation 和 family 均不存在，重试正常执行。

---

## 14. Rotation

### 14.1 默认语义

rotation 在同一 family 下新增 version：

- owner/tenant/project/role/policy/lineage/expiry/limits 不复制、不改变。
- family expiry 不自动延长。
- expired/revoked family 不能 rotate。
- 新旧 version 共用 family quota。

### 14.2 Overlap

默认使用短 overlap，建议：

- 默认 5 分钟。
- production 硬上限 15 分钟。
- 旧 version 设置 `retire_at`。
- 新 version ACTIVE。
- 调用方在窗口内探活并可提前 retire 旧 version。

普通 self rotation 不能请求无限 overlap。

### 14.3 Immediate cutover

secret 泄漏时，ADMIN 可显式 immediate：

- 新 version 创建成功。
- 旧 version 同事务 REVOKED。
- 返回新 raw。

若 immediate response 丢失，operator 可通过 operation public ID 定位未知新 version，
执行 version-only revoke；不能误撤销完整 family。

### 14.4 并发 rotation

- `SELECT ... FOR UPDATE` 锁 family。
- 同 Idempotency-Key 返回同一 operation 结果。
- 不同 Idempotency-Key 并发时，第二个请求看到已有未完成 overlap，应返回 409，
  不能创建第三个 ACTIVE version。
- family 同时最多存在预定义数量 active/overlap versions，第一阶段建议 2。

### 14.5 Rotation completion

定时任务只做状态清理：

- `now >= retire_at` 的旧 version 标记 ROTATED。
- 即使任务延迟，认证已经按 `retire_at` 拒绝。
- 清理失败产生 metric/alert，不恢复旧 version。

---

## 15. Revoke 与 ADMIN 保护

### 15.1 Family revoke

`DELETE /api-keys/{keyId}`：

1. `keyId -> version -> family`。
2. 锁定 security state、目标 family 和 descendant family 集合。
3. 检查 operator action。
4. 检查 revoke 后仍有可用 ADMIN family。
5. 递归 revoke family 和 descendants。
6. revoke 所有 versions。
7. legacy shadow 双写。
8. 写事务 audit。

已撤销 family 重复撤销应幂等。

### 15.2 Version revoke

独立 endpoint：

```text
DELETE /api/v1/rag/api-key-versions/{keyId}
```

用于：

- 提前结束 overlap。
- 单个 secret 泄漏。
- rotate response 丢失恢复。

撤销最后一个 active version 时：

- NORMAL self action 默认拒绝并返回明确 conflict。
- ADMIN 可在确认参数和 audit reason 下执行。
- 若目标为最后 ADMIN 的最后 version，仍必须拒绝，除非先完成 break-glass recovery。

### 15.3 可用 ADMIN 定义

按 family 计数，必须同时满足：

- family ACTIVE 且未过期。
- 至少一个 ACTIVE、未到 retireAt 的 version。
- role=ADMIN。
- effective policy 仍包含最小 recovery 管理 actions。

影响该定义的操作都锁 singleton security state：

- ADMIN family revoke。
- descendant cascade。
- ADMIN policy update。
- ADMIN expiry update。
- ADMIN version revoke。

### 15.4 自动过期风险

只靠“操作时最后 ADMIN 检查”不能阻止最后 ADMIN 在未来自动过期。因此生产必须至少有一个：

- 独立 break-glass ADMIN family。
- Secret Manager 托管。
- 默认不日常使用。
- 可使用显式审计例外保持无 expiry，或使用足够长 expiry + 强提前告警和自动轮换。

普通 external Key 不享受该例外。

---

## 16. Bootstrap 与 Recovery

### 16.1 MVP-0 environment root

最短路径使用：

```text
RAG_ROOT_API_KEY=<high-entropy-secret>
```

要求：

1. 独立于 legacy `RAG_API_KEY`，避免“普通 static key 意外成为 root”。
2. 不落库、不落日志、不出现在 actuator、异常消息或配置 dump。
3. WebUI 只在用户提交时发送到 `GET /api/v1/rag/auth/me` 验证；服务端返回 principal
   类型和能力，不返回 credential。
4. 通过 root 创建的业务 Key仍写入现有 `rag_api_key` hash 路径，role 固定为 NORMAL，
   语义固定为 `FULL_RAG`，expiry 必填、必须在未来且不设固定最长有效期，可附带
   Collection ACL。
5. 业务 Key不能调用 Key 管理 API；当前 NORMAL self-create 行为必须关闭。
6. root 变更通过 Secret 更新和实例重启生效，不提供 WebUI 修改 root 的能力。
7. 有效 root 自动启用 `/api/**` 的 root/数据库 Key 鉴权；未配置 root 时保持 legacy
   行为，避免破坏现有嵌入式使用方。
8. MVP 模式禁用现有 `ApiKeyBootstrapService` 的空表 ADMIN 自动生成和 raw 日志输出；
   第一个业务 Key由 root 在 WebUI 或管理 API 中创建。
9. MVP 模式只接受 `Authorization: Bearer` 或 `X-API-Key` Header，拒绝 query
   credential；create/rotate raw response 设置 `Cache-Control: no-store`。
10. rotate 保留现有未来 expiry；旧 expiry 为空时使用 `now + 365 days`，旧 Key已过期
    时拒绝轮换。

### 16.2 Production bootstrap

推荐：

1. 运维离线生成高熵 `rag_sk_` secret。
2. 通过 Secret Manager/Kubernetes Secret 文件挂载：
   `RAG_BOOTSTRAP_ADMIN_KEY_FILE`。
3. 环境变量 `RAG_BOOTSTRAP_ADMIN_KEY` 只作为兼容方案。
4. 应用获取 PostgreSQL advisory lock，并锁 `rag_api_key_security_state`。
5. 若已有可用 ADMIN，拒绝重复 bootstrap。
6. 保存 hash、family、version 和 ADMIN recovery policy。
7. expand 回滚窗口同步创建受控 legacy ADMIN shadow。
8. 写强事务 audit，operator 为明确 bootstrap source，不含 raw。
9. 日志只打印 familyId/keyId 和“请删除 bootstrap 输入”。

raw secret不得打印。

### 16.3 Multi-instance

- advisory lock + singleton row + unique constraints 三层防重复。
- 只有一个实例完成 bootstrap。
- 其他实例检测已完成后继续启动。
- DB 不可用时不能各自本地生成 ADMIN。

### 16.4 Local development

可保留显式 opt-in：

```text
rag.api-keys.bootstrap.mode=development-log
```

约束：

- prod profile 启动时拒绝该 mode。
- 默认不开启。
- 明确标记只用于本机。
- 测试验证 profile guard。

### 16.5 Recovery

当 family 表非空但无可用 ADMIN：

- readiness DOWN。
- 普通管理 endpoint 不自动提升最早 NORMAL。
- 使用显式 recovery command/runner：
  - 要求 recovery flag。
  - 要求新的 Secret Manager 输入。
  - 要求 operator/ticket/reason。
  - 获取相同 DB locks。
  - 创建新的 break-glass ADMIN。
  - 写强事务 audit。

不能通过重启触发隐式恢复。

---

## 17. Lifecycle Audit

### 17.1 不能复用的现有语义

现有 `AuditLogService`：

- optional bean。
- catch exception 后继续业务。
- operator/clientIp 没有稳定填充。

这适合普通业务 resilience，不适合 credential lifecycle。

### 17.2 新服务

新增强制 `ApiKeyLifecycleAuditService`：

- 与 family/version/operation 使用同一 datasource 和事务。
- 直接写 `rag_audit_log`，第一阶段不吞异常。
- V25 先把 `entity_id` 扩到至少 80、`operator` 扩到至少 192，并同步
  `RagAuditLog` 的 `@Column(length=...)`；不得依赖数据库静默截断。
- repository 不存在时 Key 管理 Bean 不注册或 readiness fail。
- 后续如需外部审计系统，使用同事务 outbox；不能改成异步 best effort。

### 17.3 Audit 内容

记录：

- operation：CREATE、ROTATE、FAMILY_REVOKE、VERSION_REVOKE、POLICY_UPDATE、
  EXPIRY_UPDATE、BOOTSTRAP、RECOVERY。
- operator familyId/keyId 或受验证 human operator ID。
- target familyId/keyId。
- owner/role。
- reason。
- trace ID。
- trusted client IP。
- before/after policyVersion。
- scope/limits 摘要或 hash。

不记录：

- raw secret。
- key hash。
- Authorization Header。
- 完整 request body。
- Prompt/answer。

### 17.4 Client IP

新增 `TrustedClientIpResolver`：

- 仅在请求来自配置的 trusted proxy CIDR 时解析 forwarded header。
- 否则使用 socket remote address。
- 解析代理链有长度上限。
- 非法 header 不得导致管理请求绕过或 audit 缺失。

---

## 18. Cache、吊销一致性与 Last Used

### 18.1 第一阶段认证缓存

external family/version 首版不做 positive cache：

- 每请求通过 unique `key_hash` 索引查询。
- 同时读取 family 和必要 ancestor policy。
- RAG 主成本远高于一次索引查询，先换取清晰 revoke 语义。

不得沿用当前静态 30 秒 Caffeine positive cache。

### 18.2 后续缓存条件

只有压测证明必要时才增加，并必须同时具备：

- cache value 含 version/family status、expiry、retireAt、policyVersion 和 ancestor
  revision。
- Redis/pub-sub 或 DB notification 跨实例 invalidation。
- 短 TTL 作为 invalidation 失败上限。
- 两实例 revoke/policy/rotation tests。

### 18.3 Last Used

当前每次认证同步 UPDATE 会写放大。目标：

- 认证成功不等待 last-used 持久化。
- bounded async recorder。
- repository 使用条件 UPDATE：

```text
UPDATE version
SET last_used_at = now
WHERE key_id = ?
  AND (last_used_at IS NULL OR last_used_at < now - configured_interval)
```

- 建议 interval 10 分钟，可配置 5-15 分钟。
- 队列满或 DB 写失败只影响观测，计数并告警，不撤销已通过请求。
- recorder 不接收 raw secret。

---

## 19. Quota 与并发

### 19.1 统一 family bucket

quota key 使用 stable familyId：

- rotation 不重置。
- overlap versions 共享。
- child 请求同时计入 child 和所有 ancestors。

policy 至少支持：

- `requestsPerMinute`
- `maxConcurrentRequests`
- `monthlyTokenBudget` nullable

MVP 硬门禁是 RPM + concurrency。

### 19.2 SPI

建议：

```java
interface ApiKeyQuotaBackend {
    ApiKeyQuotaLease acquire(ApiKeyPrincipal principal, RequestQuotaContext context);
}
```

lease 在 complete/error/cancel/timeout 释放。

### 19.3 Local backend

`LocalApiKeyQuotaBackend`：

- 仅 dev、test 和明确单实例部署。
- 使用 familyId，不使用 raw header。
- readiness 显示 `mode=local, global=false`。
- `rag.api-keys.quota.require-shared=true` 时不能启动。

### 19.4 Redis shared backend

由于项目 production Helm 默认可多副本，建议提供可选 Redis backend：

- optional `spring-boot-starter-data-redis` / Lettuce。
- starter consumer 若启用 Redis backend，显式声明依赖。
- Lua 原子检查 child + ancestors 的 RPM 和 concurrency。
- RPM 使用明确 fixed-window 或 token-bucket 语义，不误称 sliding window。
- concurrency 使用带 expiry 的 lease/sorted set，防进程 crash 永久泄漏。
- stream 定期续租或以最大 stream duration 设置 lease TTL。
- 正常 complete/error/cancel 主动释放。
- shared backend 不可用时，production fail closed 返回 503。

如果部署已有 gateway，可实现替代 backend，但必须：

- gateway 能验证 credential 到 stable family。
- 或只信任应用签名/mTLS 保护的 principal header。
- 不允许 gateway 直接以 raw Bearer token 作为 limiter key。

### 19.5 Pre-auth limiter

未认证攻击流量单独按 IP 限制：

- 位于 auth 前。
- 只信任 `TrustedClientIpResolver`。
- 与业务 per-family quota 分离。
- 可由 gateway 承担。

### 19.6 Token budget

- 只使用可信 provider usage。
- usage 不可用时不得估算成硬扣减。
- deployment 应选择：仅告警、拒绝硬预算 Key，或不提供该 budget。
- 不阻塞 RPM/concurrency MVP。

---

## 20. Web 安全装配与运行拓扑

### 20.1 共享配置

把 auth/rate-limit 注册从 starter-only 迁移到 core 的共享配置，例如：

```text
RagWebSecurityConfiguration
```

- core standalone 由组件扫描加载。
- starter 由 `@Import` 加载。
- `@ConditionalOnMissingBean` + 明确 bean name 防重复。
- starter 删除重复 registration 方法或改为只 import。

### 20.2 明确顺序

建议：

```text
RequestTraceFilter             -300
PreAuthIpRateLimitFilter       -250
ApiKeyAuthenticationFilter     -200
ApiKeyQuotaFilter              -100
HandlerInterceptor authorization
Controller
```

当前 `RequestTraceFilter @Order(1)`、auth order -10 和 rate-limit order 0 的顺序已由
MVP 集成测试固定；Phase 1+ 仍需为 `/v1/*` 和共享 quota 设计新的拓扑级顺序与门禁。

### 20.3 URL scope

- `/api/*`：未配置 root 时按 `rag.security.enabled` 保持 legacy 兼容；配置 root 后
  自动要求 root 或数据库业务 Key。
- `/api/v1/rag/api-keys/*`：root 模式仅 environment root 可管理；未配置 root 时保留
  legacy ADMIN/NORMAL 语义。
- `/v1/*`：由上层兼容 feature flag 控制注册，但一旦存在必须使用 family principal。
- management 与 `/v1` handler 的授权 coverage validator 在 core standalone 和 starter
  consumer 都必须启用；漏标 action/policy metadata 时 fail startup。

### 20.4 Error writer

Filter 不能把 DB/policy exception 落到普通 500：

- 使用 protocol-neutral `AuthenticationFailure`。
- legacy `/api` 写 RFC 7807/项目 ErrorResponse。
- `/v1` 由上层 path-aware writer 写 OpenAI envelope。
- 两侧共享 trace ID 和 failure classification。

### 20.5 CORS

- `/api/**` 继续由现有 CORS 配置管理。
- 允许 Header 时至少包含 Authorization、Content-Type、X-API-Key、X-Trace-Id。
- 管理面不因 CORS allow-list 而被视为安全；仍要求 operator auth。
- `/v1/**` CORS 属于上层协议规划，但复用相同 credential transport 规则。

---

## 21. WebUI 与管理面

### 21.1 当前风险与 MVP 已完成项

MVP 已修复 root-key WebUI 的主要 secret transport 风险：credential 只保存在页面内存，
streaming 改用 Header，旧 localStorage 项会在升级时清理，管理路由需要 root unlock。
仍未完成的完整 hardening 风险包括：

- legacy mode 的 query credential 兼容路径仍需在面向外部协议的后续迁移中收敛。
- 管理页面尚未承载 owner、action、deployment、limits 或 policyVersion。
- schema、family/version、shared quota、多实例吊销和 lifecycle audit 仍未完成。

### 21.2 第一阶段产品边界

MVP-0 采用 API Key 控制台解锁，不引入 username/password：

- 未解锁时只显示 `/webui/unlock`。
- 管理员输入 `RAG_ROOT_API_KEY` 对应的 secret。
- WebUI 调用 `GET /api/v1/rag/auth/me`，确认 principal 为 environment root。
- 验证成功后进入控制台；“退出”只清除浏览器内存中的 credential。
- 刷新页面后重新输入 root，MVP 不建立账号 session。
- 外部用户/系统不访问 WebUI，只持业务 Key 调用 RAG API。
- WebUI route guard 只是用户体验；后端必须对每个管理请求重新认证和授权。

WebUI 默认只部署在 local/dev 或可信管理网络。没有 IAP/OIDC/mTLS 时，不把管理面暴露
到公网；数据面仍必须使用 TLS。

### 21.3 MVP-0 控制台流程

1. `/webui/unlock` 提供 password-style root key 输入框，不提供用户名字段。
2. `GET /api/v1/rag/auth/me` 返回 `principalType`、`keyId`/stable operator ID 和
   capabilities；无效 key 返回 401。数据库业务 Key返回数据面 capabilities，
   WebUI 根据 principal/capabilities 拒绝其解锁管理控制台。
3. credential 只保存在 React auth context/内存；页面刷新后重新解锁。
4. `/webui/api-keys` 只允许 root 进入，并支持创建、列表、轮换和吊销。
5. 创建表单首版只有 `FULL_RAG` profile、名称、必填 expiry、全部或指定 Collections；
   默认建议一年，不设置前端最大值；服务端只要求 expiry 在未来。
6. raw business key 只显示一次，关闭 modal 后清理；可提供显式复制按钮。
7. 列表只显示 key ID、名称、状态、expiry、last used 和 Collection scope，不显示 hash/raw。
8. 外部调用示例使用 Header；不要求调用方打开或登录 WebUI。
9. 应用首次加载主动删除旧版本遗留的 `rag-api-key` 和 `rag-api-key-role` localStorage
   项，并移除 Settings 中的 API Key 持久化入口。

### 21.4 必做 WebUI 修复

1. `useSSE.ts` 使用 `fetch` Header 发送 X-API-Key/Bearer，不再放 query。
2. 弃用或改写 `api/chat.ts` 的 EventSource query secret。
3. 删除 root/admin credential 的 localStorage 持久化；create/rotate raw secret 只保存在
   组件内存，关闭 modal 后清除。
4. 启动时清理旧版 `rag-api-key` / `rag-api-key-role` storage，防止升级后继续残留。
5. 不把新 raw secret写入 localStorage、console、toast analytics。
6. 完整 hardening 阶段再展示 familyId、active keyId、owner、role、expiry、policy
   summary、policyVersion、limits。
7. policy update 使用 version conflict 提示，不静默覆盖。

### 21.5 Credential storage mode

建议显式配置：

- `root-key-memory`：MVP-0 模式，credential 只在当前页面生命周期保存在内存。
- `external-session`：由 IAP/OIDC gateway 建立 HttpOnly/SameSite session。
- `disabled`：生产默认关闭管理 UI route。

localStorage 和 sessionStorage 都不作为 root credential 存储。

### 21.6 Browser security

external-session 模式还需要：

- 严格 CSP。
- CSRF 防护。
- SameSite/HttpOnly/Secure cookie。
- 精确 origin allow-list。
- gateway assertion 签名或 mTLS 验证。

这些平台能力未就绪时，UI 只能部署在受控运维网络。

---

## 22. Expand / Cutover / Contract 迁移

### 22.1 总原则

当前 V24 应用映射 `rag_api_key` 和 `api_key`，不能直接 drop。采用三段：

```text
Expand release A
  -> Family-only cutover release B
  -> Contract release C
```

### 22.2 部署前数据审计

在 V25 前执行只读报告：

- `api_key IS NOT NULL` 行。
- malformed `allowed_collection_ids`。
- duplicate/invalid keyId/hash。
- invalid role。
- active/expired/disabled 分布。
- 当前可用 ADMIN 数。
- legacy timestamp 来源时区。

plaintext active 行：

- 视为 secret 已暴露。
- 创建 replacement，完成调用方切换并吊销旧 credential。
- 清空 plaintext 后才允许 migration。

disabled plaintext 行在保留必要 audit 证据后清空。

V25 必须再次检查并 fail，不能只相信 runbook。

### 22.3 V25 Expand migration

针对当前基线建议：

```text
V25__api_key_family_expand.sql
```

内容：

1. 对 `rag_api_key` 获取与 legacy INSERT/UPDATE/DELETE 冲突的 PostgreSQL table lock；
   在受控 `lock_timeout` 内不能获得锁则 migration fail，禁止无锁回填。
2. 如果存在非空 `rag_api_key.api_key`，migration fail。
3. drop `idx_rag_api_key_api_key`。
4. 增加 `CHECK (api_key IS NULL)`，暂时保留列。
5. 扩容 `rag_audit_log.entity_id` 至至少 80、`operator` 至至少 192。
6. 新建 family/version/operation/security_state。
7. 插入 singleton security row。
8. 严格验证 legacy ACL、role 和 timestamps。
9. 为每个 legacy row 回填独立 family + 初始 version，复用原 keyId/hash。
10. `enabled=false` 映射 revoked family/version。
11. role、ACL 映射 legacy policy；不授予新 `/v1` actions。
12. 建立 constraints、FK 和 indexes。

### 22.4 Legacy timezone

V18 的 timestamp 无时区。V25 使用 Flyway placeholders：

```text
legacyApiKeyTimezone
legacyApiKeyTimezoneConfirmed
```

规则：

- legacy 表有数据时，`confirmed` 必须为 true。
- timezone 必须存在于 PostgreSQL `pg_timezone_names`。
- 使用 `AT TIME ZONE <source>` 回填 TIMESTAMPTZ。
- 缺失/非法/未确认时 migration fail。
- 全新空表可使用 UTC 默认值且不要求人为确认。
- 不读取当前 JVM default timezone 猜测。

### 22.5 Legacy policy backfill

- 每个 legacy row 独立 family，不推断 parent。
- owner 使用如 `legacy:<keyId>` 的明确迁移标识。
- ADMIN 只获得恢复/管理所需 legacy policy 和显式 `keys.*`；不能自动获得未来
  `/v1` data action。
- NORMAL 获得旧 `/api` compatibility action 和原 Collection scope。
- malformed ACL 阻止迁移。

### 22.6 Mixed-version 窗口

滚动升级期间：

- compatibility endpoint 保持 disabled。
- 在任何 V25 migration 启动前，所有外部和内部入口已阻断 Key management
  create/rotate/revoke/policy update，并从每个可达入口验证维护响应；不能只依赖人工通知。
- 写阻断保持到所有实例升级、回填和双读验证完成；绕过 gateway 的直连入口必须关闭或
  执行同等门禁。
- 旧 `/api/**` 数据面继续使用 legacy table。
- 新应用认证 migrated credential 时要求 legacy shadow 与 family/version 都有效，
  scope 取两侧最严格值。
- new external credential 不得创建。

这样避免旧 V24 Controller 的 static/null/NORMAL 管理缺口继续可用。

### 22.7 全量升级后

全量运行 expand application 后：

- family/version 成为新代码 source of truth。
- 解除 management 维护门禁。
- migrated legacy credential 的 revoke/status/Collection 收窄双写 legacy shadow。
- create/rotate/policy grant 的 new external family 只写新表。
- 唯一例外：platform break-glass ADMIN active version 在回滚窗口维护 legacy shadow。

### 22.8 为什么 external Key 不写 shadow

若写入 `rag_api_key`：

- 回滚/旧实例只看到 role + Collection ACL。
- action、deployment、owner、quota、ancestor policy 都丢失。
- 旧实例可能把它当 unrestricted legacy Key。

因此 external Key 在 V24 回滚时应明确“不可用”，而不是“可用但权限变宽”。

### 22.9 Family-only cutover release B

在 contract 前先发布一个不再映射/读取 legacy table 的 bridge version：

- 所有 migrated credentials 只读 family/version。
- 旧 `/api/**` 通过 family 中的 legacy compatibility policy 工作。
- 停止 dual-write。
- legacy table 保留但静态不再使用。
- 该版本仍可回滚到 expand release A，因为 legacy table 尚在。

观察窗口内执行：

- 认证成功率。
- revoke/rotate。
- last ADMIN。
- old `/api` 回归。
- new external Key。
- 多实例 quota。

### 22.10 Contract release C

使用届时下一个可用 Flyway 版本，而不是预占固定 V26：

1. 删除 `rag_api_key` legacy table。
2. 删除 legacy repository/entity/dual-write/adapter code。
3. 删除 static key 或保留只读 deprecated config 的最终决定。
4. contract 后回滚目标只能是 family-only release B 或更新版本。
5. 不得回滚到 V24。

### 22.11 回滚

Expand A 回滚到 V24：

- migrated legacy Key 状态由 shadow 保持。
- new external Key 在 V24 不可用。
- break-glass ADMIN shadow 保证旧 bootstrap 不因空表再次生成日志 secret。
- 已经 rotation 为 new-only secret 的调用方需恢复旧 legacy credential，或取消回滚。
- runbook 必须列出受影响 family。

Family-only B 回滚到 Expand A：

- legacy table 尚在，可回滚。

Contract C：

- 只能回滚到 family-only B。

---

## 23. 代码落点

建议职责组织：

```text
spring-ai-rag-api/
  .../api/dto/apikey/
    ApiKeyFamilyCreateRequest.java
    ApiKeyFamilyCreatedResponse.java
    ApiKeyFamilyResponse.java
    ApiKeyPolicyUpdateRequest.java
    ApiKeyRotationRequest.java
    ApiKeyVersionResponse.java

spring-ai-rag-core/
  .../core/entity/
    RagAuditLog.java
    RagApiKeyFamily.java
    RagApiKeyVersion.java
    RagApiKeyOperation.java
    RagApiKeySecurityState.java

  .../core/repository/
    RagApiKeyFamilyRepository.java
    RagApiKeyVersionRepository.java
    RagApiKeyOperationRepository.java
    RagApiKeySecurityStateRepository.java

  .../core/security/
    EnvironmentRootCredentialResolver.java
    ApiKeyPrincipal.java
    ApiKeyPolicy.java
    ApiKeyPolicyValidator.java
    ApiKeyCredentialExtractor.java
    ApiKeyCredentialResolver.java
    ApiKeyPrincipalFactory.java
    ApiKeyAuthorizationService.java
    ApiKeyAuthenticationFilter.java
    ApiKeyAuthorizationInterceptor.java
    ApiAuthorizationCoverageValidator.java
    RequiresApiAction.java
    LegacyApiAuthorizationAdapter.java
    TrustedClientIpResolver.java

  .../core/security/quota/
    ApiKeyQuotaBackend.java
    ApiKeyQuotaLease.java
    LocalApiKeyQuotaBackend.java
    RedisApiKeyQuotaBackend.java

  .../core/service/
    ApiKeyFamilyManagementService.java
    ApiKeyRotationService.java
    ApiKeyRevocationService.java
    ApiKeyBootstrapService.java
    ApiKeyRecoveryService.java
    ApiKeyLifecycleAuditService.java
    ApiKeyLastUsedRecorder.java

  .../core/config/
    RagRootApiKeyProperties.java
    RagApiKeyProperties.java
    RagWebSecurityConfiguration.java

  .../core/controller/
    ApiKeyIdentityController.java

  .../resources/db/migration/
    V25__api_key_family_expand.sql
    VNN__api_key_legacy_contract.sql

spring-ai-rag-starter/
  GeneralRagAutoConfiguration.java

spring-ai-rag-webui/
  src/auth/ApiKeyAuthContext.tsx
  src/api/apikeys.ts
  src/pages/ConsoleUnlock.tsx
  src/pages/ApiKeys.tsx
  src/hooks/useSSE.ts
```

包名可按现有风格微调，但禁止把 secret lifecycle、policy 和 HTTP mapping 重新塞进单一
Controller。

---

## 24. 分阶段实施

以下 Phase 和 PR 是实现、评审与合并顺序，不是独立 production release。V25 migration
不能与 legacy 日志 bootstrap、starter-only auth 或未完成的 family management 组合部署。
首个允许进入任何环境滚动升级流程的 Expand release A 必须原子包含 Phase 1-6，并由
release gate 验证旧 bootstrap 已替换、共享 auth 已启用、management coverage validator
已生效、shared quota 已满足目标环境要求。中间 PR 只能用于受控开发/CI，不能生成生产
部署批准。

本工程有三个交付里程碑，避免把“最短可用”、外部生产就绪和长期 contract 清理混为一谈：

**Milestone MVP-0：standalone RAG service usable**

- 完成 Phase 0 和 Phase M0。
- 单实例、TLS、受控管理网络运行。
- root API Key 可解锁 WebUI，并创建、列出、轮换和吊销 `FULL_RAG` 业务 Key。
- 外部用户/系统仅持业务 Key 调用现有 RAG API，不依赖 WebUI。
- 不宣称多实例、shared quota、细粒度 policy 或公网管理面 production-ready。

**Milestone A：external data-plane ready**

- 完成 Phase 0–6。
- Phase 1–6 作为同一个可部署 Expand A 原子发布单元通过 release gate。
- Expand release A 已在全部实例部署，management 门禁已解除。
- restricted external family、双实例 revoke/quota、V24 回滚安全性和日志无 raw 已验证。
- 达到该里程碑后，OpenAI 兼容层等消费者可以接入，并可按受控发布流程开放数据面。

**Milestone B：hardening lifecycle complete**

- 完成 Phase 7 的 family-only bridge、观察窗和 contract migration。
- legacy table/code 已删除，回滚目标收敛到 family-only release。

Milestone B 是最终清理门禁，但不是 Milestone A 后所有新消费者开发和灰度的前置条件。
在 contract 前启用外部数据面时，runbook 必须接受“回滚 V24 后 new-only credential
不可用”的已定义语义。

### Phase 0：Characterization 和门禁

1. 重新核对当前 commit、Flyway 最大版本和工作树。
2. 保存 `mvn test`、targeted API Key tests 和 `scripts/e2e-test.sh` 基线。
3. 增加 characterization tests：
   - static/null caller create/rotate。
   - NORMAL self-create。
   - ADMIN rotation 降级。
   - 30 秒 cache。
   - 每请求 last-used。
   - starter/core filter 装配差异。
   - 当前 E2E 对 static ADMIN 的错误假设。
4. 冻结 legacy `/api/**` 兼容矩阵。
5. 冻结实质性迁移和回滚验收。

完成标准：

- 尚未改变生产行为。
- 当前真实行为由测试记录。
- V25 名称仍未被其他迁移占用。

### Phase M0：WebUI root-key 独立服务 MVP

> 实施状态：已于 2026-08-14 完成；验证证据和三轮实现审查见
> [API Key WebUI MVP 实施进度](2026-08-14_API_KEY_WEBUI_MVP_PROGRESS.md)。

该阶段不新增传统账号系统，不要求先执行 V25 family migration：

1. 新增独立 `RAG_ROOT_API_KEY` 配置和 environment-root resolver；与 legacy static key
   完全分离，constant-time 验证，不记录 raw。
2. 在 core 建立 standalone 可加载的最小共享认证装配，并让 starter 复用；避免 Filter
   缺失或重复；有效 root 配置自动保护 management 和现有 `/api/**` 数据面。
3. management path 无论 global auth flag 都必须认证；仅 environment root 可
   create/list/revoke/rotate，关闭 NORMAL self-create 和 self-rotate 管理入口。
4. MVP 模式关闭旧 `ApiKeyBootstrapService` 自动 ADMIN 和 raw 日志分发；空表由 root
   显式创建第一个业务 Key。
5. 增加 `GET /api/v1/rag/auth/me`，供 WebUI 验证 root 和读取 capabilities。
6. 通过现有表签发 NORMAL `FULL_RAG` Key；服务端强制 expiry 在未来但不设固定最长
   有效期，保持 revoke、last-used 和 Collection ACL，API-created Key不能管理 Key。
7. 增加 WebUI unlock route、内存 auth context、管理 route guard 和显式退出。
8. streaming credential 从 query 移到 Header；root 和业务 raw key 都不进入
   localStorage/sessionStorage、URL、日志或 console。
9. WebUI 启动时清除旧 API Key localStorage 项，并删除 Settings 中的旧持久化入口。
10. 增加 root -> WebUI -> create -> external read/write -> revoke 的后端 E2E 和
   Playwright 流程，并记录单实例/受控网络部署边界。

完成标准：

- 设置 root 环境变量并启动 standalone 服务后，可在 WebUI 解锁控制台。
- root 可创建、列表、轮换和吊销业务 Key。
- 新业务 Key可调用检索、对话、文档写入、Collection 维护和向量更新等现有数据面，
  但调用管理 API 返回 403。
- 缺失或已过期的业务 Key expiry 创建请求被拒绝；长期未来 expiry 可创建。
- 轮换保留现有未来 expiry；legacy 永不过期 Key获得一年后的 expiry；已过期 Key不能
  轮换。
- 外部调用流程无需 WebUI。
- 未配置 root 时保持 legacy 启动行为；配置弱值时启动失败。
- 有效 root 配置下，未携带 root/业务 Key 的数据面请求返回 401。
- 空表启动不再生成或日志输出 ADMIN raw secret。
- root 模式不接受 query credential，create/rotate response 带 `no-store`。
- 无 raw secret 出现在持久化、URL 或日志。
- 当前交付明确限制为单实例；不能误标为 Milestone A。

### Phase 1：Expand schema 与模型

1. 增加 V25 preflight、plaintext null constraint、审计 ID 列扩容和新表。
2. 增加 timezone placeholders。
3. 同步 `RagAuditLog` 列宽，增加 family/version/operation/security-state entities 和
   repositories。
4. 增加 typed policy、validator 和 secure defaults。
5. 增加 V24 -> V25 Testcontainers migration tests。
6. 回填 legacy rows，但不授予新 external actions。
7. 验证 migration table lock 会等待/拒绝并发 legacy write，且超时后完整回滚。

完成标准：

- fresh install 和 V24 upgrade 均通过。
- plaintext/malformed/timezone 未确认均 fail。
- V24 app schema validation 仍可通过 expand schema。

### Phase 2：Resolver、Principal 和 Legacy Adapter

1. SecureRandom secret/public ID。
2. immutable principal。
3. family/version/ancestor resolver。
4. no-positive-cache external validation。
5. Bearer + X-API-Key conflict rules。
6. static explicit legacy principal。
7. management path 永不 anonymous。
8. legacy `/api` authorization adapter。
9. protocol-neutral failure classification。
10. 在 core 建立最小共享 `RagWebSecurityConfiguration`，注册 path-aware authentication
    和 authorization interceptor；standalone 由扫描加载，starter 由 import 加载。
11. starter 删除重复 auth registration，并增加两个 topology 的 missing/duplicate bean
    integration tests；legacy rate-limit 暂留到 Phase 5 替换。

完成标准：

- migrated legacy Key 行为不变。
- new family principal 不依赖 JPA entity request attribute。
- DB/policy error 返回 503，不 fallback。
- core standalone 与 starter consumer 的 management/auth 链一致且无重复 Bean。

### Phase 3：Management Lifecycle

1. create + Idempotency-Key。
2. family list/metadata。
3. same-family rotation、overlap 和 immediate。
4. family revoke、version revoke 和 descendants。
5. policyVersion update。
6. security state locks 和最后 ADMIN。
7. transactional lifecycle audit。
8. no-store/TLS guard。
9. 为全部 management handler 声明 action/policy metadata，并在同一变更中启用
   `ApiAuthorizationCoverageValidator`；不能先启用 validator 再等待后续 PR 补标。

完成标准：

- raw 只返回一次。
- rotation 保持 family。
- response 丢失不会创建重复 secret。
- audit 失败导致管理事务回滚。
- management handler 漏标授权策略时 core/starter context 均启动失败。

### Phase 4：Bootstrap、Recovery 和 Last Used

1. file/env bootstrap 输入和 prod mode guard。
2. PostgreSQL advisory lock。
3. break-glass ADMIN shadow。
4. explicit recovery runner。
5. readiness invariant。
6. async/throttled last-used。

完成标准：

- prod 日志没有 raw ADMIN。
- 多实例只 bootstrap 一次。
- 无 ADMIN 时不隐式提权。

### Phase 5：Shared Web Security 与 Quota

1. 在 Phase 2 的共享配置上增加 `PreAuthIpRateLimitFilter` 和 `ApiKeyQuotaFilter`。
2. 删除 starter legacy `RateLimitFilter` registration，并验证无重复/遗漏。
3. trace/pre-auth/auth/quota 显式 order。
4. local family quota backend。
5. Redis/shared quota backend 或批准的 gateway backend。
6. child + ancestors atomic quota。
7. stream concurrency lease 和 cancel cleanup。
8. trusted proxy IP。

完成标准：

- standalone/starter 安全链一致。
- rotation 不重置 quota。
- 两个实例共享 revoke/quota 语义。
- shared backend 故障 fail closed。

### Phase 6：WebUI、运维和文档

1. stream secret 从 query 改 Header。
2. raw modal memory cleanup。
3. family/policy UI。
4. credential mode 和生产 UI guard。
5. configuration/rest-api/deployment/testing 双语文档。
6. Kubernetes Secret、Redis/gateway 和 rollback runbook。
7. E2E/Playwright。

完成标准：

- 生产管理 UI 不依赖 localStorage ADMIN key。
- query secret 不再由当前 fetch streaming 路径产生。
- 运维可以创建、轮换、吊销和恢复。

### Phase 7：Cutover 与 Contract

1. 全量升级、解除维护门禁。
2. family-only bridge release。
3. 观察窗和回滚演练。
4. contract migration 删除 legacy table。
5. 删除 legacy code。

完成标准：

- contract 前后回滚目标明确。
- old `/api` 回归通过。
- new external Key 不会被旧版本宽松接受。

---

## 25. 建议 PR 拆分与工作量

| PR | 内容 | 估计 |
|---|---|---|
| M0 | environment root、共享 standalone auth、WebUI unlock、root-only management、E2E | 3-5 人日 |
| 1 | Characterization、shared topology tests、迁移 preflight | 2-3 人日 |
| 2 | V25 family/version/operation/security-state、policy、backfill | 4-6 人日 |
| 3 | resolver、principal、Bearer、legacy adapter、最小共享 auth topology、failure mapping | 3-5 人日 |
| 4 | management lifecycle、idempotency、audit、ADMIN guard | 4-6 人日 |
| 5 | bootstrap/recovery/last-used、完整 filter order、local/shared quota、多实例测试 | 4-7 人日 |
| 6 | WebUI、运维脚本、文档、E2E、family-only cutover 准备 | 3-5 人日 |

Phase 0 + Phase M0 约 5-8 人日，可先形成单实例独立 RAG 服务。Expand 到 Milestone A
仍约 20-32 人日；实际 contract 删除可在观察窗后单独 PR，约 2-3 人日。

若已有可靠 Redis/gateway/IAP 可复用，工作量下降；若需要本项目同时建设这些平台能力，
需单独估算。该估计高于总规划早期粗估，是因为拆分后把 mixed-version、幂等、最后 ADMIN、
多实例 quota 和安全 WebUI 纳入了可验收范围。

---

## 26. 测试规划

### 26.1 Unit tests

Secret：

- SecureRandom bytes 和格式。
- public IDs 长度、collision retry。
- hash deterministic。
- DTO/toString 不含 raw。

Policy：

- unknown action/schema fail closed。
- ALL/LIST/NONE。
- empty external scope 不成为 ALL。
- limit 和 expiry。
- role/action 上限。
- parent-child subset。
- ancestor intersection。

Resolver：

- Bearer。
- X-API-Key compatibility。
- 双 Header 相同/冲突。
- static explicit legacy。
- unknown/expired/revoked/retireAt。
- malformed policy -> 503。
- DB error -> 503，无 static fallback。

Lifecycle：

- create secure defaults。
- idempotency。
- 同一 Idempotency-Key 改变 validated request 时返回 conflict。
- API Key operator 与可信 human operator 的幂等命名空间隔离。
- same-family rotation。
- overlap/immediate。
- expired family rotation 拒绝。
- family/version revoke 区分。
- descendants。
- last ADMIN。
- audit failure rollback。

Quota：

- family stable bucket。
- overlap versions same bucket。
- child + ancestor buckets。
- stream complete/error/cancel release。
- lease expiry。
- shared backend failure。

### 26.2 MVC/Filter tests

- environment root valid/weak/placeholder；missing 保持 legacy mode。
- 有效 root 自动保护 management 和数据面，即使 legacy global auth disabled。
- root 模式拒绝 query credential；Bearer/X-API-Key Header 行为和冲突规则可验证。
- rotate null/超长/已过期 expiry 的收敛或拒绝语义。
- `/auth/me` 对 root 和业务 Key返回各自 capabilities；WebUI 只接受 environment root。
- management security 不受 global auth disabled 影响。
- legacy static/null/NORMAL 管理拒绝。
- MVP 空表启动不生成 ADMIN、不记录 raw secret。
- no-store headers。
- 401/403/409/429/503。
- raw/hash 不出 list/error。
- explicit filter order。
- `/api/*` 和 future `/v1/*` patterns。
- core standalone 与 starter context 无 duplicate/missing bean。
- management/new external handler 漏标 action/policy metadata 时两个 topology 均启动失败。

### 26.3 PostgreSQL integration

使用 Testcontainers：

- family/version repositories。
- hash unique。
- policy JSONB。
- row locks。
- concurrent rotation。
- concurrent last ADMIN revoke。
- recursive family revoke。
- lifecycle audit transaction。
- conditional last-used update。

### 26.4 Migration tests

- fresh V1-V25。
- populated V24 -> V25。
- ADMIN/NORMAL/expired/disabled。
- restricted/unrestricted ACL。
- plaintext row fail。
- malformed ACL fail。
- timezone missing/invalid/unconfirmed fail。
- timezone conversion。
- lifecycle audit 的 80 字符 target ID 和 160 字符 human operator ID 可无损写入。
- 并发 legacy create/rotate/revoke 不能与 V25 回填交错；锁超时不留下部分 schema/backfill。
- rerun/idempotence。
- external Key absent from shadow。
- break-glass ADMIN shadow。
- family-only bridge。
- contract。
- allowed rollback targets。

### 26.5 Multi-instance

启动两个 application context，共享 PostgreSQL 和 Redis/shared backend：

- A 验证后 B revoke，A 下一请求失败。
- policy 收窄对两个实例生效。
- rotation immediate/retireAt。
- RPM 在两个实例合计不超过 family limit。
- child fan-out 不超过 ancestor limit。
- concurrency 在两个实例和 stream cancel 下正确。
- quota backend failure 503。

### 26.6 WebUI

- 未解锁时只显示 unlock route。
- root unlock 成功/失败、刷新后重新输入、退出清空内存。
- 业务 Key不能解锁管理控制台。
- root 创建 `FULL_RAG` Key、raw shown-once、列表、轮换和吊销。
- expiry 必填、默认一年且可提交长期未来值；过去时间展示服务端 validation error。
- 启动时清除旧 `rag-api-key` / `rag-api-key-role`，Settings 不再提供持久化入口。
- 外部业务 Key调用 RAG API 不依赖 WebUI。
- prod 禁止 localStorage admin。
- streaming Header，不含 query key。
- raw create/rotate modal 关闭即清理。
- family/policyVersion conflict。
- role/scope/limits 表单 validation。
- Playwright 检查浏览器 URL、localStorage、console 和网络请求。

### 26.7 Secret regression

自动扫描：

- database rows。
- application log。
- audit rows。
- metrics exposition。
- HTTP error body。
- WebUI storage。
- access log fixture。

测试 secret 只能使用明显 fake 值。

### 26.8 项目门禁

实施完成至少执行：

```bash
mvn test
scripts/e2e-test.sh
scripts/real-llm-e2e-smoke.sh
```

OpenAI 兼容层实施时再叠加官方 Python/Node SDK E2E。API Key migration、并发和多实例测试
不得只使用 mock repository。

---

## 27. 可观测性与 Readiness

### 27.1 Metrics

低基数 tags：

- `rag.auth.api_key.validation{result,reason}`
- `rag.auth.api_key.policy_denied{action,reason}`
- `rag.auth.api_key.lifecycle{operation,result}`
- `rag.auth.api_key.last_used_write{result}`
- `rag.auth.api_key.quota{backend,result,kind}`
- `rag.auth.api_key.bootstrap{result}`
- `rag.auth.api_key.cache_invalidation{result}`（未来启用缓存时）

metrics tag 不含 keyId、familyId、owner、raw。

### 27.2 Logs

可记录：

- trace ID。
- public keyId/familyId。
- operation。
- result。
- policyVersion。

不记录 secret/hash/完整 policy/body。

### 27.3 Readiness

新增组件：

- credential store 可用。
- policy schema 可加载。
- 存在可用 ADMIN/break-glass。
- production shared quota backend 可用。
- legacy mixed-version mode 是否符合当前发布阶段。
- plaintext null constraint 是否存在。

`require-shared=true` 而 backend=local 时 readiness DOWN 或 startup fail。

---

## 28. 上线 Runbook

### 28.1 Expand 前

1. 备份数据库。
2. 运行 plaintext/ACL/timezone/admin 报告。
3. 轮换 plaintext rows。
4. 设置并确认 legacy timezone。
5. 准备 bootstrap/break-glass secret。
6. 准备 Redis/shared quota。
7. 在所有 gateway、内部 ingress 和直连入口启用 Key management write 维护门禁，并从
   每个可达入口验证 create/rotate/revoke/policy update 已被拒绝。
8. 保持外部兼容 endpoint disabled。

### 28.2 Expand 滚动升级

1. 再次验证所有入口的 management write 门禁仍生效。
2. 部署 V25 schema；迁移必须获取 legacy table lock，锁超时则停止发布。
3. 滚动升级所有实例。
4. 验证两种 topology Bean 和 Filter。
5. 验证 legacy data path。
6. 验证 shadow、family/version 回填不存在遗漏或孤儿。
7. 验证日志和 DB 无 raw。

### 28.3 解除门禁

1. 确认可用 break-glass ADMIN。
2. 解除 management write。
3. 创建有限 external test family。
4. 测试 create/rotate/revoke/policy。
5. 测试两个实例 revoke/quota。
6. 演练回滚到 V24，确认 external Key 不可用且不提权。
7. 恢复 expand release。

### 28.4 Family-only 与 contract

1. 部署 family-only bridge。
2. 观察一个完整回滚窗口。
3. 确认没有 legacy table reads/writes。
4. 备份并执行 contract。
5. 验证 contract rollback target。

---

## 29. 风险清单

| 风险 | 严重度 | 缓解 |
|---|---|---|
| plaintext schema 被未来代码重新写入 | 高 | V25 CHECK NULL + entity 移除 + contract drop |
| 新 external Key 被 V24 宽松接受 | 高 | new-only tables + mixed-version 禁管理 |
| rotation 丢 owner/policy/quota | 高 | family/version 分层 |
| overlap 形成永久双活 | 高 | retireAt 硬截止 + active version 上限 |
| response 丢失产生重复 secret | 高 | scoped idempotency operation，不重放 raw |
| 最后 ADMIN 被并发/级联移除 | 高 | security-state 行锁 + family invariant |
| ADMIN 自动过期导致失管 | 高 | 独立 break-glass + readiness/alert |
| NORMAL child 放大权限/配额 | 高 | 默认禁委派；ancestor intersection + quota |
| DB/policy 故障降级 static | 高 | explicit static principal；其他故障 503 |
| 多实例 revoke 被 cache 延迟 | 高 | 首版无 positive cache |
| 多副本 local quota 倍增 | 高 | production require shared |
| Redis concurrency lease 泄漏 | 高 | TTL + normal release + cancel tests |
| raw key 进入 URL/浏览器日志 | 高 | Header streaming + query deprecation + Playwright |
| raw ADMIN 进入集中日志 | 高 | provided bootstrap secret，不打印 |
| audit 失败但管理成功 | 高 | 同事务 mandatory lifecycle audit |
| 无时区 expiry 错迁 | 高 | explicit IANA timezone + confirmed placeholder |
| malformed legacy ACL 变 ALL | 高 | migration fail closed |
| core/starter 重复或缺少 Filter | 高 | shared config + 双拓扑 integration |
| 直接信任 X-Forwarded-For | 高 | trusted proxy resolver |
| 每请求 last-used 写放大 | 中 | async + conditional throttled update |
| family list 改坏旧 WebUI | 中 | legacy-compatible summary + UI 同 PR |
| role 与 action 语义混淆 | 中 | role 仅上限，authorization 只看 effective policy |
| policy JSON 演进不兼容 | 中 | schemaVersion + strict validator |
| Redis 依赖增加部署复杂度 | 中 | optional backend + explicit production gate |

---

## 30. 验收标准

### MVP-0 standalone service

- [x] 项目不新增 username/password、用户表或账号 session。
- [x] `RAG_ROOT_API_KEY` 与 legacy `RAG_API_KEY` 完全分离；未配置时保持 legacy mode，
  已配置但少于 32 个 ASCII 字符或为 placeholder 时 fail startup。
- [x] 有效 root 配置自动保护 management 和现有 RAG 数据面，不能被 global auth
  disabled 绕过。
- [x] MVP 空表启动不自动生成或日志输出 ADMIN raw secret。
- [x] WebUI 可用 root 解锁，credential 只保存在页面内存。
- [x] WebUI 升级后主动清除旧 API Key localStorage 项，Settings 不再保存 credential。
- [x] root 是唯一可创建、列表、轮换和吊销业务 Key 的主体。
- [x] API-created Key 固定为 `FULL_RAG` 数据面权限，不能获得 root 或 Key 管理能力。
- [x] API-created Key expiry 必填、在未来且不设固定最长有效期。
- [x] rotate 保留现有未来 expiry；legacy 永不过期 Key获得一年 expiry，已过期 Key不能
  轮换。
- [x] 业务 Key可完成现有 RAG 读取和写入主流程，并可受 Collection ACL 限制。
- [x] 外部用户/系统只携带 Key 调用 API，不需要访问 WebUI。
- [x] root/业务 raw key 不进入 DB 明文、URL、日志、console、localStorage 或 sessionStorage。
- [x] root 模式拒绝 query credential；create/rotate response 使用 `no-store`。
- [x] standalone core 与 starter 的认证装配无遗漏、无重复。
- [x] 文档明确 MVP-0 为单实例、TLS、受控管理网络，不等同 Milestone A。

### Secret

- [ ] 新 secret 至少 256 bit SecureRandom。
- [ ] 新 familyId/keyId 至少 128 bit。
- [ ] raw 只在 create/rotate/bootstrap 输入或成功响应出现一次。
- [ ] DB/entity/audit/log/metrics/URL/WebUI storage 不含 raw。
- [ ] create/rotate response 有 no-store。
- [ ] V25 后 plaintext column 受 NULL constraint；contract 后 legacy table 删除。

### Principal 与 Policy

- [ ] family/version 分层。
- [ ] immutable principal 含 effective policy。
- [ ] unknown/malformed policy fail closed。
- [ ] external owner/expiry/action/deployment/Collection/limits 明确。
- [ ] empty scope 不成为 ALL。
- [ ] ADMIN 数据面权限不自动 bypass。
- [ ] legacy unrestricted 只在 legacy adapter。

### Management

- [ ] management endpoint 在 global auth disabled 时仍不匿名。
- [ ] management 和已启用 `/v1` handler 漏标授权策略时 fail startup，不能默认放行。
- [ ] static/null caller 不能 create/list/revoke/rotate/policy。
- [ ] NORMAL 默认不能 create child。
- [ ] create/rotate idempotent 且不重放 raw。
- [ ] family revoke 与 version revoke 语义分离。
- [ ] 从任一 keyId 可撤销完整 family。
- [ ] policyVersion 冲突可检测。
- [ ] lifecycle audit 与状态变更同事务。
- [ ] 最大长度 family/key ID 和可信 human operator ID 可写入审计表，不截断、不导致事务失败。

### Rotation 与 Revoke

- [ ] rotation 保持 familyId/owner/policy/expiry/quota。
- [ ] rotation 不延长 family expiry。
- [ ] overlap 有硬上限和 retireAt。
- [ ] immediate cutover 可用。
- [ ] family revoke 覆盖 versions 和 descendants。
- [ ] child 请求受全部 ancestors 约束。
- [ ] 最后 ADMIN 在并发/级联/expiry/policy 下受保护。

### Bootstrap

- [ ] prod 不自动打印 raw ADMIN。
- [ ] 多实例只 bootstrap 一次。
- [ ] 无 ADMIN readiness DOWN。
- [ ] recovery 显式、锁保护、强审计。
- [ ] expand rollback 不触发 V24 第二次日志 bootstrap。

### Multi-instance 与 Quota

- [ ] 首版 external auth 无 positive cache，或有被验证的跨实例失效。
- [ ] A 节点 revoke/policy 后 B 节点下一请求按定义失败。
- [ ] quota 使用 familyId，不使用 raw。
- [ ] rotation 不重置 quota。
- [ ] child/ancestors 同时扣减。
- [ ] stream 所有终态释放 concurrency。
- [ ] production shared backend 故障 fail closed。

### Migration

- [ ] fresh、V24 upgrade、mixed-version、family-only、contract 全通过。
- [ ] plaintext/malformed ACL/timezone 未确认会阻止 migration。
- [ ] V25 扩容审计 ID 列并与 `RagAuditLog` 映射一致。
- [ ] management write 门禁先于 V25 生效，migration table lock 阻止并发 legacy 写入。
- [ ] lock timeout 完整回滚，回填后 legacy 与 family/version 无遗漏或孤儿。
- [ ] existing Key 不自动获得新 external actions。
- [ ] new external Key 不写 legacy shadow。
- [ ] V24 rollback 时 new external Key 不可用而不是提权。
- [ ] contract 后不允许回滚 V24。

### Topology 与 WebUI

- [ ] core standalone 和 starter consumer 安全链一致。
- [ ] Filter order 明确。
- [ ] 管理 WebUI prod 不持久化 ADMIN key。
- [ ] current fetch streaming 不把 secret 放 query。
- [ ] Playwright 未发现 URL/localStorage/console secret。

### 回归

- [ ] legacy `/api/v1/rag/**` 既有契约通过。
- [ ] `mvn test` 全过。
- [ ] `scripts/e2e-test.sh` 全过并修正 static ADMIN 错误假设。
- [ ] migration 和多实例测试使用真实 PostgreSQL/shared backend。
- [ ] 真实 LLM smoke 全过。

---

## 31. 实施前批准项

开始生产代码前需批准：

1. MVP-0 使用独立 `RAG_ROOT_API_KEY`，不复用 legacy static key。
2. MVP-0 不建设 username/password；WebUI 使用内存 root-key unlock。
3. MVP-0 只签发不能管理 Key 的 `FULL_RAG` 业务 Key，并限制为单实例、TLS、受控管理网络。
4. 使用 family/version/operation/security-state 四类目标数据。
5. 新 secret 改用 256 bit SecureRandom；public IDs 加长。
6. external NORMAL expiry 必填；deployment/policy max TTL 为可选显式配置，默认关闭。
7. NORMAL self-service create 默认关闭。
8. role 只作为管理能力上限，数据面由显式 policy 决定。
9. `/v1` 等新 external path 只接受 database family principal。
10. static key 仅保留 legacy `/api/**` 数据面，不可管理 Key。
11. management endpoint 不受 global auth disabled 的匿名兼容。
12. create/rotate 使用 Idempotency-Key；成功响应丢失时不重放 raw。
13. rotation 默认 5 分钟 overlap、硬上限 15 分钟；支持 ADMIN immediate。
14. `DELETE /api-keys/{keyId}` 表示 family revoke；新增 version-only revoke endpoint。
15. family revoke 对 descendants 级联。
16. lifecycle audit fail closed 并与管理事务原子提交。
17. production bootstrap 使用 Secret Manager/file 输入，不打印 raw。
18. 至少保留一个独立 break-glass ADMIN family。
19. external 首版不使用 positive auth cache。
20. production 多实例要求 Redis/shared quota backend；local backend 只用于单实例/dev。
21. V25 migration 要求显式确认 legacy timestamp source timezone。
22. expand 期间阻断 management writes，并保持 external compatibility disabled。
23. new external Key 不写 legacy shadow；V24 回滚时明确不可用。
24. production WebUI 没有可信 external session 时保持关闭或仅部署在受控管理网络。

只批准最短可用路径时，先执行 Phase 0 + Phase M0，不开始 V25 migration；批准完整加固后
再继续 Phase 1-7。任何路径都不能跳过对应 characterization、management authorization
和 secret regression 测试。
