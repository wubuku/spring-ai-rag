package com.springairag.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源路径规范化的<strong>完整攻击面</strong>（Batch 767）。
 *
 * <p>{@code ResourceCatalog} 负责把外部传入的资源位置与相对路径收敛成受控形式，
 * 再由 skill/knowledge 目录去读盘。这里的两个规范化函数就是
 * <strong>路径遍历防护</strong>的最后一道：它们必须挡掉 {@code ..} 上跳、
 * NUL 字节截断、绝对路径注入，以及用反斜杠绕过 {@code ..} 检测的写法。
 *
 * <p>既有测试只用过 {@code "skill/../etc"} 一个攻击输入。反斜杠那一条尤其关键：
 * {@code normalizeRelativePath} 先把 {@code \} 换成 {@code /} 再判断，
 * 只要哪一步顺序调换，{@code "..\\..\\etc\\passwd"} 就会以合法相对路径的身份
 * 通过检查——这是 Windows 与容器部署下最常见的绕过手法。
 */
class ResourceCatalogPathTraversalTest {

    private static Method normalizeLocation() throws Exception {
        Method method = ResourceCatalog.class.getDeclaredMethod(
                "normalizeLocation", String.class);
        method.setAccessible(true);
        return method;
    }

    private static Method normalizeRelativePath() throws Exception {
        Method method = ResourceCatalog.class.getDeclaredMethod(
                "normalizeRelativePath", String.class);
        method.setAccessible(true);
        return method;
    }

    private static String callLocation(String location) throws Exception {
        try {
            return (String) normalizeLocation().invoke(null, location);
        } catch (InvocationTargetException e) {
            throw asUnchecked(e.getCause());
        }
    }

    private static String callRelativePath(String value) throws Exception {
        try {
            return (String) normalizeRelativePath().invoke(null, value);
        } catch (InvocationTargetException e) {
            throw asUnchecked(e.getCause());
        }
    }

    private static RuntimeException asUnchecked(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(cause);
    }

    // ==================== 位置：拒绝上跳与 NUL ====================

    @ParameterizedTest(name = "位置含上跳被拒：{0}")
    @ValueSource(strings = {
            "..", "../", "skill/../etc", "a/../../b", "./../x",
            "classpath:../secret", "classpath:/../etc/passwd",
            "skills/..", "..\\windows",
    })
    @DisplayName("任何形式的 .. 上跳都被拒绝")
    void traversalInLocationIsRejected(String location) {
        assertThrows(IllegalArgumentException.class,
                () -> callLocation(location),
                () -> location + " 必须被拒绝");
    }

    @Test
    @DisplayName("反斜杠上跳在归一化后同样被拒绝")
    void backslashTraversalIsRejected() {
        // 先把 \ 换成 / 再判断 ..，所以 Windows 写法不能绕过。
        // 顺序一旦调换，"..\\..\\etc\\passwd" 就会以合法相对路径通过。
        assertThrows(IllegalArgumentException.class,
                () -> callLocation("..\\..\\etc\\passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> callLocation("skill\\..\\..\\secret"));
    }

    @Test
    @DisplayName("NUL 字节截断被拒绝")
    void nulByteTruncationIsRejected() {
        // 某些下游 API 会在 NUL 处截断字符串；不先拒绝就可能把
        // "safe\0/../../etc" 变成合法输入。
        assertThrows(IllegalArgumentException.class,
                () -> callLocation("safe\u0000/../../etc"));
        assertThrows(IllegalArgumentException.class,
                () -> callLocation("classpath:skills\u0000/../x"));
    }

    @ParameterizedTest(name = "空位置被拒：{0}")
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("空白位置被拒绝")
    void blankLocationIsRejected(String location) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> callLocation(location));
        assertEquals("Resource location must not be blank", error.getMessage());
    }

    @Test
    @DisplayName("classpath 位置补齐尾部斜杠并去掉反斜杠")
    void classpathLocationIsNormalized() throws Exception {
        assertEquals("classpath:skills/", callLocation("classpath:skills"));
        assertEquals("classpath:skills/", callLocation("classpath:skills/"));
        assertEquals("classpath:a/b/", callLocation("classpath:a\\b"));
        assertEquals("classpath:a/b/", callLocation("  classpath:a/b  "));
    }

    @Test
    @DisplayName("classpath* 位置同样补齐尾部斜杠")
    void classpathAllLocationIsNormalized() throws Exception {
        assertEquals("classpath*:skills/", callLocation("classpath*:skills"));
    }

    @Test
    @DisplayName("file: 位置被规范化为绝对路径")
    void fileLocationBecomesAbsolute() throws Exception {
        // file: 必须是 hierarchical 且无 authority 的 URI：file:/x 与
        // file:///x 可以，opaque 的 file:x 与带 authority 的 file://host/x 都不行
        // （Paths.get(URI) 分别报 "not hierarchical" 和 "has an authority"）。
        String normalized = callLocation("file:///skills/demo");

        assertTrue(normalized.startsWith("/") || normalized.matches("^[A-Za-z]:\\\\"),
                () -> "file: 位置应归一化为绝对路径，实际：" + normalized);
    }

    @Test
    @DisplayName("file: 位置含 NUL 时先被安全性检查拦下")
    void fileLocationWithNulIsRejectedAsUnsafe() {
        // NUL 检查发生在 file: 分支之前，所以报的是 unsafe 而非 invalid。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> callLocation("file://skills\u0000/demo"));
        assertEquals("Resource location is unsafe", error.getMessage());
    }

    @Test
    @DisplayName("opaque 的 file: URI 报出 file 专用的错误信息")
    void opaqueFileLocationIsRejected() {
        // "file:skills/demo" 少了斜杠，是 opaque URI，Paths.get(URI) 拒绝。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> callLocation("file:skills/demo"));
        assertEquals("Resource file location is invalid", error.getMessage());
    }

    @Test
    @DisplayName("带 authority 的 file: URI 同样被拒")
    void fileLocationWithAuthorityIsRejected() {
        // "file://host/x" 会被解析成 authority=host，Paths.get(URI) 拒绝。
        // 这条很重要：带 authority 的 file URI 常被误用来指向网络位置。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> callLocation("file://evil.example/skills"));
        assertEquals("Resource file location is invalid", error.getMessage());
    }

    // ==================== 相对路径：拒绝上跳、去掉前导斜杠 ====================

    @ParameterizedTest(name = "相对路径上跳被拒：{0}")
    @ValueSource(strings = {
            "..", "../etc/passwd", "a/../b", "./../x", "a/..",
            "a/../../b", "a/b/../../../c", "a\\..\\b", "..\\etc\\passwd",
            "a/..\\../b",
    })
    @DisplayName("相对路径的任意上跳形式都被拒绝")
    void traversalInRelativePathIsRejected(String value) {
        assertThrows(IllegalArgumentException.class,
                () -> callRelativePath(value),
                () -> value + " 必须被拒绝");
    }

    @Test
    @DisplayName("反斜杠上跳在相对路径里同样被拒绝")
    void backslashTraversalInRelativePathIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> callRelativePath("..\\..\\etc\\passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> callRelativePath("skills\\..\\..\\secret"));
    }

    @Test
    @DisplayName("相对路径里的 NUL 字节被拒绝")
    void nulByteInRelativePathIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> callRelativePath("safe\u0000/../etc"));
    }

    @ParameterizedTest(name = "空相对路径被拒：{0}")
    @ValueSource(strings = {"", "   ", "/", "//", "///"})
    @DisplayName("去掉前导斜杠后为空的相对路径被拒绝")
    void relativePathEmptyAfterStrippingIsRejected(String value) {
        // 全是斜杠的输入去掉前导斜杠后会变成空串；空串必须拒绝而不是
        // 当作"根目录"放行。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> callRelativePath(value));
        assertEquals("Resource relative path is unsafe", error.getMessage());
    }

    @Test
    @DisplayName("null 相对路径被拒绝")
    void nullRelativePathIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> callRelativePath(null));
    }

    @ParameterizedTest(name = "合法相对路径原样返回：{0} → {1}")
    @CsvSource({
            "skill.md,                    skill.md",
            "a/b/c.md,                    a/b/c.md",
            "'/leading/slash.md',         leading/slash.md",
            "'///many/slashes.md',        many/slashes.md",
            "'a\\b\\c.md',                a/b/c.md",
            "'dots.in.name.md',           dots.in.name.md",
            "'file..name.md',             file..name.md",
    })
    @DisplayName("合法相对路径去掉前导斜杠并统一分隔符后原样返回")
    void safeRelativePathIsAccepted(String input, String expected) throws Exception {
        assertEquals(expected, callRelativePath(input));
    }

    @Test
    @DisplayName("名字里含 .. 但不构成上跳的路径放行")
    void dotsInFileNamesAreNotTraversal() throws Exception {
        // ".." 只有作为独立路径段才是上跳；文件名里的两个点完全合法。
        assertEquals("report..2024.md", callRelativePath("report..2024.md"));
        assertEquals("..hidden/file.md", callRelativePath("..hidden/file.md"));
        assertEquals("archive..zip/x.md", callRelativePath("archive..zip/x.md"));
    }

    @Test
    @DisplayName("已知保守行为：以 .. 开头的目录名会被误拒")
    void dotPrefixedDirectoryNameIsConservativelyRejected() {
        // normalizeRelativePath 用 contains("/..") 做字符串判断，而不是按路径段
        // 比较，于是名为 "..b" 的目录也会被拒。这是 fail-closed 方向的过度拒绝：
        // 上跳（安全问题）被挡住了，代价是少数合法资源读不到。
        //
        // 本批不改动它——放宽安全检查需要独立的评估，而一个名为 "..b" 的目录
        // 在实践中几乎没有。此处把行为钉住，避免将来有人误以为是回归。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> callRelativePath("a/..b/c.md"));
        assertEquals("Resource relative path is unsafe", error.getMessage());
    }
}
