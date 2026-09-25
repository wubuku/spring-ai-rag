package com.springairag.core.config;

import com.springairag.core.config.MultiModelProperties.ModelCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多模型配置属性长尾（Batch 645，JaCoCo 驱动）：equals/hashCode
 * 的同值与差异分支、toString 投影、capabilityFor 对未知 provider
 * 回退默认能力。
 */
class MultiModelPropertiesValueTailTest {

    @Test
    void equalsHashCodeFollowValueFields() {
        MultiModelProperties left = new MultiModelProperties();
        left.setConfigFile("models.json");
        left.setLegacyCapabilities(Map.of(
                "zhipu", new ModelCapabilities(true, false)));
        MultiModelProperties right = new MultiModelProperties();
        right.setConfigFile("models.json");
        right.setLegacyCapabilities(Map.of(
                "zhipu", new ModelCapabilities(true, false)));

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertEquals(left, left);

        right.setLegacyCapabilities(Map.of(
                "zhipu", new ModelCapabilities(false, false)));
        assertNotEquals(left, right);

        assertNotEquals(left, null);
        assertNotEquals(left, new Object());
    }

    @Test
    void toStringContainsConfigFile() {
        MultiModelProperties properties = new MultiModelProperties();
        properties.setConfigFile("models.json");

        String text = properties.toString();

        assertTrue(text.contains("models.json"));
    }

    @Test
    void legacyCapabilityForFallsBackToDefaultsForUnknownProvider() {
        MultiModelProperties properties = new MultiModelProperties();
        ModelCapabilities expected = new ModelCapabilities(true, true);
        properties.setLegacyCapabilities(Map.of("zhipu", expected));

        assertEquals(expected, properties.getLegacyCapabilities("zhipu"));
        assertEquals(expected, properties.getLegacyCapabilities("ZHIPU"));
        assertEquals(ModelCapabilities.defaults(),
                properties.getLegacyCapabilities("unknown"));
        assertEquals(ModelCapabilities.defaults(),
                properties.getLegacyCapabilities(null));

        properties.setLegacyCapabilities(null);
        assertEquals(ModelCapabilities.defaults(),
                properties.getLegacyCapabilities("zhipu"));
    }
}
