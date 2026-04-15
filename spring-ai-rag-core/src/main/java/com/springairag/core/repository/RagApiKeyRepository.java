package com.springairag.core.repository;

import com.springairag.core.entity.RagApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * API Key JPA Repository
 */
@Repository
public interface RagApiKeyRepository extends JpaRepository<RagApiKey, Long> {

    /**
     * Find by public keyId.
     */
    Optional<RagApiKey> findByKeyId(String keyId);

    /**
     * Update last-used timestamp.
     */
    @Modifying
    @Query("UPDATE RagApiKey k SET k.lastUsedAt = :lastUsedAt WHERE k.keyId = :keyId")
    int updateLastUsed(@Param("keyId") String keyId, @Param("lastUsedAt") LocalDateTime lastUsedAt);

    /**
     * Disable a key by keyId.
     */
    @Modifying
    @Query("UPDATE RagApiKey k SET k.enabled = false WHERE k.keyId = :keyId")
    int disableByKeyId(@Param("keyId") String keyId);

    /**
     * Find a key by its SHA-256 hash (O(log n) via index instead of O(n) full scan).
     */
    Optional<RagApiKey> findByKeyHash(String keyHash);

    /**
     * List all keys ordered by creation time (newest first).
     */
    List<RagApiKey> findAllByOrderByCreatedAtDesc();
}
