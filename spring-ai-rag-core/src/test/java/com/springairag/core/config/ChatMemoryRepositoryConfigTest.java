package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Spring AI JDBC Memory 与 JPA history 共享事务管理器的装配条件。 */
class ChatMemoryRepositoryConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(ChatMemoryRepositoryConfig.class)
                    .withBean("jdbcTemplate", JdbcTemplate.class,
                            ChatMemoryRepositoryConfigTest::stubJdbcTemplate)
                    .withBean("transactionManager",
                            PlatformTransactionManager.class, () ->
                                    mock(PlatformTransactionManager.class));

    private static JdbcTemplate stubJdbcTemplate() {
        // builder 会从 jdbcTemplate 解析 DataSource。
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.getDataSource()).thenReturn(mock(DataSource.class));
        return jdbcTemplate;
    }

    @Test
    void createsTheSharedTransactionManagerBackedRepository() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JdbcChatMemoryRepository.class);
            JdbcChatMemoryRepository repository =
                    context.getBean(JdbcChatMemoryRepository.class);
            assertThat(repository).isNotNull();
        });
    }

    @Test
    void backsOffWhenAnotherJdbcChatMemoryRepositoryAlreadyExists() {
        contextRunner.withBean("predefinedMemoryRepository",
                JdbcChatMemoryRepository.class,
                () -> mock(JdbcChatMemoryRepository.class)
        ).run(context -> {
            // @ConditionalOnMissingBean：不与既有 bean 竞争。
            assertThat(context.getBeansOfType(JdbcChatMemoryRepository.class))
                    .hasSize(1);
        });
    }

    @Test
    void backsOffWithoutATransactionManager() {
        new ApplicationContextRunner()
                .withUserConfiguration(ChatMemoryRepositoryConfig.class)
                .withBean("jdbcTemplate", JdbcTemplate.class, () ->
                        mock(JdbcTemplate.class))
                .run(context -> {
                    // @ConditionalOnBean(PlatformTransactionManager) 未满足。
                    assertThat(context)
                            .doesNotHaveBean(JdbcChatMemoryRepository.class);
                });
    }
}
