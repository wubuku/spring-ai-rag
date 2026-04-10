package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NotificationConfig.
 * Tests property binding and default values.
 */
class NotificationConfigTest {

    @Test
    void defaultEnabled_isFalse() {
        NotificationConfig config = new NotificationConfig();
        assertFalse(config.isEnabled());
    }

    @Test
    void setEnabled_reflectsInIsEnabled() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        assertTrue(config.isEnabled());
    }

    @Test
    void defaultDingtalkList_isEmpty() {
        NotificationConfig config = new NotificationConfig();
        assertNotNull(config.getDingtalk());
        assertTrue(config.getDingtalk().isEmpty());
    }

    @Test
    void addDingtalkChannel_receivedInList() {
        NotificationConfig config = new NotificationConfig();
        NotificationConfig.DingTalkConfig dt = new NotificationConfig.DingTalkConfig();
        dt.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=xxx");
        dt.setEnabled(true);
        config.getDingtalk().add(dt);

        assertEquals(1, config.getDingtalk().size());
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=xxx",
                config.getDingtalk().get(0).getWebhookUrl());
    }

    @Test
    void emailConfig_isNeverNull() {
        NotificationConfig config = new NotificationConfig();
        assertNotNull(config.getEmail());
    }

    @Test
    void emailConfig_defaultValues() {
        NotificationConfig config = new NotificationConfig();
        NotificationConfig.EmailConfig email = config.getEmail();
        assertNull(email.getHost()); // null by default
        assertEquals(587, email.getPort());
        assertFalse(email.isEnabled());
        assertNotNull(email.getAlertTypes());
    }

    @Test
    void emailConfig_settersAndGetters() {
        NotificationConfig config = new NotificationConfig();
        NotificationConfig.EmailConfig email = config.getEmail();
        email.setHost("smtp.example.com");
        email.setPort(465);
        email.setUsername("user");
        email.setPassword("pass");
        email.setFrom("from@example.com");
        email.setEnabled(true);
        email.setTo(new ArrayList<>());
        email.getTo().add("to@example.com");

        assertEquals("smtp.example.com", email.getHost());
        assertEquals(465, email.getPort());
        assertEquals("user", email.getUsername());
        assertEquals("pass", email.getPassword());
        assertEquals("from@example.com", email.getFrom());
        assertTrue(email.isEnabled());
        assertEquals(1, email.getTo().size());
        assertEquals("to@example.com", email.getTo().get(0));
    }

    @Test
    void dingtalkConfig_settersAndGetters() {
        NotificationConfig config = new NotificationConfig();
        NotificationConfig.DingTalkConfig dt = new NotificationConfig.DingTalkConfig();
        dt.setName("prod-alerts");
        dt.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        dt.setSecret("SEC123");
        dt.setEnabled(true);
        dt.setAlertTypes(new ArrayList<>());
        dt.getAlertTypes().add("LATENCY");
        dt.getAlertTypes().add("AVAILABILITY");
        config.getDingtalk().add(dt);

        NotificationConfig.DingTalkConfig retrieved = config.getDingtalk().get(0);
        assertEquals("prod-alerts", retrieved.getName());
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=ABC", retrieved.getWebhookUrl());
        assertEquals("SEC123", retrieved.getSecret());
        assertTrue(retrieved.isEnabled());
        assertEquals(2, retrieved.getAlertTypes().size());
        assertTrue(retrieved.getAlertTypes().contains("LATENCY"));
        assertTrue(retrieved.getAlertTypes().contains("AVAILABILITY"));
    }

    @Test
    void dingtalkConfig_defaultValues() {
        NotificationConfig.DingTalkConfig dt = new NotificationConfig.DingTalkConfig();
        assertEquals("default", dt.getName());
        assertNull(dt.getWebhookUrl());
        assertNull(dt.getSecret());
        assertTrue(dt.isEnabled());
        assertEquals(4, dt.getAlertTypes().size());
        assertTrue(dt.getAlertTypes().contains("THRESHOLD_HIGH"));
    }

    @Test
    void emailConfig_defaultAlertTypes() {
        NotificationConfig.EmailConfig email = new NotificationConfig.EmailConfig();
        assertEquals(2, email.getAlertTypes().size());
        assertTrue(email.getAlertTypes().contains("CRITICAL"));
        assertTrue(email.getAlertTypes().contains("SLO_BREACH"));
    }

    @Test
    void multipleDingtalkChannels_allRetrieved() {
        NotificationConfig config = new NotificationConfig();

        NotificationConfig.DingTalkConfig dt1 = new NotificationConfig.DingTalkConfig();
        dt1.setName("channel1");
        dt1.setWebhookUrl("https://example.com/1");
        config.getDingtalk().add(dt1);

        NotificationConfig.DingTalkConfig dt2 = new NotificationConfig.DingTalkConfig();
        dt2.setName("channel2");
        dt2.setWebhookUrl("https://example.com/2");
        config.getDingtalk().add(dt2);

        assertEquals(2, config.getDingtalk().size());
        assertEquals("channel1", config.getDingtalk().get(0).getName());
        assertEquals("channel2", config.getDingtalk().get(1).getName());
    }
}
