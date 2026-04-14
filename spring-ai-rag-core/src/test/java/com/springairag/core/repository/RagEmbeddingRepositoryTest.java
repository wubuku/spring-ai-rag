package com.springairag.core.repository;

import com.springairag.core.entity.RagEmbedding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagEmbeddingRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagEmbeddingRepository Tests")
class RagEmbeddingRepositoryTest {

    @Mock
    private RagEmbeddingRepository repository;

    private RagEmbedding createEmbedding(Long id, Long documentId, String chunkText) {
        RagEmbedding e = new RagEmbedding();
        e.setId(id);
        e.setDocumentId(documentId);
        e.setChunkText(chunkText);
        e.setChunkIndex(0);
        e.setEmbedding(new float[]{0.1f, 0.2f, 0.3f});
        e.setMetadata(Map.of());
        return e;
    }

    // deleteByDocumentId

    @Nested
    @DisplayName("deleteByDocumentId")
    class DeleteByDocumentId {

        @Test
        @DisplayName("deletes all embeddings for document")
        void deletesAllEmbeddingsForDocument() {
            doNothing().when(repository).deleteByDocumentId(1L);

            repository.deleteByDocumentId(1L);

            verify(repository).deleteByDocumentId(1L);
        }

        @Test
        @DisplayName("deleteByDocumentId with no embeddings does not throw")
        void deleteByDocumentIdWithNoEmbeddingsDoesNotThrow() {
            doNothing().when(repository).deleteByDocumentId(999L);

            assertDoesNotThrow(() -> repository.deleteByDocumentId(999L));
        }
    }

    // deleteByDocumentIdIn

    @Nested
    @DisplayName("deleteByDocumentIdIn")
    class DeleteByDocumentIdIn {

        @Test
        @DisplayName("batch deletes embeddings for multiple documents")
        void batchDeletesEmbeddingsForMultipleDocuments() {
            doNothing().when(repository).deleteByDocumentIdIn(List.of(1L, 2L, 3L));

            repository.deleteByDocumentIdIn(List.of(1L, 2L, 3L));

            verify(repository).deleteByDocumentIdIn(List.of(1L, 2L, 3L));
        }

        @Test
        @DisplayName("batch delete with empty list does not throw")
        void batchDeleteWithEmptyListDoesNotThrow() {
            doNothing().when(repository).deleteByDocumentIdIn(List.of());

            assertDoesNotThrow(() -> repository.deleteByDocumentIdIn(List.of()));
        }
    }

    // countByDocumentId

    @Nested
    @DisplayName("countByDocumentId")
    class CountByDocumentId {

        @Test
        @DisplayName("returns count of embeddings for document")
        void returnsCountOfEmbeddingsForDocument() {
            when(repository.countByDocumentId(10L)).thenReturn(5L);

            long count = repository.countByDocumentId(10L);

            assertEquals(5L, count);
        }

        @Test
        @DisplayName("returns zero for document with no embeddings")
        void returnsZeroForDocumentWithNoEmbeddings() {
            when(repository.countByDocumentId(999L)).thenReturn(0L);

            long count = repository.countByDocumentId(999L);

            assertEquals(0L, count);
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores embedding and returns it")
        void saveStoresEmbeddingAndReturnsIt() {
            RagEmbedding e = createEmbedding(null, 10L, "new chunk");
            RagEmbedding saved = createEmbedding(1L, 10L, "new chunk");
            when(repository.save(e)).thenReturn(saved);

            RagEmbedding result = repository.save(e);

            assertNotNull(result.getId());
            assertEquals(10L, result.getDocumentId());
        }

        @Test
        @DisplayName("findById returns embedding when present")
        void findByIdReturnsEmbeddingWhenPresent() {
            RagEmbedding e = createEmbedding(1L, 10L, "chunk text");
            when(repository.findById(1L)).thenReturn(Optional.of(e));

            Optional<RagEmbedding> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("chunk text", result.get().getChunkText());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagEmbedding> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all embeddings")
        void findAllReturnsAllEmbeddings() {
            RagEmbedding e1 = createEmbedding(1L, 10L, "chunk A");
            RagEmbedding e2 = createEmbedding(2L, 20L, "chunk B");
            when(repository.findAll()).thenReturn(List.of(e1, e2));

            List<RagEmbedding> embeddings = repository.findAll();

            assertEquals(2, embeddings.size());
        }

        @Test
        @DisplayName("deleteById removes embedding")
        void deleteByIdRemovesEmbedding() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("count returns total embedding count")
        void countReturnsTotalEmbeddingCount() {
            when(repository.count()).thenReturn(1000L);

            long count = repository.count();

            assertEquals(1000L, count);
        }

        @Test
        @DisplayName("existsById returns true when present")
        void existsByIdReturnsTrueWhenPresent() {
            when(repository.existsById(1L)).thenReturn(true);

            boolean exists = repository.existsById(1L);

            assertTrue(exists);
        }

        @Test
        @DisplayName("existsById returns false when not present")
        void existsByIdReturnsFalseWhenNotPresent() {
            when(repository.existsById(999L)).thenReturn(false);

            boolean exists = repository.existsById(999L);

            assertFalse(exists);
        }
    }
}
