package com.springairag.core.tenant;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Phase 0：多租户 P0-1 / P0-3 / P0-4 单元测试。
 */
class TenantP0FixesTest {

    @Test
    void p01_rlsVerifierRejectsPermissiveAndOrTrueAndMissingGucExpressions() {
        assertTrue(TenantRlsVerifier.isPermissivePolicy(null));
        assertTrue(TenantRlsVerifier.isPermissivePolicy("   "));
        assertTrue(TenantRlsVerifier.isPermissivePolicy("true"));
        assertTrue(TenantRlsVerifier.isPermissivePolicy(
                "(tenant_id = current_setting('app.current_tenant_id', true) OR true)"));
        assertFalse(TenantRlsVerifier.isValidTenantPolicyExpression(
                "(tenant_id = current_setting('app.current_tenant_id', true) OR true)"));
        assertFalse(TenantRlsVerifier.isValidTenantPolicyExpression("tenant_id = 'default'"));
        assertTrue(TenantRlsVerifier.isValidTenantPolicyExpression(
                "(tenant_id = current_setting('app.current_tenant_id'::text, true))"));
    }

    @Test
    void p03_tenantContextFilterReturnsExplicit403ForSuspendedTenantInStrictMode() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(
                new PrincipalTenantResolver(), TenantMode.STRICT);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rag/collections");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        AuthenticatedApiPrincipal suspended = new AuthenticatedApiPrincipal(
                "p-1", "k-1", 1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL, null, null, 1L, null,
                List.of("RAG_READ"), "harbor-edu", "SUSPENDED");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE, suspended);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("TENANT_SUSPENDED"));
        assertNull(chain.getRequest(), "非 ACTIVE 租户必须短路，不能继续进入下游业务链");
    }

    @Test
    void p04_defaultTenantCannotBeDisabledOrSoftDeleted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                TransactionStatus status = new SimpleTransactionStatus();
                return action.doInTransaction(status);
            }
        };
        TenantLifecycleService service = new TenantLifecycleService(
                jdbc, tx, TenantMode.STRICT, new TenantLifecycleAudit.Logging());

        IllegalStateException disableEx = assertThrows(IllegalStateException.class,
                () -> service.disable("default", "root"));
        assertTrue(disableEx.getMessage().contains("DEFAULT_TENANT_PROTECTED"));

        IllegalStateException deleteEx = assertThrows(IllegalStateException.class,
                () -> service.softDelete("default", "root"));
        assertTrue(deleteEx.getMessage().contains("DEFAULT_TENANT_PROTECTED"));
    }

    @Test
    void p04_dataSourcePostProcessorExcludesPlatformAndFlywayDataSources() {
        TenantDataSourceBeanPostProcessor processor = new TenantDataSourceBeanPostProcessor();
        DataSource appDs = mock(DataSource.class);
        DataSource platformDs = mock(DataSource.class);
        DataSource flywayDs = mock(DataSource.class);

        assertInstanceOf(TenantGucDataSource.class,
                processor.postProcessAfterInitialization(appDs, "dataSource"));
        assertSame(platformDs,
                processor.postProcessAfterInitialization(platformDs, "platformDataSource"));
        assertSame(flywayDs,
                processor.postProcessAfterInitialization(flywayDs, "flywayDataSource"));
    }
}
