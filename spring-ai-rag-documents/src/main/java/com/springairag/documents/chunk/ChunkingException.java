package com.springairag.documents.chunk;

/** 不携带原文的安全分块错误。 */
public class ChunkingException extends IllegalArgumentException {
    private final String code;

    public ChunkingException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
