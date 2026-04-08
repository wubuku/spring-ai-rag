package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE (Server-Sent Events) configuration properties.
 *
 * @param heartbeatIntervalSeconds heartbeat interval in seconds (0 = disabled, default 30).
 *                                 Heartbeat sends a comment (: heartbeat\n\n) to keep connections alive
 *                                 through proxies and load balancers.
 */
@ConfigurationProperties(prefix = "rag.sse")
public class RagSseProperties {

    /**
     * Heartbeat interval in seconds.
     * Set to 0 to disable heartbeat.
     * Proxies may close idle connections after ~60s, so a heartbeat every 30s is recommended.
     */
    private int heartbeatIntervalSeconds = 30;

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    /**
     * Returns true if heartbeat is enabled.
     */
    public boolean isHeartbeatEnabled() {
        return heartbeatIntervalSeconds > 0;
    }
}
