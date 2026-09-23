package com.springairag.core.apikeyalert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.NotificationService;
import com.springairag.core.apikeyalert.ApiPrincipalExpiryAlertService.ReconcileResult;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到期告警对账长尾（Batch 598，JaCoCo 驱动）：便捷构造器对空通知
 * 通道的容忍、并发冲突重试后成功、非重试异常立即抛出、瞬态异常
 * 耗尽重试预算、静默期跳过通知、持久 outbox 认领后跳过直发、
 * 直发通道对成功/失败 future/抛异常三种实现的容错。
 */
class ApiPrincipalExpiryAlertReconcileTailTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-09-05T10:00:00");

    private JdbcTemplate jdbcTemplate;
    private AlertNotificationOutboxService outboxService;
    private RagProperties ragProperties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ragProperties = new RagProperties();
        outboxService = mock(AlertNotificationOutboxService.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);
        when(jdbcTemplate.query(contains("SET notified_version = state_version"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(2L));
    }

    private ObjectProvider<AlertService> alertServices(boolean silenced) {
        ObjectProvider<AlertService> provider = mock(ObjectProvider.class);
        if (silenced) {
            AlertService alertService = mock(AlertService.class);
            when(alertService.isSilenced(anyString(), anyString()))
                    .thenReturn(true);
            when(provider.getIfAvailable()).thenReturn(alertService);
        } else {
            when(provider.getIfAvailable()).thenReturn(null);
        }
        return provider;
    }

    private org.springframework.core.env.Environment environment() {
        org.springframework.core.env.Environment environment =
                mock(org.springframework.core.env.Environment.class);
        when(environment.getProperty(anyString(), anyString()))
                .thenReturn("UTC");
        return environment;
    }

    private ApiPrincipalExpiryAlertService service(
            List<NotificationService> notificationServices) {
        return new ApiPrincipalExpiryAlertService(
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                ragProperties,
                new ObjectMapper(),
                (ObjectProvider) alertServices(false),
                notificationServices,
                outboxService,
                mock(ApiPrincipalExpiryAlertMetrics.class),
                environment());
    }

    private void stubPrincipalRow() {
        when(jdbcTemplate.query(contains("FROM rag_api_principal"),
                any(RowMapper.class), eq("p-1"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("principal_id")).thenReturn("p-1");
                    when(rs.getString("role")).thenReturn("ADMIN");
                    when(rs.getObject("expires_at", LocalDateTime.class))
                            .thenReturn(NOW.plusDays(60));
                    when(rs.getLong("policy_version")).thenReturn(3L);
                    when(rs.getObject("revoked_at", LocalDateTime.class))
                            .thenReturn(null);
                    when(rs.getObject("database_now", LocalDateTime.class))
                            .thenReturn(NOW);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubNoActiveAlert() {
        when(jdbcTemplate.query(contains("FROM rag_alerts"),
                any(RowMapper.class), anyString())).thenReturn(List.of());
    }

    private NotificationService throwingChannel() {
        NotificationService channel = mock(NotificationService.class);
        when(channel.sendAlert(anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenThrow(new IllegalStateException("channel down"));
        return channel;
    }

    @Test
    void convenienceConstructorToleratesNullNotificationChannels() {
        ApiPrincipalExpiryAlertService convenience =
                new ApiPrincipalExpiryAlertService(
                        jdbcTemplate,
                        mock(PlatformTransactionManager.class),
                        ragProperties,
                        new ObjectMapper(),
                        (ObjectProvider) alertServices(false),
                        null,
                        mock(ApiPrincipalExpiryAlertMetrics.class),
                        environment());
        stubPrincipalRow();
        stubNoActiveAlert();

        ReconcileResult result = convenience.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.NOOP,
                result.outcome());
    }

    @Test
    void reconcileRetriesConcurrentConflictThenSucceeds() {
        ragProperties.getApiKeyExpiryAlerts().setEventRetryAttempts(3);
        stubPrincipalRow();
        stubNoActiveAlert();
        // 首次标记已检未命中（并发冲突，可重试），第二次成功。
        when(jdbcTemplate.update(contains("expiry_alert_checked_at"),
                eq("p-1"))).thenReturn(0).thenReturn(1);

        ReconcileResult result = service(List.of())
                .reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.NOOP,
                result.outcome());
        verify(jdbcTemplate, times(2)).update(
                contains("expiry_alert_checked_at"), eq("p-1"));
    }

    @Test
    void reconcileRethrowsNonRetryableFailureImmediately() {
        stubPrincipalRow();
        stubNoActiveAlert();
        // 非法状态异常不可重试 → 首次失败即抛出，不再重试。
        when(jdbcTemplate.update(contains("expiry_alert_checked_at"),
                eq("p-1")))
                .thenThrow(new IllegalStateException("bad state"));

        assertThrows(IllegalStateException.class,
                () -> service(List.of()).reconcilePrincipalExpiry("p-1"));
        verify(jdbcTemplate, times(1)).update(
                contains("expiry_alert_checked_at"), eq("p-1"));
    }

    @Test
    void reconcileExhaustsRetriesOnPersistentIntegrityFailure() {
        ragProperties.getApiKeyExpiryAlerts().setEventRetryAttempts(2);
        stubPrincipalRow();
        stubNoActiveAlert();
        when(jdbcTemplate.update(contains("expiry_alert_checked_at"),
                eq("p-1")))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service(List.of()).reconcilePrincipalExpiry("p-1"));
        verify(jdbcTemplate, times(2)).update(
                contains("expiry_alert_checked_at"), eq("p-1"));
    }

    @Test
    void reconcileExhaustsRetriesOnPersistentTransientFailure() {
        ragProperties.getApiKeyExpiryAlerts().setEventRetryAttempts(2);
        stubPrincipalRow();
        stubNoActiveAlert();
        when(jdbcTemplate.update(contains("expiry_alert_checked_at"),
                eq("p-1")))
                .thenThrow(new QueryTimeoutException("slow"));

        assertThrows(QueryTimeoutException.class,
                () -> service(List.of()).reconcilePrincipalExpiry("p-1"));
        verify(jdbcTemplate, times(2)).update(
                contains("expiry_alert_checked_at"), eq("p-1"));
    }

    @Test
    void durableOutboxEnqueuesAndSkipsDirectNotification() {
        when(outboxService.isDurableEnabled()).thenReturn(true);
        NotificationService channel = mock(NotificationService.class);
        stubPrincipalRow(NOW.plusDays(3));
        stubActiveAlert("PENDING", 1, 0);

        ReconcileResult result = service(List.of(channel))
                .reconcilePrincipalExpiry("p-1");

        // 持久 outbox 已认领 → 通知改为异步派发，直发跳过。
        verify(outboxService).enqueueManaged(
                eq(11L), org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString(), anyString(),
                anyString(), any());
        verify(channel, times(0)).sendAlert(anyString(), anyString(),
                anyString(), anyString(), any());
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    @Test
    void directDispatchToleratesThrowingNullAndCompletedChannels() {
        NotificationService throwing = throwingChannel();
        NotificationService nullFuture = mock(NotificationService.class);
        when(nullFuture.sendAlert(anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(null);
        NotificationService completed = mock(NotificationService.class);
        when(completed.sendAlert(anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        stubPrincipalRow(NOW.plusDays(3));
        stubActiveAlert("PENDING", 1, 0);

        ReconcileResult result = service(List.of(throwing, nullFuture, completed))
                .reconcilePrincipalExpiry("p-1");

        // 任一通道异常/失败不阻断对账结果。
        verify(throwing, times(1)).sendAlert(anyString(), anyString(),
                anyString(), anyString(), any());
        verify(completed, times(1)).sendAlert(anyString(), anyString(),
                anyString(), anyString(), any());
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    @Test
    void silencedAlertsSkipNotificationDispatch() {
        AlertService alertService = mock(AlertService.class);
        when(alertService.isSilenced(anyString(), anyString()))
                .thenReturn(true);
        ObjectProvider<AlertService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(alertService);

        NotificationService channel = mock(NotificationService.class);
        ragProperties.getApiKeyExpiryAlerts().setEventRetryAttempts(2);
        stubPrincipalRow(NOW.plusDays(3));
        stubActiveAlert("PENDING", 1, 0);

        ReconcileResult result = new ApiPrincipalExpiryAlertService(
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                ragProperties,
                new ObjectMapper(),
                provider,
                List.of(channel),
                outboxService,
                mock(ApiPrincipalExpiryAlertMetrics.class),
                environment()).reconcilePrincipalExpiry("p-1");

        // 静默期：claim 返回 null → 不派发任何通知。
        verify(channel, times(0)).sendAlert(anyString(), anyString(),
                anyString(), anyString(), any());
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    private void stubPrincipalRow(LocalDateTime expiresAt) {
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
                            .thenReturn(null);
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

}
