package com.springairag.core.tenant;

import java.util.List;

/**
 * WP7：ACTIVE 租户目录（实施规划 §5.4「多租户用平台池枚举 ACTIVE 租户后逐租户调用」）。
 *
 * <p>实现方用平台池（{@code rag_tenant} 是 PLATFORM_GLOBAL 表）读取 ACTIVE 租户 id。
 * 枚举失败必须 fail-closed：不得退化为「按 default 跑一遍」或「无租户裸跑」。</p>
 */
public interface TenantDirectory {

    /**
     * 列出当前可执行任务的 ACTIVE 租户 id（稳定顺序）。
     *
     * @throws IllegalStateException 枚举失败时抛出；调用方必须 fail-closed，不得静默降级
     */
    List<String> activeTenantIds();
}
