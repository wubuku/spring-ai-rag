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

    @Query("""
            SELECT h FROM RagChatHistory h
            WHERE h.sessionId = :sessionId
              AND (h.ownerPrincipalId = :ownerPrincipalId
                   OR (:includeLegacy = true AND h.ownerPrincipalId IS NULL))
            ORDER BY h.createdAt DESC, h.id DESC
            """)
    List<RagChatHistory> findVisibleByOwnerAndSessionNewestFirst(
            @Param("ownerPrincipalId") String ownerPrincipalId,
            @Param("sessionId") String sessionId,
            @Param("includeLegacy") boolean includeLegacy,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT h FROM RagChatHistory h
            WHERE h.ownerPrincipalId = :ownerPrincipalId
              AND h.sessionId = :sessionId
            ORDER BY h.createdAt ASC, h.id ASC
            """)
    List<RagChatHistory> findOwnedBySessionAsc(
            @Param("ownerPrincipalId") String ownerPrincipalId,
            @Param("sessionId") String sessionId);

    @Query("""
            SELECT h FROM RagChatHistory h
            WHERE h.ownerPrincipalId = :ownerPrincipalId
              AND h.sessionId = :sessionId
            ORDER BY h.createdAt DESC, h.id DESC
            """)
    List<RagChatHistory> findOwnedBySessionNewestFirst(
            @Param("ownerPrincipalId") String ownerPrincipalId,
            @Param("sessionId") String sessionId,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT h FROM RagChatHistory h
            WHERE h.ownerPrincipalId = :ownerPrincipalId
              AND h.sessionId = :sessionId
              AND h.id > :afterHistoryId
            ORDER BY h.id ASC
            """)
    List<RagChatHistory> findOwnedAfterHistoryId(
            @Param("ownerPrincipalId") String ownerPrincipalId,
            @Param("sessionId") String sessionId,
            @Param("afterHistoryId") long afterHistoryId,
            org.springframework.data.domain.Pageable pageable);

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

    @Query(value = """
            SELECT * FROM rag_chat_history
            WHERE session_id = :sessionId
              AND (
                    owner_principal_id = :ownerPrincipalId
                    OR (:includeLegacy = true AND owner_principal_id IS NULL)
                  )
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<RagChatHistory> findVisibleTopNNewestFirst(
            @Param("ownerPrincipalId") String ownerPrincipalId,
            @Param("sessionId") String sessionId,
            @Param("includeLegacy") boolean includeLegacy,
            @Param("limit") int limit);

    /**
     * Delete all history by session ID.
     */
    @Modifying
    @Query("DELETE FROM RagChatHistory h WHERE h.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    @Modifying
    @Query("""
            DELETE FROM RagChatHistory h
            WHERE h.ownerPrincipalId = :ownerPrincipalId
              AND h.sessionId = :sessionId
            """)
    int deleteOwnedBySession(
            @Param("ownerPrincipalId") String ownerPrincipalId,
            @Param("sessionId") String sessionId);

    /**
     * Delete chat history older than the given cutoff (TTL cleanup).
     */
    @Modifying
    @Query("DELETE FROM RagChatHistory h WHERE h.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);
}
