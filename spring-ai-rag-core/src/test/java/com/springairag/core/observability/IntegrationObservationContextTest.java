package com.springairag.core.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntegrationObservationContextTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void nonHttpCallIsSafeNoOp() {
        RequestContextHolder.resetRequestAttributes();

        IntegrationObservationContext.addAuthorizedCollection(7L);

        assertEquals(List.of(),
                IntegrationObservationContext.authorizedCollectionIds(null));
    }

    @Test
    void capturesOnlyPositiveLongValuesAndReturnsSortedUniqueIds() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        IntegrationObservationContext.addAuthorizedCollection(9L);
        IntegrationObservationContext.addAuthorizedCollection(2L);
        IntegrationObservationContext.addAuthorizedCollection(9L);
        IntegrationObservationContext.addAuthorizedCollection(null);
        IntegrationObservationContext.addAuthorizedCollection(0L);

        assertEquals(List.of(2L, 9L),
                IntegrationObservationContext.authorizedCollectionIds(request));
    }

    @Test
    void ignoresMalformedRequestAttributeAndCapsAtOneHundredIds() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                IntegrationObservationContext.AUTHORIZED_COLLECTION_IDS_ATTRIBUTE,
                new ArrayList<>(List.of("not-a-long", -1L)));

        for (long id = 1; id <= 105; id++) {
            IntegrationObservationContext.addAuthorizedCollection(request, id);
        }

        List<Long> ids = IntegrationObservationContext.authorizedCollectionIds(request);
        assertEquals(IntegrationObservation.MAX_COLLECTION_IDS, ids.size());
        assertEquals(1L, ids.get(0));
        assertEquals(100L, ids.get(ids.size() - 1));
    }

    @Test
    void rejectsNonSetAttributeWithoutReplacingItWithUntrustedValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                IntegrationObservationContext.AUTHORIZED_COLLECTION_IDS_ATTRIBUTE,
                List.of(1L, 2L));

        assertEquals(List.of(),
                IntegrationObservationContext.authorizedCollectionIds(request));
        IntegrationObservationContext.addAuthorizedCollection(request, 3L);
        assertEquals(List.of(3L),
                IntegrationObservationContext.authorizedCollectionIds(request));
    }
}
