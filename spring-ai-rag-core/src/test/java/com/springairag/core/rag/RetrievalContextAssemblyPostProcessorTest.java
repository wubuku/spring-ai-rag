package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.PromptTokenEstimator;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.RetrievalContextRepository.Chunk;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalContextAssemblyPostProcessorTest {
    private final RagProperties properties = new RagProperties();
    private final RetrievalContextRepository repository = mock(RetrievalContextRepository.class);
    private final RetrievalTraceCollector trace = new RetrievalTraceCollector();
    private final AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
            RetrievalScope.unscoped(), null, trace, "context-test", null);
    private final Query query = new Query("query", List.of(), Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context));
    private final RetrievalDocumentMapper mapper = new RetrievalDocumentMapper();
    private final PromptTokenEstimator estimator = text -> text == null ? 0 : text.codePointCount(0, text.length());

    @Test
    void versionedQualityCasesProveRequiredFactsOverlapAndStableHandles() throws Exception {
        Path data = Path.of("testdata/regression/context-assembly-v1.json");
        if (!Files.exists(data)) { data = Path.of("../testdata/regression/context-assembly-v1.json"); }
        JsonNode fixture = new ObjectMapper().readTree(Files.readString(data));
        assertEquals(1, fixture.path("version").asInt());
        for (JsonNode test : fixture.path("cases")) {
            String source = test.path("source").asText();
            List<Document> seeds = new ArrayList<>();
            List<Chunk> rows = new ArrayList<>();
            for (int ordinal = 0; ordinal < test.path("seeds").size(); ordinal++) {
                int seedIndex = test.path("seeds").get(ordinal).asInt();
                JsonNode seed = test.path("chunks").get(seedIndex);
                seeds.add(anchor("1", seedIndex, seed.path("prefix").asText("") + seed.path("payload").asText()));
                for (int index = Math.max(0, seedIndex - 1); index <= Math.min(test.path("chunks").size() - 1, seedIndex + 1); index++) {
                    JsonNode chunk = test.path("chunks").get(index);
                    String payload = chunk.path("payload").asText();
                    int start = source.indexOf(payload);
                    assertTrue(start >= 0);
                    rows.add(new Chunk(ordinal, 1, 1, "hash", "v3", index, start, start + payload.length(),
                            chunk.path("prefix").asText("") + payload));
                }
            }
            trace.record(seeds.stream().map(mapper::toRetrievalResult).toList());
            when(repository.load(anyList(), eq(context), eq(1), eq(4000))).thenReturn(rows);
            List<Document> assembled = processor().process(query, seeds);
            String text = assembled.stream().map(Document::getText).reduce("", String::concat);
            for (JsonNode fact : test.path("requiredFacts")) { assertTrue(text.contains(fact.asText()), test.path("id").asText()); }
            if (test.has("uniqueFact")) {
                String unique = test.path("uniqueFact").asText();
                assertEquals(text.indexOf(unique), text.lastIndexOf(unique));
            }
            assertTrue(assembled.size() <= seeds.size());
            assertEquals(seeds.getFirst().getId(), assembled.getFirst().getId());
            assertTrue(assembled.getFirst().getMetadata().containsKey("contextAssembly"));
            var sourceProjection = mapper.toChatSource(assembled.getFirst(), "S1");
            assertFalse(sourceProjection.getMetadata().containsKey(RetrievalContextAssemblyPostProcessor.ANCHORS));
            assertTrue(sourceProjection.getMetadata().containsKey("contextAssembly"));
            String handle = trace.sourceHandle(mapper.toRetrievalResult(seeds.getFirst()));
            assertTrue(new CitationQueryAugmenter(properties).augment(query, assembled).text().contains("[" + handle + "]"));
        }
    }

    @Test
    void insufficientBudgetRestoresAnOriginalAnchorWithoutPhantomRanges() {
        Document first = anchor("1", 0, "甲甲");
        Document second = anchor("1", 1, "乙乙");
        register(first, second);
        when(repository.load(anyList(), any(), anyInt(), anyInt())).thenReturn(List.of(
                chunk(0, 0, 0, "甲甲"), chunk(0, 1, 2, "乙乙"), chunk(0, 2, 4, "丙丙丙丙"),
                chunk(1, 0, 0, "甲甲"), chunk(1, 1, 2, "乙乙"), chunk(1, 2, 4, "丙丙丙丙")));
        List<Document> assembled = processor().process(query, List.of(first, second));
        assertEquals(1, assembled.size());
        properties.getChat().getContext().setMaxRagContextTokens(4);
        var finalEvidence = new PromptBudgetDocumentPostProcessor(estimator, properties.getChat()).process(query, assembled);
        assertEquals(1, finalEvidence.size());
        assertEquals(first.getId(), finalEvidence.getFirst().getId());
        assertTrue(finalEvidence.getFirst().getText().contains("甲"));
        assertEquals(2, trace.contextAssembly().get("anchorFallbacks"));
        assertTrue(finalEvidence.stream().noneMatch(doc -> doc.getMetadata().containsKey("contextAssembly")));
        assertTrue(finalEvidence.stream().mapToInt(doc -> estimator.estimate(doc.getText())).sum() <= 4);
    }

    @Test
    void truncationDoesNotSplitSurrogateOrClaimUnseenNeighbors() {
        Document anchor = anchor("1", 1, "🧭甲乙");
        properties.getChat().getContext().setMaxRagContextTokens(1);
        var output = new PromptBudgetDocumentPostProcessor(estimator, properties.getChat()).process(query, List.of(anchor));
        assertEquals("🧭", output.getFirst().getText());
        assertTrue((Boolean) output.getFirst().getMetadata().get("contextBudgetTruncated"));
        assertFalse(output.getFirst().getMetadata().containsKey("contextAssembly"));
    }

    @Test
    void differentIdentityAndDocumentsNeverMergeOrReduceCoverage() {
        Document first = anchor("1", 0, "甲");
        Document second = anchor("2", 0, "乙");
        register(first, second);
        when(repository.load(anyList(), any(), anyInt(), anyInt())).thenReturn(List.of(
                chunk(0, 0, 0, "甲"), chunk(0, 1, 1, "丙"),
                new Chunk(1, 2, 2, "other", "v3", 0, 0, 1, "乙"),
                new Chunk(1, 2, 2, "other", "v3", 1, 1, 2, "丁")));
        assertEquals(2, processor().process(query, List.of(first, second)).size());
        assertEquals(2L, trace.contextAssembly().get("documentCoverage"));
    }

    @Test
    void disabledUnsupportedAndUnregisteredEvidenceNeverQueries() {
        Document doc = anchor("1", 1, "甲");
        properties.getChat().getContextAssembly().setEnabled(false);
        assertEquals(List.of(doc), processor().process(query, List.of(doc)));
        properties.getChat().getContextAssembly().setEnabled(true);
        assertEquals(List.of(doc), processor().process(query, List.of(doc)));
        register(doc);
        Document json = doc.mutate().metadata(Map.of("documentId", "1", "chunkIndex", 1, "documentType", "json-record")).build();
        assertEquals(List.of(json), processor().process(query, List.of(json)));
        Document staticDoc = doc.mutate().metadata(Map.of("documentId", "1", "chunkIndex", 1, "sourceType", "STATIC_KNOWLEDGE")).build();
        assertEquals(List.of(staticDoc), processor().process(query, List.of(staticDoc)));
        verifyNoInteractions(repository);
    }

    @Test
    void lookupFailureIsSafeAndDoesNotPretendToExpand() {
        Document doc = anchor("1", 1, "甲"); register(doc);
        when(repository.load(anyList(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("private-fixture"));
        assertEquals(List.of(doc), processor().process(query, List.of(doc)));
        assertEquals("CONTEXT_LOOKUP_UNAVAILABLE", trace.contextAssembly().get("degradedCode"));
        assertFalse(trace.summary().toString().contains("private-fixture"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2, 20})
    void invalidOrOversizedRangesFallBack(int start) {
        Document doc = anchor("1", 0, "甲"); register(doc);
        properties.getChat().getContextAssembly().setMaxWindowCharacters(256);
        when(repository.load(anyList(), any(), anyInt(), anyInt())).thenReturn(List.of(
                chunk(0, 0, 0, "甲"), new Chunk(0, 1, 1, "hash", "v3", 1, start, start + 1000, "乙")));
        assertEquals(List.of(doc), processor().process(query, List.of(doc)));
    }

    @Test
    void defaultsAndAllConfigurationBoundsAreValidated() {
        var config = properties.getChat().getContextAssembly();
        assertTrue(config.isEnabled()); assertEquals(1, config.getNeighborRadius());
        assertEquals(20, config.getMaxSeeds()); assertEquals(4000, config.getMaxWindowCharacters());
        properties.getChat().validate();
        config.setNeighborRadius(3); assertThrows(IllegalArgumentException.class, properties.getChat()::validate);
        config.setNeighborRadius(2); config.setMaxSeeds(41); assertThrows(IllegalArgumentException.class, config::validate);
        config.setMaxSeeds(40); config.setMaxWindowCharacters(16001); assertThrows(IllegalArgumentException.class, config::validate);
        config.setMaxWindowCharacters(256); config.validate();
    }

    private RetrievalContextAssemblyPostProcessor processor() { return new RetrievalContextAssemblyPostProcessor(repository, properties); }
    private Document anchor(String documentId, int index, String text) {
        return Document.builder().id(documentId + ":" + index).text(text)
                .metadata(Map.of("documentId", documentId, "chunkIndex", index, "score", 0.9)).build();
    }
    private Chunk chunk(int ordinal, int index, int start, String text) {
        return new Chunk(ordinal, 1, 1, "hash", "v3", index, start, start + text.length(), text);
    }
    private void register(Document... documents) { trace.record(List.of(documents).stream().map(mapper::toRetrievalResult).toList()); }
}
