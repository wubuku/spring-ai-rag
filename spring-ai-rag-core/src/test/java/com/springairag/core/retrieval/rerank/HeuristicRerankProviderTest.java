package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicRerankProviderTest {

    private final HeuristicRerankProvider provider =
            new HeuristicRerankProvider(new RagRerankProperties());

    @Test
    void rerank_preservesProvenanceFields() {
        RetrievalResult source = new RetrievalResult();
        source.setDocumentId("1");
        source.setTitle("ZX-9042 液压校准规范");
        source.setChunkText("matching content");
        source.setScore(0.8);
        source.setVectorScore(0.7);
        source.setFulltextScore(0.6);
        source.setChunkIndex(3);
        source.setMetadata(Map.of("section", "calibration"));
        source.setSource("pdf-import:uuid/default.md");
        source.setOriginalFilename("manual.pdf");
        source.setFileDirectoryPath("uuid/");
        source.setIndexedFilePath("uuid/default.md");
        source.setOriginalFilePath("uuid/original.pdf");

        RetrievalResult result = new HeuristicRerankProvider(
                new RagRerankProperties())
                .rerank("matching", List.of(source), 1)
                .get(0);

        assertEquals(source.getTitle(), result.getTitle());
        assertEquals(source.getVectorScore(), result.getVectorScore());
        assertEquals(source.getFulltextScore(), result.getFulltextScore());
        assertEquals(source.getChunkIndex(), result.getChunkIndex());
        assertSame(source.getMetadata(), result.getMetadata());
        assertEquals(source.getSource(), result.getSource());
        assertEquals(source.getOriginalFilename(), result.getOriginalFilename());
        assertEquals(source.getFileDirectoryPath(), result.getFileDirectoryPath());
        assertEquals(source.getIndexedFilePath(), result.getIndexedFilePath());
        assertEquals(source.getOriginalFilePath(), result.getOriginalFilePath());
    }

    @Test
    void relevanceScore_matchesReorderedCjkBigramsWithoutWhitespace() {
        float relevant = provider.calculateRelevanceScore(
                "中文检索质量优化",
                "检索质量需要结合中文分词进行优化");
        float unrelated = provider.calculateRelevanceScore(
                "中文检索质量优化",
                "账户权限审批流程和用量账本统计");

        assertTrue(relevant > 0.5f);
        assertEquals(0f, unrelated, 1e-6);
    }

    @Test
    void relevanceScore_supportsMixedLatinCjkAndDigits() {
        float score = provider.calculateRelevanceScore(
                "SpringAI中文RAG2",
                "RAG2 使用 SPRINGAI 构建中文检索");

        assertTrue(score > 0.8f);
    }

    @Test
    void relevanceScore_preservesEnglishWhitespaceSemantics() {
        assertEquals(
                1.0f,
                provider.calculateRelevanceScore(
                        "Spring Boot",
                        "Spring Boot is a framework"),
                1e-6);
        assertEquals(
                0.8f,
                provider.calculateRelevanceScore(
                        "Spring Boot",
                        "Spring is a framework"),
                1e-6);
    }

    @Test
    void relevanceScore_rejectsEmbeddedLatinAndNumericMatches() {
        assertEquals(
                0f,
                provider.calculateRelevanceScore(
                        "rag ai 9042",
                        "storage chair 19042"),
                1e-6);
        assertEquals(
                0f,
                provider.calculateRelevanceScore(
                        "ai",
                        "OpenAI reference"),
                1e-6);
    }

    @Test
    void relevanceScore_acceptsOuterPunctuationAndSeparatedIdentifiers() {
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "RAG?",
                        "RAG-based retrieval"),
                1e-6);
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "\"AI\"",
                        "AI systems"),
                1e-6);
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "(ZX-9042)",
                        "ZX-9042 reference"),
                1e-6);
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "SpringAI",
                        "中文SpringAI检索"),
                1e-6);
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "9042",
                        "型号9042说明"),
                1e-6);
    }

    @Test
    void relevanceScore_preservesSymbolBearingTechnicalTerms() {
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "C++",
                        "C++ reference"),
                1e-6);
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "C#",
                        "C# reference"),
                1e-6);
        assertEquals(
                1f,
                provider.calculateRelevanceScore(
                        "api/v1",
                        "api/v1 reference"),
                1e-6);
    }

    @Test
    void relevanceScore_usesLaterBoundaryMatchAfterEmbeddedOccurrence() {
        String text = "storage " + "filler ".repeat(10) + "rag";

        assertEquals(
                0.5f,
                provider.calculateRelevanceScore(
                        "rag missing",
                        text),
                1e-6);
    }

    @Test
    void relevanceScore_blankInputReturnsZeroAndPunctuationStaysFinite() {
        assertEquals(0f, provider.calculateRelevanceScore("", "text"));
        assertEquals(0f, provider.calculateRelevanceScore("   ", "text"));

        float punctuation = provider.calculateRelevanceScore("---", "---");
        assertTrue(Float.isFinite(punctuation));
        assertTrue(punctuation >= 0f && punctuation <= 1f);
    }

    @Test
    void textSimilarity_detectsCjkOverlapAndSingleCharacter() {
        float partial = provider.calculateTextSimilarity(
                "中文检索质量",
                "检索质量评估");

        assertTrue(partial > 0f && partial < 1f);
        assertEquals(1f, provider.calculateTextSimilarity("中", "中"), 1e-6);
        assertEquals(0f, provider.calculateTextSimilarity("a", "a"), 1e-6);
    }

    @Test
    void lexicalScoring_longCjkInputRemainsFiniteAndStable() {
        String longText = "中文检索质量优化".repeat(300);

        float relevance = provider.calculateRelevanceScore(
                "中文检索质量优化",
                longText);
        float similarity = provider.calculateTextSimilarity(longText, longText);

        assertTrue(Float.isFinite(relevance));
        assertEquals(1f, similarity, 1e-6);
    }

    @Test
    void rerank_promotesCjkRelevantCandidateWithDefaultWeight() {
        RetrievalResult distractor = result(
                "distractor",
                "账户权限审批流程和用量账本统计",
                1.0);
        RetrievalResult relevant = result(
                "relevant",
                "检索质量需要结合中文分词进行优化",
                0.99);

        List<RetrievalResult> reranked = provider.rerank(
                "中文检索质量优化",
                List.of(distractor, relevant),
                2);

        assertEquals("relevant", reranked.getFirst().getDocumentId());
    }

    @Test
    void rerank_promotesTitleOnlyMatchesAcrossEnglishCjkAndMixedIds() {
        assertTitleOnlyCandidatePromoted(
                "Spring AI",
                "Spring AI");
        assertTitleOnlyCandidatePromoted(
                "液压校准规范",
                "液压校准规范");
        assertTitleOnlyCandidatePromoted(
                "ZX-9042 液压校准",
                "ZX-9042 液压校准");
    }

    @Test
    void rerank_rejectsBoundaryFalsePositiveTitle() {
        RetrievalResult distractor = titledResult(
                "distractor",
                "Storage Chair 19042",
                "shared neutral evidence",
                1.0);
        RetrievalResult relevant = titledResult(
                "relevant",
                "RAG AI ZX-9042",
                "shared neutral evidence",
                0.99);

        List<RetrievalResult> reranked = provider.rerank(
                "RAG AI 9042",
                List.of(distractor, relevant),
                2);

        assertEquals("relevant", reranked.getFirst().getDocumentId());
        assertTrue(reranked.getFirst().getScore() > reranked.getLast().getScore());
    }

    @Test
    void rerank_blankTitlePreservesPreviousScoresAndOrderExactly() {
        List<RetrievalResult> withoutTitles = List.of(
                result("first", "Spring AI overview", 0.8),
                result("second", "unrelated content", 0.7));
        List<RetrievalResult> blankTitles = List.of(
                titledResult("first", null, "Spring AI overview", 0.8),
                titledResult("second", "   ", "unrelated content", 0.7));

        List<RetrievalResult> expected =
                provider.rerank("Spring AI", withoutTitles, 2);
        List<RetrievalResult> actual =
                provider.rerank("Spring AI", blankTitles, 2);

        assertEquals(
                expected.stream().map(RetrievalResult::getDocumentId).toList(),
                actual.stream().map(RetrievalResult::getDocumentId).toList());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(
                    expected.get(index).getScore(),
                    actual.get(index).getScore());
        }
    }

    @Test
    void rerank_titleCannotReduceStrongChunkRelevance() {
        RetrievalResult withoutTitle =
                result("without-title", "Spring AI reference", 0.5);
        RetrievalResult matchingTitle = titledResult(
                "matching-title",
                "Spring AI",
                "Spring AI reference",
                0.5);

        double baseline = provider.rerank(
                "Spring AI", List.of(withoutTitle), 1).getFirst().getScore();
        double withTitle = provider.rerank(
                "Spring AI", List.of(matchingTitle), 1).getFirst().getScore();

        assertEquals(baseline, withTitle);
    }

    @Test
    void rerank_titleDoesNotChangeChunkDiversity() {
        RagRerankProperties properties = new RagRerankProperties();
        properties.setDiversityWeight(1.0f);
        HeuristicRerankProvider diversityProvider =
                new HeuristicRerankProvider(properties);

        List<RetrievalResult> reranked = diversityProvider.rerank(
                "unmatched",
                List.of(
                        titledResult(
                                "first",
                                "Alpha operations",
                                "完全相同的重复证据",
                                0.9),
                        titledResult(
                                "second",
                                "Beta reference",
                                "完全相同的重复证据",
                                0.8)),
                2);

        assertEquals(0f, reranked.get(0).getScore(), 1e-6);
        assertEquals(0f, reranked.get(1).getScore(), 1e-6);
    }

    @Test
    void rerank_longInternalTitleRemainsFiniteAndTitleAware() {
        RetrievalResult candidate = titledResult(
                "long-title",
                "prefix " + "long ".repeat(2_000) + "target",
                "unrelated chunk",
                0.0);

        double score = provider.rerank(
                "target", List.of(candidate), 1).getFirst().getScore();

        assertTrue(Double.isFinite(score));
        assertEquals(0.19, score, 1e-6);
    }

    @Test
    void rerank_penalizesASeparateCandidateWithIdenticalChunkText() {
        RagRerankProperties properties = new RagRerankProperties();
        properties.setDiversityWeight(1.0f);
        HeuristicRerankProvider diversityProvider =
                new HeuristicRerankProvider(properties);
        RetrievalResult duplicateOne = result(
                "duplicate-1",
                "完全相同的重复证据",
                0.9);
        RetrievalResult duplicateTwo = result(
                "duplicate-2",
                "完全相同的重复证据",
                0.8);
        RetrievalResult distinct = result(
                "distinct",
                "另一条没有重叠的独立内容",
                0.7);

        List<RetrievalResult> reranked = diversityProvider.rerank(
                "unmatched",
                List.of(duplicateOne, duplicateTwo, distinct),
                3);

        assertEquals("distinct", reranked.getFirst().getDocumentId());
        assertEquals(0f, reranked.get(1).getScore(), 1e-6);
        assertEquals(0f, reranked.get(2).getScore(), 1e-6);
    }

    @Test
    void rerank_doesNotRewardBlankChunkDiversity() {
        RagRerankProperties properties = new RagRerankProperties();
        properties.setDiversityWeight(1.0f);
        HeuristicRerankProvider diversityProvider =
                new HeuristicRerankProvider(properties);

        List<RetrievalResult> reranked = diversityProvider.rerank(
                "unmatched",
                List.of(
                        result("blank", "   ", 0.9),
                        result("content", "可用的独立证据", 0.8)),
                2);

        assertEquals("content", reranked.getFirst().getDocumentId());
        assertEquals(0f, reranked.getLast().getScore(), 1e-6);
    }

    private static RetrievalResult result(
            String documentId,
            String chunkText,
            double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkText(chunkText);
        result.setScore(score);
        return result;
    }

    private static RetrievalResult titledResult(
            String documentId,
            String title,
            String chunkText,
            double score) {
        RetrievalResult result = result(documentId, chunkText, score);
        result.setTitle(title);
        return result;
    }

    private void assertTitleOnlyCandidatePromoted(
            String query,
            String relevantTitle) {
        RetrievalResult distractor = titledResult(
                "distractor",
                "Unrelated operations handbook",
                "shared neutral evidence",
                1.0);
        RetrievalResult relevant = titledResult(
                "relevant",
                relevantTitle,
                "shared neutral evidence",
                0.99);

        List<RetrievalResult> reranked = provider.rerank(
                query,
                List.of(distractor, relevant),
                2);

        assertEquals("relevant", reranked.getFirst().getDocumentId());
        assertEquals(0.882, reranked.getFirst().getScore(), 1e-6);
    }
}
