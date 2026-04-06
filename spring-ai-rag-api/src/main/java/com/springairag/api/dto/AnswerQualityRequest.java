package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request for LLM-as-judge answer quality evaluation.
 *
 * <p>Provides query, retrieved context, and generated answer for the LLM to evaluate
 * groundedness (is the answer supported by the context?), relevance (does it answer the query?),
 * and helpfulness (is it useful to the user?).
 */
@Schema(description = "LLM-as-judge answer quality evaluation request")
public class AnswerQualityRequest {

    @NotBlank(message = "Query must not be blank")
    @Size(max = 2000, message = "Query must be at most 2000 characters")
    @Schema(description = "Original user query", example = "What is Spring AI?", maxLength = 2000)
    private String query;

    @NotBlank(message = "Context must not be blank")
    @Size(max = 8000, message = "Context must be at most 8000 characters")
    @Schema(description = "Retrieved context documents that form the basis for the answer", example = "Spring AI is a framework...", maxLength = 8000)
    private String context;

    @NotBlank(message = "Answer must not be blank")
    @Size(max = 4000, message = "Answer must be at most 4000 characters")
    @Schema(description = "Generated RAG answer to evaluate", example = "Spring AI is a framework for building AI applications...", maxLength = 4000)
    private String answer;

    public AnswerQualityRequest() {
    }

    public AnswerQualityRequest(String query, String context, String answer) {
        this.query = query;
        this.context = context;
        this.answer = answer;
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
}
