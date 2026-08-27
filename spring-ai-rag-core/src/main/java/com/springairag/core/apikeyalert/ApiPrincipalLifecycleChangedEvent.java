package com.springairag.core.apikeyalert;

/** API principal 权威生命周期状态已经提交。 */
public record ApiPrincipalLifecycleChangedEvent(String principalId) {

    public ApiPrincipalLifecycleChangedEvent {
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId must not be blank");
        }
    }
}
