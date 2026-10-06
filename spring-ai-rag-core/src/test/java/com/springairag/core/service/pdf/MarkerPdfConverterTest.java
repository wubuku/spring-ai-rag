package com.springairag.core.service.pdf;

import com.springairag.core.config.RagPdfProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MarkerPdfConverter null-safety and configuration edge cases.
 * Note: Tests that invoke the actual marker CLI process are integration tests.
 */
class MarkerPdfConverterTest {

    private final RagPdfProperties pdfProperties = new RagPdfProperties();
    private final MarkerPdfConverter converter = new MarkerPdfConverter(pdfProperties);

    @Test
    @DisplayName("convert returns false when pdfPath is null")
    void convert_nullPdfPath_returnsFalse() {
        Path outputDir = mock(Path.class);
        boolean result = converter.convert(null, outputDir);
        assertFalse(result);
    }

    @Test
    @DisplayName("convert returns false when outputDir is null")
    void convert_nullOutputDir_returnsFalse() {
        Path pdfPath = mock(Path.class);
        when(pdfPath.toAbsolutePath()).thenReturn(pdfPath);
        when(pdfPath.toString()).thenReturn("/test.pdf");
        boolean result = converter.convert(pdfPath, null);
        assertFalse(result);
    }

    @Test
    @DisplayName("isAvailable returns false when marker CLI is null")
    void isAvailable_nullMarkerCli_returnsFalse() {
        pdfProperties.setMarkerCli(null);
        assertFalse(converter.isAvailable());
    }

    @Test
    @DisplayName("isAvailable returns false when marker CLI is blank")
    void isAvailable_blankMarkerCli_returnsFalse() {
        pdfProperties.setMarkerCli("   ");
        assertFalse(converter.isAvailable());
    }

    @Test
    @DisplayName("isAvailable returns false when marker CLI is empty")
    void isAvailable_emptyMarkerCli_returnsFalse() {
        pdfProperties.setMarkerCli("");
        assertFalse(converter.isAvailable());
    }

    @Test
    @DisplayName("getName returns descriptive name")
    void getName_returnsMarkerName() {
        assertEquals("marker-pdf (marker_single CLI)", converter.getName());
    }

    // ==================== 进程执行链（受控命令） ====================

    private static final String ECHO_CLI = "/bin/echo";
    private static final String JAVA_CLI =
            System.getProperty("java.home") + "/bin/java";

    @Test
    @DisplayName("convert 走完整进程链：成功退出 0 且读取输出")
    void convert_runsProcessAndSucceedsOnZeroExit(@TempDir Path tempDir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isExecutable(Path.of(ECHO_CLI)));
        pdfProperties.setMarkerCli(ECHO_CLI);
        converter.isAvailable();

        boolean result = converter.convert(tempDir.resolve("a.pdf"),
                tempDir.resolve("out"));

        // echo 退出 0：输出读取循环 + waitFor + exitValue==0 全链触发。
        assertTrue(result);
    }

    @Test
    @DisplayName("isAvailable 对退出 0 的 CLI 返回 true")
    void isAvailable_returnsTrueWhenCliExitsZero() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isExecutable(Path.of(ECHO_CLI)));
        pdfProperties.setMarkerCli(ECHO_CLI);

        assertTrue(converter.isAvailable());
    }

    @Test
    @DisplayName("isAvailable 被中断时保留中断标志，不把它当成「CLI 不可用」吞掉")
    void isAvailable_keepsInterruptStatusWhenWaitIsInterrupted(@TempDir Path tempDir)
            throws IOException {
        // 必须用一个**真的会阻塞**的 CLI，否则这条断言是空的：实测 /bin/echo、
        // /bin/cat、/bin/sh、/bin/sleep 在 waitFor 进去之前就已退出，
        // waitFor 走的是"已退出"分支、从不进 Object.wait()，中断标志因此原封不动
        // 地留着——那时 assertTrue(isInterrupted()) 会**空过**，与"代码恢复了标志"
        // 无法区分。同一个命令在不被中断时退出 0，于是 available == false 只能来自
        // waitFor 抛出的 InterruptedException，两个方向都钉死，且不依赖计时。
        Path cli = Files.createFile(tempDir.resolve("blocking-cli.sh"));
        Files.writeString(cli, "#!/bin/sh\nsleep 1\nexit 0\n");
        assertTrue(cli.toFile().setExecutable(true), "the fixture CLI must be executable");
        pdfProperties.setMarkerCli(cli.toString());

        Thread.currentThread().interrupt();
        boolean available;
        try {
            available = converter.isAvailable();
            assertTrue(Thread.currentThread().isInterrupted(),
                    "Process.waitFor 会清掉中断标志；isAvailable 必须把它恢复回去，"
                            + "否则本线程后续每一次阻塞调用都看不到这个取消信号");
        } finally {
            // JUnit 在线程池里跑，别把中断留给同线程后面的用例。
            Thread.interrupted();
        }
        assertFalse(available);
    }

    @Test
    @DisplayName("convert 对 --help 退出 0 但业务调用退出非 0 的 CLI 返回 false")
    void convert_returnsFalseOnNonZeroExit() {
        // java --help 退出 0（isAvailable 通过），但作为 marker_single
        // 调用任意路径会加载主类失败 → 退出码 1。
        java.nio.file.Path java = Path.of(JAVA_CLI);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(java));
        pdfProperties.setMarkerCli(JAVA_CLI);
        assertTrue(converter.isAvailable());

        Path pdf = mock(Path.class);
        when(pdf.toAbsolutePath()).thenReturn(pdf);
        when(pdf.toString()).thenReturn("/test.pdf");
        Path outputDir = mock(Path.class);
        when(outputDir.toAbsolutePath()).thenReturn(outputDir);
        when(outputDir.toString()).thenReturn("/tmp/out");
        boolean result = converter.convert(pdf, outputDir);

        assertFalse(result);
    }

    @Test
    @DisplayName("CLI 不存在时 convert 在可用性检查处短路")
    void convert_shortCircuitsWhenCliUnavailable() {
        pdfProperties.setMarkerCli("/nonexistent/marker-cli-xyz");

        assertFalse(converter.isAvailable());
        assertFalse(converter.convert(
                Path.of("/tmp/a.pdf"), Path.of("/tmp/out")));
    }
}
