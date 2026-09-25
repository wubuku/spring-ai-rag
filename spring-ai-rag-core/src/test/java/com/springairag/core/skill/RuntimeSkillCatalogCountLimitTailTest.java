package com.springairag.core.skill;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RuntimeSkillCatalog 数量上限长尾（Batch 647，JaCoCo 驱动）：
 * 可解析 Skill 数量超过 maxSkills 时在 initialize 阶段拒绝。
 */
class RuntimeSkillCatalogCountLimitTailTest {

    @Test
    void skillCountBeyondLimitIsRejected() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        // fixture 含 weather 与 support 两个可解析 Skill，上限 1 → 拒绝。
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));
        properties.getSkills().setMaxSkills(1);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new RuntimeSkillCatalog(
                        new ResourceCatalog(), properties).initialize());

        assertTrue(error.getMessage()
                .contains("Runtime Skill count exceeds configured limit"));
    }
}
