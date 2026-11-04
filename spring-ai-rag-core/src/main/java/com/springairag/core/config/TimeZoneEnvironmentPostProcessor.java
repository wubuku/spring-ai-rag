package com.springairag.core.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 在任何 Spring bean 创建之前把进程默认时区固定为 UTC。
 *
 * <p>为什么需要：数据库里的业务时间戳列是 {@code TIMESTAMP(6)}（不带时区），
 * 大量代码用 {@code LocalDateTime.now()} 写入。如果 JVM 默认时区跟随宿主机，
 * 同一个二进制在 {@code TZ=Asia/Shanghai} 与 {@code TZ=America/New_York} 下
 * 会写入不同的“墙上时间”，导出、TTL 清理、审计查询的含义都会漂移。
 *
 * <p>通过 {@link EnvironmentPostProcessor} 在日志系统与容器初始化之前生效，
 * 且对 direct jar / 引入 core 的所有 demo 都一致。需要显式改用其它时区时，
 * 设置 {@code rag.time-zone}（或环境变量 {@code RAG_TIME_ZONE}），属于显式配置
 * 而非“看机器时区”。
 */
public class TimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 显式时区配置项；缺省为 UTC。 */
    public static final String TIME_ZONE_PROPERTY = "rag.time-zone";

    public static final String DEFAULT_TIME_ZONE = "UTC";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment.getProperty(TIME_ZONE_PROPERTY, DEFAULT_TIME_ZONE));
    }

    /**
     * 设置 JVM 默认时区；非法值回退 UTC，避免启动期因为配置错误直接失败。
     *
     * <p>同时把默认 Locale 固定为 {@link Locale#ROOT}：服务端的大小写转换、
     * 数字格式化不应该随宿主机语言变化（例如土耳其语 {@code "I".toLowerCase()}
     * 会得到无点的 {@code "ı"}）。HTTP 请求语言仍由 LocaleResolver 按
     * {@code Accept-Language} 决定，不受这里影响。
     */
    public static ZoneId apply(String configured) {
        ZoneId zoneId = resolve(configured);
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
        Locale.setDefault(Locale.ROOT);
        return zoneId;
    }

    static ZoneId resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return ZoneId.of(DEFAULT_TIME_ZONE);
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (RuntimeException ex) {
            return ZoneId.of(DEFAULT_TIME_ZONE);
        }
    }

    @Override
    public int getOrder() {
        // 必须早于 LoggingApplicationListener（HIGHEST_PRECEDENCE + 20），日志时间才稳定。
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
