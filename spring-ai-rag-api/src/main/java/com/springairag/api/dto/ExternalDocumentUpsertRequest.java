package com.springairag.api.dto;

import com.springairag.api.validation.ValidCollectionKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Stable-identity upsert request for ordinary externally managed documents.
 */
@Schema(description = "External document upsert request")
public class ExternalDocumentUpsertRequest {

    @NotBlank
    @ValidCollectionKey
    @Schema(description = "Stable target Collection key", requiredMode = Schema.RequiredMode.REQUIRED)
    private String collectionKey;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Caller-supplied stable document identity", requiredMode = Schema.RequiredMode.REQUIRED)
    private String externalId;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Opaque source revision token", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceRevision;

    @Size(max = 255)
    @Schema(description = "Expected current source revision for compare-and-set")
    private String expectedSourceRevision;

    @NotBlank
    @Size(max = 255)
    private String title;

    @NotBlank
    @Size(max = 1_000_000)
    private String content;

    @Size(max = 255)
    private String source;

    @Size(max = 50)
    private String documentType = "text";

    private Map<String, Object> metadata;

    @Schema(description = "Generate embedding after persistence", defaultValue = "true")
    private boolean embed = true;

    public String getCollectionKey() { return collectionKey; }
    public void setCollectionKey(String collectionKey) { this.collectionKey = collectionKey; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(String sourceRevision) { this.sourceRevision = sourceRevision; }

    public String getExpectedSourceRevision() { return expectedSourceRevision; }
    public void setExpectedSourceRevision(String expectedSourceRevision) {
        this.expectedSourceRevision = expectedSourceRevision;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public boolean isEmbed() { return embed; }
    public void setEmbed(boolean embed) { this.embed = embed; }
}
