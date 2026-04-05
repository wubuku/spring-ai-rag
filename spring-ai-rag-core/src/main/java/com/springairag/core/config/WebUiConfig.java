package com.springairag.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * WebUI static files configuration.
 *
 * <p>Serves the pre-built WebUI from {@code classpath:/static/webui/}.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve /webui/assets/** from classpath:/static/webui/assets/
        registry.addResourceHandler("/webui/assets/**")
                .addResourceLocations("classpath:/static/webui/assets/");
    }

    /**
     * Serves /webui and /webui/ as the React SPA entry point.
     */
    @RestController
    public static class WebUiController {

        @GetMapping(value = {"/webui", "/webui/"}, produces = MediaType.TEXT_HTML_VALUE)
        public String webuiIndex() throws IOException {
            ClassPathResource r = new ClassPathResource("/static/webui/index.html");
            return new String(r.getInputStream().readAllBytes());
        }
    }
}
