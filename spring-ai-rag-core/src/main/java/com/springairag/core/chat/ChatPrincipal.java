package com.springairag.core.chat;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Stable authenticated identity used for chat history and memory namespaces.
 *
 * <p>Raw credentials never enter this object. The identity is captured while
 * the servlet request is active so asynchronous streaming work does not need
 * ThreadLocal or request access.</p>
 */
public record ChatPrincipal(String id, String type, boolean admin) {

    /**
     * 三个非数据库身份的主键。同一批字符串在
     * {@code ProvisioningOwnerResolver} 里也各有一份 OWNER 常量，而两边是
     * <b>各走各的代码路径</b>算出同一个身份的（这里从 principal 类型派生，
     * 那里从认证快照派生）。漂移了不会有人发现，所以
     * {@code ChatPrincipalOwnerIdTest} 逐字钉住两边相等。
     */
    public static final String ENVIRONMENT_ROOT_ID = "root:environment-root";
    public static final String LEGACY_STATIC_ID = "legacy:static";
    public static final String AUTH_DISABLED_ID = "local:auth-disabled";

    /** 数据库 API key 的 id 前缀，后接 key 主键。 */
    public static final String DATABASE_KEY_ID_PREFIX = "db:";

    public ChatPrincipal {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("principal id must contain 1-128 characters");
        }
    }

    public static ChatPrincipal local() {
        return new ChatPrincipal(AUTH_DISABLED_ID, "AUTH_DISABLED", false);
    }

    public static ChatPrincipal fromCurrentRequest() {
        ServletRequestAttributes attributes = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current) {
            attributes = current;
        }
        return from(attributes != null ? attributes.getRequest() : null);
    }

    public static ChatPrincipal from(HttpServletRequest request) {
        if (request == null) {
            return local();
        }
        Object id = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object authenticated = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);
        String principalType = type != null ? String.valueOf(type) : null;

        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(principalType)) {
            return new ChatPrincipal(ENVIRONMENT_ROOT_ID, principalType, true);
        }
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(principalType)) {
            String keyId = id != null ? String.valueOf(id) : "unknown";
            boolean admin = authenticated instanceof AuthenticatedApiPrincipal principal
                    && principal.getRole() == ApiKeyRole.ADMIN;
            return new ChatPrincipal(DATABASE_KEY_ID_PREFIX + keyId, principalType, admin);
        }
        if (ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(principalType)) {
            return new ChatPrincipal(LEGACY_STATIC_ID, principalType, false);
        }
        return local();
    }

    /**
     * 从持久化的 {@code owner_principal_id} 重建身份——{@link #from} 的反向映射。
     *
     * <p>这段逻辑原先住在 {@code ChatTurnOperationService.principalFor} 里，是同一张
     * 「id -> 类型/权限」表的第二份实现。变异实验（Batch 867，N1）证明它可以与
     * {@link #from} 自由漂移而全套件无感：把这里重建出的环境根降级成非管理员，
     * 7629 个用例 0 红。现在两张表住在同一个类里。
     *
     * <p><b>对数据库 API key 而言这条映射是有损的</b>：落库的 id 只有
     * {@code db:<keyId>}，不带角色，所以重建结果一律 {@code admin=false}。
     * 这是刻意的 fail-closed——角色拿不回来时按最低权限解释，而不是从别处猜一个。
     * 三个非数据库身份本身不携带角色信息，往返无损。
     */
    public static ChatPrincipal fromOwnerId(String ownerId) {
        if (ownerId != null && ownerId.startsWith(DATABASE_KEY_ID_PREFIX)) {
            return new ChatPrincipal(
                    ownerId, ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, false);
        }
        if (ENVIRONMENT_ROOT_ID.equals(ownerId)) {
            return new ChatPrincipal(
                    ownerId, ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT, true);
        }
        if (LEGACY_STATIC_ID.equals(ownerId)) {
            return new ChatPrincipal(
                    ownerId, ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC, false);
        }
        return local();
    }

    /**
     * 能否读到归属字段上线之前写下的历史行。
     *
     * <p>这份判定原先在 {@code RagChatHistoryRepository} 与
     * {@code ChatExportService} 里各写了一份<b>逐字节相同</b>的私有方法，而只有
     * 导出那一侧有测试（变异实验 Batch 867 N2：只从仓库侧删掉环境根那一行，
     * 7629 个用例 0 红）。两份漂移的后果是「历史列表」与「导出」对同一批
     * legacy 行给出不同的可见性。现在只有这一份。
     */
    public boolean canReadLegacyRows() {
        return ENVIRONMENT_ROOT_ID.equals(id)
                || LEGACY_STATIC_ID.equals(id)
                || AUTH_DISABLED_ID.equals(id);
    }

    /**
     * Deterministic, bounded namespace accepted by Spring AI's VARCHAR(36) memory key.
     */
    public String memoryConversationId(String sessionId) {
        String input = id + "\u0000" + sessionId;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(36);
            for (int i = 0; i < 18; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
