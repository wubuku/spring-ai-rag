package com.springairag.core.controller;

import com.springairag.api.dto.EmbeddingJobBatchResponse;
import com.springairag.api.dto.EmbeddingJobCreateRequest;
import com.springairag.api.dto.EmbeddingJobPageResponse;
import com.springairag.api.dto.EmbeddingJobResponse;
import com.springairag.core.embeddingjob.EmbeddingJobService;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.versioning.ApiVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 持久化 embedding/reindex job API。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/embedding-jobs")
public class EmbeddingJobController {

    private final EmbeddingJobService service;

    public EmbeddingJobController(EmbeddingJobService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EmbeddingJobBatchResponse> create(
            @RequestBody EmbeddingJobCreateRequest request) {
        return ResponseEntity.accepted().body(service.create(request));
    }

    @GetMapping("/{id}")
    public EmbeddingJobResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public EmbeddingJobPageResponse list(
            @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) EmbeddingJobStatus status,
            @RequestParam(required = false) String collectionKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listPage(batchId, status, collectionKey, page, size);
    }

    @PostMapping("/{id}/cancel")
    public EmbeddingJobResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/retry")
    public EmbeddingJobResponse retry(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer maxAttempts) {
        return service.retry(id, maxAttempts);
    }
}
