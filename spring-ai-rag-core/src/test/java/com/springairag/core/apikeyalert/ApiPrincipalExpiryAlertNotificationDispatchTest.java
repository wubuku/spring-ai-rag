package com.springairag.core.apikeyalert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.apikeyalert.ApiPrincipalExpiryAlertService.ReconcileResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.NotificationService;
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
 * 同相位刷新与通知派发（Batch 325）：活动告警停留在当前阶段时
 * 经 CAS 原位刷新（REFRESHED），CAS 未命中经退避重试后上抛并
 * 记账；通知按通道派发且单通道失败不影响其余通道。
 */
class ApiPrincipalExpiryAlertNotificationDispatchTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-09-05T10:00:00");

    private JdbcTemplate jdbcTemplate;
    private AlertNotificationOutboxService outboxService;
    private ApiPrincipalExpiryAlertMetrics metrics;
    private NotificationService okChannel;
    private NotificationService nullChannel;
    private NotificationService brokenChannel;
    private PlatformTransactionManager transactionManager;
    private org.springframework.core.env.Environment environment;
    @SuppressWarnings("rawtypes")
    private ObjectProvider alertServices;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        outboxService = mock(AlertNotificationOutboxService.class);
        metrics = mock(ApiPrincipalExpiryAlertMetrics.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        environment = mock(org.springframework.core.env.Environment.class);
        when(environment.getProperty(anyString(), anyString()))
                .thenReturn("UTC");
        alertServices = mock(ObjectProvider.class);
        when(alertServices.getIfAvailable()).thenReturn(null);

        okChannel = mock(NotificationService.class);
        when(okChannel.sendAlert(anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        nullChannel = mock(NotificationService.class);
        when(nullChannel.sendAlert(anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(null);
        brokenChannel = mock(NotificationService.class);
        when(brokenChannel.sendAlert(anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenThrow(new IllegalStateException("channel down"));

        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);
        when(jdbcTemplate.query(
                contains("SET notified_version = state_version"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(5L));
    }

    private ApiPrincipalExpiryAlertService service(
            List<NotificationService> channels) {
        return new ApiPrincipalExpiryAlertService(
                jdbcTemplate,
                transactionManager,
                new RagProperties(),
                new ObjectMapper(),
                (ObjectProvider) alertServices,
                channels,
                outboxService,
                metrics,
                environment);
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
                    when(rs.getString("condition_state"))
                            .thenReturn(conditionState);
                    when(rs.getInt("state_version")).thenReturn(stateVersion);
                    when(rs.getInt("notified_version"))
                            .thenReturn(notifiedVersion);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void samePhaseActiveAlertIsRefreshedInPlace() {
        stubPrincipalRow(NOW.plusDays(3));
        // 活动告警已处于 CRITICAL 且通知版本与状态版本一致 → 原位刷新。
        stubActiveAlert("CRITICAL", 3, 3);

        ReconcileResult result = service(List.of())
                .reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.REFRESHED,
                result.outcome());
        assertEquals(ApiPrincipalExpiryAlertService.Phase.CRITICAL,
                result.phase());
        verify(jdbcTemplate).update(
                contains("SET message = ?"), any(Object[].class));
        verify(metrics).recordReconcile("REFRESHED", "CRITICAL");
        // 通知版本未推进 → 不重复派发，也不入 outbox。
        verify(outboxService, times(0)).enqueueManaged(
                any(long.class), any(int.class), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void samePhaseCasMissRetriesThenThrows() {
        stubPrincipalRow(NOW.plusDays(3));
        stubActiveAlert("CRITICAL", 3, 3);
        when(jdbcTemplate.update(
                contains("SET message = ?"), any(Object[].class)))
                .thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service(List.of()).reconcilePrincipalExpiry("p-1"));

        // 重试预算耗尽后按失败记账（外层兜底相位为 NONE）。
        verify(metrics).recordReconcile(eq("FAILURE"), anyString());
    }

    @Test
    void pendingNotificationIsDispatchedAcrossChannelsToleratingFailures() {
        stubPrincipalRow(NOW.plusDays(3));
        // 通知版本落后于状态版本 → 认领并派发。
        stubActiveAlert("CRITICAL", 3, 2);

        ReconcileResult result = service(List.of(
                        okChannel, nullChannel, brokenChannel))
                .reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.REFRESHED,
                result.outcome());
        // 三个通道均被调用；坏通道抛错不影响其余通道与主流程。
        verify(okChannel).sendAlert(
                eq(ApiPrincipalExpiryAlertService.ALERT_TYPE),
                eq(ApiPrincipalExpiryAlertService.ALERT_NAME),
                anyString(), anyString(), any());
        verify(nullChannel).sendAlert(
                anyString(), anyString(), anyString(), anyString(), any());
        verify(brokenChannel).sendAlert(
                anyString(), anyString(), anyString(), anyString(), any());
    }
}
