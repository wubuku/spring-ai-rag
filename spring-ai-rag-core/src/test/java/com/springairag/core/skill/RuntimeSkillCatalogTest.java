package com.springairag.core.skill;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import com.springairag.core.resource.ResourceEntry;
import com.springairag.core.resource.ResourceKind;
import com.springairag.core.resource.ResourceRoot;
import com.springairag.core.resource.ResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSkillCatalogTest {

    @Test
    void parsesFrontmatterAndEnforcesAttemptLocalLevelTwoAndThreeAccess() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));

        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();

        assertEquals(List.of("support", "weather"),
                catalog.all().stream().map(RuntimeSkill::name).toList());
        assertTrue(catalog.levelOnePrompt(10_000).contains("weather"));

        RuntimeSkillLoadSession session = new RuntimeSkillLoadSession(2, 2, 4_000);
        assertEquals("{\"error\":\"skill_not_loaded\"}",
                catalog.readReference(
                        "weather", "api.md", session, 4_000));
        assertTrue(catalog.loadBody("weather", session, 4_000)
                .contains("weather.read"));
        assertTrue(catalog.readReference(
                "weather", "api.md", session, 4_000)
                .contains("configured city"));
        assertEquals("{\"error\":\"invalid_skill_reference_path\"}",
                catalog.readReference(
                        "weather", "../SKILL.md", session, 4_000));
    }

    @Test
    void rejectsBrokenLinksAndDirectoryNameDrift() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));

        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        assertThrows(IllegalStateException.class, () -> {
            properties.getSkills().setLocations(List.of(
                    "classpath:skills-fixture/weather/"));
            catalog.initialize();
        });
    }

    @Test
    void loadedSkillStateIsRequestLocalAndDoesNotLeakAcrossSessions() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));

        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();

        RuntimeSkillLoadSession firstRequest =
                new RuntimeSkillLoadSession(1, 1, 4_000);
        RuntimeSkillLoadSession secondRequest =
                new RuntimeSkillLoadSession(1, 1, 4_000);

        assertTrue(catalog.loadBody("weather", firstRequest, 4_000)
                .contains("weather.read"));
        assertTrue(catalog.readReference(
                "weather", "api.md", firstRequest, 4_000)
                .contains("configured city"));
        assertEquals("{\"error\":\"skill_not_loaded\"}",
                catalog.readReference(
                        "weather", "api.md", secondRequest, 4_000));
    }

    @Test
    void referencesRemainScopedToTheSkillResourceRoot() {
        ResourceCatalog resourceCatalog =
                org.mockito.Mockito.mock(ResourceCatalog.class);
        ResourceRoot alphaRoot = ResourceCatalog.root(
                ResourceKind.SKILL, "classpath:skills-alpha/");
        ResourceRoot bravoRoot = ResourceCatalog.root(
                ResourceKind.SKILL, "classpath:skills-bravo/");
        org.mockito.Mockito.when(resourceCatalog.discover(
                org.mockito.ArgumentMatchers.eq(ResourceKind.SKILL),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ResourceSnapshot(
                        ResourceKind.SKILL,
                        1,
                        Instant.now(),
                        List.of(
                                entry(alphaRoot, "SKILL.md", skill("alpha")),
                                entry(alphaRoot, "references/api.md",
                                        "alpha-reference"),
                                entry(bravoRoot, "SKILL.md", skill("bravo")),
                                entry(bravoRoot, "references/api.md",
                                        "bravo-reference")),
                        "digest",
                        List.of(),
                        true));
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of(
                "classpath:skills-alpha/",
                "classpath:skills-bravo/"));
        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                resourceCatalog, properties);

        catalog.initialize();

        RuntimeSkillLoadSession session =
                new RuntimeSkillLoadSession(2, 2, 4_000);
        catalog.loadBody("alpha", session, 4_000);
        catalog.loadBody("bravo", session, 4_000);
        assertTrue(catalog.readReference(
                "alpha", "api.md", session, 4_000)
                .contains("alpha-reference"));
        assertTrue(catalog.readReference(
                "bravo", "api.md", session, 4_000)
                .contains("bravo-reference"));
    }

    private ResourceEntry entry(
            ResourceRoot root,
            String relativePath,
            String content) {
        return new ResourceEntry(
                root,
                relativePath,
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String skill(String name) {
        return """
                ---
                name: %s
                description: Root-scoped test Skill
                ---
                Use the root-scoped reference.
                """.formatted(name);
    }
}
