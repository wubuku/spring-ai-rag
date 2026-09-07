package com.springairag.core.alertdelivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 低延迟唤醒事件：无载荷标记事件，可安全跨事务提交后发布。 */
class AlertNotificationsAvailableEventTest {

    @Test
    void eventIsALightweightMarkerWithNoPayload() {
        AlertNotificationsAvailableEvent event = new AlertNotificationsAvailableEvent();

        // record 生成的 toString 包含类型名，便于日志定位唤醒来源。
        assertTrue(event.toString().contains("AlertNotificationsAvailableEvent"));
    }
}
