package com.springairag.core.controller;

import com.springairag.api.dto.DocumentChunkPreviewResponse;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.DocumentChunkingService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.versioning.ApiVersion;
import com.springairag.core.security.ApiRoutePolicy;
import com.springairag.documents.chunk.ChunkDiagnostics;
import com.springairag.documents.chunk.ChunkingException;
import com.springairag.documents.chunk.ChunkingResult;
import com.springairag.documents.chunk.TextChunk;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 沿用文档读取授权的只读分块诊断。 */
@RestController
@ApiVersion("v1")
@ApiRoutePolicy
@RequestMapping("/rag/documents")
public class DocumentChunkPreviewController {
    private final RagDocumentRepository repository;
    private final DocumentChunkingService chunkingService;
    private final DocumentDerivationDescriptorProvider descriptorProvider;

    public DocumentChunkPreviewController(RagDocumentRepository repository,
            DocumentChunkingService chunkingService, DocumentDerivationDescriptorProvider descriptorProvider) {
        this.repository = repository;
        this.chunkingService = chunkingService;
        this.descriptorProvider = descriptorProvider;
    }

    @GetMapping("/{id}/chunks/preview")
    @Operation(summary = "只读预览当前文档的分块及诊断")
    public DocumentChunkPreviewResponse preview(@PathVariable Long id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new ChunkingException("CHUNK_PREVIEW_INVALID_PAGE");
        }
        RagDocument document = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        ApiKeyCollectionAccess.requireDocumentAccess(document, ApiKeyCollectionAccess.currentPolicy());
        ChunkingResult result = chunkingService.preview(document);
        List<DocumentChunkPreviewResponse.Chunk> page = new ArrayList<>();
        int from = Math.min(offset, result.chunks().size());
        int to = from + Math.min(limit, result.chunks().size() - from);
        for (int index = from; index < to; index++) {
            TextChunk chunk = result.chunks().get(index);
            ChunkingResult.ChunkMetadata metadata = result.metadata().get(index);
            page.add(new DocumentChunkPreviewResponse.Chunk(index, chunk.text(), chunk.startPos(), chunk.endPos(),
                    metadata.kind(), metadata.titlePath(), metadata.generatedChars()));
        }
        ChunkDiagnostics diagnostic = result.diagnostics();
        DocumentDerivationDescriptorProvider.Descriptor descriptor = descriptorProvider.describe(document);
        return new DocumentChunkPreviewResponse(document.getId(), document.getDocumentRevision(),
                descriptor.documentKind(), chunkingService.profileFor(document), descriptor.chunkerVersion(),
                result.chunks().size(), offset, limit, to < result.chunks().size(),
                new DocumentChunkPreviewResponse.Diagnostics(diagnostic.valid(), diagnostic.originalChars(),
                        diagnostic.coveredNonWhitespaceChars(), diagnostic.minPayloadChars(),
                        diagnostic.maxPayloadChars(), diagnostic.generatedChars(), diagnostic.overlapChars(),
                        diagnostic.warnings(), diagnostic.violations()), page);
    }
}
