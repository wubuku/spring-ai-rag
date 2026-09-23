package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalDiagnosticsService boundedMetadata 长尾（Batch 588，JaCoCo
 * 驱动）：元数据在 maxDetailBytes 内原样返回、超限裁剪 attempts 并
 * 置 truncated。
 */
class RetrievalDiagnosticsBoundedMetadataTailTest {

    private RetrievalDiagnosticsService serviceWith(int maxDetailBytes) {
        RagProperties properties = new RagProperties();
        properties.getRetrievalDiagnostics().setMaxDetailBytes(maxDetailBytes);
        return new RetrievalDiagnosticsService(
                null,
                properties,
                new ObjectMapper(),
                null);
    }

    private Map<String, Object> invokeBounded(
            RetrievalDiagnosticsService service,
            Map<String, Object> metadata) throws Exception {
        Method method = RetrievalDiagnosticsService.class
                .getDeclaredMethod("boundedMetadata", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(
                service, metadata);
        return result;
    }

    @Test
    void metadataWithinBudgetReturnedUnchanged() throws Exception {
        var service = serviceWith(10_000);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("key", "value");

        var result = invokeBounded(service, metadata);

        assertEquals("value", result.get("key"));
        assertFalse(result.containsKey("truncated"));
    }

    @Test
    void oversizedMetadataTrimmedAttemptsAndMarkedTruncated() throws Exception {
        // setMaxDetailBytes 夹取到 [1024, 262144]，200 实际生效为 1024；
        // 载荷需明显超过 1024 字节才能触发裁剪分支。
        var service = serviceWith(200);
        var metadata = new LinkedHashMap<String, Object>();
        var big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append("item-").append(i).append(" ");
        }
        metadata.put("big", big.toString());
        metadata.put("attempts", java.util.List.of("a", "b"));

        var result = invokeBounded(service, metadata);

        assertTrue(result.containsKey("truncated"));
        List<?> attempts = (List<?>) result.get("attempts");
        assertTrue(attempts == null || attempts.isEmpty());
    }

    @Test
    void unserializableMetadataFallsBackToSchemaVersionStub() throws Exception {
        var service = serviceWith(10_000);
        var metadata = new LinkedHashMap<String, Object>();
        // 默认 ObjectMapper 无法序列化无属性的 Object，触发异常兜底分支。
        metadata.put("unserializable", new Object());

        var result = invokeBounded(service, metadata);

        assertEquals(1, result.get("schemaVersion"));
        assertEquals(Boolean.TRUE, result.get("truncated"));
    }
}
