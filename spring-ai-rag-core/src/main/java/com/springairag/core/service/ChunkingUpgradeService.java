package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.documents.chunk.ChunkingException;
import com.springairag.documents.chunk.TextChunk;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 保留真实旧身份，在provider成功后原子升级关键词和活动Profile向量。 */
@Service
public class ChunkingUpgradeService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ChunkingUpgradeService.class);
    private static final String SUPPORTED =
            "^(hierarchical-v2:[0-9]+:[0-9]+:[0-9]+|bounded-v3:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+)$";
    private final JdbcTemplate jdbc;
    private final RagDocumentRepository documents;
    private final EmbeddingProfileProvider profiles;
    private final RagProperties properties;
    private final DocumentDerivationDescriptorProvider descriptors;
    private final DocumentChunkingService chunking;
    private final KeywordIndexPersistenceService keywords;
    private final EmbeddingPersistenceService vectors;
    private final EmbeddingBatchService embeddings;
    private final TransactionTemplate transactions;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile boolean ready;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chunking-upgrade");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chunking-upgrade-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public ChunkingUpgradeService(JdbcTemplate jdbc, RagDocumentRepository documents,
            EmbeddingProfileProvider profiles, RagProperties properties,
            DocumentDerivationDescriptorProvider descriptors, DocumentChunkingService chunking,
            KeywordIndexPersistenceService keywords, EmbeddingPersistenceService vectors,
            EmbeddingBatchService embeddings, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.profiles = profiles;
        this.properties = properties;
        this.descriptors = descriptors;
        this.chunking = chunking;
        this.keywords = keywords;
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            registerServingVersions();
        } catch (RuntimeException failure) {
            log.warn("分块服务身份登记暂不可用 code=CHUNK_UPGRADE_REGISTRATION_FAILED");
        } finally {
            ready = true;
        }
    }

    public void registerServingVersions() {
        jdbc.update("""
                WITH candidates AS (
                    SELECT document.id, document.version,
                           COALESCE(local.chunker_version, vector.chunker_version) AS serving_version
                    FROM rag_documents document
                    LEFT JOIN rag_document_local_index_state local
                      ON local.document_id = document.id AND local.content_hash = document.content_hash
                     AND local.local_index_status = 'READY' AND local.chunk_count > 0
                    LEFT JOIN rag_document_embedding_state vector
                      ON vector.document_id = document.id AND vector.embedding_profile_id = ?
                     AND vector.content_hash = document.content_hash
                     AND vector.status = 'COMPLETED' AND vector.chunk_count > 0
                    WHERE document.derivation_chunker_version IS NULL
                      AND document.document_type IS DISTINCT FROM 'json-record'
                      AND ((local.chunker_version <> ?) OR (vector.chunker_version <> ?))
                )
                UPDATE rag_documents document
                SET derivation_chunker_version = candidates.serving_version,
                    version = document.version + 1
                FROM candidates
                WHERE document.id = candidates.id AND document.version = candidates.version
                  AND document.derivation_chunker_version IS NULL
                  AND candidates.serving_version ~ ?
                """, profiles.getActiveProfile().id(), targetVersion(), targetVersion(), SUPPORTED);
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 30000)
    public void schedule() {
        if (!ready || !properties.getChunk().isAutoUpgradeEnabled()
                || !scanning.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                scanOnce();
            } catch (RuntimeException failure) {
                log.warn("分块自动升级扫描失败 code=CHUNK_UPGRADE_SCAN_FAILED");
            } finally {
                scanning.set(false);
            }
        });
    }

    public void scanOnce() {
        if (!properties.getChunk().isAutoUpgradeEnabled()) {
            return;
        }
        registerServingVersions();
        jdbc.update("""
                UPDATE rag_chunking_upgrade_tasks SET status = 'FAILED', lease_owner = NULL,
                    lease_expires_at = NULL, last_error_code = 'CHUNK_UPGRADE_LEASE_EXHAUSTED', updated_at = NOW()
                WHERE status = 'RUNNING' AND attempt_count >= 3 AND lease_expires_at < NOW()
                """);
        long profileId = profiles.getActiveProfile().id();
        List<Long> candidates = jdbc.queryForList("""
                SELECT document.id FROM rag_documents document
                WHERE document.enabled = true AND document.source_deleted_at IS NULL
                  AND document.document_type IS DISTINCT FROM 'json-record'
                  AND document.content_hash IS NOT NULL
                  AND document.derivation_chunker_version IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM rag_embedding_jobs job WHERE job.document_id = document.id
                                  AND job.status IN ('QUEUED', 'RUNNING'))
                  AND NOT EXISTS (
                      SELECT 1 FROM rag_chunking_upgrade_tasks task
                      WHERE task.document_id = document.id AND task.embedding_profile_id = ?
                        AND task.target_version = ? AND task.source_hash = document.content_hash
                        AND (task.status IN ('COMPLETED', 'FAILED')
                             OR (task.status = 'RUNNING' AND task.lease_expires_at > NOW())
                             OR (task.status = 'RETRY' AND task.available_at > NOW())))
                ORDER BY document.id LIMIT 2
                """, Long.class, profileId, targetVersion());
        for (Long documentId : candidates) {
            documents.findById(documentId).ifPresent(this::upgrade);
        }
    }

    private void upgrade(RagDocument document) {
        EmbeddingProfile profile = profiles.getActiveProfile();
        String target = targetVersion();
        String owner = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                INSERT INTO rag_chunking_upgrade_tasks
                    (document_id, embedding_profile_id, target_version, source_hash,
                     status, attempt_count, lease_owner, lease_expires_at)
                VALUES (?, ?, ?, ?, 'RUNNING', 1, ?, NOW() + INTERVAL '120 seconds')
                ON CONFLICT (document_id, embedding_profile_id) DO UPDATE
                SET target_version = EXCLUDED.target_version, source_hash = EXCLUDED.source_hash,
                    status = 'RUNNING', lease_owner = EXCLUDED.lease_owner,
                    lease_expires_at = EXCLUDED.lease_expires_at, last_error_code = NULL,
                    attempt_count = CASE WHEN rag_chunking_upgrade_tasks.target_version <> EXCLUDED.target_version
                        OR rag_chunking_upgrade_tasks.source_hash <> EXCLUDED.source_hash THEN 1
                        ELSE rag_chunking_upgrade_tasks.attempt_count + 1 END, updated_at = NOW()
                WHERE (rag_chunking_upgrade_tasks.status <> 'RUNNING'
                       OR rag_chunking_upgrade_tasks.lease_expires_at < NOW())
                  AND (rag_chunking_upgrade_tasks.target_version <> EXCLUDED.target_version
                       OR rag_chunking_upgrade_tasks.source_hash <> EXCLUDED.source_hash
                       OR (rag_chunking_upgrade_tasks.attempt_count < 3
                           AND rag_chunking_upgrade_tasks.status IN ('RUNNING', 'RETRY')
                           AND rag_chunking_upgrade_tasks.available_at <= NOW()))
                """, document.getId(), profile.id(), target, document.getContentHash(), owner);
        if (claimed == 0) {
            return;
        }
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> heartbeat(document.getId(), profile.id(), owner),
                30, 30, TimeUnit.SECONDS);
        try {
            String serving = document.getDerivationChunkerVersion();
            RagDocument targetDocument = targetDocument(document);
            boolean migrateVectors = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM rag_document_embedding_state
                    WHERE document_id = ? AND embedding_profile_id = ?
                      AND content_hash = ? AND status = 'COMPLETED' AND chunk_count > 0 AND chunker_version <> ?)
                    """, Boolean.class, document.getId(), profile.id(), document.getContentHash(), target));
            boolean migrateKeywords = migrateVectors || Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM rag_document_local_index_state
                    WHERE document_id = ? AND content_hash = ? AND local_index_status = 'READY' AND chunk_count > 0)
                    """, Boolean.class, document.getId(), document.getContentHash()));
            DocumentChunkingService.PreparedChunks prepared = migrateKeywords ? chunking.prepare(targetDocument) : null;
            List<EmbeddingBatchService.EmbeddingResult> results = migrateVectors
                    ? embeddings.createEmbeddingsBatch(prepared.chunks().stream().map(TextChunk::text).toList())
                    : List.of();
            if (migrateVectors) {
                validateResults(prepared.chunks(), results, profile);
            }
            transactions.executeWithoutResult(transaction -> {
                if (!target.equals(targetVersion()) || profile.id() != profiles.getActiveProfile().id()) {
                    throw new IllegalStateException("CHUNK_UPGRADE_TARGET_CHANGED");
                }
                int fenced = jdbc.update("""
                        UPDATE rag_chunking_upgrade_tasks SET updated_at = NOW()
                        WHERE document_id = ? AND embedding_profile_id = ?
                          AND status = 'RUNNING' AND lease_owner = ? AND lease_expires_at > NOW()
                        """, document.getId(), profile.id(), owner);
                if (fenced != 1) {
                    throw new IllegalStateException("CHUNK_UPGRADE_LEASE_LOST");
                }
                int updated = jdbc.update("""
                        UPDATE rag_documents document SET derivation_chunker_version = NULL, version = version + 1
                        WHERE id = ? AND version = ? AND content_hash = ?
                          AND derivation_chunker_version IS NOT DISTINCT FROM ?
                          AND enabled = true AND source_deleted_at IS NULL
                          AND NOT EXISTS (SELECT 1 FROM rag_embedding_jobs job
                              WHERE job.document_id = document.id AND job.status IN ('QUEUED', 'RUNNING'))
                        """, document.getId(), document.getVersion(), document.getContentHash(), serving);
                if (updated != 1) {
                    throw new IllegalStateException("CHUNK_UPGRADE_SNAPSHOT_CHANGED");
                }
                targetDocument.setVersion(document.getVersion() + 1);
                if (migrateKeywords) {
                    keywords.ensureCurrent(targetDocument, prepared);
                }
                if (migrateVectors) {
                    vectors.replace(document.getId(), targetDocument.getVersion(), document.getContentHash(),
                            profile, prepared.chunks(), results);
                }
                jdbc.update("""
                        UPDATE rag_chunking_upgrade_tasks SET status = 'COMPLETED', lease_owner = NULL,
                            lease_expires_at = NULL, last_error_code = NULL, updated_at = NOW()
                        WHERE document_id = ? AND embedding_profile_id = ? AND lease_owner = ?
                        """, document.getId(), profile.id(), owner);
            });
        } catch (RuntimeException failure) {
            String code = failure instanceof ChunkingException chunking ? chunking.getCode() : "CHUNK_UPGRADE_FAILED";
            jdbc.update("""
                    UPDATE rag_chunking_upgrade_tasks
                    SET status = CASE WHEN attempt_count >= 3 OR ? THEN 'FAILED' ELSE 'RETRY' END,
                        lease_owner = NULL, lease_expires_at = NULL,
                        available_at = NOW() + INTERVAL '60 seconds', last_error_code = ?, updated_at = NOW()
                    WHERE document_id = ? AND embedding_profile_id = ? AND lease_owner = ?
                    """, failure instanceof ChunkingException, code, document.getId(), profile.id(), owner);
            log.warn("分块自动升级保留旧结果 documentId={} code={}", document.getId(), code);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private RagDocument targetDocument(RagDocument source) {
        RagDocument target = new RagDocument();
        target.setId(source.getId());
        target.setVersion(source.getVersion());
        target.setDocumentType(source.getDocumentType());
        target.setContent(source.getContent());
        target.setContentHash(source.getContentHash());
        target.setEnabled(source.getEnabled());
        return target;
    }

    private static void validateResults(List<TextChunk> chunks,
            List<EmbeddingBatchService.EmbeddingResult> results, EmbeddingProfile profile) {
        if (results == null || results.size() != chunks.size()) {
            throw new IllegalStateException("CHUNK_UPGRADE_INVALID_EMBEDDINGS");
        }
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            if (result == null || !result.isSuccess() || !Objects.equals(result.getText(), chunks.get(index).text())
                    || result.getEmbedding() == null || result.getEmbedding().length != profile.dimensions()) {
                throw new IllegalStateException("CHUNK_UPGRADE_INVALID_EMBEDDINGS");
            }
            for (float value : result.getEmbedding()) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("CHUNK_UPGRADE_INVALID_EMBEDDINGS");
                }
            }
        }
    }

    private void heartbeat(long documentId, long profileId, String owner) {
        try {
            jdbc.update("""
                    UPDATE rag_chunking_upgrade_tasks SET lease_expires_at = NOW() + INTERVAL '120 seconds'
                    WHERE document_id = ? AND embedding_profile_id = ? AND lease_owner = ?
                      AND status = 'RUNNING' AND lease_expires_at > NOW()
                    """, documentId, profileId, owner);
        } catch (RuntimeException failure) {
            log.warn("分块自动升级心跳失败 documentId={} code=CHUNK_UPGRADE_HEARTBEAT_FAILED", documentId);
        }
    }

    private String targetVersion() {
        return descriptors.textDescriptor().chunkerVersion();
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
        heartbeats.shutdownNow();
    }
}
