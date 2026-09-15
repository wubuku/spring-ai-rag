package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaticKnowledge 配置校验矩阵（Batch 408，经
 * RagChatProperties.validate() 公共入口驱动）：六项正值下限、
 * chunk-overlap 区间、visibility 大小写、fileExtensions 的空值/
 * 空白/路径分隔符拒绝。
 */
class StaticKnowledgeValidationTailTest {

    private RagChatProperties chat;

    @BeforeEach
    void setUp() {
        chat = new RagChatProperties();
    }

    private RagChatProperties.StaticKnowledgeProperties knowledge() {
        return chat.getStaticKnowledge();
    }

    private void assertInvalid(String expectedFragment) {
        IllegalStateException error = assertThrows(
                IllegalStateException.class, chat::validate);
        assertTrue(error.getMessage().contains(expectedFragment),
                "expected message to contain '" + expectedFragment
                        + "' but was: " + error.getMessage());
    }

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(chat::validate);
    }

    @Test
    void positiveBudgetsMustStayAboveZero() {
        String[] keys = {
                "max-files-per-root", "max-file-bytes", "max-total-bytes",
                "chunk-max-characters", "retrieval-max-results",
                "retrieval-max-result-characters"};

        for (String key : keys) {
            RagChatProperties fresh = new RagChatProperties();
            RagChatProperties.StaticKnowledgeProperties target =
                    fresh.getStaticKnowledge();
            switch (key) {
                case "max-files-per-root" -> target.setMaxFilesPerRoot(0);
                case "max-file-bytes" -> target.setMaxFileBytes(0);
                case "max-total-bytes" -> target.setMaxTotalBytes(-1);
                case "chunk-max-characters" -> target.setChunkMaxCharacters(0);
                case "retrieval-max-results" -> target.setRetrievalMaxResults(0);
                case "retrieval-max-result-characters" ->
                        target.setRetrievalMaxResultCharacters(0);
                default -> throw new IllegalStateException(key);
            }
            IllegalStateException error = assertThrows(
                    IllegalStateException.class, fresh::validate);
            assertTrue(error.getMessage().contains(key),
                    key + " should be named in: " + error.getMessage());
        }
    }

    @Test
    void chunkOverlapMustBeNonNegativeAndBelowChunkMax() {
        knowledge().setChunkOverlapCharacters(-1);
        assertInvalid("chunk-overlap-characters");

        RagChatProperties fresh = new RagChatProperties();
        fresh.getStaticKnowledge().setChunkOverlapCharacters(
                fresh.getStaticKnowledge().getChunkMaxCharacters());
        assertInvalid(fresh, "chunk-overlap-characters");

        RagChatProperties boundary = new RagChatProperties();
        boundary.getStaticKnowledge().setChunkOverlapCharacters(
                boundary.getStaticKnowledge().getChunkMaxCharacters() - 1);
        assertDoesNotThrow(boundary::validate);
    }

    @Test
    void visibilityIsCaseInsensitivelyGlobal() {
        knowledge().setVisibility("global");
        assertDoesNotThrow(chat::validate);

        knowledge().setVisibility("LOCAL");
        assertInvalid("visibility must be GLOBAL");
    }

    @Test
    void fileExtensionsMayNotBeNullOrEmpty() {
        knowledge().setFileExtensions(null);
        assertInvalid("file-extensions are invalid");

        knowledge().setFileExtensions(List.of());
        assertInvalid("file-extensions are invalid");
    }

    @Test
    void fileExtensionsRejectBlankAndPathSeparators() {
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        knowledge().setFileExtensions(withNull);
        assertInvalid("file-extensions are invalid");

        knowledge().setFileExtensions(List.of("md", "  "));
        assertInvalid("file-extensions are invalid");

        knowledge().setFileExtensions(List.of("a/b"));
        assertInvalid("file-extensions are invalid");

        knowledge().setFileExtensions(List.of("a\\b"));
        assertInvalid("file-extensions are invalid");
    }

    @Test
    void uppercaseExtensionsAreAccepted() {
        knowledge().setFileExtensions(List.of("MD", "Txt"));
        assertDoesNotThrow(chat::validate);
    }

    private void assertInvalid(RagChatProperties properties,
                               String expectedFragment) {
        IllegalStateException error = assertThrows(
                IllegalStateException.class, properties::validate);
        assertTrue(error.getMessage().contains(expectedFragment));
    }
}
