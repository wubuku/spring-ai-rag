package com.springairag.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.CollectionScopeMode;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionScopeRequestDtoTest {

    @Test
    void chatRequestIncludesScopeModeInValueSemantics() {
        ChatRequest left = new ChatRequest("question", "session");
        left.setCollectionScopeMode(
                CollectionScopeMode.SELECTED_COLLECTIONS);
        left.setCollectionKeys(List.of("manual:v3"));

        ChatRequest right = new ChatRequest("question", "session");
        right.setCollectionScopeMode(
                CollectionScopeMode.SELECTED_COLLECTIONS);
        right.setCollectionKeys(List.of("manual:v3"));

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertTrue(left.toString().contains("SELECTED_COLLECTIONS"));
    }

    @Test
    void searchRequestIncludesScopeModeInValueSemantics() {
        SearchRequest request = new SearchRequest("query");
        request.setCollectionScopeMode(CollectionScopeMode.ANY_COLLECTION);

        SearchRequest same = new SearchRequest("query");
        same.setCollectionScopeMode(CollectionScopeMode.ANY_COLLECTION);

        assertEquals(request, same);
        assertTrue(request.toString().contains("ANY_COLLECTION"));
    }

    @Test
    void chatAndSearchDeclareConfiguredMaximums() throws Exception {
        assertSize(ChatRequest.class, "collectionIds", 100);
        assertSize(ChatRequest.class, "collectionKeys", 100);
        assertSize(ChatRequest.class, "documentIds", 1000);
        assertSize(SearchRequest.class, "collectionIds", 100);
        assertSize(SearchRequest.class, "collectionKeys", 100);
        assertSize(SearchRequest.class, "documentIds", 1000);
    }

    @Test
    void numericListsDeclarePositiveElementConstraint() throws Exception {
        assertPositiveElements(ChatRequest.class, "collectionIds");
        assertPositiveElements(ChatRequest.class, "documentIds");
        assertPositiveElements(SearchRequest.class, "collectionIds");
        assertPositiveElements(SearchRequest.class, "documentIds");
    }

    @Test
    void invalidScopeModeJsonIsRejected() {
        ObjectMapper mapper = new ObjectMapper();

        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"query\":\"q\",\"collectionScopeMode\":\"UNKNOWN\"}",
                SearchRequest.class));
        assertDoesNotThrow(() -> mapper.readValue(
                "{\"query\":\"q\",\"collectionScopeMode\":\"ANY_COLLECTION\"}",
                SearchRequest.class));
    }

    private void assertSize(
            Class<?> type, String fieldName, int expectedMax) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        Size size = field.getAnnotation(Size.class);
        assertNotNull(size);
        assertEquals(expectedMax, size.max());
    }

    private void assertPositiveElements(
            Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        AnnotatedParameterizedType listType =
                (AnnotatedParameterizedType) field.getAnnotatedType();
        assertNotNull(listType.getAnnotatedActualTypeArguments()[0]
                .getAnnotation(Positive.class));
    }
}
