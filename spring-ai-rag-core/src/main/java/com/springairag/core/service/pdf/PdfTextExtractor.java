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
import java.util.Locale;
import java.util.Map;

/**
 * 纯文本 PDF 抽取器（Apache PDFBox）。
 *
 * <p>只负责“有没有可抽取文字”的判定和阅读顺序整理，不写文件。图片型扫描件
 * 会抛出 {@link PdfConversionException.Reason#NO_EXTRACTABLE_TEXT}，绝不返回
 * 空字符串让上层静默生成空文档。
 *
 * <p>PDFBox 的 {@code sortByPosition} 会把双栏正文逐行交错。这里在行级位置
 * 信息上做一次可解释的版式判定：
 * <ul>
 *   <li>先按 x 起始值聚类，再找“主导空白带”：大于页宽 8% 且明显宽于其他
 *       列间隙（≥1.5 倍）的间隙，视为栏间 gutter；</li>
 *   <li>只有两个 x 簇时，单靠间隙不足以判断（两列表格和双栏正文都可能出现
 *       一条宽间隙），再比较单元格特征与正文连续性：短行占比、左右行是否
 *       逐行成对、同一簇内相邻长行是否像被排版折断的句子；</li>
 *   <li>证据冲突或不足时按行序输出（保真优先），并把判定原因写入
 *       {@link PageLayout}，不静默猜。</li>
 * </ul>
 */
public final class PdfTextExtractor {

    /** 判定栏间 gutter 的最小宽度（页宽比例）。 */
    private static final float GUTTER_MIN_RATIO = 0.08f;
    /** 主导 gutter 必须至少是次宽列间隙的倍数。 */
    private static final float GUTTER_DOMINANCE = 1.5f;
    /** 单元格短行阈值（字符数）。 */
    private static final int SHORT_CELL_MAX_CHARS = 12;
    /** 正文长行阈值（字符数）。 */
    private static final int LONG_LINE_MIN_CHARS = 20;

    private PdfTextExtractor() {
    }

    /** 抽取结果：正文 + 逐页版式判定（诊断用，不参与检索打分）。 */
    public record PdfExtraction(String text, List<PageLayout> layout) {
        public PdfExtraction {
            text = text == null ? "" : text;
            layout = List.copyOf(layout == null ? List.of() : layout);
        }
    }

    /**
     * 单页版式判定。
     *
     * @param decision       {@code COLUMN_BANDS}（按栏重排）或 {@code ROW_ORDER}（按行序）
     * @param reason         判定依据；降级时说明为什么没有分栏
     * @param primaryGap     最宽 x 簇间隙
     * @param secondaryGap   次宽 x 簇间隙，单簇或双簇时为 -1
     * @param split          重排时使用的栏间分割点；未重排时为 -1
     */
    public record PageLayout(int page, int clusterCount, String decision, String reason,
                             float primaryGap, float secondaryGap, float split,
                             double shortCellRatio, double pairedRowRatio,
                             double continuationRatio, double longLineRatio) {

        public String summary() {
            return String.format(Locale.ROOT,
                    "page=%d decision=%s reason=%s clusters=%d primaryGap=%.1f "
                            + "secondaryGap=%.1f split=%.1f shortCell=%.2f paired=%.2f "
                            + "continuation=%.2f longLine=%.2f",
                    page, decision, reason, clusterCount, primaryGap, secondaryGap,
                    split, shortCellRatio, pairedRowRatio, continuationRatio,
                    longLineRatio);
        }
    }

    public static String extractText(Path pdfPath, int maxPages) {
        return extract(pdfPath, maxPages).text();
    }

    /** 供上传内容抽取使用：从内存字节读取，不落临时文件。 */
    public static String extractText(byte[] pdfBytes, int maxPages) {
        return extract(pdfBytes, maxPages).text();
    }

    public static PdfExtraction extract(Path pdfPath, int maxPages) {
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
            return extract(document, maxPages);
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

    public static PdfExtraction extract(byte[] pdfBytes, int maxPages) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PdfConversionException(
                    PdfConversionException.Reason.INVALID_PDF, "PDF content is empty");
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return extract(document, maxPages);
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
        return extract(document, maxPages).text();
    }

    static PdfExtraction extract(PDDocument document, int maxPages) throws IOException {
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
        return new PdfExtraction(text, stripper.layout());
    }

    private record VisualLine(int page, float x, float y, String text) {
    }

    private static final class PositionAwareStripper extends PDFTextStripper {

        private final List<VisualLine> lines = new ArrayList<>();
        private final List<PageLayout> layout = new ArrayList<>();
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
            // PDFTextStripper 会把同一基线上、x 相距很远的左右两栏内容合并成一次
            // writeString。只记录整行起点会让“按栏重排”拿到仍然混在一起的文本。
            // 因此按字间距切分：普通词间距只有几磅，栏间距/单元格间距明显更大。
            int page = getCurrentPageNo();
            float gapThreshold = Math.max(12f, pageWidths.getOrDefault(page, 595f) * 0.02f);
            TextPosition first = positions.get(0);
            StringBuilder fragment = new StringBuilder();
            TextPosition previous = null;
            for (TextPosition position : positions) {
                if (previous != null) {
                    float gap = position.getXDirAdj()
                            - (previous.getXDirAdj() + previous.getWidthDirAdj());
                    if (gap > gapThreshold) {
                        lines.add(new VisualLine(page, first.getXDirAdj(), first.getYDirAdj(),
                                fragment.toString()));
                        fragment.setLength(0);
                        first = position;
                    }
                }
                fragment.append(position.getUnicode());
                previous = position;
            }
            if (!fragment.toString().isBlank()) {
                lines.add(new VisualLine(page, first.getXDirAdj(), first.getYDirAdj(),
                        fragment.toString()));
            }
        }

        private List<PageLayout> layout() {
            return List.copyOf(layout);
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
                appendPage(output, page, pageLines,
                        pageWidths.getOrDefault(page, 595f));
                pageStart = pageEnd;
            }
            return output.toString().strip();
        }

        private void appendPage(StringBuilder output, int page,
                                List<VisualLine> pageLines, float pageWidth) {
            List<Float> starts = pageLines.stream()
                    .map(VisualLine::x)
                    .sorted()
                    .toList();
            List<Float> clusters = clusterStarts(starts,
                    Math.max(8f, pageWidth * 0.01f));
            if (clusters.size() < 2) {
                appendRows(output, pageLines);
                layout.add(new PageLayout(page, clusters.size(), "ROW_ORDER",
                        "SINGLE_CLUSTER", 0f, -1f, -1f, 0, 0, 0, 0));
                return;
            }

            float primaryGap = -1f;
            float secondaryGap = -1f;
            int primaryIndex = -1;
            for (int index = 0; index < clusters.size() - 1; index++) {
                float gap = clusters.get(index + 1) - clusters.get(index);
                if (gap > primaryGap) {
                    secondaryGap = primaryGap;
                    primaryGap = gap;
                    primaryIndex = index;
                } else if (gap > secondaryGap) {
                    secondaryGap = gap;
                }
            }
            float split = (clusters.get(primaryIndex)
                    + clusters.get(primaryIndex + 1)) / 2f;

            double shortCellRatio = shortCellRatio(pageLines, split);
            double longLineRatio = longLineRatio(pageLines, split);
            double pairedRowRatio = pairedRowRatio(pageLines, split);
            double continuationRatio = continuationRatio(pageLines, split);

            String decision;
            String reason;
            if (clusters.size() == 2) {
                boolean geometric = primaryGap >= pageWidth * GUTTER_MIN_RATIO;
                boolean tableLike = shortCellRatio >= 0.5 && pairedRowRatio >= 0.7;
                boolean textLike = continuationRatio >= 0.5 || longLineRatio >= 0.6;
                if (!geometric) {
                    decision = "ROW_ORDER";
                    reason = "NO_DOMINANT_GUTTER";
                } else if (tableLike && textLike) {
                    decision = "ROW_ORDER";
                    reason = "AMBIGUOUS_MIXED_SIGNALS";
                } else if (tableLike) {
                    decision = "ROW_ORDER";
                    reason = "CELL_LIKE_COLUMNS";
                } else if (textLike) {
                    decision = "COLUMN_BANDS";
                    reason = "CONTINUOUS_TEXT";
                } else {
                    decision = "ROW_ORDER";
                    reason = "AMBIGUOUS_FALLBACK";
                }
            } else {
                boolean geometric = primaryGap >= pageWidth * GUTTER_MIN_RATIO
                        && (secondaryGap <= 0
                        || primaryGap >= secondaryGap * GUTTER_DOMINANCE);
                decision = geometric ? "COLUMN_BANDS" : "ROW_ORDER";
                reason = geometric ? "DOMINANT_GUTTER" : "NO_DOMINANT_GUTTER";
            }

            if ("COLUMN_BANDS".equals(decision)) {
                appendRows(output, sorted(pageLines, split, true));
                appendRows(output, sorted(pageLines, split, false));
            } else {
                appendRows(output, pageLines);
            }
            layout.add(new PageLayout(page, clusters.size(), decision, reason,
                    primaryGap, secondaryGap, split, shortCellRatio,
                    pairedRowRatio, continuationRatio, longLineRatio));
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
            return clusters;
        }

        /**
         * 统计只应作用于“视觉行”，而不是 PDFTextStripper 的 writeString 片段。
         * 同一行内字体切换（例如中文正文夹着 lease、1/1000）会产生多个短片段，
         * 用片段做长度/成对统计会把双栏正文误判成两列表格。
         */
        private List<VisualLine> mergedSideLines(List<VisualLine> pageLines,
                                                        float split, boolean left) {
            List<VisualLine> side = sorted(pageLines, split, left);
            List<VisualLine> merged = new ArrayList<>();
            VisualLine current = null;
            for (VisualLine line : side) {
                if (current == null || Math.abs(line.y() - current.y()) > 3f) {
                    if (current != null) {
                        merged.add(current);
                    }
                    current = line;
                } else {
                    current = new VisualLine(current.page(), current.x(), current.y(),
                            current.text() + "   " + line.text());
                }
            }
            if (current != null) {
                merged.add(current);
            }
            return merged;
        }

        /**
         * 短“单元格”只统计独立成行的短行。由上一行正文折断出来的续行
         * （上一行足够长且没有句末标点）是正文特征，不应算作表格单元格；
         * 否则中文双栏正文的尾行会被误判成表格。
         */
        private double shortCellRatio(List<VisualLine> pageLines, float split) {
            long shortLines = 0;
            long candidates = 0;
            for (boolean left : new boolean[]{true, false}) {
                VisualLine previous = null;
                for (VisualLine line : mergedSideLines(pageLines, split, left)) {
                    boolean continuation = previous != null
                            && strippedLength(previous.text()) >= LONG_LINE_MIN_CHARS
                            && !endsSentence(previous.text());
                    if (!continuation) {
                        candidates++;
                        if (strippedLength(line.text()) <= SHORT_CELL_MAX_CHARS) {
                            shortLines++;
                        }
                    }
                    previous = line;
                }
            }
            return candidates == 0 ? 0 : (double) shortLines / candidates;
        }

        private double longLineRatio(List<VisualLine> pageLines, float split) {
            List<VisualLine> merged = new ArrayList<>();
            merged.addAll(mergedSideLines(pageLines, split, true));
            merged.addAll(mergedSideLines(pageLines, split, false));
            if (merged.isEmpty()) {
                return 0;
            }
            long longLines = merged.stream()
                    .filter(line -> strippedLength(line.text()) >= LONG_LINE_MIN_CHARS)
                    .count();
            return (double) longLines / merged.size();
        }

        /**
         * 左右两侧按 y 成对的行占比。表格通常逐行对齐；双栏正文的行距由
         * 各自栏的排版决定，成对比例可能很低，但这不是唯一判据。
         */
        private double pairedRowRatio(List<VisualLine> pageLines, float split) {
            List<VisualLine> sorted = pageLines.stream()
                    .sorted(Comparator.comparingDouble(VisualLine::y)
                            .thenComparingDouble(VisualLine::x))
                    .toList();
            int rows = 0;
            int pairedRows = 0;
            int index = 0;
            while (index < sorted.size()) {
                float y = sorted.get(index).y();
                boolean hasLeft = false;
                boolean hasRight = false;
                while (index < sorted.size()
                        && Math.abs(sorted.get(index).y() - y) <= 3f) {
                    VisualLine line = sorted.get(index);
                    if (line.x() < split) {
                        hasLeft = true;
                    } else {
                        hasRight = true;
                    }
                    index++;
                }
                rows++;
                if (hasLeft && hasRight) {
                    pairedRows++;
                }
            }
            return rows == 0 ? 0 : (double) pairedRows / rows;
        }

        /**
         * 同一栏内相邻长行是否像被排版折断的句子：前一行足够长且不以句末
         * 标点结束，则下一行更可能是正文接续，而不是独立表格单元格。
         */
        private double continuationRatio(List<VisualLine> pageLines,
                                               float split) {
            double left = sideContinuationRatio(pageLines, split, true);
            double right = sideContinuationRatio(pageLines, split, false);
            if (left < 0) {
                return Math.max(right, 0);
            }
            if (right < 0) {
                return left;
            }
            return (left + right) / 2;
        }

        private double sideContinuationRatio(List<VisualLine> pageLines,
                                                    float split, boolean left) {
            List<VisualLine> side = mergedSideLines(pageLines, split, left);
            if (side.size() < 2) {
                return -1;
            }
            int pairs = 0;
            int continued = 0;
            for (int index = 1; index < side.size(); index++) {
                String previous = side.get(index - 1).text().strip();
                pairs++;
                if (strippedLength(previous) >= LONG_LINE_MIN_CHARS
                        && !endsSentence(previous)) {
                    continued++;
                }
            }
            return pairs == 0 ? -1 : (double) continued / pairs;
        }

        private static boolean endsSentence(String text) {
            if (text.isEmpty()) {
                return true;
            }
            char last = text.charAt(text.length() - 1);
            return "。！？.!?;；:：".indexOf(last) >= 0;
        }

        private static int strippedLength(String text) {
            return text.strip().length();
        }
    }
}
