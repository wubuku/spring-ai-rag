package com.springairag.api.dto;

import com.springairag.api.enums.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ErrorResponse DTO 单元测试
 */
class ErrorResponseTest {

    @Test
    void defaultConstructor_setsTimestamp() {
        ErrorResponse response = new ErrorResponse();
        assertNotNull(response.getTimestamp());
        assertNull(response.getError());
        assertNull(response.getMessage());
    }

    @Test
    void constructorWithErrorAndMessage() {
        ErrorResponse response = new ErrorResponse("NOT_FOUND", "资源不存在");
        assertEquals("NOT_FOUND", response.getError());
        assertEquals("资源不存在", response.getMessage());
        assertNotNull(response.getTimestamp());
        assertNull(response.getPath());
    }

    @Test
    void constructorWithErrorMessageAndPath() {
        ErrorResponse response = new ErrorResponse("BAD_REQUEST", "参数无效", "/api/v1/rag/documents");
        assertEquals("BAD_REQUEST", response.getError());
        assertEquals("参数无效", response.getMessage());
        assertEquals("/api/v1/rag/documents", response.getPath());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void builderPattern() {
        ErrorResponse response = ErrorResponse.builder()
                .error("VALIDATION_FAILED")
                .message("title: 不能为空")
                .path("/api/v1/rag/documents")
                .build();

        assertEquals("VALIDATION_FAILED", response.getError());
        assertEquals("title: 不能为空", response.getMessage());
        assertEquals("/api/v1/rag/documents", response.getPath());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void builderWithoutPath() {
        ErrorResponse response = ErrorResponse.builder()
                .error("INTERNAL_ERROR")
                .message("服务器内部错误")
                .build();

        assertEquals("INTERNAL_ERROR", response.getError());
        assertEquals("服务器内部错误", response.getMessage());
        assertNull(response.getPath());
    }

    @Test
    void settersWork() {
        ErrorResponse response = new ErrorResponse();
        response.setError("CUSTOM_ERROR");
        response.setMessage("自定义错误");
        response.setPath("/test");
        response.setTimestamp("2026-04-02T00:00:00Z");

        assertEquals("CUSTOM_ERROR", response.getError());
        assertEquals("自定义错误", response.getMessage());
        assertEquals("/test", response.getPath());
        assertEquals("2026-04-02T00:00:00Z", response.getTimestamp());
    }

    // ---- of(String) --------------------------------------------------------
    //
    // Batch 873. This factory used to assemble its own body and set
    // .title("Bad Request"). Builder.title() also writes `error`, so the
    // eighteen endpoints calling it returned "Bad Request" in a field the rest
    // of the API fills with a machine code — two vocabularies in one field,
    // which is what a client switching on body.error ran into.
    //
    // The assertion is deliberately on the *code*, not on the fact that the
    // status is 400. Both were 400 before; only the field's contents were wrong,
    // and a test that only checked the status would have kept passing.

    @Test
    void ofString_carriesTheCodeNotTheTitle() {
        ErrorResponse response = ErrorResponse.of("providers cannot be empty");

        assertEquals("BAD_REQUEST", response.getError(),
                "a client switching on body.error must see a code, not a phrase");
        assertEquals("Bad Request", response.getTitle(),
                "the human-readable summary belongs in title");
        assertEquals(400, response.getStatus());
        assertEquals("https://springairag.dev/problems/bad-request", response.getType());
        assertEquals("providers cannot be empty", response.getDetail());
        assertEquals("providers cannot be empty", response.getMessage());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void ofString_isIdenticalToTheCodeFactory() {
        // One condition, one body: the shortcut and the explicit form must not
        // be able to drift into two different shapes.
        assertEquals(ErrorResponse.of(ErrorCode.BAD_REQUEST, "boom"),
                ErrorResponse.of("boom"));
    }

    @Test
    void ofString_hasNoInstance() {
        // RFC 7807 makes `instance` optional and the eighteen call sites have no
        // request in scope. Stated as a fact about the current shape rather than
        // left for the next reader to wonder about.
        assertNull(ErrorResponse.of("boom").getInstance());
        assertNull(ErrorResponse.of("boom").getPath());
    }

    // ---- of(ErrorCode, String, String) -------------------------------------

    @Test
    void ofCodeWithInstance_namesTheRequestItIsAbout() {
        ErrorResponse response = ErrorResponse.of(
                ErrorCode.DOCUMENT_NOT_FOUND, "no document 42", "/api/v1/rag/documents/42");

        assertEquals("DOCUMENT_NOT_FOUND", response.getError());
        assertEquals("Document Not Found", response.getTitle());
        assertEquals(404, response.getStatus());
        assertEquals("https://springairag.dev/problems/document-not-found", response.getType());
        assertEquals("/api/v1/rag/documents/42", response.getInstance());
        assertEquals("/api/v1/rag/documents/42", response.getPath(),
                "the path alias must be filled too — equals() compares both");
    }

    @Test
    void ofCodeWithInstance_differsFromTheTwoArgFormOnlyByTheInstance() {
        ErrorResponse withInstance = ErrorResponse.of(
                ErrorCode.BAD_REQUEST, "boom", "/api/v1/x");
        ErrorResponse without = ErrorResponse.of(ErrorCode.BAD_REQUEST, "boom");

        assertEquals(without.getError(), withInstance.getError());
        assertEquals(without.getTitle(), withInstance.getTitle());
        assertEquals(without.getType(), withInstance.getType());
        assertEquals(without.getStatus(), withInstance.getStatus());
        assertEquals(without.getDetail(), withInstance.getDetail());
        assertNull(without.getInstance());
        assertEquals("/api/v1/x", withInstance.getInstance());
    }

    @Test
    void ofCode_derivesEverythingFromTheEnum() {
        // Every code in the catalog must produce a body whose RFC fields come
        // from that code, so adding a code cannot yield a half-formed body.
        for (ErrorCode code : ErrorCode.values()) {
            ErrorResponse response = ErrorResponse.of(code, "detail for " + code);
            assertEquals(code.getCode(), response.getError());
            assertEquals(code.getTitle(), response.getTitle());
            assertEquals(code.getHttpStatus(), response.getStatus());
            assertEquals(code.getProblemTypeUri(), response.getType());
        }
    }

    // ---- title derived from the catalog -------------------------------------
    //
    // Batch 873. build() used to copy `error` into `title` verbatim, so the nine
    // hand-assembled sites that name a code and nothing else shipped
    // "UNAUTHORIZED" in the field RFC 7807 calls a short human-readable summary.
    // The gate cannot see this — it reads source, not built objects — so without
    // these two tests the shape could be lost again and nothing would go red.

    @Test
    void build_derivesTitleFromTheCatalogRatherThanCopyingTheCode() {
        for (ErrorCode code : ErrorCode.values()) {
            ErrorResponse response = ErrorResponse.builder()
                    .error(code.getCode())
                    .status(code.getHttpStatus())
                    .message("detail for " + code)
                    .build();
            assertEquals(code.getTitle(), response.getTitle(),
                    () -> code + " should be summarised in words, not echoed as a code");
            assertNotEquals(code.getCode(), response.getTitle(),
                    "a title identical to the code means the catalog was not consulted");
        }
    }

    @Test
    void build_keepsTheCodeAsTheTitleWhenTheCatalogDoesNotKnowIt() {
        // Not every error(...) argument is a catalog code — a test fixture, a
        // legacy string, a code added ahead of its entry. Falling back to the old
        // behaviour is right; inventing a title would be worse.
        ErrorResponse response = ErrorResponse.builder()
                .error("SOME_CODE_NOT_YET_IN_THE_CATALOG")
                .message("x")
                .build();
        assertEquals("SOME_CODE_NOT_YET_IN_THE_CATALOG", response.getTitle());
    }

    @Test
    void build_leavesAnExplicitTitleAlone() {
        ErrorResponse response = ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .title("Key rejected before the request was routed")
                .message("x")
                .build();
        assertEquals("Key rejected before the request was routed", response.getTitle());
        assertEquals("UNAUTHORIZED", response.getError(),
                "an explicit title must not reach back and rewrite the code — "
                        + "that was the side effect Batch 873 removed from title()");
    }
}
