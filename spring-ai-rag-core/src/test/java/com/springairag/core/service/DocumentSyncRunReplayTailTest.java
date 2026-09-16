package com.springairag.core.service;

import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.dto.DocumentSyncRunItemResponse;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentSyncRunService.replayOrReopenExistingItem 完整矩阵
 * （Batch 458）：指纹/类型/版本不一致冲突、终态行重放、FAILED
 * 行重开后返回 null 继续执行、IN_PROGRESS 状态冲突。
 */
class DocumentSyncRunReplayTailTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DocumentSyncRunService service = new DocumentSyncRunService(
            jdbcTemplate,
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules(),
            mock(CollectionIdentityResolver.class),
            mock(DocumentMutationService.class),
            mock(DocumentSyncRunItemReceiptRepository.class),
            new com.springairag.core.config.RagProperties(),
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    private DocumentSyncRunItemRequest item(String sourceRevision) {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT, "e1", sourceRevision,
                "Doc", "content", null, null, null, null, null,
                EmbeddingPolicy.ASYNC);
    }

    private String fingerprint(DocumentSyncRunItemRequest item) throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "fingerprint", DocumentSyncRunItemRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(service, item);
    }

    private Object ledgerRow(String fingerprint, String errorCode,
                             DocumentSyncItemStatus status) throws Exception {
        Class<?> rowClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$LedgerRow");
        Constructor<?> ctor = rowClass.getDeclaredConstructor(
                String.class, DocumentSyncDocumentKind.class, String.class,
                String.class, Long.class, DocumentSyncItemStatus.class,
                String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("e1", DocumentSyncDocumentKind.TEXT,
                fingerprint, "rev-1", 41L, status, errorCode, null);
    }

    private Object ledgerRowKind(String fingerprint,
                                 com.springairag.api.enums.DocumentSyncDocumentKind kind,
                                 DocumentSyncItemStatus status) throws Exception {
        Class<?> rowClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$LedgerRow");
        Constructor<?> ctor = rowClass.getDeclaredConstructor(
                String.class, DocumentSyncDocumentKind.class, String.class,
                String.class, Long.class, DocumentSyncItemStatus.class,
                String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("e1", kind, fingerprint, "rev-1", 41L,
                status, null, null);
    }

    private Object ledgerRowWithRevision(String fingerprint, String sourceRevision,
                                         DocumentSyncItemStatus status) throws Exception {
        Class<?> rowClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$LedgerRow");
        Constructor<?> ctor = rowClass.getDeclaredConstructor(
                String.class, DocumentSyncDocumentKind.class, String.class,
                String.class, Long.class, DocumentSyncItemStatus.class,
                String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("e1", DocumentSyncDocumentKind.TEXT,
                fingerprint, sourceRevision, 41L, status, null, null);
    }

    private Object replayOrReopen(UUID runId, String externalId,
                                  String fingerprint,
                                  DocumentSyncRunItemRequest item,
                                  Object existing) throws Exception {
        Class<?> rowClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$LedgerRow");
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "replayOrReopenExistingItem", UUID.class, String.class,
                String.class, DocumentSyncRunItemRequest.class, rowClass);
        method.setAccessible(true);
        try {
            return method.invoke(service, runId, externalId, fingerprint,
                    item, existing);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RagException) e.getCause();
        }
    }

    private Object asResponse(Object ledgerRow) throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "toItemResponse", ledgerRow.getClass());
        method.setAccessible(true);
        return method.invoke(null, ledgerRow);
    }

    @Test
    void mismatchedFingerprintConflicts() throws Exception {
        UUID runId = UUID.randomUUID();
        DocumentSyncRunItemRequest item = item("rev-1");
        String fp = fingerprint(item);
        Object existing = ledgerRow("different", null,
                DocumentSyncItemStatus.APPLIED);

        RagException error = assertThrows(RagException.class,
                () -> replayOrReopen(runId, "e1", fp, item, existing));
        assertEquals(ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void mismatchedDocumentKindConflicts() throws Exception {
        UUID runId = UUID.randomUUID();
        DocumentSyncRunItemRequest item = item("rev-1");
        String fp = fingerprint(item);
        Object existing = ledgerRowKind(fp,
                com.springairag.api.enums.DocumentSyncDocumentKind.JSON_RECORD,
                DocumentSyncItemStatus.APPLIED);

        RagException error = assertThrows(RagException.class,
                () -> replayOrReopen(runId, "e1", fp, item, existing));
        assertEquals(ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void mismatchedSourceRevisionConflicts() throws Exception {
        UUID runId = UUID.randomUUID();
        DocumentSyncRunItemRequest item = item("rev-1");
        String fp = fingerprint(item);
        Object existing = ledgerRowWithRevision(fp, "rev-other",
                DocumentSyncItemStatus.APPLIED);

        RagException error = assertThrows(RagException.class,
                () -> replayOrReopen(runId, "e1", fp, item, existing));
        assertEquals(ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void terminalRowIsReplayedDirectly() throws Exception {
        UUID runId = UUID.randomUUID();
        DocumentSyncRunItemRequest item = item("rev-1");
        String fp = fingerprint(item);
        Object existing = ledgerRow(fp, null, DocumentSyncItemStatus.APPLIED);

        Object replayed = replayOrReopen(runId, "e1", fp, item, existing);

        assertEquals(existing, replayed);
        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class));
    }

    @Test
    void failedRowIsReopenedThenReturnsNull() throws Exception {
        UUID runId = UUID.randomUUID();
        DocumentSyncRunItemRequest item = item("rev-1");
        String fp = fingerprint(item);
        Object failedRow = ledgerRow(fp, "SYNC_RUN_ITEM_IN_PROGRESS",
                DocumentSyncItemStatus.FAILED);
        Object reopenedRow = ledgerRow(fp, "SYNC_RUN_ITEM_IN_PROGRESS",
                DocumentSyncItemStatus.APPLIED);
        when(jdbcTemplate.update(anyString(), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("FROM rag_document_sync_run_items"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(runId), eq("e1")))
                .thenReturn(failedRow)
                .thenReturn(reopenedRow);

        Object result = replayOrReopen(runId, "e1", fp, item, failedRow);

        // FAILED 行重开成功 → 返回 null，继续执行本次 mutation。
        assertNull(result);
        verify(jdbcTemplate).update(
                contains("SET status = 'FAILED'"), any(), any(), any());
    }

    @Test
    void inProgressRowWithoutReopenConflicts() throws Exception {
        UUID runId = UUID.randomUUID();
        DocumentSyncRunItemRequest item = item("rev-1");
        String fp = fingerprint(item);
        Object inProgress = ledgerRow(fp, "SYNC_RUN_ITEM_IN_PROGRESS",
                DocumentSyncItemStatus.APPLIED);

        RagException error = assertThrows(RagException.class,
                () -> replayOrReopen(runId, "e1", fp, item, inProgress));
        assertEquals(ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("currently being processed"));
    }
}
