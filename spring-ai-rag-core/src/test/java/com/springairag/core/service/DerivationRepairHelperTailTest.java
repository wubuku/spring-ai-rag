package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.exception.RagException;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 派生修复助手长尾（Batch 721，JaCoCo 驱动）：upperSet 对
 * null/空白/大小写的归一、safeError 对无消息异常回退类名并截断
 * 500、purge 计划序列化失败的包装、requireActiveProfile 对
 * Profile 漂移的冲突拒绝。
 */
class DerivationRepairHelperTailTest {

    private EmbeddingProfileProvider profileProvider;
    private DerivationRepairService service;

    @BeforeEach
    void setUp() {
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        7L, "bge-m3", "vendor", "bge-m3", "rev-1",
                        1024, "cosine", "normalize", true));
        service = new DerivationRepairService(
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(com.springairag.core.service.DerivationIntegrityRepository.class),
                mock(DerivationIntegrityService.class),
                mock(RagDocumentRepository.class),
                mock(com.springairag.core.service.KeywordIndexPersistenceService.class),
                mock(com.springairag.core.embeddingjob.EmbeddingDispatchService.class),
                mock(CollectionIdentityResolver.class),
                profileProvider,
                new com.springairag.core.config.RagProperties(),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void upperSetNormalizesNullBlankAndCase() {
        assertEquals(Set.of(), DerivationRepairService.upperSet(null));
        assertEquals(Set.of(), DerivationRepairService.upperSet(List.of()));
        assertEquals(Set.of("ALPHA", "BETA"),
                DerivationRepairService.upperSet(
                        java.util.Arrays.asList(" alpha ", "", "Beta", null)));
    }

    private String safeError(RuntimeException error) throws Exception {
        Method method = DerivationRepairService.class
                .getDeclaredMethod("safeError", RuntimeException.class);
        method.setAccessible(true);
        return (String) method.invoke(service, error);
    }

    @Test
    void safeErrorFallsBackToSimpleNameAndTruncates() throws Exception {
        assertEquals("IllegalStateException",
                safeError(new IllegalStateException()));
        String longMessage = "x".repeat(600);
        String safe = safeError(new IllegalStateException(longMessage));
        assertEquals(500, safe.length());
        assertTrue(longMessage.startsWith(safe));
    }

    @Test
    void jsonWrapsSerializationFailure() throws Exception {
        Method method = DerivationRepairService.class
                .getDeclaredMethod("json", Object.class);
        method.setAccessible(true);

        var error = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(service, new Object()));
        var cause = (IllegalStateException) error.getCause();
        assertEquals("Cannot serialize derivation repair plan",
                cause.getMessage());
    }

    @Test
    void requireActiveProfileRejectsProfileDrift() throws Exception {
        when(profileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        8L, "other", "vendor", "other", "rev-2",
                        1024, "cosine", "normalize", true));
        Method method = DerivationRepairService.class
                .getDeclaredMethod("requireActiveProfile", long.class);
        method.setAccessible(true);

        RagException error = assertThrows(RagException.class,
                () -> {
                    try {
                        method.invoke(service, 7L);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        assertEquals(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void numberAndNullableNumberHelpersConvert() throws Exception {
        Method number = DerivationRepairService.class
                .getDeclaredMethod("number", Object.class);
        Method nullable = DerivationRepairService.class
                .getDeclaredMethod("nullableNumber", Object.class, long.class);
        number.setAccessible(true);
        nullable.setAccessible(true);

        assertEquals(5L, number.invoke(service, 5L));
        assertEquals(7L, nullable.invoke(service, null, 7L));
        assertEquals(3L, nullable.invoke(service, 3, 7L));
    }
}
