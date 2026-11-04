package com.springairag.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * WebUI static files configuration.
 *
 * <p>Serves the pre-built WebUI from {@code classpath:/static/webui/}.
 * All /webui/* paths (except static files) serve the React SPA index.html
 * so that client-side React Router can handle routing.
 *
 * <p>缓存策略：
 * <ul>
 *   <li>{@code /webui/assets/**} 是内容 hash 文件名，长缓存并标记 immutable；</li>
 *   <li>{@code index.html} 使用 {@code no-cache, must-revalidate} + 内容派生 ETag，
 *       未 hash 的静态文件（{@code theme-init.js}、favicon）使用
 *       {@code no-cache, must-revalidate} + {@code Last-Modified}；浏览器每次导航
 *       都会条件请求，命中则 304。</li>
 * </ul>
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    private static final Duration HASHED_ASSET_MAX_AGE = Duration.ofDays(365);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 内容 hash 资源：一年长缓存 + immutable，升级后 URL 变化即可拿到新文件。
        registry.addResourceHandler("/webui/assets/**")
                .addResourceLocations("classpath:/static/webui/assets/")
                .setCacheControl(CacheControl.maxAge(HASHED_ASSET_MAX_AGE)
                        .cachePublic()
                        .immutable());

        // 未 hash 的静态文件（theme-init.js / favicon.svg / icons.svg）：每次使用前校验，
        // 避免旧内容长期驻留。资源映射必须带通配符才能推导出文件相对路径，
        // 因此用 /webui/** 并由 SPA controller 的负向断言排除这些文件名；
        // 该映射注册在 assets 之后，assets 请求仍由上面的 immutable 规则优先处理。
        registry.addResourceHandler("/webui/**")
                .addResourceLocations("classpath:/static/webui/")
                .setCacheControl(CacheControl.noCache().mustRevalidate());
    }

    /**
     * Serves /webui and /webui/ as the React SPA entry point.
     * Also handles any /webui/{path} to support SPA client-side routing.
     */
    @RestController
    public static class WebUiController {

        private static final String WEBUI_INDEX;
        private static final String WEBUI_INDEX_ETAG;

        /** 未 hash 的静态文件由 ResourceHandler 提供，不能被 SPA fallback 截走。 */
        private static final String STATIC_FILE_EXCLUSION =
                "(?!theme-init\\.js$|favicon\\.svg$|icons\\.svg$).*";

        static {
            try {
                WEBUI_INDEX = new String(
                    new ClassPathResource("/static/webui/index.html")
                        .getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
                );
                WEBUI_INDEX_ETAG = "\"" + sha256Prefix(WEBUI_INDEX) + "\"";
            } catch (IOException e) {
                throw new RuntimeException("Failed to load WebUI index.html", e);
            }
        }

        private static String sha256Prefix(String content) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest, 0, 16);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        private static boolean etagMatches(String ifNoneMatch, String etag) {
            if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
                return false;
            }
            for (String candidate : ifNoneMatch.split(",")) {
                String normalized = candidate.trim();
                if (normalized.startsWith("W/")) {
                    normalized = normalized.substring(2);
                }
                if (normalized.equals(etag)) {
                    return true;
                }
            }
            return false;
        }

        private ResponseEntity<String> indexResponse(String ifNoneMatch) {
            CacheControl cacheControl = CacheControl.noCache().mustRevalidate();
            if (etagMatches(ifNoneMatch, WEBUI_INDEX_ETAG)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag(WEBUI_INDEX_ETAG)
                        .cacheControl(cacheControl)
                        .build();
            }
            return ResponseEntity.ok()
                    .eTag(WEBUI_INDEX_ETAG)
                    .cacheControl(cacheControl)
                    .contentType(MediaType.TEXT_HTML)
                    .body(WEBUI_INDEX);
        }

        @GetMapping(value = {"/webui", "/webui/"})
        public ResponseEntity<String> webuiIndex(
                @org.springframework.web.bind.annotation.RequestHeader(
                        value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
            return indexResponse(ifNoneMatch);
        }

        // Catch-all for /webui/* paths（静态文件名除外）。
        @GetMapping(value = "/webui/{path:" + STATIC_FILE_EXCLUSION + "}")
        public ResponseEntity<String> webuiCatchAll(
                @PathVariable String path,
                @org.springframework.web.bind.annotation.RequestHeader(
                        value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
            return indexResponse(ifNoneMatch);
        }

        /**
         * Serves known two-segment WebUI routes without intercepting static assets.
         */
        @GetMapping(value = {"/webui/chat/{identifier}", "/webui/abtest/{identifier}"})
        public ResponseEntity<String> webuiNestedRoute(
                @PathVariable String identifier,
                @org.springframework.web.bind.annotation.RequestHeader(
                        value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
            return indexResponse(ifNoneMatch);
        }

        /**
         * Catch-all for SPA client-side routes at root level (/chat, /documents, etc.).
         * @param path the URL path (e.g. "chat", "documents", "search")
         * @return the React SPA index.html
         */
        @GetMapping(value = "/{path}")
        public ResponseEntity<String> spaCatchAll(
                @PathVariable String path,
                @org.springframework.web.bind.annotation.RequestHeader(
                        value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
            return indexResponse(ifNoneMatch);
        }

        // Also handle root "/" -> serve index.html
        @GetMapping(value = "/")
        public ResponseEntity<String> rootIndex(
                @org.springframework.web.bind.annotation.RequestHeader(
                        value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
            return indexResponse(ifNoneMatch);
        }
    }
}
