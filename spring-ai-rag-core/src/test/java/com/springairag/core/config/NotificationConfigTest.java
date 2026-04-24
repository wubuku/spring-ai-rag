package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    // ===== DingTalkConfig equals/hashCode/toString =====

    @Test
    void dingtalkConfig_equals_sameFields_returnsTrue() {
        NotificationConfig.DingTalkConfig dt1 = new NotificationConfig.DingTalkConfig();
        dt1.setName("prod");
        dt1.setWebhookUrl("https://example.com/1");
        dt1.setSecret("SEC");
        dt1.setEnabled(true);
        dt1.setAlertTypes(List.of("CRITICAL"));

        NotificationConfig.DingTalkConfig dt2 = new NotificationConfig.DingTalkConfig();
        dt2.setName("prod");
        dt2.setWebhookUrl("https://example.com/1");
        dt2.setSecret("SEC");
        dt2.setEnabled(true);
        dt2.setAlertTypes(List.of("CRITICAL"));

        assertEquals(dt1, dt2);
        assertEquals(dt1.hashCode(), dt2.hashCode());
    }

    @Test
    void dingtalkConfig_equals_differentName_returnsFalse() {
        NotificationConfig.DingTalkConfig dt1 = new NotificationConfig.DingTalkConfig();
        dt1.setName("prod");

        NotificationConfig.DingTalkConfig dt2 = new NotificationConfig.DingTalkConfig();
        dt2.setName("dev");

        assertNotEquals(dt1, dt2);
    }

    @Test
    void dingtalkConfig_equals_differentWebhookUrl_returnsFalse() {
        NotificationConfig.DingTalkConfig dt1 = new NotificationConfig.DingTalkConfig();
        dt1.setWebhookUrl("https://a.com");

        NotificationConfig.DingTalkConfig dt2 = new NotificationConfig.DingTalkConfig();
        dt2.setWebhookUrl("https://b.com");

        assertNotEquals(dt1, dt2);
    }

    @Test
    void dingtalkConfig_equals_nullAlertTypes_returnsFalse() {
        NotificationConfig.DingTalkConfig dt1 = new NotificationConfig.DingTalkConfig();
        dt1.setAlertTypes(null);

        NotificationConfig.DingTalkConfig dt2 = new NotificationConfig.DingTalkConfig();
        dt2.setAlertTypes(List.of());

        assertNotEquals(dt1, dt2);
    }

    @Test
    void dingtalkConfig_equals_differentClass_returnsFalse() {
        NotificationConfig.DingTalkConfig dt = new NotificationConfig.DingTalkConfig();
        assertNotEquals(dt, "not a DingTalkConfig");
        assertNotEquals(dt, null);
    }

    @Test
    void dingtalkConfig_toString_containsAllFields() {
        NotificationConfig.DingTalkConfig dt = new NotificationConfig.DingTalkConfig();
        dt.setName("prod");
        dt.setWebhookUrl("https://example.com");
        dt.setSecret("SUPERSECRET");
        dt.setEnabled(true);
        dt.setAlertTypes(List.of("CRITICAL"));

        String str = dt.toString();
        assertTrue(str.contains("prod"));
        assertTrue(str.contains("https://example.com"));
        assertFalse(str.contains("SUPERSECRET")); // password masked
        assertTrue(str.contains("***"));
        assertTrue(str.contains("CRITICAL"));
    }

    // ===== EmailConfig equals/hashCode/toString =====

    @Test
    void emailConfig_equals_sameFields_returnsTrue() {
        NotificationConfig.EmailConfig e1 = new NotificationConfig.EmailConfig();
        e1.setHost("smtp.example.com");
        e1.setPort(587);
        e1.setUsername("user");
        e1.setPassword("pass123");
        e1.setFrom("from@example.com");
        e1.setEnabled(true);
        e1.setTo(List.of("to@example.com"));
        e1.setAlertTypes(List.of("CRITICAL"));

        NotificationConfig.EmailConfig e2 = new NotificationConfig.EmailConfig();
        e2.setHost("smtp.example.com");
        e2.setPort(587);
        e2.setUsername("user");
        e2.setPassword("pass123");
        e2.setFrom("from@example.com");
        e2.setEnabled(true);
        e2.setTo(List.of("to@example.com"));
        e2.setAlertTypes(List.of("CRITICAL"));

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void emailConfig_equals_differentHost_returnsFalse() {
        NotificationConfig.EmailConfig e1 = new NotificationConfig.EmailConfig();
        e1.setHost("smtp.example.com");

        NotificationConfig.EmailConfig e2 = new NotificationConfig.EmailConfig();
        e2.setHost("smtp.other.com");

        assertNotEquals(e1, e2);
    }

    @Test
    void emailConfig_equals_differentPort_returnsFalse() {
        NotificationConfig.EmailConfig e1 = new NotificationConfig.EmailConfig();
        e1.setPort(587);

        NotificationConfig.EmailConfig e2 = new NotificationConfig.EmailConfig();
        e2.setPort(465);

        assertNotEquals(e1, e2);
    }

    @Test
    void emailConfig_equals_nullTo_returnsFalse() {
        NotificationConfig.EmailConfig e1 = new NotificationConfig.EmailConfig();
        e1.setTo(null);

        NotificationConfig.EmailConfig e2 = new NotificationConfig.EmailConfig();
        e2.setTo(List.of());

        assertNotEquals(e1, e2);
    }

    @Test
    void emailConfig_equals_differentClass_returnsFalse() {
        NotificationConfig.EmailConfig e = new NotificationConfig.EmailConfig();
        assertNotEquals(e, "not an EmailConfig");
        assertNotEquals(e, null);
    }

    @Test
    void emailConfig_toString_containsKeyFields_excludesPassword() {
        NotificationConfig.EmailConfig e = new NotificationConfig.EmailConfig();
        e.setHost("smtp.example.com");
        e.setPort(587);
        e.setUsername("user");
        e.setPassword("SUPERPASSWORD");
        e.setFrom("from@example.com");
        e.setEnabled(true);
        e.setTo(List.of("to@example.com"));
        e.setAlertTypes(List.of("CRITICAL"));

        String str = e.toString();
        assertTrue(str.contains("smtp.example.com"));
        assertTrue(str.contains("587"));
        assertTrue(str.contains("user"));
        assertFalse(str.contains("SUPERPASSWORD")); // password masked
        assertTrue(str.contains("***"));
        assertTrue(str.contains("from@example.com"));
    }
}
