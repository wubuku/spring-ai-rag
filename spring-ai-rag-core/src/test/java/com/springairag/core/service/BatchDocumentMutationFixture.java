package com.springairag.core.service;

import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.entity.RagDocument;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BatchDocumentService 单元测试的共享夹具（Batch 832）。
 *
 * <p>在 Batch 832 之前，{@code BatchDocumentService} 有一条"没有
 * {@code DocumentMutationService} 就自己按内容哈希查重、自己
 * {@code documentRepository.save(...)}、自己驱动嵌入"的 legacy 分支，
 * 于是 20 个 {@code BatchDocumentServiceTest} 用例与 8 个
 * {@code BatchDocumentServiceLegacyTailTest} 用例**全都不发协作者**。
 * 那条分支在运行的应用里走不到——{@code DocumentMutationService} 是无条件
 * {@code @Service}——已随 Batch 832 连同 {@code createSingleDocument}、
 * 它自带的 {@code TransactionTemplate} 与那个 4 参构造器一起删除。
 *
 * <p>迁移之后，单条创建只剩一条通道：把 {@link DocumentRequest} 交给
 * {@code DocumentMutationService.createLocal}。断言对象随之从"内联代码自己
 * 落库的结果"换成"服务请求协作者做什么"——那才是生产契约。
 *
 * <p><b>本夹具最容易写错的地方</b>：{@code collectionId} 与
 * {@code policy} 是 {@code createLocal} 的**独立参数**，不在
 * {@link DocumentRequest} 里面。从请求里读 {@code getCollectionId()} 恒为
 * {@code null}，重建出的文档会少字段，**而测试照样绿**。
 * {@code PdfToRagMutationFixture}（Batch 830）就在同一处栽过一次，
 * 所以这里一律从调用实参取。
 */
final class BatchDocumentMutationFixture {

    /** 生产侧 {@code embeddingAction} 无派发时就是 {@code "NONE"}。 */
    static final String NO_EMBEDDING = "NONE";

    private BatchDocumentMutationFixture() {
    }

    /** 所有请求都回报"新建"。 */
    static void stubCreates(DocumentMutationService mutationService) {
        stubAction(mutationService, Map.of());
    }

    /**
     * 按标题回报不同动作；未列出的标题一律按 "CREATED"。
     *
     * @param actionByTitle 标题 -&gt; {@code DocumentMutationResponse.action()}
     */
    static void stubAction(
            DocumentMutationService mutationService,
            Map<String, String> actionByTitle) {
        AtomicLong ids = new AtomicLong();
        Mockito.lenient()
                .when(mutationService.createLocal(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyBoolean(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    DocumentRequest request = invocation.getArgument(0);
                    // 关键：collectionId / policy 是独立实参，不在 request 里
                    Long collectionId = invocation.getArgument(1);
                    EmbeddingPolicy policy = invocation.getArgument(2);
                    boolean force = invocation.getArgument(3);
                    String action = actionByTitle.getOrDefault(
                            request.getTitle(), "CREATED");
                    Long id = ids.incrementAndGet();
                    return new DocumentMutationService.CreatedLocal(
                            documentFor(request, collectionId, policy, force, id),
                            mutationFor(id, action, policy));
                });
    }

    /**
     * 某个标题的请求让协作者抛异常——用来钉"单条失败不能中止整个 batch"。
     */
    static void stubFailureFor(
            DocumentMutationService mutationService,
            String failingTitle,
            String message) {
        AtomicLong ids = new AtomicLong();
        Mockito.lenient()
                .when(mutationService.createLocal(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyBoolean(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    DocumentRequest request = invocation.getArgument(0);
                    if (failingTitle.equals(request.getTitle())) {
                        throw new IllegalStateException(message);
                    }
                    Long collectionId = invocation.getArgument(1);
                    EmbeddingPolicy policy = invocation.getArgument(2);
                    boolean force = invocation.getArgument(3);
                    Long id = ids.incrementAndGet();
                    return new DocumentMutationService.CreatedLocal(
                            documentFor(request, collectionId, policy, force, id),
                            mutationFor(id, "CREATED", policy));
                });
    }

    private static RagDocument documentFor(
            DocumentRequest request,
            Long collectionId,
            EmbeddingPolicy policy,
            boolean force,
            Long id) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setTitle(request.getTitle());
        doc.setContent(request.getContent());
        doc.setSource(request.getSource());
        doc.setDocumentType(request.getDocumentType());
        doc.setMetadata(request.getMetadata());
        doc.setCollectionId(collectionId);
        return doc;
    }

    private static DocumentMutationResponse mutationFor(
            Long id, String action, EmbeddingPolicy policy) {
        return new DocumentMutationResponse(
                id, action, 1L, 1,
                !"UNCHANGED".equals(action), true, true,
                embeddingActionFor(policy),
                null, null, null);
    }

    /** 与生产一致：SKIP 不派发嵌入，SYNC 同步完成，ASYNC 入队。 */
    private static String embeddingActionFor(EmbeddingPolicy policy) {
        if (policy == null || policy == EmbeddingPolicy.SKIP) {
            return NO_EMBEDDING;
        }
        if (policy == EmbeddingPolicy.ASYNC) {
            return EmbeddingAction.ASYNC_QUEUED.name();
        }
        return EmbeddingAction.SYNC_COMPLETED.name();
    }
}
