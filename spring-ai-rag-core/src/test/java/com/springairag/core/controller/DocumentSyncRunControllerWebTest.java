package com.springairag.core.controller;

import com.springairag.api.dto.DocumentSyncRunBatchUpsertResponse;
import com.springairag.api.dto.DocumentSyncRunResponse;
import com.springairag.api.dto.DocumentSyncRunStatusResponse;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.core.service.DocumentSyncRunService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = DocumentSyncRunController.class,
        properties = {
                "rag.cors.enabled=false",
                "rag.slo.enabled=false"
        })
@Import({GlobalExceptionHandler.class, ApiVersionConfig.class})
class DocumentSyncRunControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentSyncRunService service;

    @Test
    void beginRequiresLeaseHeaderAndBindsSnapshotContract() throws Exception {
        UUID runId = UUID.randomUUID();
        when(service.begin(any(), eq("lease-token")))
                .thenReturn(response(runId, DocumentSyncRunStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/rag/document-sync-runs")
                        .header("X-RAG-Sync-Lease", "lease-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "catalog",
                                  "sourceNamespace": "cms-main",
                                  "clientRunId": "catalog-2026-08-19",
                                  "snapshotMode": "ONLINE_CUT",
                                  "missingPolicy": "TOMBSTONE",
                                  "leaseSeconds": 900
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.snapshotMode").value("ONLINE_CUT"))
                .andExpect(jsonPath("$.missingPolicy").value("TOMBSTONE"));

        verify(service).begin(any(), eq("lease-token"));
    }

    @Test
    void batchPreviewCompleteUseSameRunLeaseAndJsonContracts() throws Exception {
        UUID runId = UUID.randomUUID();
        when(service.batchUpsert(eq(runId), eq("lease-token"), any()))
                .thenReturn(new DocumentSyncRunBatchUpsertResponse(
                        runId.toString(),
                        List.of(),
                        new DocumentSyncRunBatchUpsertResponse.Summary(
                                0, 0, 0, 0, 0)));
        when(service.preview(runId, "lease-token"))
                .thenReturn(new com.springairag.api.dto.DocumentSyncRunPreviewResponse(
                        runId.toString(), "preview-token", "fingerprint",
                        0, 0, 0, 0, 0, List.of()));
        when(service.complete(eq(runId), eq("lease-token"), any()))
                .thenReturn(response(runId, DocumentSyncRunStatus.COMPLETED));

        mockMvc.perform(post("/api/v1/rag/document-sync-runs/" + runId
                        + "/batch-upsert")
                        .header("X-RAG-Sync-Lease", "lease-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{
                                  "documentKind": "TEXT",
                                  "externalId": "article-1",
                                  "sourceRevision": "r1",
                                  "title": "Article",
                                  "content": "Searchable content"
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.summary.total").value(0));

        mockMvc.perform(post("/api/v1/rag/document-sync-runs/" + runId
                        + "/preview-missing")
                        .header("X-RAG-Sync-Lease", "lease-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewToken").value("preview-token"));

        mockMvc.perform(post("/api/v1/rag/document-sync-runs/" + runId
                        + "/complete")
                        .header("X-RAG-Sync-Lease", "lease-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"previewToken":"preview-token","confirmMissingCount":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void listRequiresCollectionScope() throws Exception {
        UUID runId = UUID.randomUUID();
        when(service.list("catalog", null, 0, 20))
                .thenReturn(new DocumentSyncRunStatusResponse(
                        List.of(response(runId, DocumentSyncRunStatus.COMPLETED)),
                        1, 0, 20));

        mockMvc.perform(get("/api/v1/rag/document-sync-runs")
                        .param("collectionKey", "catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.runs[0].status").value("COMPLETED"));
    }

    private static DocumentSyncRunResponse response(
            UUID runId,
            DocumentSyncRunStatus status) {
        return new DocumentSyncRunResponse(
                runId,
                "catalog",
                "cms-main",
                "client-run",
                DocumentSyncSnapshotMode.ONLINE_CUT,
                DocumentSyncMissingPolicy.TOMBSTONE,
                status,
                4,
                4,
                OffsetDateTime.parse("2026-08-19T12:00:00Z"),
                1,
                0,
                0,
                0,
                0,
                "/api/v1/rag/document-sync-runs/" + runId);
    }
}
