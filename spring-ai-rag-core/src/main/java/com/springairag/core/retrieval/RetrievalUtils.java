package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval algorithm utilities.
 *
 * <p>Extracted from HybridRetrieverService for independent testing and reuse.
 */
public final class RetrievalUtils {

    private static final int RRF_K = 60;
    private static final double RRF_SCALE = RRF_K + 1.0;
    private static final Comparator<RetrievalResult> CHANNEL_ORDER =
            RetrievalUtils::compareChannelResults;
    private static final Comparator<MergedEntry> FUSED_ORDER =
            (left, right) -> {
                int byScore = Double.compare(right.fusedScore, left.fusedScore);
                return byScore != 0
                        ? byScore
                        : compareIdentity(left.original, right.original);
            };

    private RetrievalUtils() {
    }

    /**
     * Cosine similarity between two vectors.
     *
     * @param a vector a
     * @param b vector b
     * @return similarity in [-1, 1]; returns 0 if dimensions mismatch or either is null/empty
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    /**
     * Euclidean (L2) distance between two vectors.
     *
     * @param a vector a
     * @param b vector b
     * @return non-negative distance; returns Double.MAX_VALUE if dimensions mismatch or either is null/empty
     */
    public static double euclideanDistance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return Double.MAX_VALUE;
        }
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = (double) a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * Dot product (inner product) of two vectors.
     *
     * <p>Note: pgvector uses negative dot product ({@code <#>}) for max-inner-product search.
     * Higher values indicate more similar for un-normalized embeddings.
     *
     * @param a vector a
     * @param b vector b
     * @return dot product; returns 0 if dimensions mismatch or either is null/empty
     */
    public static double dotProduct(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /**
     * Converts a float array to pgvector string format "[0.1,0.2,...]"
     */
    public static String vectorToString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Parses a vector from database representation (float[], double[], or String).
     */
    public static float[] parseVector(Object vectorObj) {
        if (vectorObj == null) {
            return new float[0];
        }
        if (vectorObj instanceof float[]) {
            return (float[]) vectorObj;
        }
        if (vectorObj instanceof double[]) {
            return toFloatArray((double[]) vectorObj);
        }
        if (vectorObj instanceof String) {
            return parseStringVector((String) vectorObj);
        }
        return new float[0];
    }

    private static float[] toFloatArray(double[] d) {
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) {
            f[i] = (float) d[i];
        }
        return f;
    }

    private static float[] parseStringVector(String s) {
        s = s.replaceAll("[\\[\\] ]", "");
        if (s.isEmpty()) {
            return new float[0];
        }
        String[] parts = s.split(",");
        float[] f = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            f[i] = Float.parseFloat(parts[i]);
        }
        return f;
    }

    /**
     * Fuses vector and fulltext retrieval results using scaled weighted RRF.
     *
     * <p>Provider scores only establish each channel's rank. The final score is:
     * {@code (K + 1) * weight / (K + rank)}, summed when a candidate appears in both
     * channels. The scale keeps the top-rank contribution close to the configured weight.
     *
     * @param vectorResults   vector retrieval results (may be null or empty)
     * @param fulltextResults fulltext retrieval results (may be null or empty)
     * @param limit           maximum number of results to return
     * @param vectorWeight    weight for vector scores (finite, 0.0–1.0)
     * @param fulltextWeight  weight for fulltext scores (finite, 0.0–1.0)
     * @return fused and sorted results
     * @throws IllegalArgumentException if either weight is non-finite or outside 0.0–1.0
     */
    public static List<RetrievalResult> fuseResults(
            List<RetrievalResult> vectorResults,
            List<RetrievalResult> fulltextResults,
            int limit, float vectorWeight, float fulltextWeight) {

        validateWeight("vectorWeight", vectorWeight);
        validateWeight("fulltextWeight", fulltextWeight);
        if (limit <= 0) {
            return List.of();
        }
        if (vectorResults == null) vectorResults = List.of();
        if (fulltextResults == null) fulltextResults = List.of();

        Map<String, MergedEntry> merged = new LinkedHashMap<>();
        mergeChannel(merged, vectorResults, vectorWeight, true);
        mergeChannel(merged, fulltextResults, fulltextWeight, false);

        return merged.values().stream()
                .sorted(FUSED_ORDER)
                .limit(limit)
                .map(RetrievalUtils::toRetrievalResult)
                .toList();
    }

    private static void mergeChannel(
            Map<String, MergedEntry> merged,
            List<RetrievalResult> results,
            float weight,
            boolean vectorChannel) {
        List<RetrievalResult> ranked = results.stream()
                .filter(result -> result != null)
                .sorted(CHANNEL_ORDER)
                .toList();
        for (int index = 0; index < ranked.size(); index++) {
            RetrievalResult result = ranked.get(index);
            String key = identityKey(result);
            MergedEntry entry = merged.computeIfAbsent(
                    key, ignored -> new MergedEntry(result));
            if (vectorChannel) {
                if (entry.vectorSeen) {
                    continue;
                }
                entry.vectorSeen = true;
                entry.vectorScore = result.getScore();
            } else {
                if (entry.fulltextSeen) {
                    continue;
                }
                entry.fulltextSeen = true;
                entry.fulltextScore = result.getScore();
            }
            int rank = index + 1;
            entry.fusedScore += RRF_SCALE * weight / (RRF_K + rank);
        }
    }

    private static void validateWeight(String name, float weight) {
        if (!Float.isFinite(weight) || weight < 0.0f || weight > 1.0f) {
            throw new IllegalArgumentException(
                    name + " must be finite and between 0.0 and 1.0, got " + weight);
        }
    }

    private static int compareChannelResults(
            RetrievalResult left, RetrievalResult right) {
        boolean leftFinite = Double.isFinite(left.getScore());
        boolean rightFinite = Double.isFinite(right.getScore());
        if (leftFinite != rightFinite) {
            return leftFinite ? -1 : 1;
        }
        if (leftFinite) {
            int byScore = Double.compare(right.getScore(), left.getScore());
            if (byScore != 0) {
                return byScore;
            }
        }
        return compareIdentity(left, right);
    }

    private static int compareIdentity(
            RetrievalResult left, RetrievalResult right) {
        int byDocument = Comparator.nullsFirst(String::compareTo)
                .compare(left.getDocumentId(), right.getDocumentId());
        return byDocument != 0
                ? byDocument
                : Integer.compare(left.getChunkIndex(), right.getChunkIndex());
    }

    private static String identityKey(RetrievalResult result) {
        return result.getDocumentId() + ":" + result.getChunkIndex();
    }

    /**
     * Creates a retrieval result (test helper).
     */
    public static RetrievalResult createResult(String docId, String chunkText,
                                                int chunkIndex, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setTitle(docId);
        r.setChunkText(chunkText);
        r.setChunkIndex(chunkIndex);
        r.setScore(score);
        r.setVectorScore(score);
        r.setFulltextScore(score);
        return r;
    }

    private static RetrievalResult toRetrievalResult(MergedEntry e) {
        RetrievalResult r = e.original;
        RetrievalResult out = new RetrievalResult();
        out.setDocumentId(r.getDocumentId());
        out.setTitle(r.getTitle());
        out.setChunkText(r.getChunkText());
        out.setScore(e.fusedScore);
        out.setVectorScore(e.vectorScore);
        out.setFulltextScore(e.fulltextScore);
        out.setChunkIndex(r.getChunkIndex());
        out.setMetadata(r.getMetadata());
        RetrievalResultProvenance.copy(r, out);
        return out;
    }

    private static class MergedEntry {
        final RetrievalResult original;
        double fusedScore;
        double vectorScore;
        double fulltextScore;
        boolean vectorSeen;
        boolean fulltextSeen;

        MergedEntry(RetrievalResult r) {
            this.original = r;
            this.fusedScore = 0.0;
            this.vectorScore = 0.0;
            this.fulltextScore = 0.0;
        }
    }
}
