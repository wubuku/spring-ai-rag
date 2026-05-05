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
     * Query the most recent N history records by session ID, ordered newest-first.
     * Uses database-level LIMIT to avoid loading all records into memory.
     *
     * @param sessionId the session ID
     * @param limit maximum number of records to return (positive value required)
     * @return at most {@code limit} records, newest first; never null
     */
    @Query(value = "SELECT * FROM rag_chat_history WHERE session_id = :sessionId ORDER BY created_at DESC, id DESC LIMIT :limit",
            nativeQuery = true)
    List<RagChatHistory> findTopNBySessionIdNewestFirst(@Param("sessionId") String sessionId,
                                                         @Param("limit") int limit);

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
