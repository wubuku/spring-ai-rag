package com.springairag.core.controller;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.core.embeddingjob.EmbeddingJobService;
import com.springairag.core.versioning.ApiVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collection 嵌入就绪只读摘要。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/collections")
public class CollectionEmbeddingReadinessController {

    private final EmbeddingJobService embeddingJobService;

    public CollectionEmbeddingReadinessController(EmbeddingJobService embeddingJobService) {
        this.embeddingJobService = embeddingJobService;
    }

    @GetMapping("/embedding-readiness")
    public CollectionEmbeddingReadinessResponse readiness(
            @RequestParam String collectionKey) {
        return embeddingJobService.readiness(collectionKey);
    }
}
