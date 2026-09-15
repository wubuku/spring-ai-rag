package com.springairag.core.resource;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ResourceCatalog 路径与消息辅助函数（Batch 415）：
 * configuredRootPath 的各前缀剥离、normalizeRelativePath 的
 * 安全拒绝矩阵、boundedMessage 的兜底与截断。
 */
class ResourceCatalogPathHelperTest {

    private final ResourceCatalog catalog = new ResourceCatalog();

    private String configuredRootPath(String location) throws Exception {
        Method method = ResourceCatalog.class.getDeclaredMethod(
                "configuredRootPath", String.class);
        method.setAccessible(true);
        return (String) method.invoke(catalog, location);
    }

    private static String normalizeRelativePath(String value) throws Exception {
        Method method = ResourceCatalog.class.getDeclaredMethod(
                "normalizeRelativePath", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static String boundedMessage(String message) throws Exception {
        Method method = ResourceCatalog.class.getDeclaredMethod(
                "boundedMessage", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, message);
    }

    @Test
    void configuredRootPathStripsKnownPrefixes() throws Exception {
        assertEquals("docs", configuredRootPath("classpath*:docs/"));
        assertEquals("docs", configuredRootPath("classpath:docs"));
        assertEquals("inner", configuredRootPath("jar:file:/app.jar!/inner/"));
        assertEquals("docs", configuredRootPath("docs\\\\"));
        assertEquals("lead/slash", configuredRootPath("/lead/slash/"));
        assertEquals("inner/x", configuredRootPath("nested!/inner/x"));
    }

    @Test
    void normalizeRelativePathRejectsUnsafeValues() throws Exception {
        // 反射调用会把目标异常包进 InvocationTargetException。
        assertInvocationFailsIAE(() -> normalizeRelativePath(null));
        assertInvocationFailsIAE(() -> normalizeRelativePath(""));
        assertInvocationFailsIAE(() -> normalizeRelativePath("../secret"));
        assertInvocationFailsIAE(() -> normalizeRelativePath("docs/../../etc"));
        assertInvocationFailsIAE(() -> normalizeRelativePath("a\u0000b"));
    }

    private void assertInvocationFailsIAE(ThrowingCall call) {
        try {
            call.invoke();
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
            return;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private interface ThrowingCall {
        Object invoke() throws Exception;
    }

    @Test
    void normalizeRelativePathStripsLeadingSlashesAndBackslashes() throws Exception {
        assertEquals("a/b.md", normalizeRelativePath("a\\b.md"));
        assertEquals("a/b.md", normalizeRelativePath("//a/b.md"));
        assertEquals("a/b.md", normalizeRelativePath("/a/b.md"));
        assertEquals("a/b", normalizeRelativePath("/a/b"));
    }

    @Test
    void boundedMessageFallsBackAndTruncates() throws Exception {
        assertEquals("resource load failed", boundedMessage(null));
        assertEquals("resource load failed", boundedMessage("  "));
        assertEquals("real failure", boundedMessage("real failure"));
        String longMessage = "x".repeat(200);
        assertEquals(160, boundedMessage(longMessage).length());
    }
}
