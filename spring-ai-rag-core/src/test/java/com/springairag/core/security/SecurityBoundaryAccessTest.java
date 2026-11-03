package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.service.PdfImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全边界单元回归：文件 ACL 与虚拟路径校验。
 *
 * <p>真实 HTTP 全入口矩阵在 `scripts/verify-security-boundary.py`（gated 层）
 * 覆盖；这里固定最容易回归的判定表与路径穿越拒绝逻辑，保证 PR 快层能早失败。
 */
class SecurityBoundaryAccessTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static ApiAccessPolicy policy(String principalId, ApiKeyRole role,
                                          String allowedCollectionIds) {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return principalId; }
            @Override public String getCredentialId() { return "cred-" + principalId; }
            @Override public ApiKeyRole getRole() { return role; }
            @Override public String getAllowedCollectionIds() { return allowedCollectionIds; }
            @Override public LocalDateTime getExpiresAt() { return null; }
        };
    }

    @ParameterizedTest(name = "{0}: principal={1}, collection={2} -> {3}")
    @CsvSource({
            // case, role, restrictedCsv, ownerPrincipal, fileCollection, expected
            "AUTH_DISABLED,   ADMIN, '',   '',          '',  true",
            "ROOT,            ADMIN, '',   root,        '',  true",
            "ADMIN_ALL,       ADMIN, '1',  other,       9,   true",
            "UNRESTRICTED,    NORMAL,  '',   other,       9,   true",
            "OWNER_MATCH,     NORMAL,  '9',  principal-a, 8,   true",
            "COLLECTION_MATCH,NORMAL,  '9',  principal-b, 9,   true",
            "OTHER_COLLECTION,NORMAL,  '9',  principal-b, 8,   false",
            "LEGACY_UNOWNED,  NORMAL,  '9',  '',          '',  false",
            "BLANK_ACL,       NORMAL,  ' ',  principal-b, 8,   true"
    })
    void fileVisibilityMatrix(String caseName, ApiKeyRole role, String restrictedCsv,
                              String ownerPrincipal, String fileCollection,
                              boolean expected) {
        ApiAccessPolicy key;
        if ("AUTH_DISABLED".equals(caseName)) {
            key = null;
        } else if ("ROOT".equals(caseName)) {
            key = policy("root", ApiKeyRole.ADMIN, null);
        } else {
            key = policy("principal-a", role, restrictedCsv.isBlank() ? null : restrictedCsv);
        }
        Long collectionId = fileCollection.isBlank() ? null : Long.parseLong(fileCollection);
        String owner = ownerPrincipal.isBlank() ? null : ownerPrincipal;
        assertEquals(expected, ApiKeyCollectionAccess.canAccessFile(key, owner, collectionId));
    }

    @Test
    void restrictedPrincipalCannotReadAnotherPrincipalsUnownedFile() {
        ApiAccessPolicy b = policy("principal-b", ApiKeyRole.NORMAL, "9");
        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.requireFileAccess(b, "principal-a", null));
        // 同一 principal 的另一个 key 可以读取
        ApiAccessPolicy a = policy("principal-a", ApiKeyRole.NORMAL, "9");
        assertDoesNotThrow(
                () -> ApiKeyCollectionAccess.requireFileAccess(a, "principal-a", null));
    }

    @Test
    void currentPolicyReadsAuthenticatedPrincipalAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ApiAccessPolicy key = policy("principal-a", ApiKeyRole.NORMAL, "9");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE, key);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals(key, ApiKeyCollectionAccess.currentPolicy());
    }

    @ParameterizedTest(name = "path traversal rejected: {0}")
    @CsvSource({
            "../../etc/passwd",
            "..%2F..%2Fetc%2Fpasswd",
            "uuid/../../secret.md",
            "..\\\\windows\\\\system.ini",
            "uuid/./default.md",
            "uuid%00default.md"
    })
    void decodesThenRejectsPathTraversal(String rawPath) {
        String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.validateVirtualPath(decoded));
    }

    @Test
    void acceptsNormalVirtualPaths() {
        assertEquals("", PdfImportService.validateVirtualPath(""));
        assertEquals("/", PdfImportService.validateVirtualPath("/"));
        assertEquals("uuid/default.md", PdfImportService.validateVirtualPath("uuid/default.md"));
        assertEquals(
                "uuid/default.md",
                PdfImportService.validateVirtualPath(
                        URLDecoder.decode("uuid%2Fdefault.md", StandardCharsets.UTF_8)));
    }
}
