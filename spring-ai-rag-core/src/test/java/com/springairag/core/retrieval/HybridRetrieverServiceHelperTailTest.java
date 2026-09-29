package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * HybridRetrieverService 静态助手长尾（Batch 729，JaCoCo 驱
 * 动）：normalizeErrorCode 对无类型异常回退 ERROR 与类名透传
 * （595-601）、mapVectorResults 委托 detailed 映射（542）。
 */
class HybridRetrieverServiceHelperTailTest {

    private HybridRetrieverService service() {
        return new HybridRetrieverService(
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(JdbcTemplate.class),
                new com.springairag.core.config.RagProperties(),
                null,
                Runnable::run);
    }

    private String normalizeErrorCode(Throwable error) throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "normalizeErrorCode", Throwable.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, error);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void normalizeErrorCodeFallsBackToErrorForAnonymousThrowable()
            throws Exception {
        assertEquals("ERROR", normalizeErrorCode(new Throwable() {
        }));
    }

    @Test
    void normalizeErrorCodeReturnsSimpleClassName() throws Exception {
        assertEquals("IllegalStateException", normalizeErrorCode(
                new IllegalStateException("boom")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapVectorResultsDelegatesToDetailedMapping() throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "mapVectorResults", List.class, float[].class, List.class,
                double.class);
        method.setAccessible(true);

        List<RetrievalResult> results = (List<RetrievalResult>) method.invoke(
                service(), List.of(), new float[]{0.1f}, List.of(), 0.5);

        assertTrue(results.isEmpty());
    }
}
