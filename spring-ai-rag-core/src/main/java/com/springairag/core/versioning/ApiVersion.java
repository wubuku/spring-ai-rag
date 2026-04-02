package com.springairag.core.versioning;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API 版本注解
 *
 * <p>标记控制器或方法支持的 API 版本。配合 {@link ApiVersionRequestMappingHandlerMapping} 使用，
 * 自动为请求映射添加 /api/{version}/ 前缀。
 *
 * <p>使用方式：
 * <pre>
 * {@literal @}RestController
 * {@literal @}ApiVersion("v1")
 * {@literal @}RequestMapping("/rag/documents")
 * public class RagDocumentController { ... }
 *
 * // 最终路径: /api/v1/rag/documents
 * </pre>
 *
 * <p>支持多版本共存：
 * <pre>
 * {@literal @}ApiVersion({"v1", "v2"})
 * // 同时匹配 /api/v1/... 和 /api/v2/...
 * </pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiVersion {

    /**
     * 支持的 API 版本数组（如 "v1", "v2"）
     */
    String[] value();

    /**
     * 是否废弃此版本（将在响应头中添加 Deprecation 提示）
     */
    boolean deprecated() default false;
}
