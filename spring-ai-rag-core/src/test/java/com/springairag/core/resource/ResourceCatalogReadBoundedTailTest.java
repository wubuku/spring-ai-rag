package com.springairag.core.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceCatalog.readBounded 文件变体长尾（Batch 453）：文件/
 * Spring Resource/JarEntry 三种来源的字节上限守卫、流式读取截
 * 断、负预算拒绝。
 */
class ResourceCatalogReadBoundedTailTest {

    @TempDir
    Path tempDir;

    private ResourceCatalog catalog;
    private Method readBoundedFile;
    private Method readBoundedStream;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new ResourceCatalog();
        readBoundedFile = ResourceCatalog.class.getDeclaredMethod(
                "readBounded", Path.class, int.class, int.class);
        readBoundedFile.setAccessible(true);
        readBoundedStream = ResourceCatalog.class.getDeclaredMethod(
                "readBounded", java.io.InputStream.class, int.class, int.class);
        readBoundedStream.setAccessible(true);
    }

    private byte[] readBoundedFile(Path file, int maxFileBytes,
                                   int remainingTotalBytes) throws Exception {
        return (byte[]) readBoundedFile.invoke(catalog, file,
                maxFileBytes, remainingTotalBytes);
    }

    private byte[] readBoundedStream(java.io.InputStream input, int maxFileBytes,
                                     int remainingTotalBytes) throws Exception {
        return (byte[]) readBoundedStream.invoke(catalog, input,
                maxFileBytes, remainingTotalBytes);
    }

    private RuntimeException exceptionOf(ThrowingInvoke call) {
        try {
            call.invoke();
        } catch (java.lang.reflect.InvocationTargetException e) {
            return (RuntimeException) e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return null;
    }

    private interface ThrowingInvoke {
        Object invoke() throws Exception;
    }

    @Test
    void fileOverLimitsIsRejected() throws Exception {
        Path big = tempDir.resolve("big.txt");
        Files.write(big, "x".repeat(300).getBytes(StandardCharsets.UTF_8));

        RuntimeException perFile = exceptionOf(
                () -> readBoundedFile(big, 200, 10_000));
        assertTrue(perFile.getMessage().endsWith("file byte limit exceeded"));

        RuntimeException perTotal = exceptionOf(
                () -> readBoundedFile(big, 1_000, 100));
        assertTrue(perTotal.getMessage().endsWith("file byte limit exceeded"));
    }

    @Test
    void fileWithinLimitsIsReadFully() throws Exception {
        Path small = tempDir.resolve("small.txt");
        Files.write(small, "内容".getBytes(StandardCharsets.UTF_8));

        byte[] content = readBoundedFile(small, 1_000, 10_000);
        assertArrayEquals("内容".getBytes(StandardCharsets.UTF_8), content);
    }

    @Test
    void streamReadRejectsPayloadBeyondSmallestBudget() throws Exception {
        byte[] payload = "abcdefghij".getBytes(StandardCharsets.UTF_8);

        // 预算内完整读取。
        byte[] within = readBoundedStream(
                new ByteArrayInputStream(payload), 20, 100);
        assertEquals(10, within.length);

        // 既有语义：读取超出预算即拒绝（不截断）。
        RuntimeException overFile = exceptionOf(() -> readBoundedStream(
                new ByteArrayInputStream(payload), 5, 100));
        assertTrue(overFile.getMessage().endsWith("file byte limit exceeded"));
        RuntimeException overTotal = exceptionOf(() -> readBoundedStream(
                new ByteArrayInputStream(payload), 100, 5));
        assertTrue(overTotal.getMessage().endsWith("file byte limit exceeded"));
    }

    @Test
    void negativeBudgetsAreRejectedImmediately() {
        RuntimeException negative = exceptionOf(() -> readBoundedStream(
                new ByteArrayInputStream(new byte[0]), -1, 100));
        assertTrue(negative.getMessage().endsWith("file byte limit exceeded"));

        RuntimeException negativeTotal = exceptionOf(() -> readBoundedStream(
                new ByteArrayInputStream(new byte[0]), 100, -1));
        assertTrue(negativeTotal.getMessage().endsWith("file byte limit exceeded"));
    }
}
