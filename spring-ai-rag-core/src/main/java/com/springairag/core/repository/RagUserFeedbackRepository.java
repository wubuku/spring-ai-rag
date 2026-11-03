package com.springairag.core.repository;

import com.springairag.core.entity.RagUserFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * User Feedback Repository
 */
@Repository
public interface RagUserFeedbackRepository extends JpaRepository<RagUserFeedback, Long> {

    /** Find by feedback type, ordered by time descending */
    List<RagUserFeedback> findByFeedbackTypeOrderByCreatedAtDesc(String feedbackType, Pageable pageable);

    /** Find by time range */
    List<RagUserFeedback> findByCreatedAtBetweenOrderByCreatedAtDesc(ZonedDateTime start, ZonedDateTime end);

    /** Find by session ID */
    List<RagUserFeedback> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** Count feedback by type */
    long countByFeedbackType(String feedbackType);

    /** Count feedback by type within time range */
    long countByFeedbackTypeAndCreatedAtBetween(String feedbackType, ZonedDateTime start, ZonedDateTime end);

    /** 受限 principal 的反馈类型计数 */
    long countByFeedbackTypeAndOwnerPrincipalIdAndCreatedAtBetween(
            String feedbackType, String ownerPrincipalId,
            ZonedDateTime start, ZonedDateTime end);

    /** 受限 principal 的时间范围内反馈 */
    List<RagUserFeedback> findByOwnerPrincipalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String ownerPrincipalId, ZonedDateTime start, ZonedDateTime end);

    /** 受限 principal 的反馈历史（分页） */
    List<RagUserFeedback> findByOwnerPrincipalIdOrderByCreatedAtDesc(
            String ownerPrincipalId, Pageable pageable);

    /** 受限 principal 按类型的反馈历史 */
    List<RagUserFeedback> findByFeedbackTypeAndOwnerPrincipalIdOrderByCreatedAtDesc(
            String feedbackType, String ownerPrincipalId, Pageable pageable);
}
