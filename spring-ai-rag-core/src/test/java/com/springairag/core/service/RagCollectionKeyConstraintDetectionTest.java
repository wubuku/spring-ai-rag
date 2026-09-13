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
}
