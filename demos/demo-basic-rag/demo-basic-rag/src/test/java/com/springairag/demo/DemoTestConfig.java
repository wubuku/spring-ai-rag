package com.springairag.demo;

import com.springairag.core.service.RagChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.RagChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Test configuration that provides mocked AI beans for demo E2E tests.
 * Excludes {@link com.springairag.core.config.SpringAiConfig} from the
 * application context (since it would try to create real API clients).
 */
@TestConfiguration
public class DemoTestConfig {

    @Bean
    @Primary
    public ChatMemory chatMemory(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .build();
    }

    @Bean
    @Primary
    public ChatMemory ragChatMemory(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .build();
    }

    @Bean
    @Primary
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(
            @Autowired ChatMemory ragChatMemory) {
        return new MessageChatMemoryAdvisor(ragChatMemory);
    }

    @Bean
    @Primary
    public RagChatMemoryAdvisor ragChatMemoryAdvisor(
            @Autowired ChatMemory chatMemory) {
        return new RagChatMemoryAdvisor(chatMemory, "${spring.ai.memory.message.window.size:10}");
    }
}
