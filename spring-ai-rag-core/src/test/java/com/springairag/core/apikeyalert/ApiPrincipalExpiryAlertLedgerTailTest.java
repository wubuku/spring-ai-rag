package com.springairag.core.apikeyalert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.service.AlertService;
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

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到期告警台账长尾（Batch 692，JaCoCo 驱动）：fallback 候选扫描
 * 的行映射、托管写 CAS RETURNING 行映射、metadata 序列化失败的
 * 包装。
 *
 * 勿再投入：reconcile 重试循环后的 lastFailure 出口（122-123 行）
 * 要求 eventRetryAttempts 为 0，但属性校验强制 [1,10]，经公共
 * API 不可达；事务返回 null 的防御（104 行）与循环退出
 * lastFailure 直抛臂（124 行）同样不可达。
 */
class ApiPrincipalExpiryAlertLedgerTailTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-09-05T10:00:00");

    private JdbcTemplate jdbcTemplate;
    private AlertNotificationOutboxService outboxService;
    private com.springairag.core.config.RagProperties ragProperties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ragProperties = new com.springairag.core.config.RagProperties();
        outboxService = mock(AlertNotificationOutboxService.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);
    }

    private ObjectProvider<AlertService> alertServices() {
        ObjectProvider<AlertService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private org.springframework.core.env.Environment environment() {
        org.springframework.core.env.Environment environment =
                mock(org.springframework.core.env.Environment.class);
        when(environment.getProperty(anyString(), anyString()))
                .thenReturn("UTC");
        return environment;
    }

    private ApiPrincipalExpiryAlertService service(ObjectMapper objectMapper) {
        return new ApiPrincipalExpiryAlertService(
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                ragProperties,
                objectMapper,
                (ObjectProvider) alertServices(),
                List.of(),
                outboxService,
                mock(ApiPrincipalExpiryAlertMetrics.class),
                environment());
    }

    /** 即将到期（warning 窗口内）的 principal 行，驱动完整通知链。 */
    private void stubExpiringPrincipalRow() {
        when(jdbcTemplate.query(contains("FROM rag_api_principal"),
                any(RowMapper.class), org.mockito.ArgumentMatchers.eq("p-1")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("principal_id")).thenReturn("p-1");
                    when(rs.getString("role")).thenReturn("ADMIN");
                    when(rs.getObject("expires_at", LocalDateTime.class))
                            .thenReturn(NOW.plusDays(5));
                    when(rs.getLong("policy_version")).thenReturn(3L);
                    when(rs.getObject("revoked_at", LocalDateTime.class))
                            .thenReturn(null);
                    when(rs.getObject("database_now", LocalDateTime.class))
                            .thenReturn(NOW);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("FROM rag_alerts"),
                any(RowMapper.class), anyString())).thenReturn(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void managedWriteCasRowMapperReadsReturnedVersion() {
        stubExpiringPrincipalRow();
        // insert 的 RETURNING 行 → ManagedWrite(0 < 1) → 进入
        // claimNotification 的 RETURNING version CAS 写。
        //
        // 每列都取互不相同的值（Batch 959）：原来 version 与 state_version
        // 都是 1，行映射读错成哪一列断言都不会红 —— 那时这条用例只断非空，
        // 恰恰又是因为读错列它也照样绿。
        when(jdbcTemplate.query(contains("INSERT INTO rag_alerts"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(5L);
                    when(rs.getLong("version")).thenReturn(7L);
                    when(rs.getInt("state_version")).thenReturn(3);
                    when(rs.getInt("notified_version")).thenReturn(0);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("RETURNING version"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Long> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("version")).thenReturn(2L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var result = service(new ObjectMapper())
                .reconcilePrincipalExpiry("p-1");

        // 夹具里 expires_at = database_now + 5 天：不在 30 天 warning 窗口外，
        // 又落在 7 天 criticalWindow 内 —— 断具体 phase，而不是断非空。
        assertEquals(ApiPrincipalExpiryAlertService.Outcome.CREATED,
                result.outcome(), "无活跃告警行时应走 insert 分支");
        assertEquals(ApiPrincipalExpiryAlertService.Phase.CRITICAL,
                result.phase());

        // 这条用例名字承诺的是"托管写 CAS 的行映射读回了 version"，
        // 而夹具把 INSERT ... RETURNING 行的 version 设成 7、state_version 设成 3。
        // 断言就落在这儿：CAS 的 WHERE version = ? 谓词必须拿到 version 列
        // 读出的那个 7，而不是 state_version 的 3、也不是别的列。行映射读错列
        // / 读成默认值，这里立刻红。
        ArgumentCaptor<Object[]> casArgs =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
                contains("RETURNING version"),
                any(RowMapper.class),
                casArgs.capture());
        assertArrayEquals(new Object[]{5L, 7L}, casArgs.getValue(),
                "CAS 谓词必须用 INSERT RETURNING 行映射读出的 (id, version)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findFallbackCandidatesMapsPrincipalIds() {
        when(jdbcTemplate.query(contains("candidate"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<String> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("principal_id")).thenReturn("p-9");
                    return List.of(mapper.mapRow(rs, 0));
                });

        var batch = service(new ObjectMapper()).findFallbackCandidates();

        assertEquals(List.of("p-9"), batch.principalIds());
    }

    @Test
    void metadataSerializationFailureWrapsAsIllegalState() throws Exception {
        stubExpiringPrincipalRow();
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {
                });

        var error = assertThrows(IllegalStateException.class,
                () -> service(failing).reconcilePrincipalExpiry("p-1"));

        assertTrue(error.getMessage()
                .contains("Unable to serialize expiry alert metadata"));
    }
}
