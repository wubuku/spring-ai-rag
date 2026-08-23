package com.springairag.core.repository;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
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

    interface AuthenticationProjection {
        String getPrincipalId();
        String getCredentialId();
        Integer getCredentialVersion();
        ApiKeyRole getRole();
        String getAllowedCollectionIds();
        LocalDateTime getExpiresAt();
        Long getPolicyVersion();
        Integer getRequestsPerMinute();
    }

    /**
     * Find by public keyId.
     */
    Optional<RagApiKey> findByKeyId(String keyId);

    Optional<RagApiKey> findFirstByPrincipalIdAndEnabledTrue(String principalId);

    List<RagApiKey> findAllByPrincipalIdOrderByCredentialVersionDesc(String principalId);

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
    @Query("UPDATE RagApiKey k SET k.enabled = false, k.revokedAt = :revokedAt "
            + "WHERE k.keyId = :keyId AND k.enabled = true")
    int disableByKeyId(@Param("keyId") String keyId,
                       @Param("revokedAt") LocalDateTime revokedAt);

    default int disableByKeyId(String keyId) {
        return disableByKeyId(keyId, LocalDateTime.now());
    }

    /**
     * Find a key by its SHA-256 hash (O(log n) via index instead of O(n) full scan).
     */
    Optional<RagApiKey> findByKeyHash(String keyHash);

    @Query("SELECT p.principalId AS principalId, k.keyId AS credentialId, "
            + "k.credentialVersion AS credentialVersion, p.role AS role, "
            + "p.allowedCollectionIds AS allowedCollectionIds, p.expiresAt AS expiresAt, "
            + "p.policyVersion AS policyVersion, p.requestsPerMinute AS requestsPerMinute "
            + "FROM RagApiKey k, RagApiPrincipal p "
            + "WHERE k.keyHash = :keyHash AND k.principalId = p.principalId "
            + "AND k.enabled = true AND k.revokedAt IS NULL "
            + "AND p.revokedAt IS NULL AND (p.expiresAt IS NULL OR p.expiresAt > :now)")
    Optional<AuthenticationProjection> authenticate(
            @Param("keyHash") String keyHash,
            @Param("now") LocalDateTime now);

    /**
     * List all keys ordered by creation time (newest first).
     */
    List<RagApiKey> findAllByOrderByCreatedAtDesc();
}
