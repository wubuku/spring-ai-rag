package com.springairag.core.entity;

/** 分阶段 credential 轮换状态。 */
public enum ApiKeyRotationStatus {
    PENDING,
    COMPLETED,
    CANCELED,
    EXPIRED,
    REVOKED
}
