package com.springairag.core.config;

public class RagAdmissionProperties {
    private boolean enabled = true;
    private int maxRequests = 64;
    private int maxRequestsPerPrincipal = 16;
    private int maxModelCalls = 32;
    private int maxModelCallsPerPrincipal = 8;
    private int maxModelCallsPerProvider = 16;
    private int maxBackgroundModelCalls = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getMaxRequests() { return maxRequests; }
    public void setMaxRequests(int value) { maxRequests = positive(value); }
    public int getMaxRequestsPerPrincipal() { return maxRequestsPerPrincipal; }
    public void setMaxRequestsPerPrincipal(int value) { maxRequestsPerPrincipal = positive(value); }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int value) { maxModelCalls = positive(value); }
    public int getMaxModelCallsPerPrincipal() { return maxModelCallsPerPrincipal; }
    public void setMaxModelCallsPerPrincipal(int value) { maxModelCallsPerPrincipal = positive(value); }
    public int getMaxModelCallsPerProvider() { return maxModelCallsPerProvider; }
    public void setMaxModelCallsPerProvider(int value) { maxModelCallsPerProvider = positive(value); }
    public int getMaxBackgroundModelCalls() { return maxBackgroundModelCalls; }
    public void setMaxBackgroundModelCalls(int value) { maxBackgroundModelCalls = positive(value); }

    private int positive(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("Admission limits must be positive");
        }
        return value;
    }
}
