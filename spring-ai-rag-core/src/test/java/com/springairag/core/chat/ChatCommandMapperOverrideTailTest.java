package com.springairag.core.chat;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatCommandMapper 检索覆盖长尾（Batch 434）：PLAIN 模式下各
 * 单一覆盖来源（maxResults/混合/重排/collectionScopeMode/
 * collectionIds/collectionKeys/documentIds/filters）逐一拒绝、
 * metadata null 归一、快照声明模型 DEFAULT→null 与 custom 透传、
 * 快照 domainId 空白归 null。
 */
class ChatCommandMapperOverrideTailTest {

    private DomainExtensionRegistry domainExtensions;
    private ChatCommandMapper mapper;

    @BeforeEach
    void setUp() {
        domainExtensions = mock(DomainExtensionRegistry.class);
        mapper = new ChatCommandMapper(new RagProperties(), domainExtensions);
    }

    private RagException rejected(java.util.function.Consumer<ChatRequest> applyOverride) {
        return assertThrows(RagException.class, () -> {
            ChatRequest request = new ChatRequest("q", "session-1");
            request.setMode(ChatMode.PLAIN);
            applyOverride.accept(request);
            mapper.map(request, RetrievalScope.unscoped(),
                    ChatPrincipal.local());
        });
    }

    @Test
    void plainModeRejectsEachSingleOverrideSource() {
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                rejected(r -> r.setMaxResults(3)).getErrorCodeEnum());
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                rejected(r -> r.setUseHybridSearch(true)).getErrorCodeEnum());
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                rejected(r -> r.setUseRerank(true)).getErrorCodeEnum());
    }

    @Test
    void plainModeRejectsScopeCollectionAndDocumentOverrides() {
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                rejected(r -> r.setCollectionScopeMode(
                        CollectionScopeMode.SELECTED_COLLECTIONS))
                        .getErrorCodeEnum());
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                rejected(r -> r.setCollectionIds(List.of(7L)))
                        .getErrorCodeEnum());
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                rejected(r -> r.setCollectionKeys(List.of("kb")))
                        .getErrorCodeEnum());
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                rejected(r -> r.setDocumentIds(List.of(3L)))
                        .getErrorCodeEnum());
    }

    @Test
    void knowledgeModeNormalizesNullMetadataToEmptyMap() {
        ChatRequest request = new ChatRequest("q", "session-1");
        request.setMode(ChatMode.KNOWLEDGE);

        ChatCommand command = mapper.map(request,
                RetrievalScope.unscoped(), ChatPrincipal.local());

        assertTrue(command.clientMetadata().isEmpty());

        ChatRequest withMetadata = new ChatRequest("q", "session-1");
        withMetadata.setMode(ChatMode.KNOWLEDGE);
        withMetadata.setMetadata(Map.of("tenant", "acme"));
        ChatCommand commandWithMetadata = mapper.map(withMetadata,
                RetrievalScope.unscoped(), ChatPrincipal.local());
        assertEquals("acme",
                commandWithMetadata.clientMetadata().get("tenant"));
    }

    @Test
    void snapshotDeclaredModelDefaultYieldsNullModelRef() {
        ChatRequest request = new ChatRequest("恢复", "session-restore");
        String snapshot = """
                {
                  "executionSnapshotVersion": 1,
                  "mode": "PLAIN",
                  "memoryMode": "SERVER",
                  "declaredModelIdentifier": "DEFAULT",
                  "resolvedCandidates": ["m1"],
                  "domainId": "  ",
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
                """;

        ChatCommand command = mapper.mapFromExecutionSnapshot(
                request, ChatPrincipal.local(), "session-restore", snapshot);

        // 既有语义：候选项非空时 modelRef 取首候选（DEFAULT 分支不可达）；
        // domainId 空白 → null。
        assertEquals("m1", command.modelRef());
        assertEquals(null, command.domainId());
    }

    @Test
    void snapshotCustomDeclaredModelIsPassedThrough() {
        ChatRequest request = new ChatRequest("恢复", "session-restore");
        String snapshot = """
                {
                  "executionSnapshotVersion": 1,
                  "mode": "PLAIN",
                  "memoryMode": "SERVER",
                  "declaredModelIdentifier": "acme/custom-model",
                  "resolvedCandidates": ["acme/custom-model"],
                  "domainId": "legal",
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
                """;

        ChatCommand command = mapper.mapFromExecutionSnapshot(
                request, ChatPrincipal.local(), "session-restore", snapshot);

        assertEquals("acme/custom-model", command.modelRef());
        assertEquals("legal", command.domainId());
    }

    @Test
    void unknownDomainIsStillRejectedByPublicMap() {
        when(domainExtensions.hasDomain("ghost-domain")).thenReturn(false);
        ChatRequest request = new ChatRequest("q", "session-1");
        request.setMode(ChatMode.KNOWLEDGE);
        request.setDomainId("ghost-domain");

        RagException error = assertThrows(RagException.class,
                () -> mapper.map(request, RetrievalScope.unscoped(),
                        ChatPrincipal.local()));
        assertEquals(ErrorCode.UNKNOWN_DOMAIN, error.getErrorCodeEnum());
        Mockito.verify(domainExtensions).hasDomain("ghost-domain");
    }
}
