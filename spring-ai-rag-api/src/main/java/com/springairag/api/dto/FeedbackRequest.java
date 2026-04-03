package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 用户反馈请求 DTO
 *
 * <p>用于用户对 RAG 检索结果和回答质量的反馈。
 */
@Schema(description = "用户反馈请求")
public class FeedbackRequest {

    @NotBlank(message = "会话 ID 不能为空")
    @Schema(description = "会话 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sessionId;

    @NotBlank(message = "查询文本不能为空")
    @Size(max = 10000, message = "查询文本不能超过 10000 字符")
    @Schema(description = "查询文本", example = "Spring AI 如何配置向量数据库？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @NotBlank(message = "反馈类型不能为空")
    @Schema(description = "反馈类型: THUMBS_UP / THUMBS_DOWN / RATING", example = "THUMBS_UP", requiredMode = Schema.RequiredMode.REQUIRED)
    private String feedbackType;

    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 5, message = "评分最大为 5")
    @Schema(description = "评分（1-5，feedbackType=RATING 时使用）", example = "4")
    private Integer rating;

    @Size(max = 2000, message = "评论不能超过 2000 字符")
    @Schema(description = "用户评论")
    private String comment;

    @Schema(description = "检索到的文档 ID 列表")
    private List<Long> retrievedDocumentIds;

    @Schema(description = "用户认为有用的文档 ID 列表")
    private List<Long> selectedDocumentIds;

    @Schema(description = "用户停留时间（毫秒）")
    private Long dwellTimeMs;

    public FeedbackRequest() {
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public List<Long> getRetrievedDocumentIds() { return retrievedDocumentIds; }
    public void setRetrievedDocumentIds(List<Long> retrievedDocumentIds) { this.retrievedDocumentIds = retrievedDocumentIds; }
    public List<Long> getSelectedDocumentIds() { return selectedDocumentIds; }
    public void setSelectedDocumentIds(List<Long> selectedDocumentIds) { this.selectedDocumentIds = selectedDocumentIds; }
    public Long getDwellTimeMs() { return dwellTimeMs; }
    public void setDwellTimeMs(Long dwellTimeMs) { this.dwellTimeMs = dwellTimeMs; }
}
