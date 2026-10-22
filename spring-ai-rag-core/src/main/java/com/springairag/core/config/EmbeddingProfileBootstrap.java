package com.springairag.core.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 模式下注册 Profile、绑定历史 Collection 并建立索引。
 */
@Component
@Profile("postgresql")
@Order(-100)
public class EmbeddingProfileBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProfileBootstrap.class);

    private final EmbeddingProfileRegistry profileRegistry;
    private final EmbeddingProfileIndexManager indexManager;
    private final EmbeddingProfileModelResolver modelResolver;
    private final JdbcTemplate jdbcTemplate;

    public EmbeddingProfileBootstrap(
            EmbeddingProfileRegistry profileRegistry,
            EmbeddingProfileIndexManager indexManager,
            EmbeddingProfileModelResolver modelResolver,
            JdbcTemplate jdbcTemplate) {
        this.profileRegistry = profileRegistry;
        this.indexManager = indexManager;
        this.modelResolver = modelResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        EmbeddingProfile active = profileRegistry.initialize();
        modelResolver.initialize();
        jdbcTemplate.update("""
                UPDATE rag_collection
                SET active_embedding_profile_id = ?
                WHERE active_embedding_profile_id IS NULL
                """, active.id());
        profileRegistry.getConfiguredProfiles().values().stream()
                .filter(EmbeddingProfile::enabled)
                .forEach(indexManager::ensureIndex);
        profileRegistry.findAll().stream()
                .filter(profile -> !profile.enabled())
                .forEach(indexManager::dropIndex);
        log.info("Active embedding profile ready: key={}, model={}, dimensions={}",
                active.profileKey(), active.modelName(), active.dimensions());
    }
}
