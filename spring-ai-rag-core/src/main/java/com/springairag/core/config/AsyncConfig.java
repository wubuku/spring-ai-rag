package com.springairag.core.config;

import com.springairag.core.properties.RagAsyncProperties;
import com.springairag.core.logging.MdcContext;
import com.springairag.core.tenant.TenantTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
 *
 * <p>WP7：taskExecutor 与调度线程统一经 {@link TenantTaskDecorator} 包装：
 * 请求链路触发的 @Async 显式传播租户上下文；无上下文时 SINGLE 显式
 * bootstrap default、STRICT 不隐式 fallback（见 decorator 注释）。</p>
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
        asyncConfig.validate();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncConfig.getCorePoolSize());
        executor.setMaxPoolSize(asyncConfig.getMaxPoolSize());
        executor.setQueueCapacity(asyncConfig.getQueueCapacity());
        executor.setThreadNamePrefix("rag-async-");
        // 跨线程保留提交方的 traceId，否则 @Async 内的日志无法回到原请求。
        // WP7：再包一层租户作用域，两者都做 capture—set—restore—clear。
        TenantTaskDecorator tenantDecorator = tenantTaskDecorator();
        executor.setTaskDecorator(runnable ->
                tenantDecorator.decorate(MdcContext.wrap(runnable)));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // Queue full degradation: CallerRunsPolicy provides backpressure
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        log.info("Async executor initialized: core={}, max={}, queue={}, tenantMode={}",
                asyncConfig.getCorePoolSize(), asyncConfig.getMaxPoolSize(),
                asyncConfig.getQueueCapacity(), ragProperties.getTenant().getMode());
        return executor;
    }

    /** 统一的租户任务装饰器；供 taskExecutor 与调度器共用同一语义。 */
    private TenantTaskDecorator tenantTaskDecorator() {
        return new TenantTaskDecorator(ragProperties.getTenant().getMode());
    }

    /**
     * 调度线程的 trace 上下文：每轮固定任务生成独立 {@code job-*} traceId；
     * WP7 同时按 {@link TenantTaskDecorator} 建立/传播租户作用域。
     *
     * <p>用 BeanPostProcessor 在调度器初始化前挂 TaskDecorator，
     * 保留 Spring Boot 的线程池/虚拟线程配置（不自己 new 调度器）。
     * 同时兼容线程池与虚拟线程两种调度实现。</p>
     */
    @Bean
    BeanPostProcessor scheduledTraceMdcPostProcessor() {
        TenantTaskDecorator tenantDecorator = tenantTaskDecorator();
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof ThreadPoolTaskScheduler scheduler) {
                    scheduler.setTaskDecorator(runnable -> tenantDecorator.decorate(
                            MdcContext.wrapScheduled(runnable)));
                } else if (bean instanceof SimpleAsyncTaskScheduler scheduler) {
                    scheduler.setTaskDecorator(runnable -> tenantDecorator.decorate(
                            MdcContext.wrapScheduled(runnable)));
                }
                return bean;
            }
        };
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
