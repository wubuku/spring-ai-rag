package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.api.service.DomainRagExtension;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatCommandMapper 映射守卫长尾（Batch 628，JaCoCo 驱动）：null
 * 请求、PLAIN 模式携带检索覆盖、未知 domain、domain 检索配置接线、
 * mapFromExecutionSnapshot 对非法 retrievalOptions/effectiveScope
 * 与候选列表的拒绝。
 */
class ChatCommandMapperGuardsTailTest {

    private RagProperties ragProperties;
    private DomainExtensionRegistry domainExtensions;
    private ChatCommandMapper mapper;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        domainExtensions = mock(DomainExtensionRegistry.class);
        mapper = new ChatCommandMapper(
                ragProperties, domainExtensions, new ObjectMapper());
    }

    private ChatRequest plainRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("hello");
        request.setMode(ChatMode.PLAIN);
        request.setSessionId("session-1");
        return request;
    }

    @Test
    void mapRejectsNullRequest() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(null, RetrievalScope.unscoped(), null));
        assertEquals("chat request must not be null", error.getMessage());
    }

    @Test
    void mapRejectsRetrievalOverridesInPlainMode() {
        ChatRequest request = plainRequest();
        request.setMaxResults(5);

        RagException error = assertThrows(RagException.class,
                () -> mapper.map(request, RetrievalScope.unscoped(), null));
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void mapRejectsUnknownDomain() {
        ChatRequest request = plainRequest();
        request.setDomainId("nope");
        when(domainExtensions.hasDomain("nope")).thenReturn(false);

        RagException error = assertThrows(RagException.class,
                () -> mapper.map(request, RetrievalScope.unscoped(), null));
        assertEquals(ErrorCode.UNKNOWN_DOMAIN, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("Unknown domain"));
    }

    @Test
    void mapWiresDomainRetrievalConfigIntoOptions() {
        ChatRequest request = plainRequest();
        request.setMode(ChatMode.KNOWLEDGE);
        request.setDomainId("support");
        var extension = mock(DomainRagExtension.class);
        var domainConfig = new RetrievalConfig();
        domainConfig.setMaxResults(3);
        when(extension.getRetrievalConfig()).thenReturn(domainConfig);
        when(domainExtensions.getExtension("support")).thenReturn(extension);
        when(domainExtensions.hasDomain("support")).thenReturn(true);

        ChatCommand command = mapper.map(
                request, RetrievalScope.unscoped(), null);

        assertEquals("support", command.domainId());
        assertEquals(3, command.retrievalOptions().maxResults());
    }

    @Test
    void mapFromExecutionSnapshotRejectsMissingRetrievalOptions() {
        String snapshot = """
                {"executionSnapshotVersion":1,"mode":"PLAIN",
                 "memoryMode":"STATELESS",
                 "resolvedCandidates":["a/b"]}""".strip();

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        plainRequest(), null, "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void mapFromExecutionSnapshotRejectsInvalidEffectiveScope() {
        String snapshot = """
                {"executionSnapshotVersion":1,"mode":"PLAIN",
                 "memoryMode":"STATELESS",
                 "resolvedCandidates":["a/b"],
                 "retrievalOptions":{"maxResults":5,"minScore":0.3,
                   "useHybridSearch":true,"useRerank":false,
                   "vectorWeight":0.5,"fulltextWeight":0.5},
                 "effectiveScope":"not-an-object"}""".strip();

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        plainRequest(), null, "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void mapFromExecutionSnapshotRejectsNonNumericCollectionIds() {
        String snapshot = """
                {"executionSnapshotVersion":1,"mode":"PLAIN",
                 "memoryMode":"STATELESS",
                 "resolvedCandidates":["a/b"],
                 "retrievalOptions":{"maxResults":5,"minScore":0.3,
                   "useHybridSearch":true,"useRerank":false,
                   "vectorWeight":0.5,"fulltextWeight":0.5},
                 "effectiveScope":{"collectionFilter":"SELECTED",
                   "collectionIds":["x"],"documentIds":[],
                   "matchNone":false}}""".strip();

        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        plainRequest(), null, "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void mapFromExecutionSnapshotAppliesFiltersAndCanonicalModel() {
        String snapshot = """
                {"executionSnapshotVersion":1,"mode":"PLAIN",
                 "memoryMode":"STATELESS",
                 "resolvedCandidates":["a/b","c/d"],
                 "declaredModelIdentifier":"DEFAULT",
                 "domainId":"  ",
                 "retrievalOptions":{"maxResults":5,"minScore":0.3,
                   "useHybridSearch":true,"useRerank":false,
                   "vectorWeight":0.5,"fulltextWeight":0.5},
                 "effectiveScope":{"collectionFilter":"NONE",
                   "collectionIds":[],"documentIds":[],
                   "matchNone":false}}""".strip();

        ChatCommand command = mapper.mapFromExecutionSnapshot(
                plainRequest(), null, "session-1", snapshot);

        // 候选链非空 → modelRef 取候选首位；domainId 空白归一为 null。
        assertEquals("a/b", command.modelRef());
        assertNull(command.domainId());
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}
