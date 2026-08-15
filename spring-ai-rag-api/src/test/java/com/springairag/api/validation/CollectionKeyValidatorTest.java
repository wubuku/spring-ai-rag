package com.springairag.api.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionKeyValidatorTest {

    @Test
    void acceptsVisibleAsciiAtLengthBoundaries() {
        assertTrue(CollectionKeyValidator.isValid("a"));
        assertTrue(CollectionKeyValidator.isValid("x".repeat(128)));
        assertTrue(CollectionKeyValidator.isValid("ABC"));
        assertTrue(CollectionKeyValidator.isValid("abc"));
        assertTrue(CollectionKeyValidator.isValid("customer-42:manual/v3?source=a+b#c"));
    }

    @Test
    void rejectsEmptyWhitespaceUnicodeControlAndOverlongValues() {
        assertFalse(CollectionKeyValidator.isValid(""));
        assertFalse(CollectionKeyValidator.isValid(" "));
        assertFalse(CollectionKeyValidator.isValid("has space"));
        assertFalse(CollectionKeyValidator.isValid("中文"));
        assertFalse(CollectionKeyValidator.isValid("line\nbreak"));
        assertFalse(CollectionKeyValidator.isValid("\u007f"));
        assertFalse(CollectionKeyValidator.isValid("x".repeat(129)));
    }

    @Test
    void nullRemainsValidForOptionalCompatibilityFields() {
        assertTrue(CollectionKeyValidator.isValid(null));
    }
}
