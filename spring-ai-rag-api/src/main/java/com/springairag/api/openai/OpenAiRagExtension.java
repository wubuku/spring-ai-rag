package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.springairag.api.dto.EvidenceAssessment;

import java.util.List;
import java.util.Map;

/**
 * Additive RAG projection for the controlled OpenAI-compatible response.
 *
 * <p>The projection contains only sources that were authorized for the
 * request and exposed in the final answer. It is not part of the standard
 * OpenAI protocol fields and may be ignored by standard clients.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiRagExtension(
        String traceId,
        EvidenceAssessment evidenceAssessment,
        List<OpenAiRagSource> sources,
        Map<String, Object> citationRegistry,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> collectionCoverage) {

    /** 保留已有 Java 调用方的四参数构造签名。 */
    public OpenAiRagExtension(
            String traceId,
            EvidenceAssessment evidenceAssessment,
            List<OpenAiRagSource> sources,
            Map<String, Object> citationRegistry) {
        this(traceId, evidenceAssessment, sources, citationRegistry, Map.of());
    }

    public OpenAiRagExtension {
        sources = sources == null ? List.of() : List.copyOf(sources);
        citationRegistry = citationRegistry == null
                ? Map.of() : Map.copyOf(citationRegistry);
        collectionCoverage = collectionCoverage == null
                ? Map.of() : Map.copyOf(collectionCoverage);
    }
}
