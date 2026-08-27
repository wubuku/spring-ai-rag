package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagUserFeedback;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagUserFeedbackRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.ApiKeyCollectionAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * User Feedback Service Implementation
 */
@Service
@Transactional
public class UserFeedbackServiceImpl implements UserFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(UserFeedbackServiceImpl.class);
    private static final int MAX_DOCUMENT_REFERENCES = 1000;

    private final RagUserFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;
    private final FeedbackDocumentReferenceStore referenceStore;
    private final CollectionIdentityResolver collectionIdentityResolver;

    public UserFeedbackServiceImpl(
            RagUserFeedbackRepository feedbackRepository,
            ObjectMapper objectMapper,
            FeedbackDocumentReferenceStore referenceStore,
            CollectionIdentityResolver collectionIdentityResolver) {
        this.feedbackRepository = feedbackRepository;
        this.objectMapper = objectMapper;
        this.referenceStore = referenceStore;
        this.collectionIdentityResolver = collectionIdentityResolver;
    }

    @Override
    public RagUserFeedback submitFeedback(String sessionId, String query, String feedbackType,
                                          Integer rating, String comment,
                                          List<Long> retrievedDocumentIds, List<Long> selectedDocumentIds,
                                          Long dwellTimeMs) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(query, "query must not be null");
        List<Long> retrievedIds = normalizeIds(
                retrievedDocumentIds, "retrievedDocumentIds");
        List<Long> selectedIds = normalizeIds(
                selectedDocumentIds, "selectedDocumentIds");
        List<Long> referenceIds = referenceUnion(retrievedIds, selectedIds);
        validateAndReserveReferences(referenceIds);

        RagUserFeedback feedback = new RagUserFeedback();
        feedback.setSessionId(sessionId);
        feedback.setQuery(query);
        feedback.setFeedbackType(feedbackType);
        feedback.setRating(rating);
        feedback.setComment(comment);
        feedback.setRetrievedDocumentIds(toJson(retrievedIds));
        feedback.setSelectedDocumentIds(toJson(selectedIds));
        feedback.setDwellTimeMs(dwellTimeMs);
        feedback.setContentReferenceIndexComplete(true);

        RagUserFeedback saved = feedbackRepository.saveAndFlush(feedback);
        if (!referenceIds.isEmpty()) {
            referenceStore.insert(saved.getId(), referenceIds);
        }
        log.info("[UserFeedback] type={}, session={}, documentReferences={}",
                feedbackType, sessionId, referenceIds.size());
        return saved;
    }

    private void validateAndReserveReferences(List<Long> referenceIds) {
        if (referenceIds.isEmpty()) {
            return;
        }
        ApiAccessPolicy policy = ApiKeyCollectionAccess.currentPolicy();
        List<FeedbackDocumentReferenceStore.DocumentSnapshot> before =
                referenceStore.load(referenceIds);
        validateSnapshots(referenceIds, before, policy);

        List<Long> collectionIds = before.stream()
                .map(FeedbackDocumentReferenceStore.DocumentSnapshot::collectionId)
                .distinct()
                .sorted()
                .toList();
        collectionIdentityResolver.beginActiveWrites(collectionIds);

        List<FeedbackDocumentReferenceStore.DocumentSnapshot> after =
                referenceStore.load(referenceIds);
        validateSnapshots(referenceIds, after, policy);
        if (!before.equals(after)) {
            throw new RagException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Feedback document references changed during submission");
        }
    }

    private void validateSnapshots(
            List<Long> expectedIds,
            List<FeedbackDocumentReferenceStore.DocumentSnapshot> snapshots,
            ApiAccessPolicy policy) {
        if (snapshots.size() != expectedIds.size()) {
            if (!ApiKeyCollectionAccess.isUnrestricted(policy)) {
                throw new SecurityException(
                        "Feedback document references are not authorized");
            }
            throw new RagException(
                    ErrorCode.DOCUMENT_NOT_FOUND,
                    "One or more feedback documents are unavailable");
        }
        for (int index = 0; index < expectedIds.size(); index++) {
            FeedbackDocumentReferenceStore.DocumentSnapshot snapshot =
                    snapshots.get(index);
            if (snapshot.documentId() != expectedIds.get(index)) {
                throw new RagException(
                        ErrorCode.CONCURRENT_MODIFICATION,
                        "Feedback document references changed during submission");
            }
            if (!snapshot.enabled()) {
                throw new RagException(
                        ErrorCode.DOCUMENT_DISABLED,
                        "Feedback cannot reference a disabled document");
            }
            if (snapshot.collectionId() == null) {
                throw new RagException(
                        ErrorCode.DOCUMENT_NOT_FOUND,
                        "Feedback document is not assigned to an active Collection");
            }
            ApiKeyCollectionAccess.requireCollectionId(
                    snapshot.collectionId(), policy);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackStats getStats(ZonedDateTime startDate, ZonedDateTime endDate) {
        long thumbsUp = feedbackRepository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_UP", startDate, endDate);
        long thumbsDown = feedbackRepository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_DOWN", startDate, endDate);
        long total = thumbsUp + thumbsDown;

        List<RagUserFeedback> allFeedbacks = feedbackRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate);

        // Aggregate RATING type
        List<RagUserFeedback> ratings = allFeedbacks.stream()
                .filter(f -> "RATING".equals(f.getFeedbackType()) && f.getRating() != null)
                .toList();

        double avgRating = ratings.stream().mapToInt(RagUserFeedback::getRating).average().orElse(0.0);
        double[] dwellStats = calculateDwellStats(allFeedbacks);

        FeedbackStats stats = new FeedbackStats();
        stats.setTotalFeedbacks(total + ratings.size());
        stats.setThumbsUp(thumbsUp);
        stats.setThumbsDown(thumbsDown);
        stats.setRatings(ratings.size());
        stats.setAvgRating(avgRating);
        stats.setSatisfactionRate(total > 0 ? (double) thumbsUp / total : 0.0);
        stats.setAvgDwellTimeMs(dwellStats[0]);
        return stats;
    }

    private double[] calculateDwellStats(List<RagUserFeedback> feedbacks) {
        double totalDwellTime = 0;
        long count = 0;
        for (RagUserFeedback f : feedbacks) {
            if (f.getDwellTimeMs() != null) {
                totalDwellTime += f.getDwellTimeMs();
                count++;
            }
        }
        return new double[]{count > 0 ? totalDwellTime / count : 0.0, count};
    }

    @Override
    @Transactional(readOnly = true)
    public List<RagUserFeedback> getHistory(int page, int size) {
        return feedbackRepository.findAll(PageRequest.of(page, size))
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RagUserFeedback> getByType(String feedbackType, int page, int size) {
        return feedbackRepository.findByFeedbackTypeOrderByCreatedAtDesc(feedbackType, PageRequest.of(page, size));
    }

    private List<Long> normalizeIds(List<Long> values, String field) {
        if (values == null) {
            return null;
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(
                        field + " must contain positive document IDs");
            }
            normalized.add(value);
            if (normalized.size() > MAX_DOCUMENT_REFERENCES) {
                throw new IllegalArgumentException(
                        "Feedback must not reference more than "
                                + MAX_DOCUMENT_REFERENCES + " documents");
            }
        }
        return normalized.stream().sorted().toList();
    }

    private List<Long> referenceUnion(
            List<Long> retrievedDocumentIds,
            List<Long> selectedDocumentIds) {
        Set<Long> union = new LinkedHashSet<>();
        if (retrievedDocumentIds != null) {
            union.addAll(retrievedDocumentIds);
        }
        if (selectedDocumentIds != null) {
            union.addAll(selectedDocumentIds);
        }
        List<Long> result = new ArrayList<>(union);
        result.sort(Long::compareTo);
        return List.copyOf(result);
    }

    private String toJson(List<Long> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize feedback document references", e);
        }
    }
}
