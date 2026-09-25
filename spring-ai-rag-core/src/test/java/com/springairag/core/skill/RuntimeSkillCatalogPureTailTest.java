package com.springairag.core.skill;

import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RuntimeSkillCatalog 纯函数长尾（Batch 644，JaCoCo 驱动）：
 * truncate 对 null 与超长文本的截断、shortDigest 对 null / 空白 /
 * 短值 / 长值的摘要截取。
 */
class RuntimeSkillCatalogPureTailTest {

    private final RuntimeSkillCatalog catalog =
            new RuntimeSkillCatalog(
                    mock(ResourceCatalog.class),
                    new com.springairag.core.config.RagChatProperties());

    private String truncate(String text, int maxCharacters) throws Exception {
        Method method = RuntimeSkillCatalog.class
                .getDeclaredMethod("truncate", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(catalog, text, maxCharacters);
    }

    private String shortDigest(String value) throws Exception {
        Method method = RuntimeSkillCatalog.class
                .getDeclaredMethod("shortDigest", String.class);
        method.setAccessible(true);
        return (String) method.invoke(catalog, value);
    }

    @Test
    void truncateHandlesNullAndOverlongText() throws Exception {
        assertEquals("", truncate(null, 8));
        assertEquals("abcdefg", truncate("abcdefgh", 7));
        assertEquals("short", truncate("short", 32));
    }

    @Test
    void shortDigestHandlesBlankAndBoundaries() throws Exception {
        assertEquals("", shortDigest(null));
        assertEquals("", shortDigest("   "));
        assertEquals("abc", shortDigest("abc"));
        assertEquals("0123456789abcdef", shortDigest("0123456789abcdefghij"));
    }
}
