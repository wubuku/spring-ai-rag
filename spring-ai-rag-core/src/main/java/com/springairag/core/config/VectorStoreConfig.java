package com.springairag.core.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVectorStore Configuration (manual Bean creation, overrides auto-configuration)
 *
 * <p>Only takes effect when the postgresql profile is active.
 * PgVectorStoreAutoConfiguration is excluded by default; this manual Bean provides finer control.
 *
 * <p>dimensions must match the EmbeddingModel output dimension (BGE-M3 = 1024).
 */
@Configuration
@Profile("postgresql")
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.pgvector.vector-table-name:rag_vector_store}")
    private String vectorTableName;

    @Value("${spring.ai.vectorstore.pgvector.distance-type:COSINE_DISTANCE}")
    private String distanceType;

    @Value("${spring.ai.vectorstore.pgvector.index-type:HNSW}")
    private String indexType;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}")
    private int dimensions;

    @Bean
    public PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(vectorTableName)
                .distanceType(PgVectorStore.PgDistanceType.valueOf(distanceType))
                .indexType(PgVectorStore.PgIndexType.valueOf(indexType))
                .dimensions(dimensions)
                .initializeSchema(true)
                .build();
    }
}
