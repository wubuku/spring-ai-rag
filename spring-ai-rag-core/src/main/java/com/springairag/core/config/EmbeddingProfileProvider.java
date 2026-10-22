package com.springairag.core.config;

/**
 * 为写入和检索提供同一个活动 Embedding Profile。
 */
@FunctionalInterface
public interface EmbeddingProfileProvider {

    EmbeddingProfile getActiveProfile();

    default EmbeddingProfile findRequiredById(long profileId) {
        EmbeddingProfile profile = getActiveProfile();
        if (profile.id() != profileId) {
            throw new IllegalStateException("Embedding profile not found: " + profileId);
        }
        return profile;
    }

    default EmbeddingProfile findRequiredByKey(String profileKey) {
        EmbeddingProfile profile = getActiveProfile();
        if (!profile.profileKey().equals(profileKey)) {
            throw new IllegalStateException("Embedding profile not found: " + profileKey);
        }
        return profile;
    }

    default EmbeddingProfile getProfileForCollection(long collectionId) {
        return getActiveProfile();
    }
}
