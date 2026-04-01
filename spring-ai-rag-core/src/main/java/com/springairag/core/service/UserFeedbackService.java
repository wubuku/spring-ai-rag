package com.springairag.core.service;

import com.springairag.core.entity.RagUserFeedback;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 用户反馈服务接口
 *
 * <p>收集和分析用户对 RAG 检索结果的反馈，形成质量闭环：
 * <ul>
 *   <li>提交反馈（点赞/点踩/评分）</li>
 *   <li>统计反馈分布</li>
 *   <li>计算满意度指标</li>
 * </ul>
 */
public interface UserFeedbackService {

    /**
     * 提交用户反馈
     *
     * @param sessionId           会话 ID
     * @param query               查询文本
     * @param feedbackType        反馈类型：THUMBS_UP / THUMBS_DOWN / RATING
     * @param rating              评分（1-5，可选）
     * @param comment             用户评论（可选）
     * @param retrievedDocumentIds 检索到的文档 ID 列表（可选）
     * @param selectedDocumentIds 用户认为有用的文档 ID 列表（可选）
     * @param dwellTimeMs         用户停留时间毫秒数（可选）
     * @return 保存后的反馈记录
     */
    RagUserFeedback submitFeedback(String sessionId, String query, String feedbackType,
                                   Integer rating, String comment,
                                   List<Long> retrievedDocumentIds, List<Long> selectedDocumentIds,
                                   Long dwellTimeMs);

    /**
     * 获取反馈统计
     *
     * @param startDate 起始时间
     * @param endDate   结束时间
     * @return 反馈统计
     */
    FeedbackStats getStats(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * 获取反馈历史（分页）
     *
     * @param page 页码（0-based）
     * @param size 每页大小
     * @return 反馈列表
     */
    List<RagUserFeedback> getHistory(int page, int size);

    /**
     * 按反馈类型查询
     *
     * @param feedbackType 反馈类型
     * @param page         页码
     * @param size         每页大小
     * @return 反馈列表
     */
    List<RagUserFeedback> getByType(String feedbackType, int page, int size);

    // ==================== Inner Classes ====================

    /**
     * 反馈统计
     */
    class FeedbackStats {
        private long totalFeedbacks;
        private long thumbsUp;
        private long thumbsDown;
        private long ratings;
        private double avgRating;
        private double satisfactionRate;
        private double avgDwellTimeMs;

        public long getTotalFeedbacks() { return totalFeedbacks; }
        public void setTotalFeedbacks(long totalFeedbacks) { this.totalFeedbacks = totalFeedbacks; }
        public long getThumbsUp() { return thumbsUp; }
        public void setThumbsUp(long thumbsUp) { this.thumbsUp = thumbsUp; }
        public long getThumbsDown() { return thumbsDown; }
        public void setThumbsDown(long thumbsDown) { this.thumbsDown = thumbsDown; }
        public long getRatings() { return ratings; }
        public void setRatings(long ratings) { this.ratings = ratings; }
        public double getAvgRating() { return avgRating; }
        public void setAvgRating(double avgRating) { this.avgRating = avgRating; }
        public double getSatisfactionRate() { return satisfactionRate; }
        public void setSatisfactionRate(double satisfactionRate) { this.satisfactionRate = satisfactionRate; }
        public double getAvgDwellTimeMs() { return avgDwellTimeMs; }
        public void setAvgDwellTimeMs(double avgDwellTimeMs) { this.avgDwellTimeMs = avgDwellTimeMs; }
    }
}
