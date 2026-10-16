package example.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 供黑盒验收脚本启动的外部 Starter Consumer。
 *
 * <p>该入口不扫描 {@code com.springairag}；核心 Bean Graph 必须由 Starter 自动配置提供。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ExternalStarterConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExternalStarterConsumerApplication.class, args);
    }
}
