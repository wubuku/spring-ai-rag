package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ClientErrorRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.ClientErrorService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClientErrorController.class)
@Import({ApiVersionConfig.class, ClientErrorControllerTest.RagPropertiesTestConfig.class})
class ClientErrorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ClientErrorService clientErrorService;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void reportError_validRequest_returnsAccepted() throws Exception {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("Error");
        request.setErrorMessage("Cannot read properties of undefined");
        request.setStackTrace("TypeError: Cannot read properties of undefined\n    at Foo (foo.js:10)");
        request.setPageUrl("/webui/chat");
        request.setSessionId("session-abc123");

        doNothing().when(clientErrorService).recordError(any(ClientErrorRequest.class));

        mockMvc.perform(post("/api/v1/rag/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        verify(clientErrorService).recordError(any(ClientErrorRequest.class));
    }

    @Test
    void reportError_missingErrorType_returnsBadRequest() throws Exception {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorMessage("Some error");

        mockMvc.perform(post("/api/v1/rag/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(clientErrorService, never()).recordError(any());
    }

    @Test
    void reportError_missingErrorMessage_returnsBadRequest() throws Exception {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("Error");

        mockMvc.perform(post("/api/v1/rag/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(clientErrorService, never()).recordError(any());
    }

    @Test
    void reportError_emptyRequest_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/rag/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(clientErrorService, never()).recordError(any());
    }

    @Test
    void reportError_userAgentFromHeader_isPassedToService() throws Exception {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("Error");
        request.setErrorMessage("Test error");

        doNothing().when(clientErrorService).recordError(any(ClientErrorRequest.class));

        mockMvc.perform(post("/api/v1/rag/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "Mozilla/5.0 TestBrowser")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        verify(clientErrorService).recordError(argThat(req ->
                "Mozilla/5.0 TestBrowser".equals(req.getUserAgent())));
    }

    @Test
    void getErrorCount_returnsCount() throws Exception {
        when(clientErrorService.getErrorCount()).thenReturn(42L);

        mockMvc.perform(get("/api/v1/rag/client-errors/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(42));

        verify(clientErrorService).getErrorCount();
    }
}
