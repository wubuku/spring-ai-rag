package com.springairag.core.repository;

import com.springairag.core.entity.RagUserFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * User Feedback Repository
 */
@Repository
public interface RagUserFeedbackRepository extends JpaRepository<RagUserFeedback, Long> {

    /**
     * Find by feedback type. 排序由调用方 Pageable 提供（createdAt desc, id desc），
     * 保证同一时间戳的反馈在分页之间不会漂移。
     */
    @Query("SELECT f FROM RagUserFeedback f WHERE f.feedbackType = :feedbackType")
    List<RagUserFeedback> findByFeedbackTypeOrderByCreatedAtDesc(
            @Param("feedbackType") String feedbackType, Pageable pageable);

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

    /** 受限 principal 的反馈历史（分页），排序由 Pageable 提供。 */
    @Query("SELECT f FROM RagUserFeedback f WHERE f.ownerPrincipalId = :ownerPrincipalId")
    List<RagUserFeedback> findByOwnerPrincipalIdOrderByCreatedAtDesc(
            @Param("ownerPrincipalId") String ownerPrincipalId, Pageable pageable);

    /** 受限 principal 按类型的反馈历史，排序由 Pageable 提供。 */
    @Query("SELECT f FROM RagUserFeedback f "
            + "WHERE f.feedbackType = :feedbackType AND f.ownerPrincipalId = :ownerPrincipalId")
    List<RagUserFeedback> findByFeedbackTypeAndOwnerPrincipalIdOrderByCreatedAtDesc(
            @Param("feedbackType") String feedbackType,
            @Param("ownerPrincipalId") String ownerPrincipalId,
            Pageable pageable);
}
