package com.springairag.core.repository;

import com.springairag.core.entity.RagUserFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 用户反馈 Repository
 */
@Repository
public interface RagUserFeedbackRepository extends JpaRepository<RagUserFeedback, Long> {

    /** 按反馈类型查询，按时间倒序 */
    List<RagUserFeedback> findByFeedbackTypeOrderByCreatedAtDesc(String feedbackType, Pageable pageable);

    /** 按时间段查询 */
    List<RagUserFeedback> findByCreatedAtBetweenOrderByCreatedAtDesc(ZonedDateTime start, ZonedDateTime end);

    /** 按会话 ID 查询 */
    List<RagUserFeedback> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 统计某类型的反馈数量 */
    long countByFeedbackType(String feedbackType);

    /** 按时间段统计某类型的反馈数量 */
    long countByFeedbackTypeAndCreatedAtBetween(String feedbackType, ZonedDateTime start, ZonedDateTime end);
}
