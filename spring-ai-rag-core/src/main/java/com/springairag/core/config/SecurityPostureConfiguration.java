package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;

/**
 * 安全关闭时的误用防护。
 *
 * <p>{@code rag.security.enabled} 默认关闭。关闭后 {@code /api/**} 不校验 API key，
 * 任何能访问端口的人都能读数据面。开发与本地演示需要这个默认值，但把这种
 * 配置暴露到公网是事故。
 *
 * <p>这里做两件事：
 * <ol>
 *   <li>启动时输出明确 WARN（包含风险与修复方式）；</li>
 *   <li>在 {@code prod}/{@code production} profile 下直接拒绝启动；</li>
 *   <li>健康检查 {@code ragSecurityPosture} 暴露风险状态，便于监控告警。</li>
 * </ol>
 * 健康状态保持 UP：把进程标记为 DOWN 会让编排系统重启一个本身健康的实例，
 * 反而掩盖配置问题。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityPostureConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityPostureConfiguration.class);

    static final String DISABLED_WARNING =
            "rag.security.enabled=false：所有 /api/** 数据面入口都未校验 API key。"
                    + "只应在本地开发或隔离环境使用；公网部署请设置 RAG_SECURITY_ENABLED=true "
                    + "并配置 RAG_ROOT_API_KEY。";

    static final String NO_STATIC_KEY_WARNING =
            "rag.security.enabled=true 但 rag.security.api-key 与 rag.security.root-api-key "
                    + "都为空：所有 /api/** 请求会被拒绝（fail closed），只有数据库中已登记的 "
                    + "managed API key 可用。若这不是本意，请设置 RAG_ROOT_API_KEY。";

    static boolean isProductionProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    static boolean isSecurityDisabled(RagProperties properties) {
        return !properties.getSecurity().isEnabled();
    }

    static boolean hasNoStaticKey(RagProperties properties) {
        return isBlank(properties.getSecurity().getApiKey())
                && isBlank(properties.getSecurity().getRootApiKey());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Bean
    public ApplicationRunner securityPostureStartupCheck(
            RagProperties properties, Environment environment) {
        return args -> {
            if (!isSecurityDisabled(properties)) {
                if (hasNoStaticKey(properties)) {
                    log.warn("==============================================================");
                    log.warn(NO_STATIC_KEY_WARNING);
                    log.warn("==============================================================");
                }
                return;
            }
            if (isProductionProfile(environment)) {
                throw new IllegalStateException(
                        "Refusing to start: rag.security.enabled=false in production profile. "
                                + "Set RAG_SECURITY_ENABLED=true and configure RAG_ROOT_API_KEY, "
                                + "or run with a non-production profile for local development.");
            }
            log.warn("==============================================================");
            log.warn(DISABLED_WARNING);
            log.warn("Active profiles: {}",
                    Arrays.toString(environment.getActiveProfiles()));
            log.warn("Health endpoint detail: /actuator/health (component ragSecurityPosture)");
            log.warn("==============================================================");
        };
    }

    @Bean
    public HealthIndicator ragSecurityPostureHealthIndicator(RagProperties properties) {
        return () -> {
            if (!isSecurityDisabled(properties)) {
                return Health.up()
                        .withDetail("securityEnabled", true)
                        .build();
            }
            return Health.up()
                    .withDetail("securityEnabled", false)
                    .withDetail("risk", "HIGH")
                    .withDetail("exposedWithoutAuthentication", true)
                    .withDetail("warning", DISABLED_WARNING)
                    .build();
        };
    }
}
