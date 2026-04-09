package com.springairag.core.versioning;

import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * API version routing mapping
 *
 * <p>Custom {@link RequestMappingHandlerMapping} that scans {@link ApiVersion} annotations,
 * automatically adding /api/{version}/ prefix to request paths.
 *
 * <p>How it works:
 * <ol>
 *   <li>Detects {@link ApiVersion} annotation on class or method</li>
 *   <li>Generates independent path mapping for each version (e.g., /api/v1/rag/documents)</li>
 *   <li>Paths without version annotation remain unchanged (e.g., /actuator/*)</li>
 * </ol>
 */
public class ApiVersionRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    private static final String API_PREFIX = "/api/";

    @Override
    protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
        RequestMappingInfo info = super.getMappingForMethod(method, handlerType);
        if (info == null) return null;

        ApiVersion version = resolveVersion(method, handlerType);
        if (version == null) return info;

        Set<String> originalPatterns = info.getPatternValues();
        if (originalPatterns.isEmpty()) return info;

        String[] allVersionedPaths = buildVersionedPaths(version.value(), originalPatterns);
        return rebuildWithPaths(info, allVersionedPaths);
    }

    private ApiVersion resolveVersion(Method method, Class<?> handlerType) {
        ApiVersion methodVersion = method.getAnnotation(ApiVersion.class);
        if (methodVersion != null) return methodVersion;
        return handlerType.getAnnotation(ApiVersion.class);
    }

    private String[] buildVersionedPaths(String[] versions, Set<String> patterns) {
        String[] allVersionedPaths = new String[versions.length * patterns.size()];
        int idx = 0;
        for (String ver : versions) {
            for (String pattern : patterns) {
                allVersionedPaths[idx++] = API_PREFIX + ver + pattern;
            }
        }
        return allVersionedPaths;
    }

    private RequestMappingInfo rebuildWithPaths(RequestMappingInfo info, String[] paths) {
        return RequestMappingInfo
                .paths(paths)
                .customCondition(info.getCustomCondition())
                .methods(info.getMethodsCondition().getMethods()
                        .toArray(new org.springframework.web.bind.annotation.RequestMethod[0]))
                .params(info.getParamsCondition().getExpressions()
                        .toArray(new String[0]))
                .headers(info.getHeadersCondition().getExpressions()
                        .toArray(new String[0]))
                .consumes(info.getConsumesCondition().getConsumableMediaTypes()
                        .stream().map(Object::toString).toArray(String[]::new))
                .produces(info.getProducesCondition().getProducibleMediaTypes()
                        .stream().map(Object::toString).toArray(String[]::new))
                .build();
    }
}
