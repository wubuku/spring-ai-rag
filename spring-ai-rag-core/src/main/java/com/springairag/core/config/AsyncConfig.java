package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 *
 * <p>配置通用异步任务执行器（@Async 用），与 PerformanceConfig 的 ragSearchExecutor（检索专用）分离。
 *
 * <ul>
 *   <li>ragSearchExecutor — HybridRetrieverService 并行检索，固定 4 线程</li>
 *   <li>taskExecutor — 通用 @Async 异步任务，可配置线程池</li>
 * </ul>
 *
 * <p>队列满时降级策略：CallerRunsPolicy（背压保护）。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private final RagProperties ragProperties;

    public AsyncConfig(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        RagProperties.Async asyncConfig = ragProperties.getAsync();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncConfig.getCorePoolSize());
        executor.setMaxPoolSize(asyncConfig.getMaxPoolSize());
        executor.setQueueCapacity(asyncConfig.getQueueCapacity());
        executor.setThreadNamePrefix("rag-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // 队列满时的降级策略：CallerRunsPolicy 提供背压
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
     * 异步方法未捕获异常处理器
     *
     * <p>记录异常日志，未来可扩展为告警通知。
     */
    static class RagAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        private static final Logger log = LoggerFactory.getLogger(RagAsyncExceptionHandler.class);

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("Async method '{}' threw exception: {}", method.getName(), ex.getMessage(), ex);
        }
    }
}
