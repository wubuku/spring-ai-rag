package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * collectionPurgeVisible 能力可见性矩阵（Batch 337）：配置开关、
 * 环境根、数据库管理员与普通密钥、匿名本机回环 + allowAuthDisabled
 * 开关组合。
 */
class IntegrationCapabilityPurgeVisibilityTest {

    private RagProperties properties;
    private IntegrationCapabilityCatalog catalog;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        var identityResolver = Mockito.mock(CollectionIdentityResolver.class);
        Mockito.when(identityResolver.mapKeys(Mockito.any()))
                .thenReturn(java.util.Map.of(7L, "kb"));
        catalog = new IntegrationCapabilityCatalog(properties, identityResolver);
    }

    private boolean purgeVisible(MockHttpServletRequest request) {
        return catalog.describe(request).getFeatures()
                .optional().collectionPurge();
    }

    private MockHttpServletRequest rootRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        return request;
    }

    private MockHttpServletRequest databaseRequest(ApiKeyRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "db:1");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new AuthenticatedApiPrincipal(
                        "db:1", "kid", 1,
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        role, "7", null, 1L, null));
        return request;
    }

    private MockHttpServletRequest anonymousRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void disabledByConfigAlwaysHidden() {
        properties.getCollectionPurge().setEnabled(false);

        assertEquals(false, purgeVisible(rootRequest()));
        assertEquals(false, purgeVisible(
                databaseRequest(ApiKeyRole.ADMIN)));
    }

    @Test
    void environmentRootSeesPurgeWhenEnabled() {
        properties.getCollectionPurge().setEnabled(true);

        assertEquals(true, purgeVisible(rootRequest()));
    }

    @Test
    void databaseAdminSeesPurgeButNormalRoleDoesNot() {
        properties.getCollectionPurge().setEnabled(true);

        assertEquals(true,
                purgeVisible(databaseRequest(ApiKeyRole.ADMIN)));
        assertEquals(false,
                purgeVisible(databaseRequest(ApiKeyRole.NORMAL)));
    }

    @Test
    void authDisabledLoopbackSeesPurgeOnlyWhenFlagOn() {
        properties.getCollectionPurge().setEnabled(true);
        properties.getCollectionPurge().setAllowAuthDisabled(true);

        assertEquals(true,
                purgeVisible(anonymousRequest("127.0.0.1")));
        // 非本机回环来源不可见。
        assertEquals(false,
                purgeVisible(anonymousRequest("93.184.216.34")));

        // allowAuthDisabled 关闭后匿名不可见。
        properties.getCollectionPurge().setAllowAuthDisabled(false);
        assertEquals(false,
                purgeVisible(anonymousRequest("127.0.0.1")));
    }

    @Test
    void legacyStaticPrincipalDoesNotSeePurge() {
        properties.getCollectionPurge().setEnabled(true);
        properties.getCollectionPurge().setAllowAuthDisabled(true);
        MockHttpServletRequest legacy = new MockHttpServletRequest();
        legacy.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
        legacy.setRemoteAddr("127.0.0.1");

        assertEquals(false, purgeVisible(legacy));
    }
}
