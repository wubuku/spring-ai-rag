package com.springairag.core.service;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * isCollectionKeyConstraint 深层判定（Batch 356）：hibernate 约束
 * 名、PG 反射 ServerErrorMessage、非约束异常、原因链遍历。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RagCollectionKeyConstraintDetectionTest {

    @Mock RagCollectionRepository collectionRepository;
    @Mock RagDocumentRepository documentRepository;

    private RagCollectionService service;

    @BeforeEach
    void setUp() {
        service = new RagCollectionService(
                collectionRepository, documentRepository, null);
        when(collectionRepository.existsByCollectionKey("clone-key"))
                .thenReturn(false);
        when(collectionRepository.advanceActiveVersion(
                any(Long.class), any(Long.class))).thenReturn(1);
        var source = new RagCollection();
        source.setId(1L);
        source.setCollectionKey("source");
        source.setName("Source");
        when(collectionRepository.findByIdAndDeletedFalse(1L))
                .thenReturn(java.util.Optional.of(source));
        when(documentRepository.findAllByCollectionId(1L))
                .thenReturn(java.util.List.of());
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenAnswer(invocation -> {
                    var collection = (RagCollection) invocation.getArgument(0);
                    collection.setId(5L);
                    return collection;
                });
    }

    private DataIntegrityViolationException dive(Throwable cause) {
        return new DataIntegrityViolationException("integrity", cause);
    }

    @Test
    void hibernateConstraintWithOtherNameIsNotMapped() {
        var otherConstraint = new org.hibernate.exception.ConstraintViolationException(
                "other", new java.sql.SQLException("dup"), "uk_other_table");
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(dive(otherConstraint));

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class,
                        () -> service.cloneCollection(1L, "clone-key"));
        assertEquals("integrity", thrown.getMessage());
    }

    @Test
    void constraintFoundDeepInCauseChain() {
        var keyViolation = new org.hibernate.exception.ConstraintViolationException(
                "dup", new java.sql.SQLException("dup"),
                "uk_rag_collection_collection_key");
        // 原因链嵌套：外层 DIVE → 内层 DIVE → 键约束违规。
        var wrapped = new DataIntegrityViolationException("outer",
                dive(keyViolation));
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(wrapped);

        com.springairag.core.exception.RagException error = assertThrows(
                com.springairag.core.exception.RagException.class,
                () -> service.cloneCollection(1L, "clone-key"));
        assertTrue(error.getMessage().contains("already exists"));
    }

    // ─── PG ServerErrorMessage 反射真值分支 ─────────────────────────

    /**
     * 按 PG ErrorResponse 字段格式构造真实 ServerErrorMessage：
     * 每段为「键字符 + 值 + \0」（键后紧跟值，\0 终止该段）。
     * 约束字段键为 'n'。
     */
    private static org.postgresql.util.ServerErrorMessage serverError(
            String constraint) {
        return new org.postgresql.util.ServerErrorMessage(
                "SERROR\u0000VERROR\u0000Mduplicate key\u0000"
                        + "C23505\u0000n" + constraint + "\u0000");
    }

    private org.postgresql.util.PSQLException psql(
            org.postgresql.util.ServerErrorMessage serverError) {
        // PSQLException 只有 (ServerErrorMessage) 单参构造承载服务端
        // 错误详情；约束名经 getServerErrorMessage().getConstraint()
        // 反射读取。
        return new org.postgresql.util.PSQLException(serverError);
    }

    @Test
    void psqlServerErrorMessageWithKeyConstraintIsMapped() {
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(dive(psql(serverError(
                        "uk_rag_collection_collection_key"))));

        com.springairag.core.exception.RagException error = assertThrows(
                com.springairag.core.exception.RagException.class,
                () -> service.cloneCollection(1L, "clone-key"));
        assertTrue(error.getMessage().contains("already exists"));
    }

    @Test
    void psqlServerErrorMessageWithOtherConstraintIsNotMapped() {
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(dive(psql(serverError("uk_other_table"))));

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class,
                        () -> service.cloneCollection(1L, "clone-key"));
        assertEquals("integrity", thrown.getMessage());
    }

    @Test
    void psqlWithoutServerErrorMessageIsNotMapped() {
        // 无 ServerErrorMessage（getServerErrorMessage() 返回 null）
        // → 反射真值判定跳过，原样上抛。
        var bare = new org.postgresql.util.PSQLException(
                "no server detail", org.postgresql.util.PSQLState.UNIQUE_VIOLATION);
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(dive(bare));

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class,
                        () -> service.cloneCollection(1L, "clone-key"));
        assertEquals("integrity", thrown.getMessage());
    }

    @Test
    void psqlConstraintLookupFailureIsIgnored() {
        // getConstraint() 反射调用抛出异常 → InvocationTargetException
        // 被 ReflectiveOperationException catch 吞掉，保持原错误上抛。
        var broken = new org.postgresql.util.ServerErrorMessage(
                serverError("uk_rag_collection_collection_key").toString()) {
            @Override
            public String getConstraint() {
                throw new IllegalStateException("driver detail broken");
            }
        };
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(dive(psql(broken)));

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class,
                        () -> service.cloneCollection(1L, "clone-key"));
        assertEquals("integrity", thrown.getMessage());
    }
}
