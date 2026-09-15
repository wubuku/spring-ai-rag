package com.springairag.core.skill;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RuntimeSkillCatalog 体积上限长尾（Batch 443）：Skill 正文与
 * 引用文件超出配置上限时在 initialize 阶段拒绝。
 */
class RuntimeSkillCatalogLimitsTailTest {

    private IllegalStateException initializeFailing(RagChatProperties properties) {
        try {
            new RuntimeSkillCatalog(new ResourceCatalog(), properties).initialize();
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("expected IllegalStateException");
    }

    @Test
    void oversizedSkillBodyIsRejected() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));
        // weather 正文远大于 10 字节 → 超限拒绝。
        properties.getSkills().setMaxSkillBodyBytes(10);

        assertTrue(initializeFailing(properties).getMessage()
                .contains("Runtime Skill body exceeds configured limit"));
    }

    @Test
    void oversizedReferenceIsRejected() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));
        // api.md 引用文件大于 5 字节 → 超限拒绝。
        properties.getSkills().setMaxReferenceBytes(5);

        assertTrue(initializeFailing(properties).getMessage()
                .contains("Runtime Skill reference exceeds configured limit"));
    }
}
