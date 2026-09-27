package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * ChatRequestFingerprint 缺省与清洗长尾（Batch 691，JaCoCo 驱动）：
 * 显式 scope 模式优先于推断、null model / 空白 domainId 归一、
 * collectionKeys 的 null 元素过滤、metadata 序列化失败的包装。
 */
class ChatRequestFingerprintDefaultTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nativeRequestHonorsExplicitCollectionScopeMode() {
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setCollectionScopeMode(CollectionScopeMode.SELECTED_COLLECTIONS);

        var result = ChatRequestFingerprint.nativeRequest(request, MAPPER);

        // 显式 scope 模式不经过推断，直接进入指纹。
        assertEquals(CollectionScopeMode.SELECTED_COLLECTIONS.name(),
                result.canonical().get("scope").get("mode").asText());
    }

    @Test
    void nativeRequestTreatsNullModelAsDefaultIdentifier() {
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setModel(null);

        var result = ChatRequestFingerprint.nativeRequest(request, MAPPER);

        assertEquals("DEFAULT",
                result.canonical().get("declaredModelIdentifier").asText());

        ChatRequest declared = new ChatRequest();
        declared.setMessage("q");
        declared.setModel("gpt-real");
        // 非空 model 原样进入指纹（直通臂）。
        assertEquals("gpt-real",
                ChatRequestFingerprint.nativeRequest(declared, MAPPER)
                        .canonical().get("declaredModelIdentifier").asText());
    }

    @Test
    void nativeRequestTreatsBlankDomainIdAsNull() {
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setDomainId("   ");

        var result = ChatRequestFingerprint.nativeRequest(request, MAPPER);

        assertTrue(result.canonical().get("domainId").isNull());

        ChatRequest withDomain = new ChatRequest();
        withDomain.setMessage("q");
        withDomain.setDomainId("domain-1");
        // 非空 domainId 原样保留（直通臂）。
        assertEquals("domain-1",
                ChatRequestFingerprint.nativeRequest(withDomain, MAPPER)
                        .canonical().get("domainId").asText());
    }

    @Test
    void nativeRequestDropsNullElementsFromCollectionKeys() {
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setCollectionKeys(Arrays.asList("kb-b", null, "kb-a", "kb-a"));

        var result = ChatRequestFingerprint.nativeRequest(request, MAPPER);

        // null 元素被过滤，剩余键排序去重后为 [kb-a, kb-b]。
        assertEquals(2,
                result.canonical().get("scope").get("collectionKeys").size());
        assertEquals("kb-a", result.canonical().get("scope")
                .get("collectionKeys").get(0).asText());
        assertEquals("kb-b", result.canonical().get("scope")
                .get("collectionKeys").get(1).asText());
    }

    @Test
    void nativeRequestWrapsMetadataSerializationFailure() throws Exception {
        ObjectMapper failing = spy(new ObjectMapper());
        doThrow(new IllegalStateException("boom"))
                .when(failing).writeValueAsBytes(any());
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setMetadata(Map.of("note", "ok"));

        RagException error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.nativeRequest(request, failing));

        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("not valid JSON"));
    }
}
