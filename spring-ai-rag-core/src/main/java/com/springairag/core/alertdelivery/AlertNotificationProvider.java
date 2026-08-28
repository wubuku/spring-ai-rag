package com.springairag.core.alertdelivery;

/** Durable worker 使用的同步、单次调用通知 provider 合同。 */
public interface AlertNotificationProvider {

    String provider();

    /** 当前配置是否声明了至少一条该类型的 route。 */
    boolean isRoutedFor(String alertType);

    /** provider 是否至少有一条已配置 route，用于低敏能力发现。 */
    boolean isConfigured();

    /** 当前进程是否具备执行 route 所需的 adapter 与完整配置。 */
    boolean isCurrentlyAvailable();

    /** 只执行一次 provider attempt，不 sleep、不做内部重试。 */
    AlertNotificationAttemptResult deliver(AlertNotificationPayload payload);
}
