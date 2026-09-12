package com.springairag.core.controller;

import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * batchEmbedDocumentsStream 的 SSE 流（Batch 318）：经 standalone
 * MockMvc 异步派发验证 progress/done 事件序、IllegalArgumentException
 * 的 error 事件、以及 ids 守卫在建流之前拒绝。
 */
class RagDocumentControllerBatchEmbedStreamTest {

    private static final String URI = "/rag/documents/batch/embed/stream";

    private DocumentEmbedService documentEmbedService;
    private RagDocumentController controller;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        documentEmbedService = mock(DocumentEmbedService.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                7L, "test-profile", "test", "test-model", "v1",
                1024, "COSINE", "PROVIDER_DEFAULT", true));
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                mock(AuditLogService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new BadRequestAdvice())
                .build();
    }

    /** standalone 环境缺全局异常处理：将参数错误映射为 400。 */
    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class BadRequestAdvice {
        @org.springframework.web.bind.annotation.ExceptionHandler(
                IllegalArgumentException.class)
        public org.springframework.http.ResponseEntity<String> handle(
                IllegalArgumentException error) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(error.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void streamEmitsProgressThenDone() throws Exception {
        when(documentEmbedService.batchEmbedDocumentsWithProgress(
                anyList(), any()))
                .thenAnswer(invocation -> {
                    Consumer<BatchEmbedProgressEvent> callback =
                            invocation.getArgument(1);
                    callback.accept(new BatchEmbedProgressEvent(
                            0, 2, 1L, "EMBEDDING", 1, 1, "doc 1", 0, 0, 0));
                    callback.accept(new BatchEmbedProgressEvent(
                            1, 2, 2L, "COMPLETED", 1, 1, "doc 2", 1, 0, 0));
                    return Map.of();
                });

        MvcResult async = mockMvc.perform(post(URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.TEXT_EVENT_STREAM));

        String body = async.getResponse().getContentAsString();
        assertTrue(body.contains("event:progress"), "应发出 progress 事件");
        assertTrue(body.contains("event:done"), "应发出 done 事件");
        assertTrue(body.contains("completed"));
        verify(documentEmbedService).batchEmbedDocumentsWithProgress(
                anyList(), any());
    }

    @Test
    void illegalArgumentFromServiceBecomesErrorEvent() throws Exception {
        when(documentEmbedService.batchEmbedDocumentsWithProgress(
                anyList(), any()))
                .thenThrow(new IllegalArgumentException("stale document"));

        MvcResult async = mockMvc.perform(post(URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[7]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk());

        String body = async.getResponse().getContentAsString();
        assertTrue(body.contains("event:error"), "应发出 error 事件");
        assertTrue(body.contains("stale document"));
    }

    @Test
    void invalidIdsRejectedBeforeStreamStarts() throws Exception {
        mockMvc.perform(post(URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" +
                                java.util.stream.LongStream
                                        .rangeClosed(1, 51)
                                        .boxed().toList() + "]}"))
                .andExpect(status().isBadRequest());
    }
}
