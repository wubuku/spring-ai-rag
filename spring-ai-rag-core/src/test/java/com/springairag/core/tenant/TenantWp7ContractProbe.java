package com.springairag.core.tenant;

import java.util.List;

/** 供清单门禁复用的一条事实：SINGLE 模式逐租户分发只跑 default。 */
final class TenantWp7ContractProbe {
    private TenantWp7ContractProbe() {
    }

    static List<String> singleTenants() {
        return List.of("default");
    }
}
