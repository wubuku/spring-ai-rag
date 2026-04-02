package com.springairag.core.versioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiVersionRequestMappingHandlerMapping 单元测试
 */
class ApiVersionRequestMappingHandlerMappingTest {

    private final ApiVersionRequestMappingHandlerMapping mapping =
            new ApiVersionRequestMappingHandlerMapping();

    @Test
    @DisplayName("无 @ApiVersion 注解时返回原始映射")
    void getMappingForMethod_noAnnotation_returnsOriginal() throws Exception {
        Method method = NoVersionController.class.getMethod("handle");
        RequestMappingInfo info = mapping.getMappingForMethod(method, NoVersionController.class);
        assertNotNull(info);
        assertEquals("/public", info.getPatternValues().iterator().next());
    }

    @Test
    @DisplayName("类级 @ApiVersion 生成 /api/v1/ 前缀")
    void getMappingForMethod_classAnnotation_addsPrefix() throws Exception {
        Method method = V1Controller.class.getMethod("handle");
        // Debug: verify annotation is found
        ApiVersion ann = V1Controller.class.getAnnotation(ApiVersion.class);
        assertNotNull(ann, "@ApiVersion should be found on V1Controller");
        assertEquals("v1", ann.value()[0]);

        RequestMappingInfo info = mapping.getMappingForMethod(method, V1Controller.class);
        assertNotNull(info);
        assertEquals("/api/v1/resource", info.getPatternValues().iterator().next());
    }

    @Test
    @DisplayName("方法级 @ApiVersion 优先于类级")
    void getMappingForMethod_methodAnnotationOverridesClass() throws Exception {
        Method method = OverrideController.class.getMethod("v2Only");
        RequestMappingInfo info = mapping.getMappingForMethod(method, OverrideController.class);
        assertNotNull(info);
        assertTrue(info.getPatternValues().contains("/api/v2/resource"));
    }

    @Test
    @DisplayName("多版本数组生成多个路径映射")
    void getMappingForMethod_multipleVersions() throws Exception {
        Method method = MultiVersionController.class.getMethod("handle");
        RequestMappingInfo info = mapping.getMappingForMethod(method, MultiVersionController.class);
        assertNotNull(info);
        var patterns = info.getPatternValues();
        assertTrue(patterns.contains("/api/v1/shared"), "Expected /api/v1/shared, got: " + patterns);
        assertTrue(patterns.contains("/api/v2/shared"), "Expected /api/v2/shared, got: " + patterns);
    }

    @Test
    @DisplayName("@ApiVersion deprecated 属性可正确读取")
    void apiVersion_deprecated_readable() {
        ApiVersion ann = DeprecatedController.class.getAnnotation(ApiVersion.class);
        assertNotNull(ann);
        assertTrue(ann.deprecated());
    }

    // ==================== 测试用 Controller 类 ====================

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/public")
    static class NoVersionController {
        @org.springframework.web.bind.annotation.GetMapping
        public String handle() { return "ok"; }
    }

    @org.springframework.web.bind.annotation.RestController
    @ApiVersion("v1")
    @org.springframework.web.bind.annotation.RequestMapping("/resource")
    static class V1Controller {
        @org.springframework.web.bind.annotation.GetMapping
        public String handle() { return "ok"; }
    }

    @org.springframework.web.bind.annotation.RestController
    @ApiVersion("v1")
    @org.springframework.web.bind.annotation.RequestMapping("/resource")
    static class OverrideController {
        @ApiVersion("v2")
        @org.springframework.web.bind.annotation.GetMapping
        public String v2Only() { return "ok"; }
    }

    @org.springframework.web.bind.annotation.RestController
    @ApiVersion({"v1", "v2"})
    @org.springframework.web.bind.annotation.RequestMapping("/shared")
    static class MultiVersionController {
        @org.springframework.web.bind.annotation.GetMapping
        public String handle() { return "ok"; }
    }

    @org.springframework.web.bind.annotation.RestController
    @ApiVersion(value = "v1", deprecated = true)
    @org.springframework.web.bind.annotation.RequestMapping("/old")
    static class DeprecatedController {
        @org.springframework.web.bind.annotation.GetMapping
        public String handle() { return "ok"; }
    }
}
