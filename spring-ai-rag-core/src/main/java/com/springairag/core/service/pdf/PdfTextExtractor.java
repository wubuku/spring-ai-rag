package com.springairag.core.service.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯文本 PDF 抽取器（Apache PDFBox）。
 *
 * <p>只负责“有没有可抽取文字”的判定和阅读顺序整理，不写文件。图片型扫描件
 * 会抛出 {@link PdfConversionException.Reason#NO_EXTRACTABLE_TEXT}，绝不返回
 * 空字符串让上层静默生成空文档。
 *
 * <p>PDFBox 的 {@code sortByPosition} 会按 y/x 排序。双栏文档因此会逐行交错。
 * 这里在行级位置信息上做一次保守的列检测：仅当页面存在两个清晰、各有多行的
 * x 起始簇时才按“左栏整段、右栏整段”重排；检测到三个及以上簇（典型表格）时
 * 保持 y/x 顺序，避免破坏表格行。
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    public static String extractText(Path pdfPath, int maxPages) {
        if (pdfPath == null || maxPages <= 0) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF,
                    "PDF path and max pages must be valid");
        }
        if (!Files.isRegularFile(pdfPath)) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF, "PDF file does not exist");
        }
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return extractText(document, maxPages);
        } catch (InvalidPasswordException e) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.ENCRYPTED_PDF,
                    "PDF is encrypted and text cannot be extracted", e);
        } catch (IOException e) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF,
                    "PDF is corrupt or not readable", e);
        } catch (PdfConversionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF,
                    "PDF is corrupt or not readable", e);
        }
    }

    /** 供上传内容抽取使用：从内存字节读取，不落临时文件。 */
    public static String extractText(byte[] pdfBytes, int maxPages) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF, "PDF content is empty");
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return extractText(document, maxPages);
        } catch (InvalidPasswordException e) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.ENCRYPTED_PDF,
                    "PDF is encrypted and text cannot be extracted", e);
        } catch (IOException e) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF,
                    "PDF is corrupt or not readable", e);
        } catch (PdfConversionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF,
                    "PDF is corrupt or not readable", e);
        }
    }

    static String extractText(PDDocument document, int maxPages) throws IOException {
        if (document.isEncrypted()) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.ENCRYPTED_PDF,
                    "PDF is encrypted and text cannot be extracted");
        }
        if (document.getNumberOfPages() > maxPages) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.TOO_MANY_PAGES,
                    "PDF exceeds max-pages (" + maxPages + ")");
        }
        PositionAwareStripper stripper = new PositionAwareStripper(document);
        stripper.getText(document);
        String text = stripper.orderedText();
        if (text == null || text.isBlank()) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.NO_EXTRACTABLE_TEXT,
                    "PDF has no extractable text (possibly a scanned image PDF)");
        }
        return text;
    }

    private record VisualLine(int page, float x, float y, String text) {
    }

    private static final class PositionAwareStripper extends PDFTextStripper {

        private final List<VisualLine> lines = new ArrayList<>();
        private final Map<Integer, Float> pageWidths = new LinkedHashMap<>();

        private PositionAwareStripper(PDDocument document) throws IOException {
            super();
            setSortByPosition(true);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDPage page = document.getPage(index);
                pageWidths.put(index + 1, page.getMediaBox().getWidth());
            }
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions)
                throws IOException {
            super.writeString(text, positions);
            if (text == null || text.isBlank() || positions == null || positions.isEmpty()) {
                return;
            }
            float x = positions.get(0).getXDirAdj();
            float y = positions.get(0).getYDirAdj();
            lines.add(new VisualLine(getCurrentPageNo(), x, y, text));
        }

        private String orderedText() {
            StringBuilder output = new StringBuilder();
            int pageStart = 0;
            while (pageStart < lines.size()) {
                int page = lines.get(pageStart).page();
                int pageEnd = pageStart;
                while (pageEnd < lines.size() && lines.get(pageEnd).page() == page) {
                    pageEnd++;
                }
                List<VisualLine> pageLines = lines.subList(pageStart, pageEnd);
                appendPage(output, pageLines, pageWidths.getOrDefault(page, 595f));
                pageStart = pageEnd;
            }
            return output.toString().strip();
        }

        private void appendPage(StringBuilder output, List<VisualLine> pageLines,
                                float pageWidth) {
            List<Float> starts = pageLines.stream()
                    .map(VisualLine::x)
                    .sorted()
                    .toList();
            List<Float> clusters = clusterStarts(starts, Math.max(8f, pageWidth * 0.01f));
            if (clusters.size() == 2
                    && clusters.get(1) - clusters.get(0) >= pageWidth * 0.2f) {
                float split = (clusters.get(0) + clusters.get(1)) / 2f;
                appendRows(output, sorted(pageLines, split, true));
                appendRows(output, sorted(pageLines, split, false));
                return;
            }
            appendRows(output, pageLines);
        }

        /**
         * 同一视觉行可能被 PDFTextStripper 拆成多个 writeString（典型表格的
         * 多个单元格）。按 y 容差合并，保持“行”而不是“单元格逐行堆叠”。
         */
        private void appendRows(StringBuilder output, List<VisualLine> pageLines) {
            List<VisualLine> sorted = pageLines.stream()
                    .sorted(Comparator.comparingDouble(VisualLine::y)
                            .thenComparingDouble(VisualLine::x))
                    .toList();
            float rowTolerance = 3f;
            VisualLine current = null;
            for (VisualLine line : sorted) {
                if (current == null || Math.abs(line.y() - current.y()) > rowTolerance) {
                    if (current != null) {
                        output.append(current.text()).append('\n');
                    }
                    current = line;
                } else {
                    current = new VisualLine(current.page(), current.x(), current.y(),
                            current.text() + "   " + line.text());
                }
            }
            if (current != null) {
                output.append(current.text()).append('\n');
            }
        }

        private List<VisualLine> sorted(List<VisualLine> pageLines, float split,
                                        boolean leftColumn) {
            return pageLines.stream()
                    .filter(line -> leftColumn ? line.x() < split : line.x() >= split)
                    .sorted(Comparator.comparingDouble(VisualLine::y)
                            .thenComparingDouble(VisualLine::x))
                    .toList();
        }

        /** 将排序后的 x 起始值合并为容差内的簇中心。 */
        private static List<Float> clusterStarts(List<Float> starts, float tolerance) {
            List<Float> clusters = new ArrayList<>();
            for (Float start : starts) {
                if (clusters.isEmpty()
                        || Math.abs(start - clusters.get(clusters.size() - 1)) > tolerance) {
                    clusters.add(start);
                }
            }
            // 只有 2 个簇时才需要关注中心点；多簇直接表示表格/多列，保持原顺序。
            return clusters;
        }
    }
}
