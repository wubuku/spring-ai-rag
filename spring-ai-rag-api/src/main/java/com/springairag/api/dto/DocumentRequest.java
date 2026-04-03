package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 文档创建/更新请求
 */
@Schema(description = "文档创建/更新请求")
public class DocumentRequest {

    @NotBlank(message = "文档标题不能为空")
    @Size(max = 500, message = "文档标题不能超过 500 字符")
    @Schema(description = "文档标题", example = "产品说明书", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank(message = "文档内容不能为空")
    @Size(max = 1_000_000, message = "文档内容不能超过 100 万字符")
    @Schema(description = "文档正文内容", example = "本文档介绍产品的使用方法...", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Size(max = 255, message = "文档来源标识不能超过 255 字符")
    @Schema(description = "文档来源标识", example = "manual-upload")
    private String source;

    @Size(max = 50, message = "文档类型不能超过 50 字符")
    @Schema(description = "文档类型", example = "markdown")
    private String documentType;

    @Schema(description = "附加元数据（JSON 对象）")
    private Map<String, Object> metadata;

    public DocumentRequest() {}

    public DocumentRequest(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
