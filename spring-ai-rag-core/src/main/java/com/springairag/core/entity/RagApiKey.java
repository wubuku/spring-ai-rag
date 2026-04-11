package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * API Key entity for programmatic authentication.
 *
 * <p>The actual key value is never stored — only its SHA-256 hash.
 * The raw key is returned only once at creation time.
 */
@Entity
@Table(name = "rag_api_key", indexes = {
    @Index(name = "idx_rag_api_key_key_id", columnList = "key_id", unique = true),
    @Index(name = "idx_rag_api_key_enabled", columnList = "enabled"),
    @Index(name = "idx_rag_api_key_hash", columnList = "key_hash", unique = true)
})
public class RagApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public key identifier (e.g., rag_k_abc123). Shown in listings, never the secret. */
    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    /** SHA-256 hash of the raw API key. The raw key is never stored. */
    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    /** Human-readable name for this key (e.g., "Production Server", "CI Pipeline"). */
    @Column(nullable = false, length = 255)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    /** Optional expiration time. Null means never expires. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private Boolean enabled = true;

    public RagApiKey() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
