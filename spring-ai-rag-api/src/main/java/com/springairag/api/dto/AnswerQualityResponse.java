package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.ZonedDateTime;

/**
 * Response from LLM-as-judge answer quality evaluation.
 *
 * <p>Three dimensions are scored 1-5:
 * <ul>
 *   <li><b>Groundedness</b>: Is the answer supported/founded by the provided context?</li>
 *   <li><b>Relevance</b>: Does the answer address the user's query?</li>
 *   <li><b>Helpfulness</b>: Is the answer useful and clear for the end user?</li>
 * </ul>
 */
@Schema(description = "LLM-as-judge answer quality evaluation result")
public class AnswerQualityResponse {

    @Schema(description = "Groundedness score: is the answer supported by the context? (1-5)", example = "4")
    private int groundedness;

    @Schema(description = "Relevance score: does the answer address the query? (1-5)", example = "5")
    private int relevance;

    @Schema(description = "Helpfulness score: is the answer useful and clear? (1-5)", example = "4")
    private int helpfulness;

    @Schema(description = "Brief reasoning for the evaluation", example = "The answer correctly summarizes the context but introduces a concept not present in the retrieved documents.")
    private String reasoning;

    @Schema(description = "Overall recommendation: ACCEPT, REVISION, or REJECT", example = "ACCEPT")
    private String recommendation;

    @Schema(description = "Timestamp when evaluation was performed", example = "2026-04-07T00:30:00Z")
    private ZonedDateTime evaluatedAt;

    public AnswerQualityResponse() {
    }

    public AnswerQualityResponse(int groundedness, int relevance, int helpfulness,
                                  String reasoning, String recommendation, ZonedDateTime evaluatedAt) {
        this.groundedness = groundedness;
        this.relevance = relevance;
        this.helpfulness = helpfulness;
        this.reasoning = reasoning;
        this.recommendation = recommendation;
        this.evaluatedAt = evaluatedAt;
    }

    public int getGroundedness() { return groundedness; }
    public void setGroundedness(int groundedness) { this.groundedness = groundedness; }
    public int getRelevance() { return relevance; }
    public void setRelevance(int relevance) { this.relevance = relevance; }
    public int getHelpfulness() { return helpfulness; }
    public void setHelpfulness(int helpfulness) { this.helpfulness = helpfulness; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public ZonedDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(ZonedDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
