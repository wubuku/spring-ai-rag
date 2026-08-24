package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import com.springairag.core.retrieval.RetrievalResultProvenance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 本地启发式重排：词面匹配、位置奖励和结果多样性。
 */
public class HeuristicRerankProvider implements RerankProvider {

    private static final int MAX_LEXICAL_FEATURES = 512;
    private static final LexicalFeatures EMPTY_FEATURES =
            new LexicalFeatures("", List.of(), Set.of());

    private final RagRerankProperties config;

    public HeuristicRerankProvider(RagRerankProperties config) {
        this.config = config != null ? config : new RagRerankProperties();
    }

    @Override
    public String getName() {
        return "heuristic";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<RetrievalResult> rerank(
            String query,
            List<RetrievalResult> results,
            int rankingDepth) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        int limit = rankingDepth > 0 ? rankingDepth : results.size();
        float diversityWeight = config.getDiversityWeight();
        LexicalFeatures queryFeatures = extractFeatures(query);
        List<LexicalFeatures> resultFeatures = results.stream()
                .map(result -> extractFeatures(result.getChunkText()))
                .toList();
        List<RetrievalResult> reranked = new ArrayList<>(results.size());

        for (int index = 0; index < results.size(); index++) {
            RetrievalResult result = results.get(index);
            LexicalFeatures features = resultFeatures.get(index);
            float relevance = calculateRelevanceScore(
                    queryFeatures.orderedTerms(),
                    features.normalizedText());
            float diversity = calculateDiversityScore(index, resultFeatures);
            float rawScore = (float) result.getScore();
            float safeScore = Float.isNaN(rawScore) ? 0f : rawScore;
            float finalScore = safeScore * (1 - diversityWeight)
                    + relevance * diversityWeight * 0.5f
                    + diversity * diversityWeight * 0.5f;

            RetrievalResult output = new RetrievalResult();
            output.setDocumentId(result.getDocumentId());
            output.setTitle(result.getTitle());
            output.setChunkText(result.getChunkText());
            output.setScore(finalScore);
            output.setVectorScore(result.getVectorScore());
            output.setFulltextScore(result.getFulltextScore());
            output.setChunkIndex(result.getChunkIndex());
            output.setMetadata(result.getMetadata());
            RetrievalResultProvenance.copy(result, output);
            reranked.add(output);
        }

        return reranked.stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::getScore).reversed())
                .limit(limit)
                .toList();
    }

    public float calculateRelevanceScore(String query, String text) {
        if (query == null || text == null) {
            return 0f;
        }
        LexicalFeatures queryFeatures = extractFeatures(query);
        String normalizedText = normalize(text);
        return calculateRelevanceScore(
                queryFeatures.orderedTerms(),
                normalizedText);
    }

    private float calculateRelevanceScore(
            List<String> queryTerms,
            String normalizedText) {
        if (queryTerms.isEmpty() || normalizedText.isBlank()) {
            return 0f;
        }
        int matchCount = 0;
        int positionScore = 0;
        for (String term : queryTerms) {
            int position = normalizedText.indexOf(term);
            if (position >= 0) {
                matchCount++;
                if (position < 50) {
                    positionScore += (50 - position) / 10;
                }
            }
        }
        float termMatchScore = (float) matchCount / queryTerms.size();
        float positionBonus = Math.min(positionScore / 10f, 0.3f);
        return Math.min(termMatchScore + positionBonus, 1.0f);
    }

    public float calculateDiversityScore(String text, List<RetrievalResult> allResults) {
        LexicalFeatures target = extractFeatures(text);
        if (target.similarityTerms().isEmpty()) {
            return 0f;
        }
        if (allResults.size() <= 1) {
            return 1.0f;
        }
        float maxSimilarity = 0f;
        boolean selfSkipped = false;
        for (RetrievalResult other : allResults) {
            if (!selfSkipped && Objects.equals(other.getChunkText(), text)) {
                selfSkipped = true;
                continue;
            }
            float similarity = calculateTextSimilarity(
                    target.similarityTerms(),
                    extractFeatures(other.getChunkText()).similarityTerms());
            maxSimilarity = Math.max(maxSimilarity, similarity);
        }
        return 1.0f - maxSimilarity;
    }

    private float calculateDiversityScore(
            int targetIndex,
            List<LexicalFeatures> allFeatures) {
        Set<String> targetTerms =
                allFeatures.get(targetIndex).similarityTerms();
        if (targetTerms.isEmpty()) {
            return 0f;
        }
        if (allFeatures.size() <= 1) {
            return 1.0f;
        }
        float maxSimilarity = 0f;
        for (int index = 0; index < allFeatures.size(); index++) {
            if (index == targetIndex) {
                continue;
            }
            float similarity = calculateTextSimilarity(
                    targetTerms,
                    allFeatures.get(index).similarityTerms());
            maxSimilarity = Math.max(maxSimilarity, similarity);
        }
        return 1.0f - maxSimilarity;
    }

    public float calculateTextSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0f;
        }
        return calculateTextSimilarity(
                extractFeatures(text1).similarityTerms(),
                extractFeatures(text2).similarityTerms());
    }

    private float calculateTextSimilarity(
            Set<String> firstTerms,
            Set<String> secondTerms) {
        if (firstTerms.isEmpty() || secondTerms.isEmpty()) {
            return 0f;
        }
        Set<String> smaller = firstTerms.size() <= secondTerms.size()
                ? firstTerms : secondTerms;
        Set<String> larger = smaller == firstTerms
                ? secondTerms : firstTerms;
        int intersectionSize = 0;
        for (String term : smaller) {
            if (larger.contains(term)) {
                intersectionSize++;
            }
        }
        int unionSize =
                firstTerms.size() + secondTerms.size() - intersectionSize;
        return (float) intersectionSize / unionSize;
    }

    private static LexicalFeatures extractFeatures(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_FEATURES;
        }
        String normalized = normalize(value);
        List<String> orderedTerms = new ArrayList<>();
        for (String segment : normalized.split("\\s+")) {
            if (orderedTerms.size() >= MAX_LEXICAL_FEATURES) {
                break;
            }
            if (!containsCjk(segment)) {
                orderedTerms.add(segment);
            } else {
                extractMixedSegment(segment, orderedTerms);
            }
        }

        Set<String> similarityTerms = new LinkedHashSet<>();
        for (String term : orderedTerms) {
            if (isSimilarityTerm(term)) {
                similarityTerms.add(term);
            }
        }
        return new LexicalFeatures(
                normalized,
                List.copyOf(orderedTerms),
                Set.copyOf(similarityTerms));
    }

    private static void extractMixedSegment(
            String segment,
            List<String> terms) {
        StringBuilder latinOrDigitRun = new StringBuilder();
        int previousCjk = -1;
        int cjkRunLength = 0;
        for (int offset = 0; offset < segment.length();) {
            int codePoint = segment.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushLatinOrDigitRun(latinOrDigitRun, terms);
                if (terms.size() >= MAX_LEXICAL_FEATURES) {
                    return;
                }
                if (cjkRunLength > 0) {
                    terms.add(new String(
                            new int[]{previousCjk, codePoint},
                            0,
                            2));
                    if (terms.size() >= MAX_LEXICAL_FEATURES) {
                        return;
                    }
                }
                previousCjk = codePoint;
                cjkRunLength++;
                continue;
            }

            if (cjkRunLength == 1) {
                terms.add(new String(Character.toChars(previousCjk)));
                if (terms.size() >= MAX_LEXICAL_FEATURES) {
                    return;
                }
            }
            previousCjk = -1;
            cjkRunLength = 0;
            if (Character.isLetterOrDigit(codePoint)) {
                latinOrDigitRun.appendCodePoint(codePoint);
            } else {
                flushLatinOrDigitRun(latinOrDigitRun, terms);
                if (terms.size() >= MAX_LEXICAL_FEATURES) {
                    return;
                }
            }
        }
        if (cjkRunLength == 1 && terms.size() < MAX_LEXICAL_FEATURES) {
            terms.add(new String(Character.toChars(previousCjk)));
        }
        flushLatinOrDigitRun(latinOrDigitRun, terms);
    }

    private static void flushLatinOrDigitRun(
            StringBuilder run,
            List<String> terms) {
        if (!run.isEmpty() && terms.size() < MAX_LEXICAL_FEATURES) {
            terms.add(run.toString());
        }
        run.setLength(0);
    }

    private static boolean containsCjk(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (isCjk(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script =
                Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.BOPOMOFO;
    }

    private static boolean isSimilarityTerm(String term) {
        return term.length() >= 2
                || (term.codePointCount(0, term.length()) == 1
                    && isCjk(term.codePointAt(0)));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record LexicalFeatures(
            String normalizedText,
            List<String> orderedTerms,
            Set<String> similarityTerms) {}
}
