package com.springairag.core.rag;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalResult;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single mapping point between project retrieval results and Spring AI Documents.
 */
@Component
public class RetrievalDocumentMapper {

    public Document toDocument(RetrievalResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.getMetadata() != null) {
            result.getMetadata().forEach((key, value) -> {
                if (isAllowedMetadata(key)) {
                    metadata.put(key, value);
                }
            });
        }
        metadata.put("documentId", result.getDocumentId());
        metadata.put("chunkIndex", result.getChunkIndex());
        metadata.put("title", result.getTitle());
        metadata.put("score", result.getScore());
        metadata.put("vectorScore", result.getVectorScore());
        metadata.put("fulltextScore", result.getFulltextScore());
        if (result.getOriginalFilename() != null) {
            metadata.put("originalFilename", result.getOriginalFilename());
        }
        if (metadata.get("documentType") == null) {
            metadata.put("documentType", "TEXT");
        }
        return Document.builder()
                .id(result.getDocumentId() + ":" + result.getChunkIndex())
                .text(result.getChunkText() != null ? result.getChunkText() : "")
                .metadata(metadata)
                .score(result.getScore())
                .build();
    }

    public ChatSource toChatSource(RetrievalResult result, int position) {
        return toChatSource(result, "S" + position);
    }

    public ChatSource toChatSource(RetrievalResult result, String citationId) {
        ChatSource source = new ChatSource();
        source.setCitationId(citationId);
        source.setDocumentId(result.getDocumentId());
        source.setChunkIndex(result.getChunkIndex());
        source.setTitle(result.getTitle() != null ? result.getTitle() : result.getDocumentId());
        source.setChunkText(truncate(result.getChunkText(), 2000));
        source.setScore(result.getScore());
        source.setVectorScore(result.getVectorScore());
        source.setFulltextScore(result.getFulltextScore());
        source.setOriginalFilename(result.getOriginalFilename());
        source.setDocumentType(stringValue(result.getMetadata(), "documentType"));
        source.setCollectionKey(stringValue(result.getMetadata(), "collectionKey"));
        source.setSourceType(result.getSource() != null && result.getSource().startsWith("pdf-import:")
                ? "PDF" : "DOCUMENT");
        source.setMetadata(allowedMetadata(result.getMetadata()));
        return source;
    }

    public ChatSource toChatSource(Document document, int position) {
        Map<String, Object> metadata = document.getMetadata();
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(stringValue(metadata, "documentId", document.getId()));
        result.setChunkIndex(intValue(metadata, "chunkIndex"));
        result.setTitle(stringValue(metadata, "title", result.getDocumentId()));
        result.setChunkText(document.getText());
        result.setScore(numberValue(metadata, "score"));
        result.setVectorScore(numberValue(metadata, "vectorScore"));
        result.setFulltextScore(numberValue(metadata, "fulltextScore"));
        result.setOriginalFilename(stringValue(metadata, "originalFilename", null));
        result.setMetadata(metadata);
        return toChatSource(result, position);
    }

    private Map<String, Object> allowedMetadata(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (input != null) {
            input.forEach((key, value) -> {
                if (isAllowedMetadata(key)) {
                    output.put(key, value);
                }
            });
        }
        return output.isEmpty() ? null : output;
    }

    private boolean isAllowedMetadata(String key) {
        return key != null && switch (key) {
            case "documentType", "collectionKey", "collectionId", "sourceType",
                    "language", "title", "originalFilename" -> true;
            default -> false;
        };
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) {
            return null;
        }
        return String.valueOf(values.get(key));
    }

    private String stringValue(Map<String, Object> values, String key, String fallback) {
        String value = stringValue(values, key);
        return value != null ? value : fallback;
    }

    private int intValue(Map<String, Object> values, String key) {
        Object value = values != null ? values.get(key) : null;
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double numberValue(Map<String, Object> values, String key) {
        Object value = values != null ? values.get(key) : null;
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
