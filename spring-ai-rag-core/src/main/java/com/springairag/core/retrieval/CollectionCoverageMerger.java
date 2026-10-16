package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CollectionCoverageMerger {

    private CollectionCoverageMerger() {
    }

    static List<RetrievalResult> merge(
            Map<Long, List<RetrievalResult>> perCollectionResults,
            int limit) {
        List<RetrievalResult> selected = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (List<RetrievalResult> results : perCollectionResults.values()) {
            if (results != null && !results.isEmpty()) {
                add(selected, identities, results.getFirst());
            }
        }
        List<RetrievalResult> remaining = perCollectionResults.values().stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .sorted(CollectionCoverageMerger::compare)
                .toList();
        for (RetrievalResult result : remaining) {
            if (selected.size() >= limit) {
                break;
            }
            add(selected, identities, result);
        }
        return selected.size() > limit
                ? List.copyOf(selected.subList(0, limit))
                : List.copyOf(selected);
    }

    public static List<RetrievalResult> retainCoverage(
            List<RetrievalResult> ranked,
            List<Long> requestedCollectionIds,
            int limit) {
        Map<Long, List<RetrievalResult>> grouped = new LinkedHashMap<>();
        if (requestedCollectionIds != null) {
            for (Long collectionId : requestedCollectionIds) {
                grouped.put(collectionId, new ArrayList<>());
            }
        }
        if (ranked != null) {
            for (RetrievalResult result : ranked) {
                if (result != null && result.getCollectionId() != null) {
                    grouped.computeIfPresent(
                            result.getCollectionId(),
                            (ignored, results) -> {
                                results.add(result);
                                return results;
                            });
                }
            }
        }
        Comparator<RetrievalResult> order = CollectionCoverageMerger::compare;
        grouped.replaceAll((ignored, results) -> results.stream()
                .sorted(order)
                .toList());
        return merge(grouped, limit);
    }

    public static List<RetrievalResult> retainCoverageInRankOrder(
            List<RetrievalResult> ranked,
            List<Long> requestedCollectionIds,
            int limit) {
        List<RetrievalResult> selected = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        List<RetrievalResult> candidates = ranked == null ? List.of() : ranked;
        if (requestedCollectionIds != null) {
            for (Long collectionId : requestedCollectionIds) {
                for (RetrievalResult result : candidates) {
                    if (result != null
                            && java.util.Objects.equals(
                                    result.getCollectionId(), collectionId)) {
                        add(selected, identities, result);
                        break;
                    }
                }
            }
        }
        for (RetrievalResult result : candidates) {
            if (selected.size() >= limit) {
                break;
            }
            add(selected, identities, result);
        }
        return selected.size() > limit
                ? List.copyOf(selected.subList(0, limit))
                : List.copyOf(selected);
    }

    public static boolean isCompleteRanking(
            List<RetrievalResult> candidates,
            List<RetrievalResult> ranked,
            List<Long> requestedCollectionIds) {
        if (candidates == null || ranked == null || ranked.size() != candidates.size()) {
            return false;
        }
        Set<String> candidateIdentities = new HashSet<>();
        for (RetrievalResult candidate : candidates) {
            if (candidate == null || !candidateIdentities.add(identity(candidate))) {
                return false;
            }
        }
        Set<String> rankedIdentities = new HashSet<>();
        Set<Long> rankedCollections = new HashSet<>();
        for (RetrievalResult result : ranked) {
            if (result == null || !rankedIdentities.add(identity(result))) {
                return false;
            }
            if (result.getCollectionId() != null) {
                rankedCollections.add(result.getCollectionId());
            }
        }
        if (!candidateIdentities.equals(rankedIdentities)) {
            return false;
        }
        if (requestedCollectionIds != null) {
            for (Long collectionId : requestedCollectionIds) {
                boolean hadCandidate = candidates.stream()
                        .anyMatch(result -> collectionId.equals(result.getCollectionId()));
                if (hadCandidate && !rankedCollections.contains(collectionId)) {
                    return false;
                }
            }
        }
        return true;
    }

    static Map<Long, Integer> countByCollection(
            List<Long> collectionIds,
            List<RetrievalResult> results) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long collectionId : collectionIds) {
            counts.put(collectionId, 0);
        }
        for (RetrievalResult result : results) {
            if (result != null && result.getCollectionId() != null) {
                counts.computeIfPresent(
                        result.getCollectionId(), (ignored, count) -> count + 1);
            }
        }
        return counts;
    }

    private static void add(
            List<RetrievalResult> selected,
            Set<String> identities,
            RetrievalResult result) {
        if (identities.add(identity(result))) {
            selected.add(result);
        }
    }

    private static int compare(RetrievalResult left, RetrievalResult right) {
        int byScore = Double.compare(right.getScore(), left.getScore());
        if (byScore != 0) {
            return byScore;
        }
        int byDocument = Comparator.nullsFirst(String::compareTo)
                .compare(left.getDocumentId(), right.getDocumentId());
        return byDocument != 0
                ? byDocument
                : Integer.compare(left.getChunkIndex(), right.getChunkIndex());
    }

    private static String identity(RetrievalResult result) {
        if (result.getDocumentId() != null && result.hasChunkIndex()) {
            return result.getDocumentId() + ":" + result.getChunkIndex();
        }
        return "unidentified:" + System.identityHashCode(result);
    }
}
