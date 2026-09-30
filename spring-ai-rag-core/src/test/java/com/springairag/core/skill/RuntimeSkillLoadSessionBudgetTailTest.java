package com.springairag.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeSkillLoadSession 预算语义长尾（Batch 742，JaCoCo 驱
 * 动）：markLoaded 幂等/容量上限、reserveReference 的读取数上限
 * 与字符预算双闸（59/63/67）。
 */
class RuntimeSkillLoadSessionBudgetTailTest {

    private final RuntimeSkillLoadSession session =
            new RuntimeSkillLoadSession(2, 2, 100);

    @Test
    void markLoadedIsIdempotentWithinCapacity() {
        assertTrue(session.markLoaded("skill-a"));
        assertTrue(session.isLoaded("skill-a"));
        // 重复加载幂等：不占用新容量。
        assertTrue(session.markLoaded("skill-a"));
        assertTrue(session.markLoaded("skill-b"));
        assertFalse(session.markLoaded("skill-c"));

        assertEquals(2, session.loadedSkills().size());
    }

    @Test
    void reserveReferenceRejectsWhenReadBudgetExhausted() {
        assertTrue(session.reserveReference(10));
        assertTrue(session.reserveReference(10));
        // maxReferenceReads=2：第三次读取被拒。
        assertFalse(session.reserveReference(10));
        assertEquals(2, session.referenceReads());
        assertEquals(20, session.referenceCharacters());
    }

    @Test
    void reserveReferenceRejectsNegativeAndOverBudgetCharacters() {
        assertFalse(session.reserveReference(-1));
        // 字符预算 100：单次预留超过剩余预算被拒。
        assertFalse(session.reserveReference(101));
        assertTrue(session.reserveReference(100));
        assertEquals(100, session.referenceCharacters());
    }
}
