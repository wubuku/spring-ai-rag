package com.springairag.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * WebUI static files configuration.
 *
 * <p>Serves the pre-built WebUI from {@code classpath:/static/webui/}.
 * All /webui/* paths (except /webui/assets/*) serve the React SPA index.html
 * so that client-side React Router can handle routing.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve /webui/assets/** from classpath:/static/webui/assets/
        // Explicitly set the order to ensure it runs AFTER the controller catch-all
        registry.addResourceHandler("/webui/assets/**")
                .addResourceLocations("classpath:/static/webui/assets/");
    }

    /**
     * Serves /webui and /webui/ as the React SPA entry point.
     * Also handles any /webui/{path} to support SPA client-side routing.
     */
    @RestController
    public static class WebUiController {

        private static final String WEBUI_INDEX;

        static {
            try {
                WEBUI_INDEX = new String(
                    new ClassPathResource("/static/webui/index.html")
                        .getInputStream().readAllBytes()
                );
            } catch (IOException e) {
                throw new RuntimeException("Failed to load WebUI index.html", e);
            }
        }

        @GetMapping(value = {"/webui", "/webui/"}, produces = MediaType.TEXT_HTML_VALUE)
        public String webuiIndex() {
            return WEBUI_INDEX;
        }

        // Catch-all for /webui/* paths (but not /webui/assets/* which is handled by ResourceHandler)
        @GetMapping(value = "/webui/{path}", produces = MediaType.TEXT_HTML_VALUE)
        public String webuiCatchAll(@PathVariable String path) {
            // Exclude assets paths - those are served by ResourceHandler
            if (path.startsWith("assets/")) {
                // This should be handled by ResourceHandler, not this controller
                // Return index.html as fallback (ResourceHandler should have handled it)
                return WEBUI_INDEX;
            }
            return WEBUI_INDEX;
        }

        /**
         * Catch-all for SPA client-side routes at root level (/chat, /documents, etc.).
         * @param path the URL path (e.g. "chat", "documents", "search")
         * @return the React SPA index.html
         */
        @GetMapping(value = "/{path}", produces = MediaType.TEXT_HTML_VALUE)
        public String spaCatchAll(@PathVariable String path) {
            return WEBUI_INDEX;
        }

        // Also handle root "/" -> serve index.html
        @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
        public String rootIndex() {
            return WEBUI_INDEX;
        }
    }
}
