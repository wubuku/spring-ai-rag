package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VectorStoreConfig
 */
class VectorStoreConfigTest {

    @Test
    @DisplayName("vectorStore creates PgVectorStore bean with default config")
    void vectorStore_createsPgVectorStore_withDefaults() {
        VectorStoreConfig config = new VectorStoreConfig();
        ReflectionTestUtils.setField(config, "vectorTableName", "rag_vector_store");
        ReflectionTestUtils.setField(config, "distanceType", "COSINE_DISTANCE");
        ReflectionTestUtils.setField(config, "indexType", "HNSW");
        ReflectionTestUtils.setField(config, "dimensions", 1024);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        PgVectorStore vectorStore = config.vectorStore(jdbcTemplate, embeddingModel);

        assertNotNull(vectorStore);
    }

    @Test
    @DisplayName("vectorStore creates PgVectorStore with custom table name")
    void vectorStore_createsPgVectorStore_withCustomTableName() {
        VectorStoreConfig config = new VectorStoreConfig();
        ReflectionTestUtils.setField(config, "vectorTableName", "custom_vector_table");
        ReflectionTestUtils.setField(config, "distanceType", "COSINE_DISTANCE");
        ReflectionTestUtils.setField(config, "indexType", "HNSW");
        ReflectionTestUtils.setField(config, "dimensions", 1024);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        PgVectorStore vectorStore = config.vectorStore(jdbcTemplate, embeddingModel);

        assertNotNull(vectorStore);
    }

    @Test
    @DisplayName("vectorStore creates PgVectorStore with EUCLIDEAN distance")
    void vectorStore_createsPgVectorStore_withEuclideanDistance() {
        VectorStoreConfig config = new VectorStoreConfig();
        ReflectionTestUtils.setField(config, "vectorTableName", "rag_vector_store");
        ReflectionTestUtils.setField(config, "distanceType", "EUCLIDEAN_DISTANCE");
        ReflectionTestUtils.setField(config, "indexType", "HNSW");
        ReflectionTestUtils.setField(config, "dimensions", 1024);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        PgVectorStore vectorStore = config.vectorStore(jdbcTemplate, embeddingModel);

        assertNotNull(vectorStore);
    }

    @Test
    @DisplayName("vectorStore creates PgVectorStore with IVFFlat index")
    void vectorStore_createsPgVectorStore_withIvfflatIndex() {
        VectorStoreConfig config = new VectorStoreConfig();
        ReflectionTestUtils.setField(config, "vectorTableName", "rag_vector_store");
        ReflectionTestUtils.setField(config, "distanceType", "COSINE_DISTANCE");
        ReflectionTestUtils.setField(config, "indexType", "IVFFLAT");
        ReflectionTestUtils.setField(config, "dimensions", 1024);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        PgVectorStore vectorStore = config.vectorStore(jdbcTemplate, embeddingModel);

        assertNotNull(vectorStore);
    }

    @Test
    @DisplayName("vectorStore creates PgVectorStore with custom dimensions")
    void vectorStore_createsPgVectorStore_withCustomDimensions() {
        VectorStoreConfig config = new VectorStoreConfig();
        ReflectionTestUtils.setField(config, "vectorTableName", "rag_vector_store");
        ReflectionTestUtils.setField(config, "distanceType", "COSINE_DISTANCE");
        ReflectionTestUtils.setField(config, "indexType", "HNSW");
        ReflectionTestUtils.setField(config, "dimensions", 768);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        PgVectorStore vectorStore = config.vectorStore(jdbcTemplate, embeddingModel);

        assertNotNull(vectorStore);
    }
}
