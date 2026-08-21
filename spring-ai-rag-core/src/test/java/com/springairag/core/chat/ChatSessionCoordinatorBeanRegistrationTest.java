package com.springairag.core.chat;

import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ChatSessionCoordinatorBeanRegistrationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class);

    @Test
    void componentScanRegistersCoordinatorWhenJdbcDependenciesAreDeclaredLater() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(JdbcChatMemoryRepository.class));
            assertNotNull(context.getBean(ChatSessionCoordinator.class));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = ChatSessionCoordinator.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = ChatSessionCoordinator.class))
    static class TestConfiguration {

        @Bean
        JdbcChatMemoryRepository memoryRepository() {
            return mock(JdbcChatMemoryRepository.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        RagChatHistoryRepository historyRepository() {
            return mock(RagChatHistoryRepository.class);
        }

        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }
}
