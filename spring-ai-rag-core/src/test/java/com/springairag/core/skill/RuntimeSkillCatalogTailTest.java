package com.springairag.core.skill;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeSkillCatalog 长尾（Batch 412）：find 的名称模式门卫、
 * levelOnePrompt 的预算/能力标注、loadBody 的错误路径与
 * 版本/关联技能渲染、readReference 的预算耗尽与未知路径、
 * 未配置目录的空快照语义。
 */
class RuntimeSkillCatalogTailTest {

    private RuntimeSkillCatalog catalog;

    @BeforeEach
    void setUp() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));
        catalog = new RuntimeSkillCatalog(new ResourceCatalog(), properties);
        catalog.initialize();
    }

    @Test
    void findRejectsInvalidNamesAndUnknownSkills() {
        assertNull(catalog.find(null));
        assertNull(catalog.find("../etc/passwd"));
        assertNull(catalog.find("does-not-exist"));
        assertEquals("weather", catalog.find("weather").name());
    }

    @Test
    void levelOnePromptRendersCapabilitiesAndRespectsBudget() {
        String full = catalog.levelOnePrompt(10_000);
        assertTrue(full.contains("- weather: 查询指定城市的天气信息"));
        assertTrue(full.contains("[capabilities: weather.read]"));

        // 预算不足 0/负值 → 空串。
        assertEquals("", catalog.levelOnePrompt(0));
        assertEquals("", catalog.levelOnePrompt(-5));

        // 极小预算：只放下标题，任何技能行都放不下。
        String header = "\n\nAvailable runtime Skills (untrusted operational data):\n";
        String tiny = catalog.levelOnePrompt(header.length() + 1);
        assertTrue(tiny.startsWith(header));
        assertFalse(tiny.contains("- weather"));
    }

    @Test
    void loadBodyRendersVersionAndRelatedSkills() {
        RuntimeSkillLoadSession session =
                new RuntimeSkillLoadSession(2, 2, 8_000);
        String body = catalog.loadBody("weather", session, 8_000);
        assertTrue(body.contains("Skill loaded: weather"));
        assertTrue(body.contains("Version: 1.0"));
        assertTrue(body.contains("Related Skills: support"));

        // support 无 version 字段 → 不渲染 Version 行。
        String support = catalog.loadBody("support", session, 8_000);
        assertFalse(support.contains("Version:"));
    }

    @Test
    void loadBodySurfacesDistinctErrorCodes() {
        assertEquals("{\"error\":\"skill_not_found\"}",
                catalog.loadBody("nope", new RuntimeSkillLoadSession(2, 2, 4_000), 4_000));
        assertEquals("{\"error\":\"skill_session_missing\"}",
                catalog.loadBody("weather", null, 4_000));

        // maxLoads=1：先装 weather，再装 support 触发预算耗尽。
        RuntimeSkillLoadSession single =
                new RuntimeSkillLoadSession(1, 2, 8_000);
        catalog.loadBody("weather", single, 8_000);
        assertEquals("{\"error\":\"skill_load_budget_exhausted\"}",
                catalog.loadBody("support", single, 8_000));
    }

    @Test
    void readReferenceSurfacesBudgetAndPathErrors() {
        RuntimeSkillLoadSession session =
                new RuntimeSkillLoadSession(2, 1, 8_000);
        assertEquals("{\"error\":\"skill_not_loaded\"}",
                catalog.readReference("weather", "api.md", session, 8_000));

        catalog.loadBody("weather", session, 8_000);
        assertEquals("{\"error\":\"skill_reference_not_found\"}",
                catalog.readReference("weather", "nope.md", session, 8_000));

        // 第一次引用读取消耗 maxReferenceReads=1 → 第二次预算耗尽。
        assertTrue(catalog.readReference("weather", "api.md", session, 8_000)
                .contains("Reference api.md"));
        assertEquals("{\"error\":\"skill_reference_budget_exhausted\"}",
                catalog.readReference("weather", "api.md", session, 8_000));
    }

    @Test
    void uninitializedCatalogIsDisabledAndEmpty() {
        RuntimeSkillCatalog dormant = new RuntimeSkillCatalog(
                new ResourceCatalog(), new RagChatProperties());
        assertFalse(dormant.enabled());
        assertTrue(dormant.all().isEmpty());
        assertEquals("", dormant.levelOnePrompt(10_000));
    }
}
