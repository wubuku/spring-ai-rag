package com.springairag.core.alertdelivery;

/** 事务提交后用于低延迟唤醒 durable delivery worker 的本地事件。 */
public record AlertNotificationsAvailableEvent() {
}
