package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Notification channel configuration for alert delivery.
 * Supports DingTalk webhooks and email SMTP.
 */
@Component
@ConfigurationProperties(prefix = "rag.notifications")
public class NotificationConfig {

    private boolean enabled = false;
    private final List<DingTalkConfig> dingtalk = new ArrayList<>();
    private final EmailConfig email = new EmailConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<DingTalkConfig> getDingtalk() {
        return dingtalk;
    }

    public EmailConfig getEmail() {
        return email;
    }

    public static class DingTalkConfig {
        private String name = "default";
        private String webhookUrl;
        private String secret;
        private boolean enabled = true;
        private List<String> alertTypes = List.of("THRESHOLD_HIGH", "THRESHOLD_LOW", "SLO_BREACH", "AVAILABILITY");

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAlertTypes() {
            return alertTypes;
        }

        public void setAlertTypes(List<String> alertTypes) {
            this.alertTypes = alertTypes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DingTalkConfig that = (DingTalkConfig) o;
            return that.enabled == enabled
                    && Objects.equals(name, that.name)
                    && Objects.equals(webhookUrl, that.webhookUrl)
                    && Objects.equals(secret, that.secret)
                    && Objects.equals(alertTypes, that.alertTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, webhookUrl, secret, enabled, alertTypes);
        }

        @Override
        public String toString() {
            return "DingTalkConfig{name='" + name + "', webhookUrl='" + webhookUrl
                    + "', secret='***', enabled=" + enabled
                    + ", alertTypes=" + alertTypes + "}";
        }
    }

    public static class EmailConfig {
        private boolean enabled = false;
        private String host;
        private int port = 587;
        private String username;
        private String password;
        private String from;
        private List<String> to = new ArrayList<>();
        private List<String> alertTypes = List.of("CRITICAL", "SLO_BREACH");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public List<String> getTo() {
            return to;
        }

        public void setTo(List<String> to) {
            this.to = to;
        }

        public List<String> getAlertTypes() {
            return alertTypes;
        }

        public void setAlertTypes(List<String> alertTypes) {
            this.alertTypes = alertTypes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EmailConfig that = (EmailConfig) o;
            return that.enabled == enabled
                    && that.port == port
                    && Objects.equals(host, that.host)
                    && Objects.equals(username, that.username)
                    && Objects.equals(password, that.password)
                    && Objects.equals(from, that.from)
                    && Objects.equals(to, that.to)
                    && Objects.equals(alertTypes, that.alertTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, host, port, username, password, from, to, alertTypes);
        }

        @Override
        public String toString() {
            return "EmailConfig{enabled=" + enabled + ", host='" + host + "', port=" + port
                    + ", username='" + username + "', password='***', from='" + from
                    + "', to=" + to + ", alertTypes=" + alertTypes + "}";
        }
    }
}
