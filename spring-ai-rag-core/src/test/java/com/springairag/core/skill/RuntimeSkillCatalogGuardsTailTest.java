package com.springairag.core.skill;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RuntimeSkillCatalog 守卫长尾（Batch 597，JaCoCo 驱动）：禁用
 * 配置的空快照、levelOnePrompt 空预算、loadBody/readReference 的
 * 错误码矩阵（未知技能、缺失会话、预算耗尽、路径非法、引用缺失）、
 * 名称超长/自链接/能力正则/UTF-8 非法的 fixture 拒绝、文本链接与
 * 重复能力的混合 fixture 解析、Snapshot 空字段收紧。
 */
class RuntimeSkillCatalogGuardsTailTest {

    private RuntimeSkillCatalog catalogFor(String location, boolean enabled) {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(enabled);
        properties.getSkills().setLocations(List.of(location));
        RuntimeSkillCatalog catalog =
                new RuntimeSkillCatalog(new ResourceCatalog(), properties);
        catalog.initialize();
        return catalog;
    }

    private RuntimeSkillCatalog mixedCatalog() {
        return catalogFor("classpath:skills-mixed-fixture/", true);
    }

    private IllegalStateException initializeFailing(String location) {
        try {
            catalogFor(location, true);
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("expected IllegalStateException for " + location);
    }

    @Test
    void disabledConfigurationYieldsEmptySnapshot() {
        RuntimeSkillCatalog catalog =
                catalogFor("classpath:skills-mixed-fixture/", false);

        assertFalse(catalog.enabled());
        assertTrue(catalog.all().isEmpty());
        assertEquals("", catalog.levelOnePrompt(1_000));
    }

    @Test
    void levelOnePromptEmptyForZeroBudgetAndListsCapabilities() {
        RuntimeSkillCatalog catalog = mixedCatalog();
        assertEquals("", catalog.levelOnePrompt(0));

        String prompt = catalog.levelOnePrompt(8_000);
        assertTrue(prompt.contains("plain: 无链接无能力的技能"));
        assertTrue(prompt.contains("[capabilities: mixed.read]"));
    }

    @Test
    void loadBodyReportsUnknownSkillMissingSessionAndBudget() {
        RuntimeSkillCatalog catalog = mixedCatalog();

        assertEquals("{\"error\":\"skill_not_found\"}",
                catalog.loadBody("ghost", null, 1_000));
        assertEquals("{\"error\":\"skill_session_missing\"}",
                catalog.loadBody("plain", null, 1_000));

        RuntimeSkillLoadSession exhausted =
                new RuntimeSkillLoadSession(1, 1, 8_000);
        catalog.loadBody("plain", exhausted, 1_000);
        assertEquals("{\"error\":\"skill_load_budget_exhausted\"}",
                catalog.loadBody("linked", exhausted, 1_000));
    }

    @Test
    void readReferenceReportsUnknownSkillAndMissingSession() {
        RuntimeSkillCatalog catalog = mixedCatalog();

        assertEquals("{\"error\":\"skill_not_found\"}",
                catalog.readReference("ghost", "a.md", null, 1_000));

        RuntimeSkillLoadSession fresh = new RuntimeSkillLoadSession(2, 2, 8_000);
        assertEquals("{\"error\":\"skill_not_loaded\"}",
                catalog.readReference("plain", "a.md", fresh, 1_000));
    }

    @Test
    void readReferenceRejectsIllegalPaths() {
        RuntimeSkillCatalog catalog = mixedCatalog();
        RuntimeSkillLoadSession session = new RuntimeSkillLoadSession(2, 4, 8_000);
        catalog.loadBody("plain", session, 1_000);

        for (String bad : new String[] {null, "", "  ", "../escape",
                "a//b", "/", "..", "x/../y", "line\nbreak"}) {
            assertEquals("{\"error\":\"invalid_skill_reference_path\"}",
                    catalog.readReference("plain", bad, session, 1_000),
                    "path=" + bad);
        }
        // 前导 NUL 会被 trim() 剥离 → 归一为 "path" → 走未命中分支。
        assertEquals("{\"error\":\"skill_reference_not_found\"}",
                catalog.readReference("plain", "\u0000path", session, 1_000));
        assertEquals("{\"error\":\"skill_reference_not_found\"}",
                catalog.readReference("plain", "missing.md", session, 1_000));
    }

    @Test
    void referenceCharBudgetExhaustionIsReported() {
        RuntimeSkillCatalog catalog =
                catalogFor("classpath:skills-fixture/", true);
        RuntimeSkillLoadSession session = new RuntimeSkillLoadSession(1, 1, 8);
        catalog.loadBody("weather", session, 12);

        // 引用字符预算只剩 8，api.md 全文超过该额度 → 拒绝。
        assertEquals("{\"error\":\"skill_reference_budget_exhausted\"}",
                catalog.readReference("weather", "api.md", session, 1_000));
    }

    @Test
    void fixtureRejectionsCoverNameLinksCapabilityAndEncoding() {
        assertTrue(initializeFailing("classpath:skills-bad-fixture/longname/")
                .getMessage().contains("name/description/directory"));
        assertTrue(initializeFailing("classpath:skills-bad-fixture/selflink/")
                .getMessage().contains("Invalid runtime Skill link"));
        assertTrue(initializeFailing("classpath:skills-bad-fixture/badcapregex/")
                .getMessage().contains("Invalid runtime Skill capability"));
        assertTrue(initializeFailing("classpath:skills-bad-fixture/badutf8/")
                .getMessage().contains("not valid UTF-8"));
    }

    @Test
    void mixedFixtureParsesTextualLinksAndDeduplicatesCapabilities() {
        RuntimeSkillCatalog catalog = mixedCatalog();

        assertEquals(2, catalog.all().size());
        RuntimeSkill linked = catalog.find("linked");
        assertEquals(List.of("mixed.read"), linked.capabilities());
        assertEquals(1, linked.links().size());
        assertEquals("plain", linked.links().getFirst().name());
        assertEquals("", linked.links().getFirst().description());
        // 空引用表 + 已加载会话的引用查询 → 未找到。
        assertTrue(catalog.loadBody("linked",
                new RuntimeSkillLoadSession(2, 2, 8_000), 1_000)
                .contains("Related Skills: plain"));
    }

    @Test
    void snapshotCompactsNullDigestAndSkills() {
        RuntimeSkillCatalog.Snapshot snapshot =
                new RuntimeSkillCatalog.Snapshot(1, null, true, null);

        assertEquals("", snapshot.digest());
        assertTrue(snapshot.skills().isEmpty());
    }
}
