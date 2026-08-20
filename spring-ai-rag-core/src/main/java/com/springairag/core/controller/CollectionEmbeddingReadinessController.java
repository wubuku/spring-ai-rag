package com.springairag.core.controller;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.core.embeddingjob.EmbeddingJobService;
import com.springairag.api.dto.DerivationReadinessPageResponse;
import com.springairag.api.dto.DerivationReadinessResponse;
import com.springairag.core.service.DerivationIntegrityService;
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
    private DerivationIntegrityService derivationIntegrityService;

    public CollectionEmbeddingReadinessController(EmbeddingJobService embeddingJobService) {
        this.embeddingJobService = embeddingJobService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDerivationIntegrityService(DerivationIntegrityService service) {
        this.derivationIntegrityService = service;
    }

    @GetMapping("/embedding-readiness")
    public CollectionEmbeddingReadinessResponse readiness(
            @RequestParam String collectionKey) {
        return derivationIntegrityService == null
                ? embeddingJobService.readiness(collectionKey)
                : derivationIntegrityService.embeddingReadiness(collectionKey);
    }

    @GetMapping("/derivation-readiness")
    public DerivationReadinessResponse derivationReadiness(
            @RequestParam String collectionKey) {
        return requireIntegrityService().summary(collectionKey);
    }

    @GetMapping("/derivation-readiness/documents")
    public DerivationReadinessPageResponse derivationDocuments(
            @RequestParam String collectionKey,
            @RequestParam(required = false) String bucket,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return requireIntegrityService().details(collectionKey, bucket, page, size);
    }

    private DerivationIntegrityService requireIntegrityService() {
        if (derivationIntegrityService == null) {
            throw new IllegalStateException("Derivation integrity service is unavailable");
        }
        return derivationIntegrityService;
    }
}
