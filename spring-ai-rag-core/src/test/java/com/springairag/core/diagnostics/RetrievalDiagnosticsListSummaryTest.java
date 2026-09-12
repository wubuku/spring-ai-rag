package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalTracePageResponse;
import com.springairag.api.dto.RetrievalTraceSummaryResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * toSummary 列表摘要映射（Batch 319）：全字段映射、嵌套
 * citationValidation.status 提取、缺元数据时 citationStatus 为空。
 */
class RetrievalDiagnosticsListSummaryTest {

    private RagRetrievalLogRepository repository;
    private RetrievalDiagnosticsService service;

    private void setUpService() {
        repository = mock(RagRetrievalLogRepository.class);
        service = new RetrievalDiagnosticsService(
                repository,
                new RagProperties(),
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class));
    }

    private RagRetrievalLog logEntry(Map<String, Object> metadata) {
        RagRetrievalLog entry = new RagRetrievalLog();
        entry.setTraceId(UUID.fromString(
                "00000000-0000-0000-0000-0000000000aa"));
        entry.setOperation("search");
        entry.setOutcomeCode("RESULTS_RETURNED");
        entry.setEmptyReasonCode(null);
        entry.setSessionId("session-1");
        entry.setCreatedAt(ZonedDateTime.parse("2026-09-13T01:00:00Z"));
        entry.setResultCount(7);
        entry.setTotalTimeMs(1234L);
        entry.setMetadata(metadata);
        entry.setOwnerPrincipalId("db:1");
        return entry;
    }

    private void stubSearch(RagRetrievalLog entry) {
        Page<RagRetrievalLog> page = new PageImpl<>(List.of(entry));
        when(repository.searchTraces(
                anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);
    }

    @Test
    void listExtractsCitationStatusFromNestedMetadata() {
        setUpService();
        stubSearch(logEntry(Map.of(
                "citationValidation", Map.of("status", "VALID"))));

        RetrievalTracePageResponse response = service.list(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                null, null, null, null, null, 0, 20);

        assertEquals(1, response.items().size());
        RetrievalTraceSummaryResponse summary = response.items().get(0);
        assertEquals("search", summary.operation());
        assertEquals("RESULTS_RETURNED", summary.outcomeCode());
        assertEquals("session-1", summary.sessionId());
        assertEquals(7, summary.resultCount());
        assertEquals(1234L, summary.totalTimeMs());
        assertEquals("VALID", summary.citationStatus());
    }

    @Test
    void missingOrNonMapCitationMetadataYieldsNullStatus() {
        setUpService();
        // 元数据缺失 citationValidation。
        stubSearch(logEntry(Map.of("schemaVersion", 1)));
        RetrievalTracePageResponse withoutCitation = service.list(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                null, null, null, null, null, 0, 20);
        assertNull(withoutCitation.items().get(0).citationStatus());

        // citationValidation 是映射但 status 为 null。
        java.util.Map<String, Object> citation =
                new java.util.HashMap<>();
        citation.put("citationValidation",
                new java.util.HashMap<>(java.util.Map.of()));
        citation.put("unused", "x");
        stubSearch(logEntry(java.util.Map.of(
                "citationValidation",
                new java.util.HashMap<String, Object>())));
        RetrievalTracePageResponse nullStatus = service.list(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                null, null, null, null, null, 0, 20);
        assertNull(nullStatus.items().get(0).citationStatus());
    }

    @Test
    void nullMetadataYieldsNullCitationStatus() {
        setUpService();
        stubSearch(logEntry(null));

        RetrievalTracePageResponse response = service.list(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                null, null, null, null, null, 0, 20);

        assertEquals(1, response.items().size());
        assertNull(response.items().get(0).citationStatus());
    }
}
