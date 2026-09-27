package com.springairag.core.service;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UserFeedbackServiceImpl 校验守卫长尾（Batch 684，JaCoCo 驱
 * 动）：受限策略下快照不足的 SecurityException、documentId 不匹
 * 配的 CONCURRENT_MODIFICATION、null collectionId 的 NOT_FOUND、
 * 引用数超限的 IAE。
 */
class UserFeedbackServiceImplValidateTailTest {

    private com.springairag.core.repository.RagUserFeedbackRepository repository;
    private FeedbackDocumentReferenceStore referenceStore;
    private CollectionIdentityResolver identityResolver;
    private UserFeedbackServiceImpl service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        repository = mock(com.springairag.core.repository.RagUserFeedbackRepository.class);
        referenceStore = mock(FeedbackDocumentReferenceStore.class);
        identityResolver = mock(CollectionIdentityResolver.class);
        service = new UserFeedbackServiceImpl(
                repository,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                referenceStore,
                identityResolver);
        request = new MockHttpServletRequest("POST", "/feedback");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private FeedbackDocumentReferenceStore.DocumentSnapshot snapshot(
            long documentId, Long collectionId) {
        return new FeedbackDocumentReferenceStore.DocumentSnapshot(
                documentId, collectionId, true);
    }

    private void allowReferences(
            FeedbackDocumentReferenceStore.DocumentSnapshot... snapshots) {
        List<FeedbackDocumentReferenceStore.DocumentSnapshot> values =
                List.of(snapshots);
        List<Long> ids = values.stream()
                .map(FeedbackDocumentReferenceStore.DocumentSnapshot::documentId)
                .toList();
        when(referenceStore.load(ids)).thenReturn(values, values);
    }

    private ApiAccessPolicy restrictedPolicy(String allowedIds) {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return "rag_p_1"; }
            @Override public String getCredentialId() { return "rag_k_1"; }
            @Override public ApiKeyRole getRole() { return ApiKeyRole.NORMAL; }
            @Override public String getAllowedCollectionIds() { return allowedIds; }
            @Override public java.time.LocalDateTime getExpiresAt() { return null; }
        };
    }

    @Test
    void restrictedPolicyFewerSnapshotsThrowsSecurityException() {
        var restricted = restrictedPolicy("10,20");
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                restricted);

        // 期望 3 个文档，但 store 只返回 2 个快照。
        when(referenceStore.load(any()))
                .thenReturn(List.of(
                        snapshot(1L, 10L),
                        snapshot(2L, 20L)));

        assertThrows(SecurityException.class,
                () -> service.submitFeedback(
                        "s-1", "查询", "THUMBS_UP", null, null,
                        List.of(1L, 2L, 3L), null, null));
    }

    @Test
    void unrestrictedPolicyFewerSnapshotsThrowsRagException() {
        // 无限制策略（null policy）→ 不抛 SecurityException，而是
        // RagException DOCUMENT_NOT_FOUND。
        when(referenceStore.load(any()))
                .thenReturn(List.of(snapshot(1L, 10L)));

        assertThrows(com.springairag.core.exception.RagException.class,
                () -> service.submitFeedback(
                        "s-1", "查询", "THUMBS_UP", null, null,
                        List.of(1L, 99L), null, null));
    }

    @Test
    void documentIdMismatchThrowsConcurrentModification() {
        // 快照的 documentId 与期望不一致 → CONCURRENT_MODIFICATION。
        when(referenceStore.load(any()))
                .thenReturn(List.of(
                        snapshot(1L, 10L),
                        snapshot(99L, 20L)));

        assertThrows(com.springairag.core.exception.RagException.class,
                () -> service.submitFeedback(
                        "s-1", "查询", "THUMBS_UP", null, null,
                        List.of(1L, 2L), null, null));
    }

    @Test
    void nullCollectionIdThrowsDocumentNotFound() {
        when(referenceStore.load(any()))
                .thenReturn(List.of(
                        snapshot(1L, null),
                        snapshot(2L, 20L)));

        assertThrows(com.springairag.core.exception.RagException.class,
                () -> service.submitFeedback(
                        "s-1", "查询", "THUMBS_UP", null, null,
                        List.of(1L, 2L), null, null));
    }

    @Test
    void overMaxDocumentReferencesThrowsIllegalArgument() {
        // MAX_DOCUMENT_REFERENCES = 1000，传入 1001 个 ID。
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 1001; i++) {
            tooMany.add(i);
        }

        assertThrows(IllegalArgumentException.class,
                () -> service.submitFeedback(
                        "s-1", "查询", "THUMBS_UP", null,
                        "回答", tooMany, null, null));
    }
}
