package com.springairag.core.config;

import com.springairag.core.service.LegacyEmbeddingMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 模式下注册 Profile、建立索引并执行 Legacy 启动门禁。
 */
@Component
@Profile("postgresql")
@Order(-100)
public class EmbeddingProfileBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProfileBootstrap.class);

    private final EmbeddingProfileRegistry profileRegistry;
    private final EmbeddingProfileIndexManager indexManager;
    private final LegacyEmbeddingMigrationService migrationService;
    private final RagEmbeddingProperties properties;

    public EmbeddingProfileBootstrap(
            EmbeddingProfileRegistry profileRegistry,
            EmbeddingProfileIndexManager indexManager,
            LegacyEmbeddingMigrationService migrationService,
            RagProperties ragProperties) {
        this.profileRegistry = profileRegistry;
        this.indexManager = indexManager;
        this.migrationService = migrationService;
        this.properties = ragProperties.getEmbedding();
    }

    @Override
    public void run(ApplicationArguments args) {
        EmbeddingProfile active = profileRegistry.initialize();
        indexManager.ensureIndex(active);

        if ("adopt-legacy".equals(properties.getMigrationMode())) {
            String profileKey = properties.getMigrationLegacyProfileKey();
            if (profileKey == null || profileKey.isBlank()) {
                profileKey = active.profileKey();
            }
            int documents = migrationService.adoptLegacy(
                    profileKey,
                    properties.getMigrationConfirm());
            log.info("Legacy embeddings adopted: documents={}, profile={}",
                    documents, profileKey);
        } else if (!"none".equals(properties.getMigrationMode())) {
            throw new IllegalStateException(
                    "Unsupported embedding migration mode: " + properties.getMigrationMode());
        }

        long remaining = migrationService.countUnassigned();
        if (remaining > 0) {
            throw new IllegalStateException(
                    "Unassigned legacy embeddings found: " + remaining
                            + ". Run with rag.embedding.migration-mode=adopt-legacy "
                            + "and explicit confirmation before online startup.");
        }
        log.info("Active embedding profile ready: key={}, model={}, dimensions={}",
                active.profileKey(), active.modelName(), active.dimensions());
    }
}
