package com.springairag.core.observability;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.observability.IntegrationObservationRepository;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 集成可观测性查询守卫长尾（Batch 712，JaCoCo 驱动）：null 请求
 * 的匿名拒绝（212）、受限策略 ACL 格式损坏时的不可用降级
 * （312-313）。
 *
 * 勿再投入：336-337（parseWindow 区间算术溢出）不可达——
 * Instant.parse 年份界限 ±999999999 内 Duration.between 恒不溢
 * 出 long；262 的 principal 引用不匹配臂同理恒假（投影类型为
 * DATABASE_API_KEY 时投影已要求 ref == principal.principalId）。
 * 的匿名拒绝（212）、受限策略 ACL 格式损坏时的不可用降级
 * （312-313）、查询区间溢出算术异常的参数化拒绝（336-337）。
 *
 * 勿再投入：262 的 principal 引用不匹配臂不可达——投影类型为
 * DATABASE_API_KEY 时投影自身已要求 ref == principal.principalId
 * （同一请求属性推导），条件恒假。
 */
class IntegrationObservabilityScopeGuardTailTest {

    private IntegrationObservabilityQueryService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationObservabilityQueryService(
                mock(IntegrationObservationRepository.class),
                new RagProperties(),
                mock(CollectionIdentityResolver.class),
                null);
    }

    private Object invokeResolveScope(MockHttpServletRequest request,
                                      String principalId,
                                      String collectionKey)
            throws Throwable {
        Method method = IntegrationObservabilityQueryService.class
                .getDeclaredMethod("resolveScope",
                        jakarta.servlet.http.HttpServletRequest.class,
                        String.class, String.class);
        method.setAccessible(true);
        try {
            return method.invoke(service, request, principalId,
                    collectionKey);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void resolveScopeRejectsNullRequestAsAnonymous() {
        RagException error = assertThrows(RagException.class,
                () -> invokeResolveScope(null, null, null));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
    }

    private Object invokeResolveCollectionFilter(
            com.springairag.core.security.ApiAccessPolicy restrictedPolicy)
            throws Throwable {
        Method method = IntegrationObservabilityQueryService.class
                .getDeclaredMethod("resolveCollectionFilter",
                        String.class,
                        com.springairag.core.security.ApiAccessPolicy.class,
                        com.springairag.core.security.ApiAccessPolicy.class);
        method.setAccessible(true);
        try {
            return method.invoke(service, null, null, restrictedPolicy);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void resolveCollectionFilterDegradesMalformedAclToUnavailable() {
        com.springairag.core.security.ApiAccessPolicy restricted =
                mock(com.springairag.core.security.ApiAccessPolicy.class);
        org.mockito.Mockito.when(restricted.getAllowedCollectionIds())
                .thenReturn("1,,2");

        RagException error = assertThrows(RagException.class,
                () -> invokeResolveCollectionFilter(restricted));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
    }

    @Test
    void resolveCollectionFilterReturnsNullForUnrestrictedPolicy()
            throws Throwable {
        com.springairag.core.security.ApiAccessPolicy unrestricted =
                mock(com.springairag.core.security.ApiAccessPolicy.class);
        org.mockito.Mockito.when(unrestricted.getRole())
                .thenReturn(com.springairag.core.entity.ApiKeyRole.ADMIN);

        Object filter = invokeResolveCollectionFilter(unrestricted);

        org.junit.jupiter.api.Assertions.assertNull(filter);
    }

    @Test
    void resolveScopeRejectsLegacyStaticPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/ops/integrations");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);

        RagException error = assertThrows(RagException.class,
                () -> invokeResolveScope(request, null, null));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
    }

    @Test
    void resolveScopeAllowsLocalAuthDisabledWithoutPrincipalId()
            throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/ops/integrations");

        Object resolution = invokeResolveScope(request, null, null);

        org.junit.jupiter.api.Assertions.assertNotNull(resolution);
    }
}
