package com.springairag.core.controller;

import com.springairag.api.dto.EvaluateRequest;
import com.springairag.api.dto.FeedbackRequest;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.entity.RagUserFeedback;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.UserFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 检索效果评估控制器
 *
 * <p>提供检索质量评估的 REST API，支持单次评估、批量评估、报告查询等操作。
 */
@RestController
@RequestMapping("/api/v1/rag/evaluation")
@Tag(name = "RAG Evaluation", description = "检索效果评估（Precision@K, Recall@K, MRR, NDCG）")
public class EvaluationController {

    private final RetrievalEvaluationService evaluationService;
    private final UserFeedbackService userFeedbackService;

    public EvaluationController(RetrievalEvaluationService evaluationService,
                                UserFeedbackService userFeedbackService) {
        this.evaluationService = evaluationService;
        this.userFeedbackService = userFeedbackService;
    }

    /**
     * 单次评估
     */
    @Operation(summary = "单次检索评估", description = "提交查询和检索结果进行效果评估，计算 Precision@K、MRR、NDCG 等指标")
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
     * 批量评估
     */
    @Operation(summary = "批量检索评估", description = "提交多组评估用例进行批量评估")
    @PostMapping("/batch")
    public ResponseEntity<List<RagRetrievalEvaluation>> batchEvaluate(
            @Valid @RequestBody List<EvaluateRequest> requests) {
        List<RetrievalEvaluationService.EvaluationCase> cases = requests.stream()
                .map(this::toEvaluationCase)
                .toList();
        List<RagRetrievalEvaluation> results = evaluationService.batchEvaluate(cases);
        return ResponseEntity.ok(results);
    }

    /**
     * 计算指标（不持久化）
     */
    @Operation(summary = "计算评估指标", description = "纯计算，不写入数据库。用于快速预览指标。")
    @GetMapping("/metrics/calculate")
    public ResponseEntity<RetrievalEvaluationService.EvaluationMetrics> calculateMetrics(
            @RequestParam List<Long> retrieved,
            @RequestParam List<Long> relevant,
            @RequestParam(defaultValue = "10") int k) {
        return ResponseEntity.ok(evaluationService.calculateMetrics(retrieved, relevant, k));
    }

    /**
     * 获取评估报告
     */
    @Operation(summary = "评估报告", description = "按时间段聚合的评估报告，包含平均指标")
    @GetMapping("/report")
    public ResponseEntity<RetrievalEvaluationService.EvaluationReport> getReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endDate) {
        return ResponseEntity.ok(evaluationService.getReport(startDate, endDate));
    }

    /**
     * 获取评估历史
     */
    @Operation(summary = "评估历史", description = "分页查询评估记录")
    @GetMapping("/history")
    public ResponseEntity<List<RagRetrievalEvaluation>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(evaluationService.getHistory(page, size));
    }

    /**
     * 获取聚合指标
     */
    @Operation(summary = "聚合指标", description = "按时间段聚合的 IR 指标（平均 MRR、NDCG、Hit Rate 等）")
    @GetMapping("/metrics/aggregated")
    public ResponseEntity<RetrievalEvaluationService.AggregatedMetrics> getAggregatedMetrics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endDate) {
        return ResponseEntity.ok(evaluationService.getAggregatedMetrics(startDate, endDate));
    }

    // ==================== 用户反馈 ====================

    /**
     * 提交用户反馈
     */
    @Operation(summary = "提交用户反馈", description = "用户对 RAG 检索结果和回答质量的反馈（点赞/点踩/评分）")
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
        return ResponseEntity.ok(result);
    }

    /**
     * 获取反馈统计
     */
    @Operation(summary = "反馈统计", description = "按时间段统计用户反馈分布（点赞/点踩/评分）")
    @GetMapping("/feedback/stats")
    public ResponseEntity<UserFeedbackService.FeedbackStats> getFeedbackStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endDate) {
        return ResponseEntity.ok(userFeedbackService.getStats(startDate, endDate));
    }

    /**
     * 获取反馈历史
     */
    @Operation(summary = "反馈历史", description = "分页查询用户反馈记录")
    @GetMapping("/feedback/history")
    public ResponseEntity<List<RagUserFeedback>> getFeedbackHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userFeedbackService.getHistory(page, size));
    }

    /**
     * 按类型查询反馈
     */
    @Operation(summary = "按类型查询反馈", description = "按反馈类型（THUMBS_UP/THUMBS_DOWN/RATING）分页查询")
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
}
