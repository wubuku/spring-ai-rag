package com.springairag.core.chat;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.RetrievalFilterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandMapperTest {

    private DomainExtensionRegistry domainExtensions;
    private ChatCommandMapper mapper;

    @BeforeEach
    void setUp() {
        domainExtensions = mock(DomainExtensionRegistry.class);
        mapper = new ChatCommandMapper(new RagProperties(), domainExtensions);
    }

    @Test
    void plainModeAllowsOmittedDtoRetrievalDefaults() {
        ChatRequest request = new ChatRequest("普通对话", "plain-session");
        request.setMode(ChatMode.PLAIN);

        ChatCommand command = mapper.map(
                request, RetrievalScope.unscoped(), ChatPrincipal.local());

        assertEquals(ChatMode.PLAIN, command.mode());
        assertFalse(request.isMaxResultsExplicitlySet());
        assertFalse(request.isUseHybridSearchExplicitlySet());
        assertFalse(request.isUseRerankExplicitlySet());
    }

    @Test
    void plainModeRejectsExplicitRetrievalOverrideWithTypedError() {
        ChatRequest request = new ChatRequest("普通对话", "plain-session");
        request.setMode(ChatMode.PLAIN);
        request.setUseRerank(true);

        RagException error = assertThrows(
                RagException.class,
                () -> mapper.map(
                        request,
                        RetrievalScope.unscoped(),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void unknownDomainUsesTypedError() {
        ChatRequest request = new ChatRequest("领域问题", "domain-session");
        request.setDomainId("missing-domain");
        when(domainExtensions.hasDomain("missing-domain")).thenReturn(false);

        RagException error = assertThrows(
                RagException.class,
                () -> mapper.map(
                        request,
                        RetrievalScope.unscoped(),
                        ChatPrincipal.local()));

        assertEquals(ErrorCode.UNKNOWN_DOMAIN, error.getErrorCodeEnum());
    }

    @Test
    void knowledgeModeAttachesValidatedFilters() throws Exception {
        ChatRequest request = new ChatRequest("按租户检索", "filter-session");
        RetrievalFilterRequest filters = new RetrievalFilterRequest();
        filters.setMetadataContains(new ObjectMapper().readTree(
                "{\"tenant\":\"acme\"}"));
        request.setFilters(filters);

        ChatCommand command = mapper.map(
                request, RetrievalScope.unscoped(), ChatPrincipal.local());

        RetrievalFilters attached = command.retrievalFilters();
        assertEquals("{\"tenant\":\"acme\"}",
                attached.metadataContains().canonicalJson());
    }

    @Test
    void plainModeRejectsFilters() throws Exception {
        ChatRequest request = new ChatRequest("普通对话", "plain-session");
        request.setMode(ChatMode.PLAIN);
        RetrievalFilterRequest filters = new RetrievalFilterRequest();
        filters.setMetadataContains(new ObjectMapper().readTree(
                "{\"tenant\":\"acme\"}"));
        request.setFilters(filters);

        RagException error = assertThrows(
                RagException.class,
                () -> mapper.map(
                        request,
                        RetrievalScope.unscoped(),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void rejectsExecutionSnapshotWithEmptyCandidateChain() {
        ChatRequest request = new ChatRequest("恢复", "session-restore");

        RagException error = assertThrows(
                RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        request,
                        ChatPrincipal.local(),
                        "session-restore",
                        """
                        {
                          "executionSnapshotVersion": 1,
                          "mode": "PLAIN",
                          "memoryMode": "SERVER",
                          "declaredModelIdentifier": "DEFAULT",
                          "resolvedCandidates": [],
                          "domainId": null,
                          "retrievalOptions": {
                            "maxResults": 5,
                            "minScore": 0.0,
                            "useHybridSearch": false,
                            "useRerank": false,
                            "vectorWeight": 0.0,
                            "fulltextWeight": 0.0
                          },
                          "effectiveScope": {
                            "collectionFilter": "NONE",
                            "collectionIds": [],
                            "documentIds": [],
                            "documentType": "",
                            "matchNone": true
                          }
                        }
                        """));

        assertEquals(
                ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

// ─── mapFromExecutionSnapshot 分支覆盖（Batch 194）────────────────────

    @Test
    void executionSnapshotBuildsCommandWithCandidatesScopeAndOptions() {
        String snapshot = """
                {
                  "executionSnapshotVersion": 1,
                  "mode": "KNOWLEDGE",
                  "memoryMode": "SERVER",
                  "resolvedCandidates": ["provider/model-a", "provider/model-b"],
                  "declaredModelIdentifier": "DEFAULT",
                  "domainId": "domain-9",
                  "retrievalOptions": {
                    "maxResults": 7,
                    "minScore": 0.25,
                    "useHybridSearch": true,
                    "useRerank": true,
                    "vectorWeight": 0.6,
                    "fulltextWeight": 0.4
                  },
                  "effectiveScope": {
                    "collectionFilter": "SELECTED",
                    "collectionIds": [5, 2],
                    "documentIds": [11],
                    "documentType": "text",
                    "matchNone": false
                  }
                }
                """;
        ChatRequest request = new ChatRequest("question", "session-9");

        ChatCommand command = mapper.mapFromExecutionSnapshot(
                request, ChatPrincipal.local(), "session-9", snapshot);

        assertEquals(ChatMode.KNOWLEDGE, command.mode());
        assertEquals(MemoryMode.SERVER, command.memoryMode());
        assertEquals("provider/model-a", command.modelRef());
        assertEquals("domain-9", command.domainId());
        assertEquals(7, command.retrievalOptions().maxResults());
        assertEquals(java.util.List.of(5L, 2L), command.retrievalScope().collectionIds());
        assertEquals("text", command.retrievalScope().documentType());
    }

    @Test
    void executionSnapshotWithWrongVersionFailsClosed() {
        String snapshot = """
                {"executionSnapshotVersion": 99, "mode": "KNOWLEDGE"}""";

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        new ChatRequest("q", "session-1"),
                        ChatPrincipal.local(), "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void executionSnapshotWithIncompleteRetrievalOptionsFailsClosed() {
        String snapshot = """
                {
                  "executionSnapshotVersion": 1,
                  "mode": "KNOWLEDGE",
                  "memoryMode": "SERVER",
                  "resolvedCandidates": ["provider/model-a"],
                  "retrievalOptions": {"maxResults": 7}
                }
                """;

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        new ChatRequest("q", "session-1"),
                        ChatPrincipal.local(), "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void executionSnapshotWithoutCandidatesFailsClosed() {
        String snapshot = """
                {
                  "executionSnapshotVersion": 1,
                  "mode": "KNOWLEDGE",
                  "memoryMode": "SERVER",
                  "resolvedCandidates": [],
                  "declaredModelIdentifier": "DEFAULT",
                  "retrievalOptions": {
                    "maxResults": 5, "minScore": 0.3, "useHybridSearch": true,
                    "useRerank": false, "vectorWeight": 0.5, "fulltextWeight": 0.5
                  },
                  "effectiveScope": {"collectionFilter": "NONE", "matchNone": false}
                }
                """;

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        new ChatRequest("q", "session-1"),
                        ChatPrincipal.local(), "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void executionSnapshotWithNonIntegralCollectionIdsFailsClosed() {
        String snapshot = """
                {
                  "executionSnapshotVersion": 1,
                  "mode": "KNOWLEDGE",
                  "memoryMode": "SERVER",
                  "resolvedCandidates": ["provider/model-a"],
                  "effectiveScope": {
                    "collectionFilter": "SELECTED",
                    "collectionIds": [0]
                  }
                }
                """;

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        new ChatRequest("q", "session-1"),
                        ChatPrincipal.local(), "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void executionSnapshotWithBlankCandidateFailsClosed() {
        String snapshot = """
                {
                  "executionSnapshotVersion": 1,
                  "mode": "KNOWLEDGE",
                  "memoryMode": "SERVER",
                  "resolvedCandidates": ["   "]
                }
                """;

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        new ChatRequest("q", "session-1"),
                        ChatPrincipal.local(), "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }
}
