package com.springairag.core.service;

/**
 * embedding job 在 provider 调用期间失去提交资格。
 */
public class EmbeddingCommitRejectedException extends RuntimeException {

    public EmbeddingCommitRejectedException(String message) {
        super(message);
    }
}
