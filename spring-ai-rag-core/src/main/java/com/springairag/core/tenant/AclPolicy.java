package com.springairag.core.tenant;

/** WP6：端点必须声明的 Collection ACL 策略。 */
public enum AclPolicy {
    /** 走 ApiKeyCollectionAccess 的凭证白名单范围求交。 */
    COLLECTION_ACL,
    /** 平台端点不走业务 Collection ACL，但必须经 capability 白名单与审计。 */
    PLATFORM_CAPABILITY
}
