package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagDocumentLifecycleProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentSyncRunService 校验辅助长尾（Batch 426）：snapshot
 * 模式与 missingPolicy 组合约束、confirmExclusiveOffline 门卫、
 * 可见 ASCII 值校验、命名空间归一与非默认开关、运行控制错误码
 * 集合。
 */
class DocumentSyncRunServiceValidateTailTest {

    private DocumentSyncRunService service;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getDocumentLifecycle().setSyncRunsEnabled(true);
        service = new DocumentSyncRunService(
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class),
                mock(DocumentMutationService.class),
                mock(DocumentSyncRunItemReceiptRepository.class),
                properties,
                mock(PlatformTransactionManager.class));
    }

    private void validateMode(DocumentSyncSnapshotMode mode,
                              DocumentSyncMissingPolicy policy,
                              boolean confirm) throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "validateMode", DocumentSyncSnapshotMode.class,
                DocumentSyncMissingPolicy.class, boolean.class);
        method.setAccessible(true);
        try {
            method.invoke(service, mode, policy, confirm);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (IllegalArgumentException) e.getCause();
        }
    }

    private String requireVisible(String value, String field, int max)
            throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "requireVisible", String.class, String.class, int.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, value, field, max);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (IllegalArgumentException) e.getCause();
        }
    }

    private String normalizeNamespace(String value) throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "normalizeNamespace", String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (IllegalArgumentException) e.getCause();
        }
    }

    private boolean isRunControlError(ErrorCode code) throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "isRunControlError", ErrorCode.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, code);
    }

    @Test
    void validateModeRequiresBothPolicies() {
        assertThrows(IllegalArgumentException.class,
                () -> validateMode(null, DocumentSyncMissingPolicy.NONE, false));
        assertThrows(IllegalArgumentException.class,
                () -> validateMode(null, null, false));
    }

    @Test
    void offlineManifestOnlySupportsNonePolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> validateMode(DocumentSyncSnapshotMode.OFFLINE_MANIFEST,
                        DocumentSyncMissingPolicy.TOMBSTONE, false));
        assertDoesNotThrow(() -> validateMode(
                DocumentSyncSnapshotMode.OFFLINE_MANIFEST,
                DocumentSyncMissingPolicy.NONE, false));
    }

    @Test
    void exclusiveOfflineTombstoneRequiresExplicitConfirmation() {
        assertThrows(IllegalArgumentException.class,
                () -> validateMode(DocumentSyncSnapshotMode.EXCLUSIVE_OFFLINE,
                        DocumentSyncMissingPolicy.TOMBSTONE, false));
        assertDoesNotThrow(() -> validateMode(
                DocumentSyncSnapshotMode.EXCLUSIVE_OFFLINE,
                DocumentSyncMissingPolicy.TOMBSTONE, true));
        // 非该组合时确认标志必须为 false。
        assertThrows(IllegalArgumentException.class,
                () -> validateMode(DocumentSyncSnapshotMode.ONLINE_CUT,
                        DocumentSyncMissingPolicy.TOMBSTONE, true));
    }

    @Test
    void requireVisibleRejectsBlankOverlongAndInvisibleChars()
            throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> requireVisible(null, "lease", 512));
        assertThrows(IllegalArgumentException.class,
                () -> requireVisible("  ", "lease", 512));
        assertThrows(IllegalArgumentException.class,
                () -> requireVisible("x".repeat(513), "lease", 512));
        assertThrows(IllegalArgumentException.class,
                () -> requireVisible("tab\tchar", "lease", 512));

        // 值被 trim 后返回。
        assertEquals("tok", requireVisible("  tok  ", "lease", 512));
    }

    @Test
    void normalizeNamespaceDefaultsAndGate() throws Exception {
        // null/空白 → 默认命名空间。
        assertEquals("default", normalizeNamespace(null));
        assertEquals("default", normalizeNamespace("  "));
        assertEquals("crm", normalizeNamespace("crm"));

        // 关闭非默认命名空间开关后拒绝。
        RagProperties properties = new RagProperties();
        properties.getDocumentLifecycle().setSyncRunsEnabled(true);
        properties.getDocumentLifecycle().setAllowNonDefaultNamespace(false);
        DocumentSyncRunService gated = new DocumentSyncRunService(
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class),
                mock(DocumentMutationService.class),
                mock(DocumentSyncRunItemReceiptRepository.class),
                properties,
                mock(PlatformTransactionManager.class));
        Method gatedMethod = DocumentSyncRunService.class.getDeclaredMethod(
                "normalizeNamespace", String.class);
        gatedMethod.setAccessible(true);
        try {
            gatedMethod.invoke(gated, "crm");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    IllegalArgumentException.class, e.getCause().getClass());
        }
    }

    @Test
    void runControlErrorCodesAreClassified() throws Exception {
        assertTrue(isRunControlError(ErrorCode.SYNC_RUN_LEASE_CONFLICT));
        assertTrue(isRunControlError(ErrorCode.ACTIVE_SYNC_RUN_EXISTS));
        assertTrue(isRunControlError(ErrorCode.SYNC_RUN_INVALID_STATE));
        assertTrue(isRunControlError(ErrorCode.SYNC_RUN_PREVIEW_CONFLICT));
        assertTrue(isRunControlError(ErrorCode.SYNC_RUN_DELETE_PROTECTION));
        assertTrue(isRunControlError(ErrorCode.SYNC_RUN_ITEM_CONFLICT));
        org.junit.jupiter.api.Assertions.assertFalse(
                isRunControlError(ErrorCode.NOT_FOUND));
    }

}
