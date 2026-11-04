package com.springairag.core.service;

import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档读取侧查询服务。
 *
 * <p>controller 只面向 service 层；ACL 分支、集合元数据批量读取、状态计数等
 * 查询组合集中在这里，避免各 controller 直接拼 repository 查询。</p>
 */
@Service
public class DocumentQueryService {

    private final RagDocumentRepository documentRepository;
    private final RagCollectionRepository collectionRepository;
    private final RagEmbeddingRepository embeddingRepository;

    public DocumentQueryService(RagDocumentRepository documentRepository,
                                RagCollectionRepository collectionRepository,
                                RagEmbeddingRepository embeddingRepository) {
        this.documentRepository = documentRepository;
        this.collectionRepository = collectionRepository;
        this.embeddingRepository = embeddingRepository;
    }

    @Transactional(readOnly = true)
    public Optional<RagDocument> findById(Long id) {
        return documentRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public RagDocument requireDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return documentRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public List<RagDocument> findAllById(Collection<Long> ids) {
        return documentRepository.findAllById(ids);
    }

    @Transactional(readOnly = true)
    public Optional<RagCollection> findCollection(Long collectionId) {
        return collectionRepository.findById(collectionId);
    }

    /**
     * 按 ACL 分支选择 repository 查询：受限调用方只查 allow-list 集合。
     */
    @Transactional(readOnly = true)
    public Page<RagDocument> searchForCaller(Optional<Set<Long>> restrictedIds,
                                             Long resolvedCollectionId,
                                             String title,
                                             String documentType,
                                             String processingStatus,
                                             Boolean enabled,
                                             LocalDateTime createdAfter,
                                             LocalDateTime createdBefore,
                                             Pageable pageable) {
        return restrictedIds.isPresent()
                ? documentRepository.searchDocumentsByCollectionIds(
                        resolvedCollectionId != null
                                ? List.of(resolvedCollectionId)
                                : List.copyOf(restrictedIds.orElseThrow()),
                        title, documentType, processingStatus, enabled,
                        createdAfter, createdBefore, pageable)
                : documentRepository.searchDocuments(
                        title, documentType, processingStatus, enabled, resolvedCollectionId,
                        createdAfter, createdBefore, pageable);
    }

    /**
     * 批量取回集合名称与 key，避免逐文档 N+1 查询。
     */
    @Transactional(readOnly = true)
    public CollectionMetadata collectionMetadata(Page<RagDocument> pageResult) {
        List<Long> collectionIds = pageResult.getContent().stream()
                .map(RagDocument::getCollectionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (collectionIds.isEmpty()) {
            return CollectionMetadata.empty();
        }
        List<RagCollection> collections = collectionRepository.findAllById(collectionIds);
        return new CollectionMetadata(
                collections.stream().collect(Collectors.toMap(RagCollection::getId, RagCollection::getName)),
                collections.stream().collect(Collectors.toMap(RagCollection::getId, RagCollection::getCollectionKey)));
    }

    /**
     * 一次读取整页文档的新鲜 chunk 数，key 为 documentId。
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> freshChunkCounts(Collection<Long> documentIds, long embeddingProfileId) {
        return embeddingRepository
                .countFreshChunksByDocumentIdsAndProfileId(List.copyOf(documentIds), embeddingProfileId)
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue()));
    }

    @Transactional(readOnly = true)
    public long freshChunkCount(Long documentId, long embeddingProfileId) {
        return embeddingRepository.countFreshChunksByDocumentIdAndProfileId(documentId, embeddingProfileId);
    }

    @Transactional(readOnly = true)
    public List<Object[]> processingStatusCounts(Optional<Set<Long>> restrictedIds) {
        return restrictedIds
                .map(ids -> documentRepository.countByProcessingStatusAndCollectionIds(List.copyOf(ids)))
                .orElseGet(documentRepository::countByProcessingStatus);
    }

    @Transactional(readOnly = true)
    public long countDocuments(Optional<Set<Long>> restrictedIds) {
        return restrictedIds
                .map(ids -> documentRepository.countByCollectionIdIn(List.copyOf(ids)))
                .orElseGet(documentRepository::count);
    }

    @Transactional(readOnly = true)
    public long countDocumentsWithoutCurrentEmbedding(Optional<Set<Long>> restrictedIds,
                                                      long embeddingProfileId,
                                                      String textVersion,
                                                      String jsonVersion) {
        return restrictedIds
                .map(ids -> documentRepository.countDocumentsWithoutCurrentEmbeddingsByCollectionIds(
                        List.copyOf(ids), embeddingProfileId, textVersion, jsonVersion))
                .orElseGet(() -> documentRepository.countDocumentsWithoutCurrentEmbeddings(
                        embeddingProfileId, textVersion, jsonVersion));
    }

    @Transactional(readOnly = true)
    public List<RagDocument> findDocumentsWithoutCurrentEmbedding(Optional<Set<Long>> restrictedIds,
                                                                  long embeddingProfileId,
                                                                  String textVersion,
                                                                  String jsonVersion) {
        return restrictedIds
                .map(ids -> documentRepository.findDocumentsWithoutCurrentEmbeddingsByCollectionIds(
                        List.copyOf(ids), embeddingProfileId, textVersion, jsonVersion))
                .orElseGet(() -> documentRepository.findDocumentsWithoutCurrentEmbeddings(
                        embeddingProfileId, textVersion, jsonVersion));
    }

    /** 集合名称与 key 的批量投影。 */
    public record CollectionMetadata(Map<Long, String> names, Map<Long, String> keys) {
        public static CollectionMetadata empty() {
            return new CollectionMetadata(Map.of(), Map.of());
        }
    }
}
