package com.springairag.core.security;

/**
 * 已注册 route 对 capability filter 的有限决策。
 */
public enum ApiRoutePolicyDecision {
    READ,
    WRITE,
    MANAGEMENT,
    UNDECLARED
}
