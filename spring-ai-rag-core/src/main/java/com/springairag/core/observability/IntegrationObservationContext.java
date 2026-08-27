package com.springairag.core.observability;

import com.springairag.core.filter.ApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 保存当前 HTTP 请求中已经完成存在性与 ACL 校验的 Collection ID。
 */
public final class IntegrationObservationContext {

    static final String AUTHORIZED_COLLECTION_IDS_ATTRIBUTE =
            IntegrationObservationContext.class.getName() + ".authorizedCollectionIds";

    private IntegrationObservationContext() {
    }

    public static void addAuthorizedCollection(Long collectionId) {
        if (collectionId == null || collectionId <= 0) {
            return;
        }
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            addAuthorizedCollection(attributes.getRequest(), collectionId);
        }
    }

    public static void addAuthorizedCollection(
            HttpServletRequest request,
            Long collectionId) {
        if (request == null || collectionId == null || collectionId <= 0) {
            return;
        }
        Object current = request.getAttribute(AUTHORIZED_COLLECTION_IDS_ATTRIBUTE);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (current instanceof LinkedHashSet<?> existing) {
            for (Object value : existing) {
                if (value instanceof Long id && id > 0) {
                    ids.add(id);
                }
            }
        }
        if (ids.size() < IntegrationObservation.MAX_COLLECTION_IDS) {
            ids.add(collectionId);
        }
        request.setAttribute(AUTHORIZED_COLLECTION_IDS_ATTRIBUTE, ids);
    }

    public static List<Long> authorizedCollectionIds(HttpServletRequest request) {
        if (request == null) {
            return List.of();
        }
        Object value = request.getAttribute(AUTHORIZED_COLLECTION_IDS_ATTRIBUTE);
        if (!(value instanceof LinkedHashSet<?> raw)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Long id && id > 0) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }
}
