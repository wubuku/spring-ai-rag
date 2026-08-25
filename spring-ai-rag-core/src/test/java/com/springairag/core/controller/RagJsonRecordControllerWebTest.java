package com.springairag.core.controller;

import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.JsonRecordService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagJsonRecordController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        RagJsonRecordControllerWebTest.RagPropertiesTestConfig.class
})
class RagJsonRecordControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JsonRecordService jsonRecordService;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void upsertSerializesJsonbResponse() throws Exception {
        when(jsonRecordService.upsert(any()))
                .thenReturn(new JsonRecordUpsertResponse(
                        42L, 7L, "external-1", "CREATED",
                        true, true, 1, "NOT_REQUESTED", null, null));

        mockMvc.perform(post("/api/v1/rag/json-records/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "collectionKey": "customer-42:records:v1",
                                  "externalId": "external-1",
                                  "title": "Record",
                                  "retrievalText": "A natural language description.",
                                  "jsonbPayload": {"id": 1, "tags": ["rag", "json"]}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(42))
                .andExpect(jsonPath("$.jsonbPayload").doesNotExist());
    }

    @Test
    void blankRetrievalTextIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/rag/json-records/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "collectionKey": "customer-42:records:v1",
                                  "externalId": "external-1",
                                  "title": "Record",
                                  "retrievalText": " ",
                                  "jsonbPayload": {"id": 1}
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchReturnsTypedResults() throws Exception {
        when(jsonRecordService.search(any()))
                .thenReturn(new JsonRecordSearchResponse("spring", java.util.List.of()));

        mockMvc.perform(post("/api/v1/rag/json-records/search")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "query": "spring",
                                  "collectionKeys": ["customer-42:records:v1"],
                                  "payloadContains": {
                                    "status": "active"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("spring"))
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results").isEmpty());

        ArgumentCaptor<com.springairag.api.dto.JsonRecordSearchRequest> request =
                ArgumentCaptor.forClass(
                        com.springairag.api.dto.JsonRecordSearchRequest.class);
        verify(jsonRecordService).search(request.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "active",
                request.getValue().getPayloadContains()
                        .path("status").asText());
    }

    @Test
    void lookupAcceptsMaximumIdentityLengths() throws Exception {
        String namespace = "n".repeat(128);
        String externalId = "e".repeat(255);

        mockMvc.perform(get("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", "records:v1")
                        .param("sourceNamespace", namespace)
                        .param("externalId", externalId))
                .andExpect(status().isOk());

        verify(jsonRecordService).getByExternalIdentity(
                "records:v1", namespace, externalId);
    }

    @Test
    void lookupRejectsInvalidIdentityBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", "records:v1")
                        .param("sourceNamespace", "n".repeat(129))
                        .param("externalId", "record-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", "records:v1")
                        .param("sourceNamespace", "namespace\tvalue")
                        .param("externalId", "record-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", "records:v1")
                        .param("sourceNamespace", "default")
                        .param("externalId", "e".repeat(256)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(jsonRecordService);
    }

    @Test
    void tombstoneAcceptsValidParameters() throws Exception {
        mockMvc.perform(delete("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", "records:v1")
                        .param("sourceNamespace", "business-client.v1")
                        .param("externalId", "record-1")
                        .param("sourceRevision", "rev-2")
                        .param("expectedSourceRevision", "rev-1"))
                .andExpect(status().isOk());

        verify(jsonRecordService).sourceDelete(
                "records:v1",
                "business-client.v1",
                "record-1",
                "rev-2",
                "rev-1");
    }

    @Test
    void tombstoneRejectsOverlongRevisionsBeforeServiceCall() throws Exception {
        mockMvc.perform(delete("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", "records:v1")
                        .param("externalId", "record-1")
                        .param("sourceRevision", "r".repeat(256)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", "records:v1")
                        .param("externalId", "record-1")
                        .param("sourceRevision", "rev-2")
                        .param("expectedSourceRevision", "r".repeat(256)))
                .andExpect(status().isBadRequest());

        verify(jsonRecordService, never()).sourceDelete(
                any(), any(), any(), any(), any());
    }
}
