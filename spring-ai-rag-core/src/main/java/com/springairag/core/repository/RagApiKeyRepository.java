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
        String getCapabilities();
    }

    /**
     * Find by public keyId.
     */
    Optional<RagApiKey> findByKeyId(String keyId);

    /**
     * @deprecated current credential 必须显式排除 retiring row。
     */
    @Deprecated
    Optional<RagApiKey> findFirstByPrincipalIdAndEnabledTrue(String principalId);

    Optional<RagApiKey> findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
            String principalId);

    Optional<RagApiKey> findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
            String principalId);

    @Query("SELECT k FROM RagApiKey k WHERE k.principalId = :principalId "
            + "AND k.enabled = true AND k.retireAt IS NOT NULL "
            + "AND k.retireAt > :now")
    Optional<RagApiKey> findLiveRetiring(
            @Param("principalId") String principalId,
            @Param("now") LocalDateTime now);

    List<RagApiKey> findAllByPrincipalIdAndEnabledTrue(String principalId);

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

    @Modifying
    @Query("UPDATE RagApiKey k SET k.enabled = false, k.revokedAt = :revokedAt "
            + "WHERE k.principalId = :principalId AND k.enabled = true")
    int disableAllActiveByPrincipalId(
            @Param("principalId") String principalId,
            @Param("revokedAt") LocalDateTime revokedAt);

    @Modifying
    @Query("UPDATE RagApiKey k SET k.name = :name, k.role = :role, "
            + "k.expiresAt = :expiresAt, k.allowedCollectionIds = :allowedCollectionIds "
            + "WHERE k.principalId = :principalId AND k.enabled = true")
    int updateActivePolicySnapshots(
            @Param("principalId") String principalId,
            @Param("name") String name,
            @Param("role") ApiKeyRole role,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("allowedCollectionIds") String allowedCollectionIds);

    /**
     * Find a key by its SHA-256 hash (O(log n) via index instead of O(n) full scan).
     */
    Optional<RagApiKey> findByKeyHash(String keyHash);

    @Query("SELECT p.principalId AS principalId, k.keyId AS credentialId, "
            + "k.credentialVersion AS credentialVersion, p.role AS role, "
            + "p.allowedCollectionIds AS allowedCollectionIds, p.expiresAt AS expiresAt, "
            + "p.policyVersion AS policyVersion, p.requestsPerMinute AS requestsPerMinute, "
            + "p.capabilities AS capabilities "
            + "FROM RagApiKey k, RagApiPrincipal p "
            + "WHERE k.keyHash = :keyHash AND k.principalId = p.principalId "
            + "AND k.enabled = true AND k.revokedAt IS NULL "
            + "AND (k.retireAt IS NULL OR k.retireAt > :now) "
            + "AND p.revokedAt IS NULL AND (p.expiresAt IS NULL OR p.expiresAt > :now)")
    Optional<AuthenticationProjection> authenticate(
            @Param("keyHash") String keyHash,
            @Param("now") LocalDateTime now);

    /**
     * List all keys ordered by creation time (newest first).
     */
    List<RagApiKey> findAllByOrderByCreatedAtDesc();
}
