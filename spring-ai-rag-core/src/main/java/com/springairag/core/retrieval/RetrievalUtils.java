package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval algorithm utilities.
 *
 * <p>Extracted from HybridRetrieverService for independent testing and reuse.
 */
public final class RetrievalUtils {

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
     * Fuses vector and fulltext retrieval results using normalized score fusion.
     *
     * <p>Normalizes both result sets to [0,1] range, applies weights, merges overlapping
     * entries (same document+chunk), and returns results sorted by fused score descending.
     *
     * @param vectorResults   vector retrieval results (may be null or empty)
     * @param fulltextResults fulltext retrieval results (may be null or empty)
     * @param limit          maximum number of results to return
     * @param vectorWeight   weight for vector scores (0.0–1.0)
     * @param fulltextWeight weight for fulltext scores (0.0–1.0)
     * @return fused and sorted results
     */
    public static List<RetrievalResult> fuseResults(
            List<RetrievalResult> vectorResults,
            List<RetrievalResult> fulltextResults,
            int limit, float vectorWeight, float fulltextWeight) {

        if (vectorResults == null) vectorResults = List.of();
        if (fulltextResults == null) fulltextResults = List.of();

        Map<String, MergedEntry> merged = buildMergedEntries(
                vectorResults, fulltextResults, vectorWeight, fulltextWeight);

        return merged.values().stream()
                .sorted((a, b) -> Float.compare(b.fusedScore, a.fusedScore))
                .limit(limit)
                .map(RetrievalUtils::toRetrievalResult)
                .toList();
    }

    private static Map<String, MergedEntry> buildMergedEntries(
            List<RetrievalResult> vectorResults, List<RetrievalResult> fulltextResults,
            float vectorWeight, float fulltextWeight) {
        float maxVector = maxScore(vectorResults);
        float maxFulltext = maxScore(fulltextResults);
        Map<String, MergedEntry> merged = new LinkedHashMap<>();

        for (RetrievalResult r : vectorResults) {
            String key = r.getDocumentId() + ":" + r.getChunkIndex();
            // Guard against division by zero (maxVector == 0 means all scores are 0)
            float normalized = (Float.isNaN(maxVector) || maxVector == 0f) ? 0f : (float) (r.getScore() / maxVector) * vectorWeight;
            // Math.max with NaN returns NaN, so use a helper that treats NaN as -inf
            MergedEntry entry = merged.computeIfAbsent(key, k -> new MergedEntry(r));
            entry.fusedScore = maxWithNaN(entry.fusedScore, normalized);
            entry.vectorScore = maxWithNaN(entry.vectorScore, (float) r.getScore());
        }

        for (RetrievalResult r : fulltextResults) {
            String key = r.getDocumentId() + ":" + r.getChunkIndex();
            // Guard against division by zero (maxFulltext == 0 means all scores are 0)
            float normalized = (Float.isNaN(maxFulltext) || maxFulltext == 0f) ? 0f : (float) ((r.getScore() / maxFulltext) * fulltextWeight);
            MergedEntry entry = merged.computeIfAbsent(key, k -> new MergedEntry(r));
            entry.fusedScore = maxWithNaN(entry.fusedScore, normalized);
            entry.fulltextScore = maxWithNaN(entry.fulltextScore, (float) r.getScore());
        }
        return merged;
    }

    /**
     * Like Math.max but treats NaN as negative infinity.
     * Math.max(Float.NaN, x) returns NaN, which is not the max semantics we want.
     */
    private static float maxWithNaN(float a, float b) {
        if (Float.isNaN(a)) return b;
        if (Float.isNaN(b)) return a;
        return Math.max(a, b);
    }

    private static float maxScore(List<RetrievalResult> results) {
        if (results.isEmpty()) return 1f;
        double max = results.stream().mapToDouble(RetrievalResult::getScore).max().orElse(1f);
        // Math.max(NaN, x) returns NaN, so replace NaN with 0
        return (float) (Double.isNaN(max) ? 0f : max);
    }

    /**
     * Creates a retrieval result (test helper).
     */
    public static RetrievalResult createResult(String docId, String chunkText,
                                                int chunkIndex, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
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
        out.setChunkText(r.getChunkText());
        out.setScore(e.fusedScore);
        out.setVectorScore(e.vectorScore);
        out.setFulltextScore(e.fulltextScore);
        out.setChunkIndex(r.getChunkIndex());
        out.setMetadata(r.getMetadata());
        return out;
    }

    private static class MergedEntry {
        final RetrievalResult original;
        float fusedScore;
        float vectorScore;
        float fulltextScore;

        MergedEntry(RetrievalResult r) {
            this.original = r;
            this.fusedScore = 0f;
            this.vectorScore = 0f;
            this.fulltextScore = 0f;
        }
    }
}
