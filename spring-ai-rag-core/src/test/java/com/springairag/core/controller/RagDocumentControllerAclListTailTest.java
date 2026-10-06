package com.springairag.core.controller;

import com.springairag.api.dto.DocumentStatsResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.service.DocumentMutationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import com.springairag.core.service.ExternalDocumentService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentRelocationService;

/**
 * RagDocumentController ACL 列举/统计/批量嵌入流长尾（Batch 697，
 * JaCoCo 驱动）：受限策略下的集合范围统计、无显式 collectionId 时
 * 按允许列表检索、批量嵌入 SSE 流的进度/完成与 IAE → sendError。
 */
class RagDocumentControllerAclListTailTest {

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private RagDocumentController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(
                new EmbeddingProfile(
                        1L, "test-profile", "test", "test-model", "v1",
                        1024, "COSINE", "PROVIDER_DEFAULT", true));
        RagCollectionRepository collectionRepository =
                mock(RagCollectionRepository.class);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                collectionRepository,
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                new CollectionIdentityResolver(collectionRepository),
                null,
                mock(DocumentMutationService.class),

                mock(ExternalDocumentService.class),


                mock(DocumentDerivationDescriptorProvider.class),



                mock(DocumentRelocationService.class));
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    /**
     * Batch 946：跑一次真实的批量嵌入流请求，读回完整 SSE 响应体。
     *
     * <p>这两条用例原来都以 {@code assertNotNull(emitter)} 收尾，而 emitter 由
     * {@code SseEmitters.create()} 无条件造出——把 {@code progress} 事件名改错、把
     * {@code sendDone} 删掉、把 IAE 分支换成 {@code completeWithError}，用例全绿。
     * {@code SseEmitter} 没有 handler 时 {@code send} 只是暂存、{@code complete()} 只是
     * 置标志位，而 {@code initialize} 包级私有、测试拿不到，所以只能走 MockMvc 的
     * async 通道。套路与 {@code RagDocumentControllerEmbedStreamTest}（Batch 946）一致。
     */
    private String batchStreamBody(List<Long> ids) throws Exception {
        MvcResult started = mockMvc.perform(post("/rag/documents/batch/embed/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":" + ids + "}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 先等异步结果就绪再 dispatch，否则会撞 MockMvc 的 timeToWait=0。
        started.getAsyncResult(10_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andReturn();
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    private static void assertEmitted(String body, String eventName) {
        List<String> names = java.util.Arrays.stream(body.split("\n\n"))
                .map(frame -> frame.lines()
                        .filter(line -> line.startsWith("event:"))
                        .map(line -> line.substring("event:".length()).trim())
                        .findFirst()
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        assertTrue(names.contains(eventName),
                () -> "SSE body carries no " + eventName + " event, only " + names + ":\n" + body);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticateRestrictedKey(Long... ids) {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(java.util.Arrays.stream(ids)
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @Test
    void documentStatsWithRestrictedPolicyUsesCollectionScopedCounts() {
        authenticateRestrictedKey(2L, 4L);
        when(documentRepository.countByProcessingStatusAndCollectionIds(
                List.of(2L, 4L)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"COMPLETED", 5L},
                        new Object[]{null, 1L}));

        DocumentStatsResponse response = controller.getDocumentStats()
                .getBody();

        assertNotNull(response);
        assertEquals(6L, response.total());
        assertEquals(5L, response.byStatus().get("COMPLETED"));
        assertEquals(1L, response.byStatus().get("UNKNOWN"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithRestrictedPolicyWithoutExplicitCollectionUsesAllowList() {
        authenticateRestrictedKey(2L, 4L);
        when(documentRepository.searchDocumentsByCollectionIds(
                anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<RagDocument>(List.of()));

        controller.listDocuments(
                0, 10, null, null, null, null,
                null, null, null, null);

        org.mockito.Mockito.verify(documentRepository)
                .searchDocumentsByCollectionIds(
                        org.mockito.ArgumentMatchers.eq(List.of(2L, 4L)),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchEmbedStreamPublishesProgressAndDone() throws Exception {
        authenticateRestrictedKey(2L, 4L);
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setCollectionId(2L);
        when(documentRepository.findAllById(anyList()))
                .thenReturn(List.of(document));
        doAnswer(invocation -> {
            // 这里是 Batch 946 的断言直接抓出来的夹具错误：原来塞的是
            // `Map.of("percent", 50)`，而回调的真实类型是
            // Consumer<BatchEmbedProgressEvent>——泛型擦除让它编译通过，
            // 运行时在桥接方法里抛 ClassCastException，又被控制器的
            // `catch (Exception) { emitter.completeWithError(e); }` 吞掉。
            // 而旧用例只断言 emitter 非 null，所以**它一直绿着跑的是"意外异常"
            // 分支**，名字却写着"进度 + 完成"。
            Consumer<com.springairag.api.dto.BatchEmbedProgressEvent> callback =
                    invocation.getArgument(1);
            callback.accept(new com.springairag.api.dto.BatchEmbedProgressEvent(
                    0, 1, 1L, "EMBEDDING", 5, 10, "第 1 个文档的第 5 块",
                    1, 0, 0));
            return Map.of("succeeded", 1, "failed", 0);
        }).when(documentEmbedService)
                .batchEmbedDocumentsWithProgress(anyList(), any());

        String body = batchStreamBody(List.of(1L));

        assertEmitted(body, "progress");
        assertEmitted(body, "done");
        assertTrue(body.contains("EMBEDDING"),
                () -> "progress 帧没带上阶段名:\n" + body);
        assertTrue(body.contains("第 1 个文档的第 5 块"),
                () -> "progress 帧没带上阶段消息:\n" + body);
        assertTrue(body.contains("\"total\":1"),
                () -> "done 帧没带上本批文档数:\n" + body);
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchEmbedStreamMapsIllegalArgumentToSseError() throws Exception {
        authenticateRestrictedKey(2L, 4L);
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setCollectionId(2L);
        when(documentRepository.findAllById(anyList()))
                .thenReturn(List.of(document));
        when(documentEmbedService.batchEmbedDocumentsWithProgress(
                anyList(), any()))
                .thenThrow(new IllegalArgumentException("profile missing"));

        String body = batchStreamBody(List.of(1L));

        // IAE 走 sendError：发一个 error 帧再正常 complete，所以 done 帧不该出现。
        // 名字说的是"映射成 SSE error"，而映射的目标就是这一个帧。
        assertEmitted(body, "error");
        assertTrue(body.contains("profile missing"),
                () -> "error 帧没带上原始消息:\n" + body);
        assertFalse(body.contains("event:done"),
                () -> "出错时不该再发 done 帧:\n" + body);
    }
}
