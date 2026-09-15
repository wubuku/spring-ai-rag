package com.springairag.core.skill;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RuntimeSkillCatalog 解析长尾（Batch 429）：frontmatter 缺失/
* YAML 非法/名称与目录不匹配/能力与链接字段形态错误的拒绝，
 * 引用路径 references/ 前缀归一，loadBody 截断。
 */
class RuntimeSkillCatalogParseTailTest {

    private RuntimeSkillCatalog catalogFor(String location) {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of(location));
        RuntimeSkillCatalog catalog =
                new RuntimeSkillCatalog(new ResourceCatalog(), properties);
        catalog.initialize();
        return catalog;
    }

    private IllegalStateException initializeFailing(String location) {
        try {
            catalogFor(location);
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("expected IllegalStateException for " + location);
    }

    @Test
    void missingFrontmatterIsRejected() {
        assertTrue(initializeFailing("classpath:skills-bad-fixture/nofront/")
                .getMessage().contains("Invalid runtime Skill frontmatter"));
    }

    @Test
    void malformedYamlFrontmatterIsRejected() {
        assertTrue(initializeFailing("classpath:skills-bad-fixture/badyaml/")
                .getMessage().contains("Failed to parse runtime Skill frontmatter"));
    }

    @Test
    void nameNotMatchingDirectoryIsRejected() {
        assertTrue(initializeFailing("classpath:skills-bad-fixture/badname/")
                .getMessage().contains("Invalid runtime Skill name/description/directory"));
    }

    @Test
    void nonArrayCapabilitiesAreRejected() {
        assertTrue(initializeFailing("classpath:skills-bad-fixture/badcap/")
                .getMessage().contains("capabilities must be an array"));
    }

    @Test
    void nonArrayLinksAreRejected() {
        assertTrue(initializeFailing("classpath:skills-bad-fixture/badlink/")
                .getMessage().contains("links must be an array"));
    }

    @Test
    void referencePathAcceptsPrefixedFormAndRejectsControlChars() {
        RuntimeSkillCatalog catalog = catalogFor("classpath:skills-fixture/");
        RuntimeSkillLoadSession session =
                new RuntimeSkillLoadSession(2, 4, 8_000);
        catalog.loadBody("weather", session, 8_000);

        // references/ 前缀被归一剥离，仍能命中引用文件。
        String prefixed = catalog.readReference(
                "weather", "references/api.md", session, 8_000);
        assertTrue(prefixed.contains("Reference api.md"));

        assertEquals("{\"error\":\"invalid_skill_reference_path\"}",
                catalog.readReference(
                        "weather", "//a//b", session, 8_000));
    }

    @Test
    void loadBodyTruncatesFinalResultToBudget() {
        RuntimeSkillCatalog catalog = catalogFor("classpath:skills-fixture/");
        RuntimeSkillLoadSession session =
                new RuntimeSkillLoadSession(2, 2, 8_000);
        String tiny = catalog.loadBody("weather", session, 12);
        assertTrue(tiny.length() <= 12);
    }
}
