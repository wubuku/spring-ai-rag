package com.springairag.core.repository;

import com.springairag.core.entity.RagChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RAG chat history JPA repository (internal use).
 */
@Repository
public interface RagChatHistoryJpaRepository extends JpaRepository<RagChatHistory, Long> {

    /**
     * Query history by session ID (paginated).
     */
    List<RagChatHistory> findBySessionIdOrderByCreatedAtDesc(String sessionId, org.springframework.data.domain.Pageable pageable);

    /**
     * Query all history by session ID (no pagination, descending by time).
     */
    List<RagChatHistory> findAllBySessionIdOrderByCreatedAtDesc(String sessionId);

    /**
     * Query all history by session ID (no pagination, ascending by time).
     */
    @Query("SELECT h FROM RagChatHistory h WHERE h.sessionId = :sessionId ORDER BY h.createdAt ASC")
    List<RagChatHistory> findBySessionIdAsc(@Param("sessionId") String sessionId);

    /**
     * Delete all history by session ID.
     */
    @Modifying
    @Query("DELETE FROM RagChatHistory h WHERE h.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * Delete chat history older than the given cutoff (TTL cleanup).
     */
    @Modifying
    @Query("DELETE FROM RagChatHistory h WHERE h.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);
}
