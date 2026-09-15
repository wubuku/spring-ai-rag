package com.springairag.core.rag;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectDocumentRetriever 长尾（Batch 409）：缺失授权上下文
 * fail-closed、非复合检索的去重短路、COMPOSITE 标志绕过、
 * matchNone 空结果、useRerank 分支的 trace 记录差异。
 */
class ProjectDocumentRetrieverTailTest {

    private HybridRetrieverService hybridRetriever;
    private RetrievalDocumentMapper mapper;
    private ProjectDocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        mapper = mock(RetrievalDocumentMapper.class);
        retriever = new ProjectDocumentRetriever(hybridRetriever, mapper);
    }

    private Query queryWith(Map<String, Object> extraContext) {
        Map<String, Object> context = new HashMap<>();
        if (extraContext != null) {
            context.putAll(extraContext);
        }
        return new Query("spring", List.of(), context);
    }

    private AuthorizedRetrievalContext context(
            RetrievalScope scope, RetrievalTraceCollector trace,
            boolean useRerank) {
        return new AuthorizedRetrievalContext(
                scope,
                new RetrievalOptions(5, 0.3, true, useRerank, 0.5, 0.5),
                trace,
                "session-1",
                ChatPrincipal.local(),
                24_000,
                RetrievalFilters.none());
    }

    private void stubOutcome(int resultCount) {
        List<RetrievalResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < resultCount; i++) {
            RetrievalResult result = new RetrievalResult();
            result.setDocumentId(String.valueOf(i));
            result.setScore(0.9);
            results.add(result);
        }
        when(hybridRetriever.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(results));
        when(mapper.toDocument(any(RetrievalResult.class)))
                .thenReturn(mock(Document.class));
    }

    @Test
    void missingAuthorizedContextFailsClosed() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> retriever.retrieve(queryWith(null)));
        assertEquals("Missing server-owned authorized retrieval context",
                error.getMessage());
    }

    @Test
    void secondRetrievalWithSameTraceShortCircuits() {
        RetrievalTraceCollector trace = spy(new RetrievalTraceCollector(1, 1, 20));
        AuthorizedRetrievalContext authorized =
                context(RetrievalScope.unscoped(), trace, false);
        stubOutcome(1);

        List<Document> first = retriever.retrieve(
                queryWith(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, authorized)));
        List<Document> second = retriever.retrieve(
                queryWith(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, authorized)));

        // 第一次正常检索并映射；第二次去重短路返回空。
        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
        verify(hybridRetriever, times(1)).searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void compositeFlagBypassesDedupShortCircuit() {
        RetrievalTraceCollector trace = spy(new RetrievalTraceCollector(1, 1, 20));
        AuthorizedRetrievalContext authorized =
                context(RetrievalScope.unscoped(), trace, false);
        stubOutcome(1);
        Map<String, Object> extra = Map.of(
                ProjectDocumentRetriever.CONTEXT_KEY, authorized,
                CompositeChatDocumentRetriever.COMPOSITE_RETRIEVAL_CONTEXT_KEY,
                Boolean.TRUE);

        List<Document> first = retriever.retrieve(queryWith(extra));
        List<Document> second = retriever.retrieve(queryWith(extra));

        // 复合检索上下文不参与去重：两次都执行真实检索。
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        verify(hybridRetriever, times(2)).searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void matchNoneScopeWithEmptyOutcomeRecordsOutcomeOnly() {
        RetrievalTraceCollector trace = spy(new RetrievalTraceCollector());
        AuthorizedRetrievalContext authorized =
                context(RetrievalScope.noMatches(), trace, false);
        when(hybridRetriever.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(List.of()));

        List<Document> documents = retriever.retrieve(
                queryWith(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, authorized)));

        assertTrue(documents.isEmpty());
        verify(trace).recordOutcome(any(RetrievalOutcome.class), anyInt());
        verify(trace, never()).recordCandidateOutcome(any());
    }

    @Test
    void rerankOptionRecordsCandidateOutcome() {
        RetrievalTraceCollector trace = spy(new RetrievalTraceCollector());
        AuthorizedRetrievalContext authorized =
                context(RetrievalScope.unscoped(), trace, true);
        stubOutcome(2);

        List<Document> documents = retriever.retrieve(
                queryWith(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, authorized)));

        assertEquals(2, documents.size());
        verify(trace).recordCandidateOutcome(any());
        verify(trace, never()).recordOutcome(any(RetrievalOutcome.class), anyInt());
    }
}
