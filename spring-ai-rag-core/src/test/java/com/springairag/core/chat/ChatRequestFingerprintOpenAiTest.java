package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * openAiRequest 指纹规范化（Batch 322）：缺省值回退、声明作用
 * 域与过滤器排序进指纹、PLAIN 模式检索选项拒绝、消息角色归一、
 * 哈希与字节数输出。
 */
class ChatRequestFingerprintOpenAiTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpenAiChatCompletionRequest request(String json) throws Exception {
        return objectMapper.readValue(json, OpenAiChatCompletionRequest.class);
    }

    @Test
    void defaultsAppliedWhenRagOptionsMissing() throws Exception {
        ChatRequestFingerprint.Result result =
                ChatRequestFingerprint.openAiRequest(request("""
                        {"model": null,
                         "messages": [{"role": "USER ", "content": "hi"}]}
                        """), objectMapper);

        JsonNode canonical = result.canonical();
        assertEquals("KNOWLEDGE", canonical.get("mode").asText());
        assertEquals("DEFAULT", canonical.get("memoryMode").asText());
        assertEquals("DEFAULT", canonical.get("declaredModelIdentifier").asText());
        assertEquals("CALLER_VISIBLE",
                canonical.get("scope").get("mode").asText());
        // 角色被裁剪并小写。
        assertEquals("user", canonical.get("inputMessages").get(0)
                .get("role").asText());
        assertTrue(result.canonicalBytes() > 0);
        assertEquals(64, result.sha256().length());
        // 指纹稳定：同一请求两次计算结果一致。
        ChatRequestFingerprint.Result again =
                ChatRequestFingerprint.openAiRequest(request("""
                        {"model": null,
                         "messages": [{"role": "USER ", "content": "hi"}]}
                        """), objectMapper);
        assertEquals(result.sha256(), again.sha256());
        // 不同 model 改变指纹。
        ChatRequestFingerprint.Result otherModel =
                ChatRequestFingerprint.openAiRequest(request("""
                        {"model": "other",
                         "messages": [{"role": "user", "content": "hi"}]}
                        """), objectMapper);
        assertNotEquals(result.sha256(), otherModel.sha256());
    }

    @Test
    void declaredScopeFiltersAndDocumentIdsAreCanonicalized() throws Exception {
        ChatRequestFingerprint.Result result =
                ChatRequestFingerprint.openAiRequest(request("""
                        {
                          "model": "m",
                          "messages": [{"role": "user", "content": "hi"}],
                          "rag": {
                            "mode": "AGENT",
                            "memory": " server ",
                            "document_ids": [9, 3],
                            "filters": {"metadata_contains": {"sku": "S-1"}},
                            "scope": {
                              "mode": "SELECTED_COLLECTIONS",
                              "collection_ids": [22, 7],
                              "collection_keys": ["kb-b", "kb-a"]
                            }
                          }
                        }
                        """), objectMapper);

        JsonNode canonical = result.canonical();
        assertEquals("AGENT", canonical.get("mode").asText());
        assertEquals("SERVER", canonical.get("memoryMode").asText());
        // 文档 id 与集合键均排序后进入指纹。
        assertEquals(3, canonical.get("retrieval").get("documentIds")
                .get(0).asLong());
        assertEquals(9, canonical.get("retrieval").get("documentIds")
                .get(1).asLong());
        assertEquals("SELECTED_COLLECTIONS",
                canonical.get("scope").get("mode").asText());
        assertEquals("kb-a", canonical.get("scope").get("collectionKeys")
                .get(0).asText());
        assertEquals("S-1", canonical.get("retrieval").get("filters")
                .get("metadata_contains").get("sku").asText());
    }

    @Test
    void plainModeRejectsAnyRetrievalDeclaration() throws Exception {
        RagException scopeError = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(request("""
                        {
                          "messages": [{"role": "user", "content": "hi"}],
                          "rag": {"mode": "PLAIN",
                                  "scope": {"mode": "CALLER_VISIBLE",
                                            "collection_keys": ["kb"]}}
                        }
                        """), objectMapper, List.of()));
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                scopeError.getErrorCodeEnum());

        RagException documentIdsError = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(request("""
                        {
                          "messages": [{"role": "user", "content": "hi"}],
                          "rag": {"mode": "PLAIN", "document_ids": [1]}
                        }
                        """), objectMapper, List.of()));
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                documentIdsError.getErrorCodeEnum());

        // PLAIN 模式下集合头同样被拒绝。
        assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(request("""
                        {
                          "messages": [{"role": "user", "content": "hi"}],
                          "rag": {"mode": "PLAIN"}
                        }
                        """), objectMapper, List.of("support")));
    }

    @Test
    void plainModeWithoutOptionsIsAccepted() throws Exception {
        ChatRequestFingerprint.Result result =
                ChatRequestFingerprint.openAiRequest(request("""
                        {
                          "messages": [{"role": "user", "content": "hi"}],
                          "rag": {"mode": "PLAIN"}
                        }
                        """), objectMapper, List.of());

        assertEquals("PLAIN", result.canonical().get("mode").asText());
        // PLAIN 模式无任何检索声明 → collectionKeyHeader 为空数组。
        assertEquals(0, result.canonical().get("scope")
                .get("collectionKeyHeader").size());
    }

    @Test
    void nullMessageAndNullRequestAreHandled() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        null, objectMapper));

        ChatRequestFingerprint.Result result =
                ChatRequestFingerprint.openAiRequest(request("""
                        {"messages": [null,
                                      {"role": null, "content": null}]}
                        """), objectMapper);

        // null 消息与 null 角色/内容均被容错归一。
        assertTrue(result.canonical().get("inputMessages").get(0)
                .get("content").isNull());
        assertEquals("", result.canonical().get("inputMessages").get(1)
                .get("role").asText());
        assertNull(result.canonical().get("inputMessages").get(1)
                .get("content").textValue());
    }
}
