package com.springairag.core.service.pdf;

import java.nio.file.Path;

/**
 * PDF 转 Markdown 的抽象接口。
 *
 * <p>不同的实现使用不同的技术：
 * <ul>
 *   <li>{@link MarkerPdfConverter} — marker CLI (深度学习模型，高质量，但需要GPU)</li>
 *   <li>{@link PdfBoxConverter} — Apache PDFBox (纯Java，仅提取文本，无布局)</li>
 * </ul>
 */
public interface PdfConverter {

    /**
     * 将 PDF 文件转换为 Markdown。
     *
     * <p>转换后的 Markdown 文件输出到：{@code {outputDir}/{pdfName}/{pdfName}.md}
     * 图片文件输出到：{@code {outputDir}/{pdfName}/}
     *
     * @param pdfPath   源 PDF 文件路径
     * @param outputDir 输出目录（marker 会创建以 PDF 文件名为名称的子目录）
     * @return true 转换成功，false 转换失败
     */
    boolean convert(Path pdfPath, Path outputDir);

    /**
     * 检查此转换器是否可用。
     *
     * <p>例如 MarkerPdfConverter 检查 marker CLI 是否已安装。
     *
     * @return true 可用，false 不可用
     */
    boolean isAvailable();

    /**
     * 获取转换器的名称，用于日志和错误信息。
     *
     * @return 转换器名称
     */
    String getName();
}