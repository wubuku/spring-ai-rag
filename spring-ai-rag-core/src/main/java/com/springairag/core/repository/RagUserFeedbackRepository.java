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
}
