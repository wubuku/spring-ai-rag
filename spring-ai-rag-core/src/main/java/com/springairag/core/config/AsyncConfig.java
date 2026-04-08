package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async Task Configuration
 *
 * <p>Configures the common async task executor (for @Async), separate from
 * PerformanceConfig's ragSearchExecutor (retrieval-specific).
 *
 * <ul>
 *   <li>ragSearchExecutor — HybridRetrieverService parallel retrieval, fixed 4 threads</li>
 *   <li>taskExecutor — General @Async async tasks, configurable thread pool</li>
 * </ul>
 *
 * <p>Queue full degradation strategy: CallerRunsPolicy (backpressure protection).
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private final RagProperties ragProperties;

    public AsyncConfig(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        RagAsyncProperties asyncConfig = ragProperties.getAsync();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncConfig.getCorePoolSize());
        executor.setMaxPoolSize(asyncConfig.getMaxPoolSize());
        executor.setQueueCapacity(asyncConfig.getQueueCapacity());
        executor.setThreadNamePrefix("rag-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // Queue full degradation: CallerRunsPolicy provides backpressure
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        log.info("Async executor initialized: core={}, max={}, queue={}",
                asyncConfig.getCorePoolSize(), asyncConfig.getMaxPoolSize(), asyncConfig.getQueueCapacity());
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new RagAsyncExceptionHandler();
    }

    /**
     * Uncaught exception handler for async methods
     *
     * <p>Logs the exception; can be extended to send alert notifications in the future.
     */
    static class RagAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        private static final Logger log = LoggerFactory.getLogger(RagAsyncExceptionHandler.class);

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("Async method '{}' threw exception: {}", method.getName(), ex.getMessage(), ex);
        }
    }
}
