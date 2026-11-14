package com.springairag.core.tenant;

import com.springairag.core.security.AuthenticatedApiPrincipal;

import java.util.Optional;

/**
 * WP5：租户解析 SPI（实施规划 §5.3.2）。
 *
 * <p>解析顺序由装配层保证：宿主可信 resolver（若提供）→ 凭据绑定 → 单租户 default。
 * SPI 本身只接收认证后的 principal，不接触 HttpServletRequest，
 * 从契约上禁止「从不受信 header 推导租户」。</p>
 *
 * <p>返回 empty 表示 fail-closed：调用方不得把 empty 当作 default。</p>
 */
public interface TenantResolver {

    /**
     * 解析当前请求应使用的 tenant_id。
     *
     * @param principal 已认证的快照；无认证时可为 null（仅 SingleTenantResolver 接受）
     * @return 解析出的 tenant_id；empty 表示无法安全解析
     */
    Optional<String> resolveTenantId(AuthenticatedApiPrincipal principal);
}
