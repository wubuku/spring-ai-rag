package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingVectorColumns;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.RetrievalUtils;
import com.springairag.documents.chunk.TextChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Profile 级 embedding 缓存、失败状态和原子替换。
 */
@Service
public class EmbeddingPersistenceService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final DocumentDerivationDescriptorProvider descriptors;
    private DerivationIntegrityRepository integrityRepository;

    public EmbeddingPersistenceService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new DocumentDerivationDescriptorProvider(new RagProperties()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EmbeddingPersistenceService(
            JdbcTemplate jdbcTemplate,
            DocumentDerivationDescriptorProvider descriptors) {
        this.jdbcTemplate = jdbcTemplate;
        this.descriptors = descriptors;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIntegrityRepository(DerivationIntegrityRepository integrityRepository) {
        this.integrityRepository = integrityRepository;
    }

    /**
     * Whether the stored embedding for this document is still current, i.e. the
     * row's content hash, chunker version and chunk count all agree with what
     * this code would derive today.
     *
     * <p>The caller supplies the document's type rather than the chunker version
     * it expects. That is the same correction Batch 803 made on the write side:
     * a version string passed in from outside is a value nothing constrains, and
     * two call sites did derive it independently and had already drifted apart
     * once. Deriving it here means the comparison is "what this code would write
     * today" against "what is in the row", which is the comparison that can
     * actually detect a mismatch — reading the type back out of the row instead
     * would make the check self-fulfilling.
     *
     * <p>Deriving here also costs nothing: it is the same O(1) descriptor lookup
     * the write side does, whereas having the caller ask
     * {@code DocumentChunkingService.prepare(...)} for a version string chunked
     * the whole document to answer it.
     */
    public CacheState findCacheState(
            long documentId,
            String documentType,
            EmbeddingProfile profile,
            String contentHash) {
        String chunkerVersion = chunkerVersionFor(documentType);
        if (integrityRepository != null) {
            DerivationIntegrityRepository.Snapshot snapshot =
                    integrityRepository.inspect(documentId);
            return snapshot.vectorFresh()
                    ? CacheState.hit(snapshot.vectorExpected()) : CacheState.miss();
        }
        String column = EmbeddingVectorColumns.columnFor(profile.dimensions());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, content_hash, chunker_version, chunk_count "
                        + "FROM rag_document_embedding_state "
                        + "WHERE document_id = ? AND embedding_profile_id = ?",
                documentId,
                profile.id());
        if (rows.isEmpty()) {
            return CacheState.miss();
        }
        Map<String, Object> row = rows.getFirst();
        int chunkCount = ((Number) row.get("chunk_count")).intValue();
        boolean metadataMatches = "COMPLETED".equals(row.get("status"))
                && contentHash.equals(row.get("content_hash"))
                && chunkerVersion.equals(row.get("chunker_version"))
                && chunkCount > 0;
        if (!metadataMatches) {
            return CacheState.miss();
        }
        Long actualCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings "
                        + "WHERE document_id = ? AND embedding_profile_id = ? "
                        + "AND " + column + " IS NOT NULL",
                Long.class,
                documentId,
                profile.id());
        return actualCount != null && actualCount == chunkCount
                ? CacheState.hit(chunkCount)
                : CacheState.miss();
    }

    @Transactional
    public void ensureContentHash(long documentId, long expectedVersion, String contentHash) {
        int updated = jdbcTemplate.update(
                "UPDATE rag_documents SET content_hash = ?, version = version + 1, "
                        + "updated_at = NOW() WHERE id = ? AND version = ? "
                        + "AND (content_hash IS NULL OR content_hash = '')",
                contentHash,
                documentId,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed while initializing content hash: " + documentId);
        }
    }

    @Transactional
    public void replace(
            long documentId,
            long expectedVersion,
            String expectedContentHash,
            EmbeddingProfile profile,
            List<TextChunk> chunks,
            List<EmbeddingBatchService.EmbeddingResult> results) {
        replace(
                documentId,
                expectedVersion,
                expectedContentHash,
                profile,
                chunks,
                results,
                EmbeddingCommitGuard.allowAll());
    }

    /**
     * 在文档快照 CAS 和可选 worker 提交门保护下原子替换向量。
     *
     * <p>这个方法<b>曾经</b>接受一个 {@code String chunkerVersion} 参数，由调用方
     * 从描述符推导后传进来。生产侧的每一处调用方都确实是从
     * {@code DocumentDerivationDescriptorProvider} 取的值，但"每个调用方都要记得
     * 推导对"这件事本身就是个隐患：一个裸 {@code String} 参数既不能被编译器检查，
     * 也不能被静态分析认出"这个值必须等于某个 provider 的输出"。
     *
     * <p>代价是实实在在付过的：Batch 801 和 Batch 802 各有一次集成测试把
     * {@code chunker_version} 写死成字面量（先是 {@code 'test'}，后是
     * {@code 'chunker-v1'}），而生产检索谓词要的是
     * {@code hierarchical-v2:<size>:<min>:<overlap>}，于是<b>一条数据都匹配不上</b>，
     * 测试却以"检索器把文档弄丢了"的面貌失败。现在这个值由服务自己按
     * {@code document_type} 推导，<b>写错的可能性在结构上被消除了</b>——
     * 不再存在一个"可以传错"的参数。
     */
    @Transactional
    public void replace(
            long documentId,
            long expectedVersion,
            String expectedContentHash,
            EmbeddingProfile profile,
            List<TextChunk> chunks,
            List<EmbeddingBatchService.EmbeddingResult> results,
            EmbeddingCommitGuard commitGuard) {
        // The worker guard performs its conditional lease transition inside this
        // transaction. Ordinary document writes are fenced by the final version CAS.
        commitGuard.verify();
        Map<String, Object> document = readDocumentSnapshot(documentId);
        long actualVersion = ((Number) document.get("version")).longValue();
        String actualHash = (String) document.get("content_hash");
        boolean enabled = Boolean.TRUE.equals(document.get("enabled"));
        if (actualVersion != expectedVersion
                || !expectedContentHash.equals(actualHash)
                || !enabled) {
            throw new IllegalStateException(
                    "Document changed while embeddings were generated: " + documentId);
        }
        String chunkerVersion = chunkerVersionFor((String) document.get("document_type"));
        String column = EmbeddingVectorColumns.columnFor(profile.dimensions());
        jdbcTemplate.update(
                "DELETE FROM rag_embeddings WHERE document_id = ? AND embedding_profile_id = ?",
                documentId,
                profile.id());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String vector = RetrievalUtils.vectorToString(results.get(i).getEmbedding());
            String sql = "INSERT INTO rag_embeddings "
                    + "(document_id, chunk_text, chunk_index, embedding, " + column + ", "
                    + "embedding_profile_id, chunk_start_pos, chunk_end_pos, created_at) "
                    + "VALUES (?, ?, ?, ?::vector, ?::vector, ?, ?, ?, NOW())";
            jdbcTemplate.update(
                    sql,
                    documentId,
                    chunk.text(),
                    i,
                    vector,
                    vector,
                    profile.id(),
                    chunk.startPos(),
                    chunk.endPos());
        }

        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, processing_error, completed_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, NULL, NOW(), NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "content_hash = EXCLUDED.content_hash, "
                        + "chunker_version = EXCLUDED.chunker_version, "
                        + "status = 'COMPLETED', chunk_count = EXCLUDED.chunk_count, "
                        + "processing_error = NULL, active_job_id = NULL, "
                        + "completed_at = NOW(), updated_at = NOW()",
                documentId,
                profile.id(),
                expectedContentHash,
                chunkerVersion,
                chunks.size());
        int updated = jdbcTemplate.update(
                "UPDATE rag_documents SET processing_status = 'COMPLETED', "
                        + "processing_error = NULL, embedded_content_hash = ?, "
                        + "version = version + 1, updated_at = NOW() WHERE id = ? AND version = ?",
                expectedContentHash,
                documentId,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed during embedding commit: " + documentId);
        }
    }

    /**
     * Records a failed embedding attempt as the current state for this profile,
     * but only while the document still matches the version the caller started
     * from.
     *
     * <p>Like {@link #findCacheState}, the caller supplies the document's type
     * rather than the chunker version it believes in, and the version is derived
     * here. Three methods on this service now take a document type for exactly
     * this reason; the version string is an output of the descriptor provider,
     * not something a caller gets to state.
     */
    @Transactional
    public void recordFailureIfNoCompleted(
            long documentId,
            long expectedVersion,
            String expectedContentHash,
            EmbeddingProfile profile,
            String documentType,
            String error) {
        String chunkerVersion = chunkerVersionFor(documentType);
        String safeError = sanitizeError(error);
        Map<String, Object> document = readDocumentSnapshot(documentId);
        long actualVersion = ((Number) document.get("version")).longValue();
        String actualHash = (String) document.get("content_hash");
        if (actualVersion != expectedVersion || !expectedContentHash.equals(actualHash)) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, processing_error, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'FAILED', 0, ?, NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "content_hash = EXCLUDED.content_hash, "
                        + "chunker_version = EXCLUDED.chunker_version, status = 'FAILED', "
                        + "chunk_count = 0, processing_error = EXCLUDED.processing_error, "
                        + "completed_at = NULL, updated_at = NOW()",
                documentId,
                profile.id(),
                expectedContentHash,
                chunkerVersion,
                safeError);
        int updated = jdbcTemplate.update(
                "UPDATE rag_documents SET processing_status = 'FAILED', processing_error = ?, "
                        + "version = version + 1, updated_at = NOW() WHERE id = ? AND version = ?",
                safeError,
                documentId,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed during embedding failure commit: " + documentId);
        }
    }

    private String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Embedding failed";
        }
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(error);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked : masked.substring(0, MAX_ERROR_LENGTH);
    }

    private Map<String, Object> readDocumentSnapshot(long documentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, content_hash, enabled, document_type FROM rag_documents "
                        + "WHERE id = ?",
                documentId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Document not found during embedding commit: " + documentId);
        }
        return rows.getFirst();
    }

    /**
     * The chunker version the retrieval scope will look for, derived from the
     * document's own type.
     *
     * <p>This is the single place that decides which
     * {@code rag_document_embedding_state.chunker_version} a write records, and
     * it deliberately uses the same provider the retrieval scope is built from —
     * two derivations that can drift are exactly the bug this replaced.
     */
    private String chunkerVersionFor(String documentType) {
        return RagDocument.JSON_RECORD.equals(documentType)
                ? descriptors.jsonRecordDescriptor().chunkerVersion()
                : descriptors.textDescriptor().chunkerVersion();
    }

    public record CacheState(boolean hit, int chunkCount) {
        static CacheState hit(int chunkCount) {
            return new CacheState(true, chunkCount);
        }

        static CacheState miss() {
            return new CacheState(false, 0);
        }
    }
}
