package com.springairag.core.validation;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.SearchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入边界与畸形文本回归。
 *
 * <p>验证 API 边界在进入模型/数据库前统一拒绝超长与非法字符，并接受
 * 合法但“看起来奇怪”的文本（emoji、组合字符、RTL、零宽、长单词、同形字），
 * 保证不会 500、不会污染日志/导出。</p>
 */
class InputBoundaryValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> violations(Object value) {
        return validator.validate(value).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private String messages(Object value) {
        return validator.validate(value).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
    }

    private ChatRequest chat(String message) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        return request;
    }

    private SearchRequest search(String query) {
        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        return request;
    }

    private DocumentRequest document(String title, String content) {
        DocumentRequest request = new DocumentRequest();
        request.setTitle(title);
        request.setContent(content);
        return request;
    }

    @Test
    void oversizedQueryIsRejectedBeforeTheModel() {
        ChatRequest chat = chat("长".repeat(100_000));
        Set<String> violations = violations(chat);
        assertTrue(violations.contains("message"));
        assertTrue(messages(chat).contains("10000"));

        SearchRequest search = search("a".repeat(100_000));
        assertTrue(violations(search).contains("query"));
        assertTrue(messages(search).contains("10000"));
    }

    @Test
    void nulBytesAreRejectedWithExplicitMessages() {
        // PostgreSQL 的 text/jsonb 不能保存 NUL；必须在 API 边界变成 400，
        // 而不是数据库异常导致的 500。
        assertTrue(messages(chat("ok\u0000bad")).contains("NUL"));
        assertTrue(messages(search("ok\u0000bad")).contains("NUL"));

        DocumentRequest withNulTitle = document("bad\u0000title", "content");
        assertTrue(violations(withNulTitle).contains("title"));
        assertTrue(messages(withNulTitle).contains("NUL"));

        DocumentRequest withNulContent = document("title", "bad\u0000content");
        assertTrue(violations(withNulContent).contains("content"));
        assertTrue(messages(withNulContent).contains("NUL"));

        JsonRecordSearchRequest recordSearch = new JsonRecordSearchRequest();
        recordSearch.setQuery("bad\u0000query");
        assertTrue(messages(recordSearch).contains("NUL"));
    }

    @Test
    void blankAndWhitespaceOnlyInputIsRejected() {
        assertTrue(violations(chat("   \n\t  ")).contains("message"));
        assertTrue(violations(search("   \n\t  ")).contains("query"));
    }

    @Test
    void documentTitleAndContentBoundsAreEnforcedOnUpdateToo() {
        DocumentUpdateRequest update = new DocumentUpdateRequest();
        update.setExpectedDocumentRevision(1L);
        update.setTitle("t".repeat(256));
        update.setContent("c".repeat(1_000_001));
        Set<String> violations = violations(update);
        assertTrue(violations.contains("title"));
        assertTrue(violations.contains("content"));

        DocumentUpdateRequest nul = new DocumentUpdateRequest();
        nul.setExpectedDocumentRevision(1L);
        nul.setTitle("bad\u0000title");
        assertTrue(messages(nul).contains("NUL"));
    }

    @Test
    void longSingleWordAndLongTitleWithinBoundsAreAccepted() {
        assertTrue(violations(chat("a".repeat(10_000))).isEmpty());
        assertTrue(violations(document("t".repeat(255), "c".repeat(1_000_000)))
                .stream().filter(path -> !path.equals("collectionKey"))
                .toList().isEmpty());
    }

    @Test
    void emojiCombiningRtlZeroWidthAndHomoglyphsAreAccepted() {
        String emoji = "😀👍🏽".repeat(1_000);          // 代理对 + 肤色修饰
        String combining = "e\u0301".repeat(2_000);    // 组合字符
        String rtl = "مرحبا بالعالم hello 世界 123";     // RTL 与 LTR 混排
        String zeroWidth = "a\u200Bb\u200C\u200Dc\uFEFF"; // 零宽/格式字符
        String homoglyph = "раураl";                   // 西里尔同形字

        for (String text : new String[]{emoji, combining, rtl,
                zeroWidth, homoglyph}) {
            assertTrue(violations(chat(text)).isEmpty(), text);
            assertTrue(violations(search(text)).isEmpty(), text);
        }
        // emoji 按 UTF-16 计数：4000 个 emoji 完全在 10000 预算内。
        assertFalse(messages(chat(emoji)).contains("exceed"));
    }

    @Test
    void nestedJsonPayloadWithNulIsRejectedBeforeJsonbWrite() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DocumentRequest withNulMetadata = document("title", "content");
        withNulMetadata.setMetadata(Map.of(
                "nested", Map.of("note", "bad\u0000value")));
        assertTrue(violations(withNulMetadata).contains("metadata"));
        assertTrue(messages(withNulMetadata).contains("NUL"));

        JsonRecordUpsertRequest record = new JsonRecordUpsertRequest();
        record.setJsonbPayload(objectMapper.readTree(
                "{\"items\":[{\"note\":\"bad\\u0000value\"}]}"));
        assertTrue(violations(record).contains("jsonbPayload"));
        assertTrue(messages(record).contains("NUL"));
    }

    @Test
    void contentWithTabsAndNewlinesIsStillAccepted() {
        String text = "第一行\n第二行\t缩进\r\n结尾";
        assertTrue(violations(chat(text)).isEmpty());
        assertTrue(violations(document("多行标题", text)).isEmpty());
        assertEquals(0, validator.validate(
                document("多行标题", text)).stream()
                .filter(v -> v.getPropertyPath().toString().equals("content"))
                .count());
    }
}
