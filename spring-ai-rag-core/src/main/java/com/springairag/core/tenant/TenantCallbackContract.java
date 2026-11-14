package com.springairag.core.tenant;

/**
 * WP8：操作级回调合同（signed 必须带 secret；unsigned 不得携带 secret）。
 *
 * <p>参考项目 {@code ../seedance-research} 案例 05：合同按 operation 区分，
 * 不把 signed/unsigned 示例泛化为所有真实 Provider。</p>
 */
public record TenantCallbackContract(String operation, boolean signed, String secret) {

    public TenantCallbackContract {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        if (signed && (secret == null || secret.isBlank())) {
            throw new IllegalArgumentException("signed contract requires secret");
        }
        if (!signed && secret == null) {
            secret = "";
        }
    }
}
