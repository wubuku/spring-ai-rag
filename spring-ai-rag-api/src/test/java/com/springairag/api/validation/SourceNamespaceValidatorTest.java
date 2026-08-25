package com.springairag.api.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceNamespaceValidatorTest {

    @Test
    void acceptsCompatibilityDefaultAndVisibleAscii() {
        assertTrue(SourceNamespaceValidator.isValid(null));
        assertTrue(SourceNamespaceValidator.isValid(""));
        assertTrue(SourceNamespaceValidator.isValid(" \t "));
        assertTrue(SourceNamespaceValidator.isValid("default"));
        assertTrue(SourceNamespaceValidator.isValid(" business-client.v1 "));
        assertTrue(SourceNamespaceValidator.isValid("namespace with spaces"));
    }

    @Test
    void rejectsNonVisibleAsciiAfterNormalization() {
        assertFalse(SourceNamespaceValidator.isValid("namespace\tvalue"));
        assertFalse(SourceNamespaceValidator.isValid("line\nbreak"));
        assertFalse(SourceNamespaceValidator.isValid("中文"));
        assertFalse(SourceNamespaceValidator.isValid("\u007f"));
    }
}
