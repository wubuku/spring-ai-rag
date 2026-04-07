package com.springairag.core.controller;

import com.springairag.api.dto.AnswerQualityRequest;
import com.springairag.api.dto.AnswerQualityResponse;
import com.springairag.api.dto.EvaluateRequest;
import com.springairag.api.dto.FeedbackRequest;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.entity.RagUserFeedback;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.UserFeedbackService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;

import java.util.List;
import java.util.Map;

/**
 * Retrieval evaluation controller
 *
 * <p>Provides REST API for retrieval quality evaluation, supporting single evaluation, batch evaluation, report queries, etc.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/evaluation")
@Tag(name = "RAG Evaluation", description = "Retrieval quality evaluation (Precision@K, Recall@K, MRR, NDCG)")
public class EvaluationController {

    private static final String ENTITY_USER_FEEDBACK = "UserFeedback";

    private final RetrievalEvaluationService evaluationService;
    private final UserFeedbackService userFeedbackService;
    private final AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public EvaluationController(RetrievalEvaluationService evaluationService,
                                UserFeedbackService userFeedbackService,
                                @Autowired(required = false) AuditLogService auditLogService) {
        this.evaluationService = evaluationService;
        this.userFeedbackService = userFeedbackService;
        this.auditLogService = auditLogService;
    }

    /**
     * Single evaluation
     */
    @Operation(summary = "Single retrieval evaluation", description = "Submit query and retrieval results for quality evaluation, computing Precision@K, MRR, NDCG, etc.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Evaluation succeeded"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    @PostMapping("/evaluate")
    public ResponseEntity<RagRetrievalEvaluation> evaluate(@Valid @RequestBody EvaluateRequest request) {
        RagRetrievalEvaluation result = evaluationService.evaluate(
                request.getQuery(),
                request.getRetrievedDocIds(),
                request.getRelevantDocIds(),
                request.getEvaluationMethod(),
                request.getEvaluatorId()
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Batch evaluation
     */
    @Operation(summary = "Batch retrieval evaluation", description = "Submit multiple evaluation cases for batch processing")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Batch evaluation succeeded"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    @PostMapping("/batch")
    public ResponseEntity<List<RagRetrievalEvaluation>> batchEvaluate(
            @Valid @RequestBody List<EvaluateRequest> requests) {
        List<RetrievalEvaluationService.EvaluationCase> cases = requests.stream()
                .map(this::toEvaluationCase)
                .toList();
        List<RagRetrievalEvaluation> results = evaluationService.batchEvaluate(cases);
        return ResponseEntity.ok(results);
    }

    // ==================== LLM-as-judge Answer Quality ====================

    /**
     * LLM-as-judge answer quality evaluation
     */
    @Operation(summary = "Answer quality evaluation (LLM-as-judge)",
            description = "Uses an LLM to evaluate a RAG answer across three dimensions: " +
                    "groundedness (1-5, is the answer supported by the context?), " +
                    "relevance (1-5, does it address the query?), " +
                    "helpfulness (1-5, is it useful and clear?). " +
                    "Results are NOT persisted to the database.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Evaluation returned successfully"),
        @ApiResponse(responseCode = "400", description = "Request parameters invalid"),
        @ApiResponse(responseCode = "500", description = "LLM judge unavailable or evaluation failed")
    })
    @PostMapping("/answer-quality")
    public ResponseEntity<AnswerQualityResponse> evaluateAnswerQuality(
            @Valid @RequestBody AnswerQualityRequest request) {
        RetrievalEvaluationService.AnswerQualityResult result =
                evaluationService.evaluateAnswerQuality(
                        request.getQuery(),
                        request.getContext(),
                        request.getAnswer()
                );
        AnswerQualityResponse response = new AnswerQualityResponse(
                result.getGroundedness(),
                result.getRelevance(),
                result.getHelpfulness(),
                result.getReasoning(),
                result.getRecommendation(),
                ZonedDateTime.now()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Calculate metrics (non-persistent)
     */
    @Operation(summary = "Calculate evaluation metrics", description = "Pure calculation without persistence. Used for quick metric preview.")
    @ApiResponse(responseCode = "200", description = "Returns calculation result")
    @GetMapping("/metrics/calculate")
    public ResponseEntity<RetrievalEvaluationService.EvaluationMetrics> calculateMetrics(
            @RequestParam List<Long> retrieved,
            @RequestParam List<Long> relevant,
            @RequestParam(defaultValue = "10") int k) {
        return ResponseEntity.ok(evaluationService.calculateMetrics(retrieved, relevant, k));
    }

    /**
     * Get evaluation report
     */
    @Operation(summary = "Evaluation report", description = "Aggregated evaluation report by time range, including average metrics")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns evaluation report"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
    })
    @GetMapping("/report")
    public ResponseEntity<RetrievalEvaluationService.EvaluationReport> getReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endDate) {
        return ResponseEntity.ok(evaluationService.getReport(startDate, endDate));
    }

    /**
     * Get evaluation history
     */
    @Operation(summary = "Evaluation history", description = "Paginated retrieval of evaluation records")
    @ApiResponse(responseCode = "200", description = "Returns paginated evaluation history")
    @GetMapping("/history")
    public ResponseEntity<List<RagRetrievalEvaluation>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(evaluationService.getHistory(page, size));
    }

    /**
     * Get aggregated metrics
     */
    @Operation(summary = "Aggregated metrics", description = "IR metrics aggregated by time range (average MRR, NDCG, Hit Rate, etc.)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns aggregated metrics"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
    })
    @GetMapping("/metrics/aggregated")
    public ResponseEntity<RetrievalEvaluationService.AggregatedMetrics> getAggregatedMetrics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endDate) {
        return ResponseEntity.ok(evaluationService.getAggregatedMetrics(startDate, endDate));
    }

    // ==================== User Feedback ====================

    /**
     * Submit user feedback
     */
    @Operation(summary = "Submit user feedback", description = "User feedback on RAG retrieval results and answer quality (thumbs up/down or rating)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Feedback submitted successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    @PostMapping("/feedback")
    public ResponseEntity<RagUserFeedback> submitFeedback(@Valid @RequestBody FeedbackRequest request) {
        RagUserFeedback result = userFeedbackService.submitFeedback(
                request.getSessionId(),
                request.getQuery(),
                request.getFeedbackType(),
                request.getRating(),
                request.getComment(),
                request.getRetrievedDocumentIds(),
                request.getSelectedDocumentIds(),
                request.getDwellTimeMs()
        );
        auditCreate(ENTITY_USER_FEEDBACK,
                String.valueOf(result.getId()),
                "User feedback submitted: " + result.getFeedbackType(),
                Map.of("sessionId", request.getSessionId() != null ? request.getSessionId() : "null",
                        "rating", result.getRating() != null ? result.getRating() : 0));
        return ResponseEntity.ok(result);
    }

    /**
     * Get feedback statistics
     */
    @Operation(summary = "Feedback statistics", description = "User feedback distribution by time range (thumbs up/down or rating)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns feedback statistics"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
    })
    @GetMapping("/feedback/stats")
    public ResponseEntity<UserFeedbackService.FeedbackStats> getFeedbackStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endDate) {
        return ResponseEntity.ok(userFeedbackService.getStats(startDate, endDate));
    }

    /**
     * Get feedback history
     */
    @Operation(summary = "Feedback history", description = "Paginated query of user feedback records")
    @ApiResponse(responseCode = "200", description = "Returns paginated feedback history")
    @GetMapping("/feedback/history")
    public ResponseEntity<List<RagUserFeedback>> getFeedbackHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userFeedbackService.getHistory(page, size));
    }

    /**
     * Query feedback by type
     */
    @Operation(summary = "Query feedback by type", description = "Paginated query of feedback by type (THUMBS_UP/THUMBS_DOWN/RATING)")
    @ApiResponse(responseCode = "200", description = "Returns paginated list of feedback for the specified type")
    @GetMapping("/feedback/type/{feedbackType}")
    public ResponseEntity<List<RagUserFeedback>> getFeedbackByType(
            @PathVariable String feedbackType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userFeedbackService.getByType(feedbackType, page, size));
    }

    private RetrievalEvaluationService.EvaluationCase toEvaluationCase(EvaluateRequest request) {
        return new RetrievalEvaluationService.EvaluationCase(
                request.getQuery(),
                request.getRetrievedDocIds(),
                request.getRelevantDocIds()
        );
    }

    // Null-safe audit logging helpers (AuditLogService is optional)
    private void auditCreate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message);
    }

    private void auditCreate(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message, details);
    }
}
