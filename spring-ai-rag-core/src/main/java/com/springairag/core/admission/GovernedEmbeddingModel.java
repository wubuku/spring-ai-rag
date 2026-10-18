package com.springairag.core.admission;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

public final class GovernedEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final DependencyCallExecutor executor;

    public GovernedEmbeddingModel(EmbeddingModel delegate, DependencyCallExecutor executor) {
        this.delegate = delegate;
        this.executor = executor;
    }

    @Override
    public float[] embed(String text) {
        return executor.execute("embedding", () -> delegate.embed(text));
    }

    @Override
    public float[] embed(Document document) {
        return executor.execute("embedding", () -> delegate.embed(document));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return executor.execute("embedding", () -> delegate.call(request));
    }

    @Override
    public int dimensions() {
        return executor.execute("embedding", delegate::dimensions);
    }
}
