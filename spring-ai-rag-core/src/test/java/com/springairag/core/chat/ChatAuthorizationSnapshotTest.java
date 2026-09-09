package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 授权快照（snapshot/initialSnapshot）构建语义：PLAIN 模式标记
 * NOT_APPLICABLE、CALLER_VISIBLE + 非受限调用方允许未分配文档、
 * RESTRICTED 调用方排序白名单、来源响应映射到文档/集合对。
 */
class ChatAuthorizationSnapshotTest {

    private RagDocumentRepository documentRepository;
    private ChatAuthorizationService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        service = new ChatAuthorizationService(
                new ObjectMapper(), documentRepository,
                mock(com.springairag.core.service.ApiKeyManagementService.class));
        // 默认请求上下文：无属性的 Mock 请求 = 非受限调用方。
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/chat")));
    }

    private ChatCommand command(
            ChatMode mode,
            com.springairag.core.retrieval.RetrievalScope scope) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                mode,
                MemoryMode.SERVER,
                null,
                null,
                scope,
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private JsonNode parse(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @Test
    void plainModeSnapshotMarksEverythingNotApplicable() throws Exception {
        String json = service.initialSnapshot(
                command(ChatMode.PLAIN, RetrievalScope.unscoped()));

        JsonNode snapshot = parse(json);
        assertEquals("NOT_APPLICABLE", snapshot.get("scopeMode").asText());
        assertEquals("NOT_APPLICABLE",
                snapshot.get("callerAccessMode").asText());
        assertTrue(snapshot.get("effectiveSelectedCollectionIds").isEmpty());
        assertTrue(snapshot.get("callerAllowList").isEmpty());
        assertEquals(false,
                snapshot.get("unassignedDocumentsAllowed").asBoolean());
    }

    @Test
    void callerVisibleSnapshotForUnrestrictedCallerAllowsUnassigned()
            throws Exception {
        String json = service.snapshot(
                command(ChatMode.KNOWLEDGE, RetrievalScope.unscoped()),
                null);

        JsonNode snapshot = parse(json);
        assertEquals("CALLER_VISIBLE", snapshot.get("scopeMode").asText());
        assertEquals("UNRESTRICTED",
                snapshot.get("callerAccessMode").asText());
        assertEquals(true,
                snapshot.get("unassignedDocumentsAllowed").asBoolean());
        assertEquals(1, snapshot.get("authorizationSnapshotVersion").asInt());
    }

    @Test
    void restrictedCallerSnapshotListsSortedAllowList() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/chat");
        request.setAttribute("authenticatedPrincipalType", "DATABASE_API_KEY");
        request.setAttribute("authenticatedApiKey", "key-42");
        // RESTRICTED 白名单由 ApiKeyCollectionAccess 从请求属性解析；
        // 这里直接构造带 allowedCollectionIds 的键实体属性。
        com.springairag.core.entity.RagApiKey key =
                new com.springairag.core.entity.RagApiKey();
        key.setRole(com.springairag.core.entity.ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds("7,9");
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                key);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));

        String json = service.snapshot(
                command(ChatMode.KNOWLEDGE,
                        com.springairag.core.retrieval.RetrievalScope
                                .selectedCollections(List.of(9L), List.of(), null)),
                null);

        Map<String, Object> snapshot =
                new ObjectMapper().readValue(json, Map.class);
        assertEquals("RESTRICTED", snapshot.get("callerAccessMode"));
        // 白名单去重并排序。
        // 白名单去重排序（类型为 ArrayList，转字符串比较）。
        assertEquals("[7, 9]",
                snapshot.get("callerAllowList").toString());
        assertEquals(false,
                snapshot.get("unassignedDocumentsAllowed"));
    }

    @Test
    void snapshotMapsResponseSourcesToDocumentCollectionPairs()
            throws Exception {
        RagDocument document = new RagDocument();
        document.setId(10L);
        document.setCollectionId(7L);
        when(documentRepository.findById(10L))
                .thenReturn(Optional.of(document));

        ChatSource source = new ChatSource();
        source.setCitationId("S1");
        source.setDocumentId("10");
        source.setChunkIndex(0);
        ChatResponse response = new ChatResponse();
        response.setAnswer("answer");
        response.setSources(List.of(source));

        String json = service.snapshot(
                command(ChatMode.KNOWLEDGE, RetrievalScope.unscoped()),
                response);

        JsonNode snapshot = parse(json);
        JsonNode rows = snapshot.get("sourceDocumentCollectionSnapshot");
        assertEquals(1, rows.size());
        assertEquals(10, rows.get(0).get("documentId").asLong());
        assertEquals(7, rows.get(0).get("collectionId").asLong());
        // 观察到的集合 id 去重排序。
        assertEquals(7, snapshot.get("sourceCollectionIdsObserved").get(0).asLong());
    }

    @Test
    void snapshotRejectsUnknownSourceDocument() {
        when(documentRepository.findById(10L))
                .thenReturn(Optional.empty());
        ChatSource source = new ChatSource();
        source.setCitationId("S1");
        source.setDocumentId("10");
        source.setChunkIndex(0);
        ChatResponse response = new ChatResponse();
        response.setAnswer("answer");
        response.setSources(List.of(source));

        RagException error = assertThrows(RagException.class,
                () -> service.snapshot(
                        command(ChatMode.KNOWLEDGE, RetrievalScope.unscoped()),
                        response));
        assertEquals(
                com.springairag.api.enums.ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }
}
