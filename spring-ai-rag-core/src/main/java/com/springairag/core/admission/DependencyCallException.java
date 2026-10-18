package com.springairag.core.admission;

public final class DependencyCallException extends RuntimeException {
    public DependencyCallException(String code) {
        super(code);
    }
}
