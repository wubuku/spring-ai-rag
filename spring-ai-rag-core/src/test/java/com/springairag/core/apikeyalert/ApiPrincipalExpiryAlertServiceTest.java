package com.springairag.core.apikeyalert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.apikeyalert.ApiPrincipalExpiryAlertService.ReconcileResult;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖托管 API principal 到期告警的对账状态机：MISSING/NOOP/RESOLVED/
 * DISABLED/CREATED/TRANSITIONED 分支、阶段判定与候选扫描截断。
 * 快照行经由真实 RowMapper + mock ResultSet 构造，映射逻辑一并覆盖。
 */
class ApiPrincipalExpiryAlertServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-09-05T10:00:00");

    private JdbcTemplate jdbcTemplate;
    private AlertNotificationOutboxService outboxService;
    private ApiPrincipalExpiryAlertMetrics metrics;
    private RagProperties ragProperties;
    private ApiPrincipalExpiryAlertService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ragProperties = new RagProperties();
        outboxService = mock(AlertNotificationOutboxService.class);
        metrics = mock(ApiPrincipalExpiryAlertMetrics.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        ObjectProvider<Object> alertServices = mock(ObjectProvider.class);
        when(alertServices.getIfAvailable()).thenReturn(null);
        org.springframework.core.env.Environment environment =
                mock(org.springframework.core.env.Environment.class);
        when(environment.getProperty(anyString(), anyString())).thenReturn("UTC");

        service = new ApiPrincipalExpiryAlertService(
                jdbcTemplate,
                transactionManager,
                ragProperties,
                new ObjectMapper(),
                (ObjectProvider) alertServices,
                List.of(),
                outboxService,
                metrics,
                environment);
        // varargs 两类匹配：数组整体（多参数）与单个 String 参数。
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);
        // 通知认领 UPDATE 默认命中，返回递增后的版本。
        when(jdbcTemplate.query(contains("SET notified_version = state_version"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(2L));
    }

    private void stubPrincipalRow(LocalDateTime expiresAt, LocalDateTime revokedAt) {
        when(jdbcTemplate.query(contains("FROM rag_api_principal"),
                any(RowMapper.class), eq("p-1"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("principal_id")).thenReturn("p-1");
                    when(rs.getString("role")).thenReturn("ADMIN");
                    when(rs.getObject("expires_at", LocalDateTime.class))
                            .thenReturn(expiresAt);
                    when(rs.getLong("policy_version")).thenReturn(3L);
                    when(rs.getObject("revoked_at", LocalDateTime.class))
                            .thenReturn(revokedAt);
                    when(rs.getObject("database_now", LocalDateTime.class))
                            .thenReturn(NOW);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubActiveAlert(
            String conditionState, int stateVersion, int notifiedVersion) {
        when(jdbcTemplate.query(contains("FROM rag_alerts"),
                any(RowMapper.class), anyString())).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(11L);
                    when(rs.getLong("version")).thenReturn(4L);
                    when(rs.getString("condition_state")).thenReturn(conditionState);
                    when(rs.getInt("state_version")).thenReturn(stateVersion);
                    when(rs.getInt("notified_version")).thenReturn(notifiedVersion);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubNoActiveAlert() {
        when(jdbcTemplate.query(contains("FROM rag_alerts"),
                any(RowMapper.class), anyString())).thenReturn(List.of());
    }

    private void stubInsertReturningManagedWrite() {
        when(jdbcTemplate.query(contains("INSERT INTO rag_alerts"),
                any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(11L);
                    when(rs.getLong("version")).thenReturn(1L);
                    when(rs.getInt("state_version")).thenReturn(1);
                    when(rs.getInt("notified_version")).thenReturn(0);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void returnsMissingWhenPrincipalIsUnknown() {
        when(jdbcTemplate.query(contains("FROM rag_api_principal"),
                any(RowMapper.class), eq("ghost"))).thenReturn(List.of());

        ReconcileResult result = service.reconcilePrincipalExpiry("ghost");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.MISSING, result.outcome());
        assertEquals(ApiPrincipalExpiryAlertService.Phase.NONE, result.phase());
        verify(jdbcTemplate, never()).update(anyString(), anyString());
    }

    @Test
    void returnsNoopWhenPhaseIsNoneWithoutActiveAlert() {
        stubPrincipalRow(NOW.plusDays(60), null);
        stubNoActiveAlert();

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.NOOP, result.outcome());
        assertEquals(ApiPrincipalExpiryAlertService.Phase.NONE, result.phase());
        verify(jdbcTemplate).update(contains("expiry_alert_checked_at"), eq("p-1"));
        verify(metrics).recordReconcile("NOOP", "NONE");
    }

    @Test
    void resolvesActiveAlertWhenConditionClears() {
        stubPrincipalRow(NOW.plusDays(60), null);
        stubActiveAlert("WARNING", 1, 0);
        when(jdbcTemplate.update(contains("status = 'RESOLVED'"),
                any(Object[].class))).thenReturn(1);

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.RESOLVED, result.outcome());
        verify(outboxService).supersedeManaged(11L);
        verify(metrics).recordReconcile("RESOLVED", "NONE");
    }

    @Test
    void skipsResolutionWhenConcurrentResolveAlreadyApplied() {
        stubPrincipalRow(NOW.plusDays(60), null);
        stubActiveAlert("WARNING", 1, 0);
        // CAS 未命中（并发已解决）→ 重试预算耗尽后抛并发异常。
        when(jdbcTemplate.update(contains("status = 'RESOLVED'"),
                any(Object[].class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service.reconcilePrincipalExpiry("p-1"));
        verify(metrics).recordReconcile("FAILURE", "NONE");
    }

    @Test
    void returnsDisabledWhenPhaseIsActiveButAlertingDisabled() {
        ragProperties.getApiKeyExpiryAlerts().setEnabled(false);
        stubPrincipalRow(NOW.plusDays(3), null);
        stubNoActiveAlert();

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.DISABLED, result.outcome());
        assertEquals(ApiPrincipalExpiryAlertService.Phase.CRITICAL, result.phase());
        verify(outboxService, never()).enqueueManaged(any(long.class),
                any(int.class), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void createsAlertWhenCriticalPhaseHasNoActiveAlert() {
        stubPrincipalRow(NOW.plusDays(3), null);
        stubNoActiveAlert();
        stubInsertReturningManagedWrite();

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.CREATED, result.outcome());
        assertEquals(ApiPrincipalExpiryAlertService.Phase.CRITICAL, result.phase());
        verify(metrics).recordReconcile("CREATED", "CRITICAL");
    }

    @Test
    void transitionsActiveAlertIntoNewPhase() {
        stubPrincipalRow(NOW.plusDays(3), null);
        // 上一阶段 WARNING，现进入 CRITICAL，通知已随旧阶段发出。
        stubActiveAlert("WARNING", 2, 2);
        when(jdbcTemplate.update(contains("condition_state = ?"),
                any(Object[].class))).thenReturn(1);

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.TRANSITIONED, result.outcome());
        assertEquals(ApiPrincipalExpiryAlertService.Phase.CRITICAL, result.phase());
    }

    @Test
    void findFallbackCandidatesTruncatesToLimitPlusOne() {
        ragProperties.getApiKeyExpiryAlerts().setFallbackScanLimit(2);
        when(jdbcTemplate.query(contains("FROM candidate"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("p-1", "p-2", "p-3"));

        var batch = service.findFallbackCandidates();

        assertEquals(List.of("p-1", "p-2"), batch.principalIds());
        assertTrue(batch.truncated());
        assertFalse(batch.principalIds().contains("p-3"));
    }

    @Test
    void findFallbackCandidatesReturnsEverythingUnderLimit() {
        when(jdbcTemplate.query(contains("FROM candidate"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("p-1"));

        var batch = service.findFallbackCandidates();

        assertEquals(List.of("p-1"), batch.principalIds());
        assertFalse(batch.truncated());
    }

    @Test
    void expiresActivePrincipalImmediatelyWhenAlreadyExpired() {
        stubPrincipalRow(NOW.minusDays(1), null);
        stubNoActiveAlert();
        stubInsertReturningManagedWrite();

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.CREATED, result.outcome());
        assertEquals(ApiPrincipalExpiryAlertService.Phase.EXPIRED, result.phase());
    }

    @Test
    void reportsWarningPhaseInsideWarningWindowOnly() {
        stubPrincipalRow(NOW.plusDays(20), null);
        stubNoActiveAlert();
        stubInsertReturningManagedWrite();

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        // 20 天后过期：在 30 天警告窗内、7 天临界窗外 → WARNING。
        assertEquals(ApiPrincipalExpiryAlertService.Phase.WARNING, result.phase());
    }

    @Test
    void mapsLegacyErrorCodeForRejection() {
        // 常量可见性冒烟：确保 conflict 路径使用专用错误码。
        assertEquals("ALERT_NOTIFICATION_DELIVERY_CONFLICT",
                ErrorCode.ALERT_NOTIFICATION_DELIVERY_CONFLICT.getCode());
    }
}
