package com.springairag.core.retrieval.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import com.springairag.core.retrieval.RetrievalResultProvenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP rerank provider compatible with SiliconFlow / OpenAI-style {@code /v1/rerank} APIs:
 * <pre>
 * POST {baseUrl}/v1/rerank
 * { "model": "...", "query": "...", "documents": ["..."], "top_n": N }
 * </pre>
 */
public class HttpRerankProvider implements RerankProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpRerankProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagRerankProperties config;
    private final RestClient restClient;
    private final HeuristicRerankProvider heuristicFallback;

    public HttpRerankProvider(RagRerankProperties config) {
        this.config = config != null ? config : new RagRerankProperties();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = Math.max(1000, this.config.getTimeoutMs());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.heuristicFallback = new HeuristicRerankProvider(this.config);
    }

    /** Test-visible constructor. */
    HttpRerankProvider(RagRerankProperties config, RestClient restClient) {
        this.config = config != null ? config : new RagRerankProperties();
        this.restClient = restClient;
        this.heuristicFallback = new HeuristicRerankProvider(this.config);
    }

    @Override
    public String getName() {
        return "http";
    }

    @Override
    public boolean isAvailable() {
        String key = config.getApiKey();
        String base = config.getBaseUrl();
        return key != null && !key.isBlank() && base != null && !base.isBlank();
    }

    @Override
    public List<RetrievalResult> rerank(
            String query,
            List<RetrievalResult> results,
            int rankingDepth) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        if (!isAvailable()) {
            log.warn("HttpRerankProvider not available (missing api-key/base-url); using heuristic fallback");
            return fallback(query, results, rankingDepth);
        }

        int topN = rankingDepth > 0
                ? rankingDepth
                : (config.getTopN() > 0 ? config.getTopN() : results.size());
        try {
            List<String> documents = results.stream()
                    .map(r -> r.getChunkText() != null ? r.getChunkText() : "")
                    .toList();

            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getModel());
            body.put("query", query != null ? query : "");
            body.put("documents", documents);
            body.put("top_n", topN);

            String base = config.getBaseUrl().replaceAll("/+$", "");
            // Spring AI embedding uses base without /v1; rerank endpoint is typically /v1/rerank
            String url = base.endsWith("/v1") ? base + "/rerank" : base + "/v1/rerank";

            String responseBody = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return mapResponse(responseBody, results, topN);
        } catch (Exception e) {
            log.warn("HTTP rerank failed: {} — falling back", e.getMessage());
            return fallback(query, results, rankingDepth);
        }
    }

    private List<RetrievalResult> fallback(
            String query,
            List<RetrievalResult> results,
            int rankingDepth) {
        if (config.isFallbackToHeuristic()) {
            return heuristicFallback.rerank(query, results, rankingDepth);
        }
        int limit = rankingDepth > 0
                ? Math.min(rankingDepth, results.size())
                : results.size();
        return results.subList(0, limit);
    }

    List<RetrievalResult> mapResponse(String responseBody, List<RetrievalResult> original, int topN)
            throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("empty rerank response");
        }
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode resultsNode = root.get("results");
        if (resultsNode == null || !resultsNode.isArray()) {
            // Some APIs nest under data
            resultsNode = root.path("data").path("results");
        }
        if (resultsNode == null || !resultsNode.isArray() || resultsNode.isEmpty()) {
            throw new IllegalStateException("no results array in rerank response");
        }

        List<ScoredIndex> scored = new ArrayList<>();
        for (JsonNode item : resultsNode) {
            int index = item.path("index").asInt(-1);
            double score = item.path("relevance_score").asDouble(
                    item.path("score").asDouble(0));
            if (index >= 0 && index < original.size()) {
                scored.add(new ScoredIndex(index, score));
            }
        }
        if (scored.isEmpty()) {
            throw new IllegalStateException("no valid indices in rerank response");
        }
        scored.sort(Comparator.comparingDouble(ScoredIndex::score).reversed());

        List<RetrievalResult> out = new ArrayList<>();
        for (ScoredIndex si : scored) {
            if (out.size() >= topN) {
                break;
            }
            RetrievalResult src = original.get(si.index());
            RetrievalResult copy = new RetrievalResult();
            copy.setDocumentId(src.getDocumentId());
            copy.setTitle(src.getTitle());
            copy.setChunkText(src.getChunkText());
            copy.setScore(si.score());
            copy.setVectorScore(src.getVectorScore());
            copy.setFulltextScore(src.getFulltextScore());
            copy.setChunkIndex(src.getChunkIndex());
            copy.setMetadata(src.getMetadata());
            RetrievalResultProvenance.copy(src, copy);
            out.add(copy);
        }
        return out;
    }

    private record ScoredIndex(int index, double score) {}
}
