package com.springairag.core.config;

import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 让 Spring AI JDBC Memory 与 JPA history 使用同一个事务管理器。
 */
@Configuration
public class ChatMemoryRepositoryConfig {

    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    @ConditionalOnMissingBean(JdbcChatMemoryRepository.class)
    public JdbcChatMemoryRepository jdbcChatMemoryRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new PostgresChatMemoryRepositoryDialect())
                .transactionManager(transactionManager)
                .build();
    }
}
