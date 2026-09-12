package com.springairag.core.skill;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeSkillCatalog.initialize 初始化分支（Batch 341）：禁用或
 * 未配置 → 空快照；资源不健康 → 降级快照；跨目录重复 Skill 名拒
 * 绝。
 */
class RuntimeSkillCatalogInitializeTest {

    @Test
    void disabledCatalogProducesEmptyHealthySnapshot() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(false);
        properties.getSkills().setLocations(
                List.of("classpath:skills-fixture/"));

        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();

        assertTrue(catalog.snapshot().healthy());
        assertTrue(catalog.snapshot().skills().isEmpty());
        assertTrue(catalog.all().isEmpty());
        // 禁用时目录不再解析资源（发现次数为 0 由空快照间接体现）。
        assertEquals(0, catalog.snapshot().generation());
    }

    @Test
    void unconfiguredLocationsProduceEmptyHealthySnapshot() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of());

        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();

        assertTrue(catalog.snapshot().healthy());
        assertTrue(catalog.snapshot().skills().isEmpty());
    }

    @Test
    void unhealthyResourcesProduceDegradedSnapshot() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(
                List.of("classpath:skills-do-not-exist-2026/"));
        // failFast=false：发现失败收敛为不健康快照而非抛错。
        properties.getSkills().setFailFast(false);

        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();

        // 资源发现不健康 → 降级快照：不健康、零技能。
        assertFalse(catalog.snapshot().healthy());
        assertTrue(catalog.snapshot().skills().isEmpty());
        assertTrue(catalog.all().isEmpty());
    }

    @Test
    void duplicateSkillNameAcrossLocationsRejected() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        // 两个位置都包含名为 weather 的 SKILL.md。
        properties.getSkills().setLocations(List.of(
                "classpath:skills-fixture/",
                "classpath:skills-fixture/weather/"));

        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);

        assertThrows(IllegalStateException.class, catalog::initialize);
    }
}
