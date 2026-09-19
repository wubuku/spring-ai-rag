package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HybridSearchAdvisor 检索日志与上下文提取长尾（Batch 531，JaCoCo
 * 驱动）：可选检索日志注入、matchNone 作用域短路、documentIds 上
 * 下文的 Long/Number/字符串混合解析、maxResults 上限钳制与非法值
 * 回退。
 */
class HybridSearchAdvisorLoggingTailTest {

    private HybridRetrieverService hybridRetriever;
    private HybridSearchAdvisor advisor;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        advisor = new HybridSearchAdvisor(
                hybridRetriever, mock(AdvisorMetrics.class));
    }

    private ChatClientRequest requestWith(Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("什么是 Spring Boot")))
                .context(context)
                .build();
    }

    private RetrievalResult result(String id, String text) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(id);
        result.setChunkIndex(0);
        result.setChunkText(text);
        result.setScore(0.9);
        return result;
    }

    @Test
    void retrievalLoggingServiceReceivesSearchTelemetry() {
        var loggingService = mock(
                com.springairag.core.service.RetrievalLoggingService.class);
        advisor.setRetrievalLoggingService(loggingService);
        List<RetrievalResult> results = List.of(result("doc-1", "text"));
        when(hybridRetriever.search(
                any(), isNull(), isNull(), eq(10),
                any(com.springairag.api.dto.RetrievalConfig.class)))
                .thenReturn(results);

        ChatClientRequest outcome = advisor.before(
                requestWith(Map.of("sessionId", "session-42")), null);

        assertNotNull(outcome.context().get(
                HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
        verify(loggingService).logRetrieval(
                eq("session-42"), eq("什么是 Spring Boot"), eq("hybrid"),
                anyLong(), eq(0L), eq(0L), anyList());
    }

    @Test
    void matchNoneScopeShortCircuitSkipsSearch() {
        RetrievalScope matchNone = RetrievalScope.noMatches();

        ChatClientRequest outcome = advisor.before(
                requestWith(Map.of(
                        HybridSearchAdvisor.RETRIEVAL_SCOPE_KEY, matchNone)),
                null);

        assertEquals(List.of(),
                outcome.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
        verify(hybridRetriever, never()).search(
                any(), any(), any(), anyInt(),
                any(com.springairag.api.dto.RetrievalConfig.class));
        verify(hybridRetriever, never()).searchInScope(
                any(), any(), any(), anyInt(),
                any(com.springairag.api.dto.RetrievalConfig.class));
    }

    @Test
    void documentIdsContextParsesMixedNumberAndStringValues() {
        List<RetrievalResult> results = List.of(result("doc-3", "text"));
        when(hybridRetriever.search(
                any(), eq(List.of(1L, 2L, 3L)), isNull(), eq(10),
                any(com.springairag.api.dto.RetrievalConfig.class)))
                .thenReturn(results);

        ChatClientRequest outcome = advisor.before(requestWith(Map.of(
                HybridSearchAdvisor.DOCUMENT_IDS_KEY,
                Arrays.asList(1L, 2, "3", "not-a-number", null))), null);

        assertNotNull(outcome.context().get(
                HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
        verify(hybridRetriever).search(
                any(), eq(List.of(1L, 2L, 3L)), isNull(), eq(10),
                any(com.springairag.api.dto.RetrievalConfig.class));
    }

    @Test
    void maxResultsContextIsCappedAtFifty() {
        when(hybridRetriever.search(
                any(), isNull(), isNull(), eq(50),
                any(com.springairag.api.dto.RetrievalConfig.class)))
                .thenReturn(List.of());

        advisor.before(requestWith(Map.of(
                HybridSearchAdvisor.MAX_RESULTS_KEY, 100)), null);

        verify(hybridRetriever).search(
                any(), isNull(), isNull(), eq(50),
                any(com.springairag.api.dto.RetrievalConfig.class));
    }

    @Test
    void nonPositiveMaxResultsFallsBackToDefault() {
        when(hybridRetriever.search(
                any(), isNull(), isNull(), eq(10),
                any(com.springairag.api.dto.RetrievalConfig.class)))
                .thenReturn(List.of());

        advisor.before(requestWith(Map.of(
                HybridSearchAdvisor.MAX_RESULTS_KEY, 0)), null);

        verify(hybridRetriever).search(
                any(), isNull(), isNull(), eq(10),
                argThat(config -> config instanceof com.springairag.api.dto.RetrievalConfig
                        && !((com.springairag.api.dto.RetrievalConfig) config)
                                .isUseRerank()));
    }
}
