package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import org.springframework.stereotype.Component;

/**
 * 派生输入身份的单一来源，供调度、提交门、缓存和检索 freshness 共用。
 */
@Component
public class DocumentDerivationDescriptorProvider {

    private final RagProperties properties;

    public DocumentDerivationDescriptorProvider(RagProperties properties) {
        this.properties = properties;
    }

    public Descriptor describe(RagDocument document) {
        return RagDocument.JSON_RECORD.equals(document.getDocumentType())
                ? jsonRecordDescriptor() : textDescriptor();
    }

    public Descriptor textDescriptor() {
        return new Descriptor(
                "TEXT",
                "hierarchical-v2:"
                        + properties.getChunk().getDefaultChunkSize() + ":"
                        + properties.getChunk().getMinChunkSize() + ":"
                        + properties.getChunk().getDefaultChunkOverlap());
    }

    public Descriptor jsonRecordDescriptor() {
        return new Descriptor("JSON_RECORD", "json-record-v1:single");
    }

    public record Descriptor(String documentKind, String chunkerVersion) {
    }
}
