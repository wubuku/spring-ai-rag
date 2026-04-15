# API Key 管理重构规划

> 起草日期：2026-04-15  
> 状态：✅ 已定稿（经 5 轮审查）

---

## 一、现状（代码审查结果）

### 已有完整功能的组件

| 组件 | 路径 | 说明 |
|------|------|------|
| `ApiKeyManagementService` | `core/service/` | generateKey / revokeKey / rotateKey / listKeys / validateKeyEntity |
| `ApiKeyController` | `core/controller/` | `/api-keys` CRUD 端点，使用 ApiKeyManagementService |
| `ApiKeyAuthFilter` | `core/filter/` | 验证 X-API-Key，存 `AUTHENTICATED_KEY_ATTRIBUTE = "authenticatedApiKey"`（String keyId）|
| `RagApiKeyRepository` | `core/repository/` | findByKeyHash / findByKeyId / disableByKeyId / updateLastUsed |
| `RagApiKey` entity | `core/entity/` | keyId + keyHash(SHA-256) + apiKey(plain) + createdAt + enabled + role(**待加**) |
| DTOs | `api/dto/` | ApiKeyCreateRequest / ApiKeyCreatedResponse / ApiKeyResponse |

### 现有 key 格式

```
前缀：rag_sk_（ApiKeyManagementService.generateRawKey() 生成）
格式：rag_sk_ + UUID_without_dashes
存储：SHA-256(raw_key) → keyHash
验证：validateKeyEntity() → hash 查找，有效返回 RagApiKey entity
```

### 真正缺失的功能

1. **启动引导**：首次启动没有自动生成 admin key
2. **角色分层**：RagApiKey 没有 role 字段，所有 key 等价
3. **权限控制**：Controller 没有角色校验（任何 key 都能调用所有端点）
4. **前端集成**：WebUI 没有把 RAG API Key 传给 SSE

---

## 二、设计方案

### 2.1 核心原则

**不重写已有功能**。增量实现：
- Bootstrap + role → 在现有 entity + service 基础上加
- 权限校验 → Controller 层新增（不动 Service）
- key 格式不变（rag_sk_ 前缀，保持兼容）

### 2.2 启动引导（Bootstrap）

**逻辑**：
```
应用启动 → 检查 rag_api_key 表
→ 表为空 → 生成 admin key（rag_sk_ 前缀，与现有系统一致）
           → 设置 role = ADMIN
           → 打印到启动日志
→ 表不为空 → 跳过
```

**不改变现有 key 格式**（不用 rag-admin- 前缀，避免破坏 validateKeyEntity 的 rag_sk_ 前缀判断）。

### 2.3 权限矩阵

| 操作 | admin key | normal key |
|------|-----------|------------|
| GET /api-keys（列表） | ✅ | ❌ 403 |
| POST /api-keys（创建） | ✅ | ✅（self-service，只能创建 NORMAL key） |
| DELETE /api-keys/{keyId} | ✅ | ❌ 403 |
| RAG Chat/Search | ✅ | ✅ |

### 2.4 后端实现

#### 2.4.1 Entity 变更（确认 + 新增）

`RagApiKey.java` 确认已有字段：id / keyId / keyHash / name / createdAt / lastUsedAt / expiresAt / enabled / apiKey

**新增字段**：

```java
// RagApiKey.java 新增
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private ApiKeyRole role = ApiKeyRole.NORMAL;
```

⚠️ **import 注意事项**：从 `import jakarta.persistence.*;` 改为显式 import 时，必须包含 `jakarta.persistence.Index`（@Table 的 indexes 参数用到）。

#### 2.4.2 ApiKeyRole 枚举（新建）

```java
// core/entity/ApiKeyRole.java
package com.springairag.core.entity;

public enum ApiKeyRole {
    ADMIN,   // 可管理 keys 和 collections
    NORMAL   // 仅 RAG 功能
}
```

#### 2.4.3 Repository 方法（新增）

`RagApiKeyRepository.java` 新增方法：

```java
// 用于 BootstrapService：按创建时间排序
List<RagApiKey> findAllByOrderByCreatedAtDesc();
```

> `findByKeyId` / `findByKeyHash` / `disableByKeyId` / `updateLastUsed` 已存在，无需修改。

#### 2.4.4 ApiKeyBootstrapService（新建）

```java
package com.springairag.core.service;

@Service
public class ApiKeyBootstrapService implements ApplicationRunner {

    private final ApiKeyManagementService apiKeyManagementService;
    private final RagApiKeyRepository apiKeyRepository;

    public ApiKeyBootstrapService(ApiKeyManagementService apiKeyManagementService,
                                   RagApiKeyRepository apiKeyRepository) {
        this.apiKeyManagementService = apiKeyManagementService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public void run(ApplicationArguments args) {
        if (apiKeyRepository.count() > 0) {
            return; // 已有 key，跳过
        }

        // 生成 admin key（rag_sk_ 前缀，与现有系统完全一致）
        ApiKeyCreatedResponse admin = apiKeyManagementService.generateKey(
            new ApiKeyCreateRequest("Admin Key (auto-generated)", null));

        // 找到刚创建的 entity，设置 role = ADMIN
        RagApiKey key = apiKeyRepository.findByKeyId(admin.getKeyId())
            .orElseThrow(() -> new IllegalStateException("Key not found: " + admin.getKeyId()));
        key.setRole(ApiKeyRole.ADMIN);
        apiKeyRepository.save(key);

        log.info("");
        log.info("================================================================================");
        log.info("🔑  FIRST-TIME SETUP: Admin API Key Generated");
        log.info("================================================================================");
        log.info("  Public Key ID: {}", admin.getKeyId());
        log.info("  Raw API Key:   {}", admin.getRawKey());
        log.info("  ⚠️  Save the raw key now — it cannot be retrieved again.");
        log.info("================================================================================");
        log.info("");
    }
}
```

> 注意：`generateKey()` 不设置 `role`（字段当时还不存在），所以需要后续 UPDATE。这在同一 `@Transactional` 中完成。

#### 2.4.5 Filter 变更（关键）

现有 `AUTHENTICATED_KEY_ATTRIBUTE = "authenticatedApiKey"` 存 **String keyId**（给 `RateLimitFilter` 用，依赖 `instanceof String`）。

**向后兼容方案**：新增常量存 entity，原有 String 属性不变。

```java
// ApiKeyAuthFilter.java 新增常量
public static final String AUTHENTICATED_API_KEY_ENTITY = "rag.authenticatedApiKeyEntity";

// doFilterInternal 中，key 验证成功后：
request.setAttribute(AUTHENTICATED_KEY_ATTRIBUTE, validatedKey.getKeyId());     // 不变（String，给 RateLimitFilter）
request.setAttribute(AUTHENTICATED_API_KEY_ENTITY, validatedKey);              // 新增（RagApiKey entity）
```

#### 2.4.6 Controller 权限校验（修改现有 ApiKeyController）

在现有端点上增加角色检查。**调用 `ApiKeyManagementService` 的逻辑完全不变**，只新增权限判断：

```java
// ApiKeyController.java 新增辅助方法
private ApiKeyRole getCallerRole(HttpServletRequest request) {
    // 优先用 entity（filter 已存）
    Object entity = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY);
    if (entity instanceof RagApiKey key) {
        return key.getRole();
    }
    // fallback：从未知 keyId 查 DB（不应该走到这里）
    String keyId = (String) request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
    return apiKeyRepository.findByKeyId(keyId)
            .map(RagApiKey::getRole)
            .orElse(ApiKeyRole.NORMAL);
}

// 各端点权限：
// GET /api-keys     → requireRole(ADMIN)
// POST /api-keys    → 任何有效 key（self-service）
// DELETE /api-keys  → requireRole(ADMIN)
```

---

## 三、数据库迁移

### V22：`rag_api_key` 加 role 列

```sql
ALTER TABLE rag_api_key ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

-- 第一个 key 成为 ADMIN（按 created_at 排序）
UPDATE rag_api_key
SET role = 'ADMIN'
WHERE id = (SELECT id FROM rag_api_key ORDER BY created_at ASC LIMIT 1);

-- 索引
CREATE INDEX IF NOT EXISTS idx_rag_api_key_role ON rag_api_key (role);
```

---

## 四、迁移步骤

### Phase 1：后端（最小改动）

1. ✅ 创建 `ApiKeyRole` 枚举（`core/entity/ApiKeyRole.java`）
2. ✅ 修改 `RagApiKey` entity，加 `role` 字段（注意保留 `jakarta.persistence.Index` import）
3. ✅ `RagApiKeyRepository` 新增 `findAllByOrderByCreatedAtDesc()`
4. ✅ 创建 `ApiKeyBootstrapService`（调用现有 `ApiKeyManagementService`）
5. ✅ 修改 `ApiKeyAuthFilter`，新增 `AUTHENTICATED_API_KEY_ENTITY` 属性
6. ✅ 修改 `ApiKeyController`，增加角色校验（ApiKeyManagementService 调用逻辑不变）
7. ✅ 单元测试

### Phase 2：前端

1. `apiKeyStorage.ts`（localStorage 读写）
2. `Settings.tsx` 增加 RAG API Key 输入框
3. `ApiKeys.tsx` 对接后端
4. `Chat.tsx` 从 localStorage 取 key，传给 SSE

### Phase 3：E2E

---

## 五、验收标准

1. ✅ 首次启动，日志打印 admin key（含 keyId + rawKey）
2. ✅ admin key 可以 GET /api-keys（列出所有）
3. ✅ admin key 可以 DELETE /api-keys/{keyId}（删除任意）
4. ✅ normal key 调用 GET /api-keys 返回 403
5. ✅ normal key 可以 POST /api-keys 创建 NORMAL key（self-service）
6. ✅ 前端 Settings 可以输入/保存 RAG API Key
7. ✅ 前端 Chat SSE 自动带 ?apiKey=xxx
8. ✅ E2E 测试覆盖

---

## 六、风险与注意事项

| # | 风险 | 缓解 |
|---|------|------|
| 1 | Admin key 只显示一次，无找回机制 | 在日志中显眼提示 |
| 2 | 所有 admin key 等价，无法区分"谁是谁" | 通过 keyId 识别（人有 keyId 记录即可） |
| 3 | Normal key 可无限创建（无配额） | 内部使用场景可接受；后续可加 max 限制 |
| 4 | 前端 localStorage 不安全 | 适用于开发/内部使用 |
| 5 | Filter 存 entity 而非 String → RateLimitFilter 破坏 | 用两个属性：`AUTHENTICATED_KEY_ATTRIBUTE`（String）不变，`AUTHENTICATED_API_KEY_ENTITY`（entity）新增 |
| 6 | V22 migration 加 role 列默认值设为 NORMAL，但 entity 此时没有 role 字段 | BootstrapService 在 migration 后首次运行（应用启动时），此时 entity 已有 role 字段 ✅ |

---

_已定稿 — 2026-04-15_
