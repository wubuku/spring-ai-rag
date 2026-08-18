package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.RetrievalTraceDetailResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalDiagnosticsServiceTest {

    @Test
    void persistRedactsQueryAndUsesPositionalScores() {
        RagRetrievalLogRepository repository = mock(RagRetrievalLogRepository.class);
        RetrievalDiagnosticsService service = new RetrievalDiagnosticsService(
                repository,
                new RagProperties(),
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class));
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("secret-doc");
        result.setScore(0.91);
        RetrievalOutcome outcome = RetrievalOutcome.ofResults(List.of(result));
        RetrievalTraceSession session = new RetrievalTraceSession(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                RetrievalTraceHeaders.OPERATION_SEARCH,
                "session-1");
        session.recordRetrieval(null, new RetrievalOutcome(
                outcome.traceId(),
                outcome.results(),
                "破皮沙发",
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                outcome.outcomeCode(),
                outcome.emptyReasonCode(),
                12L,
                1));

        service.persist(session);

        ArgumentCaptor<RagRetrievalLog> captor = ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());
        RagRetrievalLog saved = captor.getValue();
        assertEquals(RetrievalTraceHeaders.REDACTED_QUERY, saved.getQuery());
        assertEquals(session.traceId(), saved.getTraceId());
        assertEquals("db:1", saved.getOwnerPrincipalId());
        assertTrue(saved.getResultScores().containsKey("rank_1"));
        assertFalse(saved.getResultScores().containsKey("secret-doc"));
        assertEquals(1, saved.getMetadata().get("schemaVersion"));
    }

    @Test
    void persistFailureDoesNotThrow() {
        RagRetrievalLogRepository repository = mock(RagRetrievalLogRepository.class);
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        RetrievalDiagnosticsService service = new RetrievalDiagnosticsService(
                repository,
                new RagProperties(),
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class));
        RetrievalTraceSession session = service.createSession(
                ChatPrincipal.local(),
                RetrievalTraceHeaders.OPERATION_SEARCH,
                null);
        service.persistSearch(
                session,
                RetrievalOutcome.ofResults(List.of()),
                Map.of(),
                RetrievalFilters.none());
    }

    @Test
    void persistWithoutRepositoryDoesNotThrow() {
        RetrievalDiagnosticsService service = new RetrievalDiagnosticsService(
                null,
                new RagProperties(),
                new ObjectMapper(),
                null);
        RetrievalTraceSession session = service.createSession(
                ChatPrincipal.local(),
                RetrievalTraceHeaders.OPERATION_SEARCH,
                null);
        service.persistSearch(
                session,
                RetrievalOutcome.ofResults(List.of()),
                Map.of(),
                RetrievalFilters.none());
        assertEquals(0, service.list(
                ChatPrincipal.local(), null, null, null, null, null, 0, 20)
                .items().size());
    }

    @Test
    void principalCannotReadAnotherOwnersTrace() {
        RagRetrievalLogRepository repository = mock(RagRetrievalLogRepository.class);
        UUID traceId = UUID.randomUUID();
        RagRetrievalLog row = new RagRetrievalLog();
        row.setTraceId(traceId);
        row.setOwnerPrincipalId("db:other");
        when(repository.findByTraceId(traceId)).thenReturn(Optional.of(row));
        RetrievalDiagnosticsService service = new RetrievalDiagnosticsService(
                repository,
                new RagProperties(),
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class));

        RagException error = assertThrows(
                RagException.class,
                () -> service.get(
                        new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                        traceId));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void detailOmitsLegacyDocumentScoreKeys() {
        RagRetrievalLogRepository repository = mock(RagRetrievalLogRepository.class);
        UUID traceId = UUID.randomUUID();
        RagRetrievalLog row = new RagRetrievalLog();
        row.setTraceId(traceId);
        row.setOwnerPrincipalId("db:1");
        row.setResultScores(Map.of("doc-9", 0.4, "rank_1", 0.8));
        row.setMetadata(Map.of("schemaVersion", 1));
        when(repository.findByTraceId(traceId)).thenReturn(Optional.of(row));
        RetrievalDiagnosticsService service = new RetrievalDiagnosticsService(
                repository,
                new RagProperties(),
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class));

        RetrievalTraceDetailResponse detail = service.get(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                traceId);
        assertEquals(0.8, detail.resultScores().get("rank_1"));
        assertFalse(detail.resultScores().containsKey("doc-9"));
    }
}
