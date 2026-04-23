package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiKeyRole enum.
 */
class ApiKeyRoleTest {

    @Test
    @DisplayName("enum has exactly two values")
    void values_hasTwoRoles() {
        ApiKeyRole[] values = ApiKeyRole.values();
        assertEquals(2, values.length);
    }

    @Test
    @DisplayName("valueOf resolves ADMIN and NORMAL")
    void valueOf_resolvesBothRoles() {
        assertEquals(ApiKeyRole.ADMIN, ApiKeyRole.valueOf("ADMIN"));
        assertEquals(ApiKeyRole.NORMAL, ApiKeyRole.valueOf("NORMAL"));
    }

    @Test
    @DisplayName("valueOf with invalid name throws IllegalArgumentException")
    void valueOf_invalidName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyRole.valueOf("SUPERADMIN"));
    }
}
