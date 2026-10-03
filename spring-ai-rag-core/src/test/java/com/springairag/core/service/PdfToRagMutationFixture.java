package com.springairag.core.service;

import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * PdfToRagService 单元测试的共享夹具（Batch 830）。
 *
 * <p>在 Batch 830 之前，{@code PdfToRagService} 有一条"没有
 * {@code DocumentMutationService} 就自己内联落库"的 legacy 分支，于是
 * 单元测试全部不发协作者，直接断言 {@code documentRepository.save(...)}
 * 的入参。那条分支在运行中的应用里走不到——{@code DocumentMutationService}
 * 是无条件 {@code @Service}——已随 Batch 830 删除。
 *
 * <p>迁移之后，被测的服务只做一件事：把 {@link DocumentRequest} 交给
 * {@code DocumentMutationService.upsertLocalImport}。于是断言对象从
 * "内联代码自己拼出来的 RagDocument" 换成"**服务请求协作者写什么**"，
 * 而那正是生产契约。
 *
 * <p>{@link #created} / {@link #updated} 按入参逐字段重建一份
 * {@code RagDocument}，让迁移前"落库后字段应当是……"的断言可以原样保留：
 * 断言的内容没变，变的只是这些字段改由谁来填。
 */
final class PdfToRagMutationFixture {

    private PdfToRagMutationFixture() {
    }

    /**
     * 协作者按请求建好文档并返回"新建"。
     *
     * <p>{@code collectionId} 与 {@code originalFilename} 都是
     * {@code upsertLocalImport} 的**独立参数**，不在 {@link DocumentRequest}
     * 里面——夹具必须照着服务的传法取，否则重建出来的文档会少两个字段，
     * 而测试照样绿，那才是真的假绿。
     */
    static DocumentMutationService.CreatedLocal created(
            DocumentRequest request, Long collectionId, String originalFilename, Long id) {
        return respond(request, collectionId, originalFilename, id, "CREATED", true);
    }

    /** 协作者按请求建好文档并返回"更新"（文档已存在，scope 未变）。 */
    static DocumentMutationService.CreatedLocal updated(
            DocumentRequest request, Long collectionId, String originalFilename, Long id) {
        return respond(request, collectionId, originalFilename, id, "UPDATED", false);
    }

    private static DocumentMutationService.CreatedLocal respond(
            DocumentRequest request,
            Long collectionId,
            String originalFilename,
            Long id,
            String action,
            boolean scopeChanged) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setTitle(request.getTitle());
        doc.setContent(request.getContent());
        doc.setSource(request.getSource());
        doc.setDocumentType(request.getDocumentType());
        doc.setMetadata(request.getMetadata());
        doc.setOriginalFilename(originalFilename);
        if (request.getContent() != null) {
            doc.setContentHash(DigestUtils.sha256(request.getContent()));
            doc.setSize((long) request.getContent()
                    .getBytes(StandardCharsets.UTF_8).length);
        }
        doc.setCollectionId(collectionId);
        return new DocumentMutationService.CreatedLocal(
                doc,
                new DocumentMutationResponse(
                        id, action, 1L, 1, true, true, scopeChanged,
                        null, null, null, null));
    }
}
