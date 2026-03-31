package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.*;

/**
 * 检索算法工具类
 *
 * <p>提取自 HybridRetrieverService 的纯算法方法，便于独立测试和复用。
 */
public final class RetrievalUtils {

    private RetrievalUtils() {
    }

    /**
     * 余弦相似度计算
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 相似度 [-1, 1]，维度不匹配返回 0
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
     * 将 float 数组转换为 pgvector 格式字符串 "[0.1,0.2,...]"
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
     * 解析数据库中的向量表示（float[]、double[] 或 String）
     */
    public static float[] parseVector(Object vectorObj) {
        if (vectorObj == null) {
            return new float[0];
        }
        if (vectorObj instanceof float[]) {
            return (float[]) vectorObj;
        }
        if (vectorObj instanceof double[]) {
            double[] d = (double[]) vectorObj;
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) {
                f[i] = (float) d[i];
            }
            return f;
        }
        if (vectorObj instanceof String) {
            String s = ((String) vectorObj).replaceAll("[\\[\\] ]", "");
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
        return new float[0];
    }

    /**
     * 检索结果分数融合
     *
     * <p>将向量检索和全文检索的结果归一化后加权合并，按融合分数降序返回。
     *
     * @param vectorResults  向量检索结果
     * @param fulltextResults 全文检索结果
     * @param limit          返回数量上限
     * @param vectorWeight   向量权重
     * @param fulltextWeight 全文权重
     * @return 融合后的排序结果
     */
    public static List<RetrievalResult> fuseResults(
            List<RetrievalResult> vectorResults,
            List<RetrievalResult> fulltextResults,
            int limit, float vectorWeight, float fulltextWeight) {

        if (vectorResults == null) vectorResults = List.of();
        if (fulltextResults == null) fulltextResults = List.of();

        float maxVector = vectorResults.isEmpty() ? 1f :
                (float) vectorResults.stream()
                        .mapToDouble(RetrievalResult::getScore).max().orElse(1f);
        float maxFulltext = fulltextResults.isEmpty() ? 1f :
                (float) fulltextResults.stream()
                        .mapToDouble(RetrievalResult::getScore).max().orElse(1f);

        Map<String, MergedEntry> merged = new LinkedHashMap<>();

        for (RetrievalResult r : vectorResults) {
            String key = r.getDocumentId() + ":" + r.getChunkIndex();
            float normalizedScore = (float) (r.getScore() / maxVector) * vectorWeight;
            MergedEntry entry = merged.computeIfAbsent(key, k -> new MergedEntry(r));
            entry.fusedScore = Math.max(entry.fusedScore, normalizedScore);
            entry.vectorScore = Math.max(entry.vectorScore, (float) r.getScore());
        }

        for (RetrievalResult r : fulltextResults) {
            String key = r.getDocumentId() + ":" + r.getChunkIndex();
            float normalizedScore = (float) ((r.getScore() / maxFulltext) * fulltextWeight);
            MergedEntry entry = merged.computeIfAbsent(key, k -> new MergedEntry(r));
            entry.fusedScore = Math.max(entry.fusedScore, normalizedScore);
            entry.fulltextScore = Math.max(entry.fulltextScore, (float) r.getScore());
        }

        return merged.values().stream()
                .sorted((a, b) -> Float.compare(b.fusedScore, a.fusedScore))
                .limit(limit)
                .map(e -> {
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
                })
                .toList();
    }

    /**
     * 创建检索结果（测试辅助）
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
