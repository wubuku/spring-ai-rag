package com.springairag.core.config;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

/**
 * Unit tests for WebUiConfig - serves pre-built WebUI static files.
 * Tests the controller endpoints that serve the React SPA index.html.
 */
@WebMvcTest(WebUiConfig.WebUiController.class)
@Import(WebUiConfig.class)
class WebUiConfigTest {

    @MockBean
    private RagProperties ragProperties;

    @MockBean
    private CorsConfig corsConfig;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootIndex_returnsHtml() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<!doctype html")));
    }

    @Test
    void webuiIndex_returnsHtml() throws Exception {
        mockMvc.perform(get("/webui"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<!doctype html")));
    }

    @Test
    void webuiIndexWithTrailingSlash_returnsHtml() throws Exception {
        mockMvc.perform(get("/webui/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<!doctype html")));
    }

    @Test
    void spaRoute_chat_returnsHtml() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<!doctype html")));
    }

    @Test
    void spaRoute_documents_returnsHtml() throws Exception {
        mockMvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<!doctype html")));
    }

    @Test
    void webuiCatchAll_documentsRoute_returnsHtml() throws Exception {
        mockMvc.perform(get("/webui/documents"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<!doctype html")));
    }

    @Test
    void webuiCatchAll_searchRoute_returnsHtml() throws Exception {
        mockMvc.perform(get("/webui/search"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<!doctype html")));
    }
}
