package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagUserFeedback;
import com.springairag.core.repository.RagUserFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 用户反馈服务实现
 */
@Service
@Transactional
public class UserFeedbackServiceImpl implements UserFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(UserFeedbackServiceImpl.class);

    private final RagUserFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;

    public UserFeedbackServiceImpl(RagUserFeedbackRepository feedbackRepository,
                                   ObjectMapper objectMapper) {
        this.feedbackRepository = feedbackRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public RagUserFeedback submitFeedback(String sessionId, String query, String feedbackType,
                                          Integer rating, String comment,
                                          List<Long> retrievedDocumentIds, List<Long> selectedDocumentIds,
                                          Long dwellTimeMs) {
        RagUserFeedback feedback = new RagUserFeedback();
        feedback.setSessionId(sessionId);
        feedback.setQuery(query);
        feedback.setFeedbackType(feedbackType);
        feedback.setRating(rating);
        feedback.setComment(comment);
        feedback.setRetrievedDocumentIds(toJson(retrievedDocumentIds));
        feedback.setSelectedDocumentIds(toJson(selectedDocumentIds));
        feedback.setDwellTimeMs(dwellTimeMs);

        RagUserFeedback saved = feedbackRepository.save(feedback);
        log.info("[UserFeedback] type={}, session={}, query=\"{}\"", feedbackType, sessionId, query);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackStats getStats(ZonedDateTime startDate, ZonedDateTime endDate) {
        FeedbackStats stats = new FeedbackStats();

        long thumbsUp = feedbackRepository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_UP", startDate, endDate);
        long thumbsDown = feedbackRepository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_DOWN", startDate, endDate);
        long total = thumbsUp + thumbsDown;

        // 统计 RATING 类型
        List<RagUserFeedback> ratings = feedbackRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate)
                .stream()
                .filter(f -> "RATING".equals(f.getFeedbackType()) && f.getRating() != null)
                .toList();

        double avgRating = 0.0;
        double avgDwellTime = 0.0;
        long dwellCount = 0;

        for (RagUserFeedback f : ratings) {
            avgRating += f.getRating();
            if (f.getDwellTimeMs() != null) {
                avgDwellTime += f.getDwellTimeMs();
                dwellCount++;
            }
        }

        // 也统计 thumbs up/down 的停留时间
        List<RagUserFeedback> allFeedbacks = feedbackRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate);
        double totalDwellTime = 0;
        long totalDwellCount = 0;
        for (RagUserFeedback f : allFeedbacks) {
            if (f.getDwellTimeMs() != null) {
                totalDwellTime += f.getDwellTimeMs();
                totalDwellCount++;
            }
        }

        stats.setTotalFeedbacks(total + ratings.size());
        stats.setThumbsUp(thumbsUp);
        stats.setThumbsDown(thumbsDown);
        stats.setRatings(ratings.size());
        stats.setAvgRating(ratings.size() > 0 ? avgRating / ratings.size() : 0.0);
        stats.setSatisfactionRate(total > 0 ? (double) thumbsUp / total : 0.0);
        stats.setAvgDwellTimeMs(totalDwellCount > 0 ? totalDwellTime / totalDwellCount : 0.0);

        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RagUserFeedback> getHistory(int page, int size) {
        return feedbackRepository.findAll(PageRequest.of(page, size))
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RagUserFeedback> getByType(String feedbackType, int page, int size) {
        return feedbackRepository.findByFeedbackTypeOrderByCreatedAtDesc(feedbackType, PageRequest.of(page, size));
    }

    private String toJson(List<Long> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize list to JSON", e);
            return "[]";
        }
    }
}
