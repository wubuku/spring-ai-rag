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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatSessionCoordinatorBeanRegistrationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class);

    @Test
    void componentScanRegistersCoordinatorWhenJdbcDependenciesAreDeclaredLater() {
        contextRunner.run(context -> {
            // 原来只断两个 bean 都拿得到——而 context.getBean 拿不到本来
            // 就直接抛 NoSuchBeanDefinitionException，assertNotNull 在这里
            // 纯属装饰。
            //
            // 这条用例真正要说的是"后声明的 JDBC 依赖照样注得进组件扫描
            // 出来的协调器"，所以断的是**依赖真的被用上了**：往协调器的
            // sharedMemory 里写一条消息，容器里那个 JdbcChatMemoryRepository
            // mock 必须收到调用。注入失败（拿到 null 或别的实例）时它一次
            // 都不会被碰到。
            assertNull(context.getStartupFailure(),
                    "上下文启动失败：" + context.getStartupFailure());

            ChatSessionCoordinator coordinator =
                    context.getBean(ChatSessionCoordinator.class);
            // 不是 CGLIB 代理、也不是子类，就是组件扫描出来的那一个。
            assertEquals(ChatSessionCoordinator.class, coordinator.getClass());

            JdbcChatMemoryRepository memoryRepository =
                    context.getBean(JdbcChatMemoryRepository.class);
            String conversationId = "session-1";
            coordinator.sharedMemory().add(conversationId,
                    List.of(new org.springframework.ai.chat.messages.UserMessage("hello")));

            verify(memoryRepository, org.mockito.Mockito.atLeastOnce())
                    .saveAll(org.mockito.ArgumentMatchers.eq(conversationId),
                            org.mockito.ArgumentMatchers.anyList());
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
