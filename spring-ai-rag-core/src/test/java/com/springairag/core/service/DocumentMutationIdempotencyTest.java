package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Idempotency-Key 预留语义：键修剪与上限、首次插入、过期回收重试、
 * 指纹冲突拒绝、成功重放与进行中冲突。
 */
class DocumentMutationIdempotencyTest {

    /** 按语句类型分类的 JdbcTemplate 桩（Batch 155 模式）。 */
    private static class StubJdbc extends JdbcTemplate {
        String lastSql;
        Object[] lastArgs;
        int insertResult;
        final List<String> executedSql = new ArrayList<>();
        List<Map<String, Object>> queryRows = List.of();

        @Override
        public int update(String sql, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            this.executedSql.add(sql);
            if (sql.contains("INSERT INTO rag_document_idempotency_operations")) {
                return insertResult;
            }
            return 1;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            return queryRows;
        }
    }

    private StubJdbc jdbc;
    private DocumentMutationService service;
    @BeforeEach
    void setUp() {
        jdbc = new StubJdbc();
        service = newService(jdbc);
        authenticateAsDatabaseKey();
    }

    private static DocumentMutationService newService(JdbcTemplate jdbcTemplate) {
        RagDocumentRepository documentRepository =
                mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        DocumentVersionService versionService = mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        PlatformTransactionManager transactionManager =
                mock(JpaTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        return new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                resolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    private static void authenticateAsDatabaseKey() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/documents");
        request.setAttribute("authenticatedPrincipalType", "DATABASE_API_KEY");
        request.setAttribute("authenticatedApiKey", "key-42");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private Object reserve(String rawKey) {
        // IdempotencyReservation 是私有嵌套 record，经反射调用私有方法。
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "reserveIdempotency", rawKey, "CREATE_LOCAL",
                "fingerprint-sha");
    }

    @Test
    void blankIdempotencyKeyReservesNothing() {
        Object reservation = reserve("   ");

        // 私有 record 断言 owner 为 null（none 语义）。
        assertEquals(null, org.springframework.test.util.ReflectionTestUtils
                .getField(reservation, "owner"));
        assertEquals(0, jdbc.executedSql.size());
    }

    @Test
    void rejectsIdempotencyKeysOver255Characters() {
        String longKey = "k".repeat(256);

        assertThrows(IllegalArgumentException.class, () -> reserve(longKey));
    }

    @Test
    void firstInsertReservesWithHashedKeyAndNoReplay() {
        jdbc.insertResult = 1;

        Object reservation = reserve("  idem-key  ");

        // 键被修剪后哈希为 64 位十六进制；owner 绑定当前 db principal。
        assertEquals("db:key-42", org.springframework.test.util.ReflectionTestUtils
                .getField(reservation, "owner"));
        assertEquals("CREATE_LOCAL", org.springframework.test.util.ReflectionTestUtils
                .getField(reservation, "operationType"));
        String keyHash = (String) org.springframework.test.util.ReflectionTestUtils
                .getField(reservation, "keyHash");
        assertEquals(64, keyHash.length());
        assertEquals(null, org.springframework.test.util.ReflectionTestUtils
                .getField(reservation, "replayDocumentId"));
        assertEquals("db:key-42", jdbc.lastArgs[0]);
    }

    @Test
    void deletesAnExpiredRowThenFailsClosedWhenTheRetryFindsItGone() {
        StubJdbc stateful = new StubJdbc() {
            @Override
            public int update(String sql, Object... args) {
                // DELETE 后清空查询行，模拟过期行被物理移除。
                if (sql.contains("DELETE FROM")) {
                    queryRows = List.of();
                }
                // DELETE 后清空查询行；INSERT 保持 0（行已被移除）。
                if (sql.contains("DELETE FROM")) {
                    queryRows = List.of();
                    return 1;
                }
                return 0;
            }
        };
        Map<String, Object> expiredRow = new java.util.HashMap<>();
        expiredRow.put("request_fingerprint", "fingerprint-sha");
        expiredRow.put("status", "IN_PROGRESS");
        expiredRow.put("result_document_id", null);
        expiredRow.put("expired", true);
        stateful.insertResult = 0;
        stateful.queryRows = List.of(expiredRow);

        DocumentMutationService serviceWithStateful = newService(stateful);

        // 删除过期行后重查为空 → fail-closed（不无限重试）。
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> org.springframework.test.util.ReflectionTestUtils
                        .invokeMethod(serviceWithStateful, "reserveIdempotency",
                                "idem-key", "CREATE_LOCAL", "fingerprint-sha"));
        assertTrue(error.getMessage().contains("disappeared"));
    }

    @Test
    void fingerprintMismatchIsRejectedAsConflict() {
        jdbc.insertResult = 0;
        jdbc.queryRows = List.of(Map.of(
                "request_fingerprint", "different-fingerprint",
                "status", "SUCCEEDED",
                "result_document_id", 99L,
                "expired", false));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class, () -> reserve("idem-key"));
        assertTrue(error.getMessage().contains("another request"));
    }

    @Test
    void succeededOperationReplaysTheStoredDocumentId() {
        jdbc.insertResult = 0;
        jdbc.queryRows = List.of(Map.of(
                "request_fingerprint", "fingerprint-sha",
                "status", "SUCCEEDED",
                "result_document_id", 99L,
                "expired", false));

        Object reservation = reserve("idem-key");

        // SUCCEEDED 状态携带可重放的 result_document_id。
        assertEquals(99L, org.springframework.test.util.ReflectionTestUtils
                .getField(reservation, "replayDocumentId"));
    }

    @Test
    void inProgressOperationIsRejectedAsStillRunning() {
        Map<String, Object> inProgressRow = new java.util.HashMap<>();
        inProgressRow.put("request_fingerprint", "fingerprint-sha");
        inProgressRow.put("status", "IN_PROGRESS");
        inProgressRow.put("result_document_id", null);
        inProgressRow.put("expired", false);
        jdbc.insertResult = 0;
        jdbc.queryRows = List.of(inProgressRow);

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class, () -> reserve("idem-key"));
        assertTrue(error.getMessage().contains("still in progress"));
    }
}
