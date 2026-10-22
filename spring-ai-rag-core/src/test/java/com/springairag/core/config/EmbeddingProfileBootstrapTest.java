package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingProfileBootstrapTest {

    private EmbeddingProfileRegistry profileRegistry;
    private EmbeddingProfileIndexManager indexManager;
    private EmbeddingProfileModelResolver modelResolver;
    private JdbcTemplate jdbcTemplate;
    private EmbeddingProfileBootstrap bootstrap;

    private final EmbeddingProfile active = new EmbeddingProfile(
            7L, "bge-m3", "siliconflow", "BAAI/bge-m3",
            "r1", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    @BeforeEach
    void setUp() {
        profileRegistry = mock(EmbeddingProfileRegistry.class);
        indexManager = mock(EmbeddingProfileIndexManager.class);
        modelResolver = mock(EmbeddingProfileModelResolver.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        bootstrap = new EmbeddingProfileBootstrap(
                profileRegistry, indexManager, modelResolver, jdbcTemplate);
        when(profileRegistry.initialize()).thenReturn(active);
        when(profileRegistry.getConfiguredProfiles())
                .thenReturn(Map.of(active.profileKey(), active));
    }

    @Test
    void registersProfileBindsHistoricalCollectionsAndCreatesIndex() {
        bootstrap.run(new DefaultApplicationArguments());

        verify(profileRegistry).initialize();
        verify(modelResolver).initialize();
        verify(jdbcTemplate).update("""
                UPDATE rag_collection
                SET active_embedding_profile_id = ?
                WHERE active_embedding_profile_id IS NULL
                """, active.id());
        verify(indexManager).ensureIndex(active);
    }
}
