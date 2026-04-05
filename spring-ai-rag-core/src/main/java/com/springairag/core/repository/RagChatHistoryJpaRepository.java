package com.springairag.core.repository;

import com.springairag.core.entity.RagChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RAG 聊天历史 JPA Repository（内部使用）
 */
@Repository
public interface RagChatHistoryJpaRepository extends JpaRepository<RagChatHistory, Long> {

    /**
     * 按会话 ID 查询历史记录（分页）
     */
    List<RagChatHistory> findBySessionIdOrderByCreatedAtDesc(String sessionId, org.springframework.data.domain.Pageable pageable);

    /**
     * 按会话 ID 查询所有历史记录（无分页，按时间倒序）
     */
    List<RagChatHistory> findAllBySessionIdOrderByCreatedAtDesc(String sessionId);

    /**
     * 按会话 ID 查询所有历史记录（无分页，按时间正序）
     */
    @Query("SELECT h FROM RagChatHistory h WHERE h.sessionId = :sessionId ORDER BY h.createdAt ASC")
    List<RagChatHistory> findBySessionIdAsc(@Param("sessionId") String sessionId);

    /**
     * 按会话 ID 删除所有历史记录
     */
    @Modifying
    @Query("DELETE FROM RagChatHistory h WHERE h.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * 删除指定时间之前的聊天历史（TTL 清理）
     */
    @Modifying
    @Query("DELETE FROM RagChatHistory h WHERE h.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);
}
