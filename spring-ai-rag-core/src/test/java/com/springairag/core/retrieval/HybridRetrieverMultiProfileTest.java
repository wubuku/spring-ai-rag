package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileModelResolver;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.EmbeddingException;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverMultiProfileTest {

    private final EmbeddingProfile defaultProfile = profile(1L, "default", 1024);
    private final EmbeddingProfile firstProfile = profile(11L, "first", 384);
    private final EmbeddingProfile secondProfile = profile(22L, "second", 768);
    private final EmbeddingProfileModelResolver modelResolver =
            mock(EmbeddingProfileModelResolver.class);
    private final EmbeddingModel defaultModel = mock(EmbeddingModel.class);
    private final EmbeddingModel firstModel = mock(EmbeddingModel.class);
    private final EmbeddingModel secondModel = mock(EmbeddingModel.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EmbeddingProfileProvider profileProvider =
            mock(EmbeddingProfileProvider.class);
    private HybridRetrieverService service;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        service = new HybridRetrieverService(
                defaultModel, profileProvider, jdbcTemplate, properties, null, Runnable::run);
        service.setProfileModelResolver(modelResolver);
        when(profileProvider.getActiveProfile()).thenReturn(defaultProfile);
        when(profileProvider.findRequiredById(11L)).thenReturn(firstProfile);
        when(profileProvider.findRequiredById(22L)).thenReturn(secondProfile);
        when(modelResolver.resolve(firstProfile)).thenReturn(firstModel);
        when(modelResolver.resolve(secondProfile)).thenReturn(secondModel);
        when(jdbcTemplate.queryForList(contains("SELECT DISTINCT CASE"), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of("profile_id", 11L, "collection_id", 101L),
                        Map.of("profile_id", 22L, "collection_id", 202L)));
        when(firstModel.embed("query")).thenReturn(vector(384));
        when(secondModel.embed("query")).thenReturn(vector(768));
    }

    @Test
    void routesEachProfileToItsDimensionColumnAndFusesByRank() {
        when(jdbcTemplate.queryForList(contains("e.embedding_384 <=>"), any(Object[].class)))
                .thenReturn(List.of(row(1L, "a", 0, 384), row(2L, "b", 0, 384)));
        when(jdbcTemplate.queryForList(contains("e.embedding_768 <=>"), any(Object[].class)))
                .thenReturn(List.of(row(3L, "b", 0, 768), row(4L, "c", 0, 768)));

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                "query", RetrievalScope.forDocumentIds(List.of(1L, 2L)), List.of(), 10,
                RetrievalConfig.builder().maxResults(10).minScore(0).useRerank(false).build(),
                RetrievalFilters.none());

        assertEquals(3, outcome.results().size());
        assertEquals("b", outcome.results().getFirst().getDocumentId());
        assertTrue(outcome.results().getFirst().getScore()
                > outcome.results().get(1).getScore());
        assertEquals(2, outcome.scopeSummary().get("embeddingProfileFanoutCount"));
        verify(firstModel).embed("query");
        verify(secondModel).embed("query");
        verify(jdbcTemplate).queryForList(contains("e.embedding_384 <=>"), any(Object[].class));
        verify(jdbcTemplate).queryForList(contains("e.embedding_768 <=>"), any(Object[].class));
    }

    @Test
    void rejectsPartialResultsWhenAnyRequiredProfileFails() {
        when(jdbcTemplate.queryForList(contains("e.embedding_384 <=>"), any(Object[].class)))
                .thenReturn(List.of(row(1L, "a", 0, 384)));
        when(secondModel.embed("query")).thenThrow(new IllegalStateException("provider timeout"));

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> service.searchInScopeDetailed(
                        "query", RetrievalScope.forDocumentIds(List.of(1L, 2L)), List.of(), 10,
                        RetrievalConfig.builder().maxResults(10).minScore(0)
                                .useRerank(false).build(),
                        RetrievalFilters.none()));

        assertEquals(ErrorCode.EMBEDDING_FAILED, exception.getErrorCodeEnum());
        verify(jdbcTemplate, never())
                .queryForList(contains("e.embedding_768 <=>"), any(Object[].class));
    }

    @Test
    void rejectsScopeExceedingConfiguredFanoutBeforeEmbeddingCalls() {
        service = serviceWithFanoutLimit(1);

        RagException exception = assertThrows(RagException.class,
                () -> service.searchInScopeDetailed(
                        "query", RetrievalScope.forDocumentIds(List.of(1L, 2L)), List.of(), 10,
                        RetrievalConfig.builder().maxResults(10).useRerank(false).build(),
                        RetrievalFilters.none()));

        assertEquals(ErrorCode.EMBEDDING_PROFILE_FANOUT_LIMIT_EXCEEDED,
                exception.getErrorCodeEnum());
        verify(firstModel, never()).embed(anyString());
        verify(secondModel, never()).embed(anyString());
    }

    private HybridRetrieverService serviceWithFanoutLimit(int limit) {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        properties.getRetrieval().setMaxEmbeddingProfileFanout(limit);
        HybridRetrieverService limited = new HybridRetrieverService(
                defaultModel, profileProvider, jdbcTemplate, properties, null, Runnable::run);
        limited.setProfileModelResolver(modelResolver);
        return limited;
    }

    private EmbeddingProfile profile(long id, String key, int dimensions) {
        return new EmbeddingProfile(id, key, "openai-compatible", key, "rev-1",
                dimensions, "COSINE", "PROVIDER_DEFAULT", true);
    }

    private float[] vector(int dimensions) {
        float[] vector = new float[dimensions];
        Arrays.fill(vector, 1.0f / dimensions);
        return vector;
    }

    private Map<String, Object> row(long id, String documentId, int chunkIndex, int dimensions) {
        return Map.of(
                "id", id,
                "document_id", documentId,
                "chunk_index", chunkIndex,
                "chunk_text", "chunk-" + documentId,
                "embedding", vector(dimensions),
                "metadata", Map.of());
    }
}
