package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.RetrievalTraceCollector;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按稳定 chunk identity 合并多查询证据，并保留同一 chunk 的最高有限分候选。
 */
public final class ProjectDocumentJoiner implements DocumentJoiner {

    private static final Comparator<Map.Entry<Query, List<List<Document>>>>
            QUERY_ORDER = Comparator.comparing(entry -> entry.getKey().text());

    @Override
    public List<Document> join(
            Map<Query, List<List<Document>>> documentsForQuery) {
        Assert.notNull(
                documentsForQuery,
                "documentsForQuery cannot be null");
        Assert.noNullElements(
                documentsForQuery.keySet(),
                "documentsForQuery cannot contain null keys");
        Assert.noNullElements(
                documentsForQuery.values(),
                "documentsForQuery cannot contain null values");

        List<Map.Entry<Query, List<List<Document>>>> orderedEntries =
                documentsForQuery.entrySet().stream()
                        .sorted(QUERY_ORDER)
                        .toList();
        assertUniqueQueryText(orderedEntries);

        List<RetrievalTraceCollector> traces = orderedEntries.stream()
                .map(entry -> trace(entry.getKey()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<Candidate> candidates = flatten(orderedEntries);
        Map<String, Candidate> selectedByIdentity = new LinkedHashMap<>();
        List<Candidate> anonymous = new ArrayList<>();
        int scoreReplacements = 0;

        for (Candidate candidate : candidates) {
            if (candidate.identity() == null) {
                anonymous.add(candidate);
                continue;
            }
            Candidate existing = selectedByIdentity.get(candidate.identity());
            if (existing == null) {
                selectedByIdentity.put(candidate.identity(), candidate);
            } else if (shouldReplace(existing.document(), candidate.document())) {
                selectedByIdentity.put(candidate.identity(), candidate);
                scoreReplacements++;
            }
        }

        List<Candidate> selected = new ArrayList<>(
                selectedByIdentity.size() + anonymous.size());
        selected.addAll(selectedByIdentity.values());
        selected.addAll(anonymous);
        selected.sort(this::compareCandidates);

        int inputDocuments = candidates.size();
        int uniqueDocuments = selected.size();
        int replacements = scoreReplacements;
        traces.forEach(trace -> trace.recordDocumentJoin(
                inputDocuments,
                uniqueDocuments,
                replacements));

        return selected.stream().map(Candidate::document).toList();
    }

    private void assertUniqueQueryText(
            List<Map.Entry<Query, List<List<Document>>>> orderedEntries) {
        Set<String> queryTexts = new HashSet<>();
        for (Map.Entry<Query, List<List<Document>>> entry : orderedEntries) {
            Assert.isTrue(
                    queryTexts.add(entry.getKey().text()),
                    "documentsForQuery cannot contain duplicate query text");
        }
    }

    private List<Candidate> flatten(
            List<Map.Entry<Query, List<List<Document>>>> orderedEntries) {
        List<Candidate> candidates = new ArrayList<>();
        int position = 0;
        for (Map.Entry<Query, List<List<Document>>> entry : orderedEntries) {
            List<List<Document>> sourceLists = entry.getValue();
            Assert.noNullElements(
                    sourceLists,
                    "documentsForQuery cannot contain null source lists");
            for (List<Document> documents : sourceLists) {
                Assert.noNullElements(
                        documents,
                        "documentsForQuery cannot contain null documents");
                for (Document document : documents) {
                    candidates.add(new Candidate(
                            document,
                            identity(document),
                            position++));
                }
            }
        }
        return candidates;
    }

    private boolean shouldReplace(Document existing, Document candidate) {
        Double existingScore = existing.getScore();
        Double candidateScore = candidate.getScore();
        boolean existingFinite = isFinite(existingScore);
        boolean candidateFinite = isFinite(candidateScore);
        if (candidateFinite && !existingFinite) {
            return true;
        }
        return candidateFinite
                && existingFinite
                && Double.compare(candidateScore, existingScore) > 0;
    }

    private int compareCandidates(Candidate left, Candidate right) {
        Double leftScore = left.document().getScore();
        Double rightScore = right.document().getScore();
        boolean leftFinite = isFinite(leftScore);
        boolean rightFinite = isFinite(rightScore);
        if (leftFinite != rightFinite) {
            return leftFinite ? -1 : 1;
        }
        if (leftFinite) {
            int scoreOrder = Double.compare(rightScore, leftScore);
            if (scoreOrder != 0) {
                return scoreOrder;
            }
        }
        if (left.identity() != null && right.identity() == null) {
            return -1;
        }
        if (left.identity() == null && right.identity() != null) {
            return 1;
        }
        if (left.identity() != null) {
            int identityOrder = left.identity().compareTo(right.identity());
            if (identityOrder != 0) {
                return identityOrder;
            }
        }
        return Integer.compare(left.position(), right.position());
    }

    private boolean isFinite(Double score) {
        return score != null && Double.isFinite(score);
    }

    private String identity(Document document) {
        String id = document.getId();
        return id != null && !id.isBlank() ? id : null;
    }

    private RetrievalTraceCollector trace(Query query) {
        Object value = query.context().get(ProjectDocumentRetriever.CONTEXT_KEY);
        if (value instanceof AuthorizedRetrievalContext context) {
            return context.trace();
        }
        return null;
    }

    private record Candidate(
            Document document,
            String identity,
            int position) {
    }
}
