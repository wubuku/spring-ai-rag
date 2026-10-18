package com.springairag.api.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public record EvidenceSnapshot(
        long documentRevision,
        String contentHash,
        Long collectionId,
        String indexKind,
        Long embeddingProfileId,
        long generation,
        String chunkerVersion,
        String evidenceDigest) {

    public boolean sameDocument(EvidenceSnapshot other) {
        return other != null && documentRevision == other.documentRevision
                && Objects.equals(contentHash, other.contentHash)
                && Objects.equals(collectionId, other.collectionId);
    }

    public boolean compatibleEvidence(EvidenceSnapshot other) {
        return sameDocument(other) && Objects.equals(chunkerVersion, other.chunkerVersion)
                && (!Objects.equals(indexKind, other.indexKind)
                    || generation == other.generation
                        && Objects.equals(embeddingProfileId, other.embeddingProfileId));
    }

    public EvidenceSnapshot withText(String text) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(text, "").getBytes(StandardCharsets.UTF_8)));
            return new EvidenceSnapshot(documentRevision, contentHash, collectionId,
                    indexKind, embeddingProfileId, generation, chunkerVersion, digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static EvidenceSnapshot from(Object value) {
        if (value instanceof EvidenceSnapshot snapshot) {
            return snapshot;
        }
        if (!(value instanceof Map<?, ?> values)) {
            return null;
        }
        return new EvidenceSnapshot(requiredNumber(values, "documentRevision"),
                (String) values.get("contentHash"), number(values.get("collectionId")),
                (String) values.get("indexKind"), number(values.get("embeddingProfileId")),
                requiredNumber(values, "generation"), (String) values.get("chunkerVersion"),
                (String) values.get("evidenceDigest"));
    }

    private static long requiredNumber(Map<?, ?> values, String key) {
        Long number = number(values.get(key));
        if (number == null) {
            throw new IllegalArgumentException("Invalid evidence snapshot: " + key);
        }
        return number;
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
