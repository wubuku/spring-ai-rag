package com.springairag.core.apikeyalert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.service.NotificationService;
import com.springairag.core.apikeyalert.ApiPrincipalExpiryAlertService.ReconcileResult;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiPrincipalExpiryAlertService CAS 竞争与通知长尾（Batch 494，
 * JaCoCo 驱动）：告警插入 CAS 未命中、同阶段更新 CAS 未命中、通知
 * 认领 CAS 未命中的三类并发异常与重试预算耗尽；durable outbox 认
 * 领路径（enqueueManaged + 不直接派发）；失败 future 的告警日志通
 * 道；重试退避被中断的降级；已吊销主体的 NONE 阶段。
 */
class ApiPrincipalExpiryAlertCasTailTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-09-05T10:00:00");

    private JdbcTemplate jdbcTemplate;
    private AlertNotificationOutboxService outboxService;
    private ApiPrincipalExpiryAlertMetrics metrics;
    private NotificationService channel;
    private RagProperties ragProperties;
    private ApiPrincipalExpiryAlertService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ragProperties = new RagProperties();
        ragProperties.getApiKeyExpiryAlerts().setEventRetryAttempts(2);
        outboxService = mock(AlertNotificationOutboxService.class);
        metrics = mock(ApiPrincipalExpiryAlertMetrics.class);
        channel = mock(NotificationService.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        ObjectProvider<Object> alertServices = mock(ObjectProvider.class);
        when(alertServices.getIfAvailable()).thenReturn(null);
        org.springframework.core.env.Environment environment =
                mock(org.springframework.core.env.Environment.class);
        when(environment.getProperty(anyString(), anyString()))
                .thenReturn("UTC");

        service = new ApiPrincipalExpiryAlertService(
                jdbcTemplate,
                transactionManager,
                ragProperties,
                new ObjectMapper(),
                (ObjectProvider) alertServices,
                List.of(channel),
                outboxService,
                metrics,
                environment);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);
        // 通知认领 UPDATE 默认命中（notified_version 递增）。
        when(jdbcTemplate.query(contains("SET notified_version = state_version"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(2L));
    }

    @AfterEach
    void tearDown() {
        // 清理可能残留的中断标记，避免污染后续测试。
        Thread.interrupted();
    }

    private void stubPrincipalRow(LocalDateTime expiresAt,
                                  LocalDateTime revokedAt) {
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

    private void stubInsertReturningRows(int rows) {
        when(jdbcTemplate.query(contains("INSERT INTO rag_alerts"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(rows == 1
                        ? List.of(new Object())
                        : List.of());
    }

    @Test
    void insertCasMissRetriesThenThrows() {
        stubPrincipalRow(NOW.plusDays(3), null);
        stubNoActiveAlert();
        stubInsertReturningRows(0);

        assertThrows(RuntimeException.class,
                () -> service.reconcilePrincipalExpiry("p-1"));
        verify(metrics).recordReconcile("FAILURE", "NONE");
    }

    @Test
    void samePhaseUpdateCasMissRetriesThenThrows() {
        stubPrincipalRow(NOW.plusDays(3), null);
        stubActiveAlert("WARNING", 2, 2);
        when(jdbcTemplate.update(contains("condition_state = ?"),
                any(Object[].class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service.reconcilePrincipalExpiry("p-1"));
        verify(metrics).recordReconcile("FAILURE", "NONE");
    }

    @Test
    void claimCasMissRetriesThenThrows() {
        stubPrincipalRow(NOW.plusDays(3), null);
        // WARNING 通知未发出（notified 0 < state 2）→ 进入认领。
        stubActiveAlert("WARNING", 2, 0);
        when(jdbcTemplate.update(contains("condition_state = ?"),
                any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.query(
                contains("SET notified_version = state_version"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThrows(RuntimeException.class,
                () -> service.reconcilePrincipalExpiry("p-1"));
        verify(metrics).recordReconcile("FAILURE", "NONE");
    }

    @Test
    void durableOutboxEnqueuesAndSkipsDirectDispatch() {
        when(outboxService.isDurableEnabled()).thenReturn(true);
        stubPrincipalRow(NOW.plusDays(3), null);
        stubActiveAlert("WARNING", 2, 0);
        when(jdbcTemplate.update(contains("condition_state = ?"),
                any(Object[].class))).thenReturn(1);

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.TRANSITIONED,
                result.outcome());
        verify(outboxService).enqueueManaged(
                eq(11L), anyInt(), anyString(), anyString(),
                anyString(), anyString(), any());
        verify(channel, never()).sendAlert(anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void failedFutureChannelIsLoggedNotThrown() {
        stubPrincipalRow(NOW.plusDays(3), null);
        stubActiveAlert("WARNING", 2, 0);
        when(jdbcTemplate.update(contains("condition_state = ?"),
                any(Object[].class))).thenReturn(1);
        when(channel.sendAlert(anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("async blew up")));

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Outcome.TRANSITIONED,
                result.outcome());
        verify(channel).sendAlert(anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void interruptedBackoffDegradesToIllegalState() {
        stubPrincipalRow(NOW.plusDays(3), null);
        stubNoActiveAlert();
        stubInsertReturningRows(0);
        // 预置中断标记：退避 sleep 立即抛出 InterruptedException。
        Thread.currentThread().interrupt();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reconcilePrincipalExpiry("p-1"));
        assertTrue(error.getMessage().contains("interrupted"),
                "应报中断: " + error.getMessage());
    }

    @Test
    void revokedPrincipalMapsToNonePhase() {
        stubPrincipalRow(NOW.plusDays(60), NOW.minusDays(1));
        stubNoActiveAlert();

        ReconcileResult result = service.reconcilePrincipalExpiry("p-1");

        assertEquals(ApiPrincipalExpiryAlertService.Phase.NONE,
                result.phase());
        verify(metrics).recordReconcile("NOOP", "NONE");
    }
}
