package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * WP6：静态边界测试（实施规划 §6 WP6「ArchUnit/静态边界测试禁止业务包依赖平台实现」）。
 *
 * <p>规则：业务包（alertdelivery / budget / chat / ratelimit / repository / service /
 * usage / retrieval / document* 等非 tenant 包）不得 import
 * {@code com.springairag.core.tenant.PlatformCapabilityRegistry} 或
 * {@code PlatformOpsService}；只有 controller / web / filter / config / observability
 * 与 tenant 包自身可以引用。</p>
 */
@DisplayName("Platform boundary (业务包不得依赖平台实现)")
class PlatformBoundaryArchTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "import\\s+com\\.springairag\\.core\\.tenant\\."
                    + "(PlatformCapabilityRegistry|PlatformOpsService)");

    private static final List<String> BUSINESS_PACKAGES = List.of(
            "alertdelivery", "apikeyalert", "budget", "chat", "ratelimit",
            "repository", "service", "usage", "retrieval", "documents",
            "documentprocessing", "evaluation", "webui");

    @Test
    @DisplayName("业务包源码不含平台实现 import")
    void businessPackagesDoNotImportPlatformImplementation() throws IOException {
        Path src = Path.of("src/main/java/com/springairag/core");
        assertTrue(Files.isDirectory(src), "core 源码根目录必须存在：" + src.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(src)) {
            List<Path> offenders = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String path = p.toString();
                        return BUSINESS_PACKAGES.stream().anyMatch(pkg -> path.contains("/" + pkg + "/"));
                    })
                    .filter(p -> {
                        try {
                            return FORBIDDEN.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertFalse(!offenders.isEmpty(),
                    "业务包依赖平台实现：" + offenders);
        }
    }
}
