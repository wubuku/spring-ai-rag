package com.springairag.core.controller;

import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.springairag.core.service.CollectionIdentityResolver;

/**
 * PdfImportController 嵌入包装 catch 臂长尾（Batch 694，JaCoCo 驱
 * 动）：SSE 路径同步段的 IAE → 400、受限 API Key 策略下越权
 * collectionId 的 SecurityException 原样重抛。
 *
 * 勿再投入：sync 路径的 IAE / 通用异常在 5 参 triggerEmbeddingSync
 * 内部已自行捕获转 400/500，包装层对应 catch（658-660）仅可能由
 * SSE 同步段的非 IAE/SE 异常触发，当前无已知可达来源。
 */
class PdfImportControllerEmbedCatchTailTest {

    private PdfToRagService pdfToRagService;
    private PdfImportController controller;

    private CollectionIdentityResolver collectionIdentityResolver;

    @BeforeEach
    void setUp() {
        pdfToRagService = mock(PdfToRagService.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        controller = new PdfImportController(
                mock(PdfImportService.class),
                null,
                pdfToRagService, collectionIdentityResolver);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void useRestrictedPolicy(String allowedCollectionIds) {
        ApiAccessPolicy policy = mock(ApiAccessPolicy.class);
        when(policy.getPrincipalId()).thenReturn("principal-embed");
        when(policy.getCredentialId()).thenReturn("credential-embed");
        when(policy.getAllowedCollectionIds()).thenReturn(allowedCollectionIds);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/pdf-import");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policy);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @Test
    void sseEmbedWithBlankUuidBecomesBadRequest() {
        // SSE 同步段的 UUID 校验 IAE 逃逸到包装层 → 400。
        Object response = controller.triggerEmbedding(
                "  ", null, "sse", false);

        org.junit.jupiter.api.Assertions.assertTrue(
                response instanceof org.springframework.http.ResponseEntity);
        org.springframework.http.ResponseEntity<?> entity =
                (org.springframework.http.ResponseEntity<?>) response;
        org.junit.jupiter.api.Assertions.assertEquals(400,
                entity.getStatusCode().value());
    }

    @Test
    void sseEmbedOutsideRestrictedPolicyRethrowsSecurityException() {
        useRestrictedPolicy("7");

        // collectionId=9 不在受限策略允许列表 {7} → SE 原样重抛。
        assertThrows(SecurityException.class,
                () -> controller.triggerEmbedding(
                        "uuid-1", 9L, "sse", false));
    }
}
