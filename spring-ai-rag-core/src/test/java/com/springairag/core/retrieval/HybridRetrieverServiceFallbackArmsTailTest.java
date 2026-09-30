package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * HybridRetrieverService 降级臂长尾（Batch 738，JaCoCo 驱动）：
 * timeoutOrError 对非超时异常走 ERROR + normalizeErrorCode 臂
 * （475-487）、对超时异常走 TIMEOUT 臂；null query 的 QueryStat
 * 零长度归一（225）。
 */
class HybridRetrieverServiceFallbackArmsTailTest {

    private HybridRetrieverService service() {
        return new HybridRetrieverService(
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(JdbcTemplate.class),
                new RagProperties(),
                null,
                Runnable::run);
    }

    private Object invokeTimeoutOrError(Throwable error) throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "timeoutOrError", String.class, String.class,
                Throwable.class, String.class);
        method.setAccessible(true);
        return method.invoke(service(), "VECTOR", "embedding", error,
                "Vector search");
    }

    private Object stageOf(Object branch) throws Exception {
        return branch.getClass().getMethod("stage").invoke(branch);
    }

    private String stageStatus(Object branch) throws Exception {
        Object stage = stageOf(branch);
        return (String) stage.getClass().getMethod("status").invoke(stage);
    }

    private String stageErrorCode(Object branch) throws Exception {
        Object stage = stageOf(branch);
        return (String) stage.getClass().getMethod("errorCode")
                .invoke(stage);
    }

    @SuppressWarnings("unchecked")
    private List<RetrievalResult> branchResults(Object branch)
            throws Exception {
        return (List<RetrievalResult>) branch.getClass()
                .getMethod("results").invoke(branch);
    }

    @Test
    void nonTimeoutErrorYieldsErrorStageWithNormalizedCode()
            throws Exception {
        Object branch = invokeTimeoutOrError(
                new IllegalStateException("boom"));

        assertEquals("VECTOR",
                stageOf(branch).getClass().getMethod("branch")
                        .invoke(stageOf(branch)));
        assertEquals(RetrievalBranchStage.ERROR, stageStatus(branch));
        assertEquals("IllegalStateException", stageErrorCode(branch));
        assertTrue(branchResults(branch).isEmpty());
    }

    @Test
    void timeoutErrorYieldsTimeoutStage() throws Exception {
        Object branch = invokeTimeoutOrError(new TimeoutException("slow"));

        assertEquals(RetrievalBranchStage.TIMEOUT, stageStatus(branch));
        assertEquals("TIMEOUT", stageErrorCode(branch));
        assertTrue(branchResults(branch).isEmpty());
    }

    @Test
    void searchInScopeDetailedWithNullQueryYieldsZeroLengthQueryStat()
            throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "searchInScopeDetailed", String.class,
                RetrievalScope.class, List.class, int.class,
                RetrievalConfig.class, RetrievalFilters.class);
        method.setAccessible(true);

        com.springairag.core.retrieval.RetrievalOutcome outcome =
                (com.springairag.core.retrieval.RetrievalOutcome)
                        method.invoke(service(), null,
                                RetrievalScope.unscoped(), List.of(), 5,
                                null, null);

        assertEquals(1, outcome.effectiveQueries().size());
        assertEquals(0, outcome.effectiveQueries().get(0).charCount());
    }
}
