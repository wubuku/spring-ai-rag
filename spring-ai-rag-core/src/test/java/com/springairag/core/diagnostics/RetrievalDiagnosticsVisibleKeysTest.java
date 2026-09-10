package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalTraceDetailResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索诊断详情对受限调用方的键位脱敏：非受限调用方原样返回请求键
 * （不触发解析）、受限调用方仅保留允许集合内的键（未知键与解析异
 * 常静默丢弃）、解析器缺失时全部隐藏（防错误差异探测）。
 */
class RetrievalDiagnosticsVisibleKeysTest {

    private RagRetrievalLogRepository repository;
    private CollectionIdentityResolver identityResolver;
    private UUID traceId;

    @BeforeEach
    void setUp() {
        repository = mock(RagRetrievalLogRepository.class);
        identityResolver = mock(CollectionIdentityResolver.class);
        traceId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ApiAccessPolicy restrictedPolicy() {
        return new ApiAccessPolicy() {
            @Override
            public String getPrincipalId() {
                return "db:key-1";
            }

            @Override
            public String getCredentialId() {
                return "rag_k_1";
            }

            @Override
            public ApiKeyRole getRole() {
                return ApiKeyRole.NORMAL;
            }

            @Override
            public String getAllowedCollectionIds() {
                return "10,11";
            }

            @Override
            public LocalDateTime getExpiresAt() {
                return null;
            }
        };
    }

    private void authenticateWith(ApiAccessPolicy policy) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/webui/diagnostics");
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policy);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private RagRetrievalLog logEntry() {
        RagRetrievalLog logEntry = new RagRetrievalLog();
        logEntry.setTraceId(traceId);
        logEntry.setOwnerPrincipalId("db:key-1");
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("collectionKeys",
                java.util.Arrays.asList(
                        "kb-10", "kb-11", "kb-99", "kb-crash", "  ", null));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", scope);
        logEntry.setMetadata(metadata);
        return logEntry;
    }

    private RetrievalDiagnosticsService service(
            CollectionIdentityResolver resolver) {
        return new RetrievalDiagnosticsService(
                repository,
                new RagProperties(),
                new ObjectMapper(),
                resolver);
    }

    @Test
    void unrestrictedCallerSeesRequestedKeysWithoutResolution() {
        when(repository.findByTraceId(traceId))
                .thenReturn(Optional.of(logEntry()));

        RetrievalTraceDetailResponse response = service(identityResolver)
                .get(new com.springairag.core.chat.ChatPrincipal(
                        "db:key-1", "DATABASE_API_KEY", false), traceId);

        // 非受限调用方：仅剔除空白/空值，键保持原序且不触达解析器。
        Map<?, ?> scope = (Map<?, ?>)
                response.metadata().get("scope");
        assertEquals(
                List.of("kb-10", "kb-11", "kb-99", "kb-crash"),
                scope.get("collectionKeys"));
        verify(identityResolver, Mockito.never())
                .findActive(isNull(), anyString());
    }

    @Test
    void restrictedCallerSeesOnlyAllowedKeysAndSwallowsResolutionErrors() {
        authenticateWith(restrictedPolicy());
        RagCollection allowed = new RagCollection();
        allowed.setId(10L);
        RagCollection forbidden = new RagCollection();
        forbidden.setId(99L);
        when(identityResolver.findActive(isNull(), eqKey("kb-10")))
                .thenReturn(Optional.of(allowed));
        when(identityResolver.findActive(isNull(), eqKey("kb-11")))
                .thenReturn(Optional.empty());
        when(identityResolver.findActive(isNull(), eqKey("kb-99")))
                .thenReturn(Optional.of(forbidden));
        when(identityResolver.findActive(isNull(), eqKey("kb-crash")))
                .thenThrow(new IllegalStateException("probe denied"));
        when(repository.findByTraceId(traceId))
                .thenReturn(Optional.of(logEntry()));

        RetrievalTraceDetailResponse response = service(identityResolver)
                .get(new com.springairag.core.chat.ChatPrincipal(
                        "db:key-1", "DATABASE_API_KEY", false), traceId);

        Map<?, ?> scope = (Map<?, ?>)
                response.metadata().get("scope");
        Object keys = scope.get("collectionKeys");
        assertTrue(keys instanceof List<?> list
                && list.contains("kb-10")
                && !list.contains("kb-99")
                && !list.contains("kb-crash"));
        assertEquals(List.of("kb-10"), keys);
    }

    @Test
    void missingResolverHidesAllKeysEvenForUnrestrictedCaller() {
        authenticateWith(restrictedPolicy());
        when(repository.findByTraceId(traceId))
                .thenReturn(Optional.of(logEntry()));

        RetrievalTraceDetailResponse response = service(null)
                .get(new com.springairag.core.chat.ChatPrincipal(
                        "db:key-1", "DATABASE_API_KEY", false), traceId);

        Map<?, ?> scope = (Map<?, ?>)
                response.metadata().get("scope");
        assertEquals(List.of(), scope.get("collectionKeys"));
    }

    private static String eqKey(String key) {
        return org.mockito.ArgumentMatchers.eq(key);
    }
}
