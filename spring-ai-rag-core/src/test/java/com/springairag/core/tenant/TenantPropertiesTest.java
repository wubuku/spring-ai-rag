package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WP5：租户配置绑定（rag.tenant.mode，默认 SINGLE；规划 §0 / §8.2）。
 */
@DisplayName("TenantProperties (rag.tenant.mode)")
class TenantPropertiesTest {

    @Test
    @DisplayName("默认 SINGLE")
    void defaultsToSingle() {
        TenantProperties props = new TenantProperties();
        assertEquals(TenantMode.SINGLE, props.getMode());
    }

    @Test
    @DisplayName("可设为 STRICT")
    void canSetStrict() {
        TenantProperties props = new TenantProperties();
        props.setMode(TenantMode.STRICT);
        assertEquals(TenantMode.STRICT, props.getMode());
    }

    @Test
    @DisplayName("非法模式名抛异常（Spring 绑定由 enum 保证）")
    void invalidModeRejected() {
        TenantProperties props = new TenantProperties();
        assertThrows(IllegalArgumentException.class,
                () -> props.setMode(TenantMode.valueOf("bogus")));
    }
}
