package com.springairag.core.config;

import com.springairag.core.service.LegacyEmbeddingMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 PostgreSQL 启动编排：Profile 注册、索引创建、Legacy 迁移门禁
 * 与未认领向量的启动失败。
 */
class EmbeddingProfileBootstrapTest {

    private EmbeddingProfileRegistry profileRegistry;
    private EmbeddingProfileIndexManager indexManager;
    private LegacyEmbeddingMigrationService migrationService;
    private RagEmbeddingProperties properties;
    private EmbeddingProfileBootstrap bootstrap;

    private final EmbeddingProfile active = new EmbeddingProfile(
            7L, "bge-m3", "siliconflow", "BAAI/bge-m3",
            "r1", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    @BeforeEach
    void setUp() {
        profileRegistry = mock(EmbeddingProfileRegistry.class);
        indexManager = mock(EmbeddingProfileIndexManager.class);
        migrationService = mock(LegacyEmbeddingMigrationService.class);
        RagProperties ragProperties = new RagProperties();
        properties = ragProperties.getEmbedding();
        bootstrap = new EmbeddingProfileBootstrap(
                profileRegistry, indexManager, migrationService, ragProperties);
        when(profileRegistry.initialize()).thenReturn(active);
        when(migrationService.countUnassigned()).thenReturn(0L);
    }

    @Test
    void registersProfileAndCreatesIndexOnStartup() {
        bootstrap.run(new DefaultApplicationArguments());

        verify(profileRegistry).initialize();
        verify(indexManager).ensureIndex(active);
        verify(migrationService).countUnassigned();
    }

    @Test
    void runsLegacyAdoptionWithExplicitProfileKeyAndConfirmation() {
        properties.setMigrationMode("adopt-legacy");
        properties.setMigrationLegacyProfileKey("legacy-key");
        properties.setMigrationConfirm("I_HAVE_VERIFIED_THE_LEGACY_MODEL");
        when(migrationService.adoptLegacy("legacy-key",
                "I_HAVE_VERIFIED_THE_LEGACY_MODEL")).thenReturn(3);

        bootstrap.run(new DefaultApplicationArguments());

        verify(migrationService).adoptLegacy(
                "legacy-key", "I_HAVE_VERIFIED_THE_LEGACY_MODEL");
    }

    @Test
    void fallsBackToActiveProfileKeyWhenLegacyKeyIsBlank() {
        properties.setMigrationMode("adopt-legacy");
        properties.setMigrationLegacyProfileKey("  ");
        properties.setMigrationConfirm("confirm");
        when(migrationService.adoptLegacy("bge-m3", "confirm")).thenReturn(1);

        bootstrap.run(new DefaultApplicationArguments());

        verify(migrationService).adoptLegacy("bge-m3", "confirm");
    }

    @Test
    void rejectsUnsupportedMigrationMode() {
        properties.setMigrationMode("bogus");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments()));
        assertEquals("Unsupported embedding migration mode: bogus",
                error.getMessage());
    }

    @Test
    void failsStartupWhenUnassignedLegacyEmbeddingsRemain() {
        when(migrationService.countUnassigned()).thenReturn(5L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments()));
        assertEquals("Unassigned legacy embeddings found: 5. Run with "
                + "rag.embedding.migration-mode=adopt-legacy and explicit "
                + "confirmation before online startup.", error.getMessage());
    }
}
