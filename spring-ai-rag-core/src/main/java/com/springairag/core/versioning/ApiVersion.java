package com.springairag.core.versioning;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API version annotation
 *
 * <p>Marks the API version supported by a controller or method. Used with {@link ApiVersionRequestMappingHandlerMapping},
 * automatically adding /api/{version}/ prefix to request mappings.
 *
 * <p>Usage:
 * <pre>
 * {@literal @}RestController
 * {@literal @}ApiVersion("v1")
 * {@literal @}RequestMapping("/rag/documents")
 * public class RagDocumentController { ... }
 *
 * // Final path: /api/v1/rag/documents
 * </pre>
 *
 * <p>Supports multiple versions coexisting:
 * <pre>
 * {@literal @}ApiVersion({"v1", "v2"})
 * // Matches both /api/v1/... and /api/v2/...
 * </pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiVersion {

    /**
     * Supported API version array (e.g., "v1", "v2")
     */
    String[] value();

    /**
     * Whether this version is deprecated (will add Deprecation hint in response header)
     */
    boolean deprecated() default false;
}
