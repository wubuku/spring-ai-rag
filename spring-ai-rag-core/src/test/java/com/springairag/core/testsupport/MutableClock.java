package com.springairag.core.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个可以往前拨的时钟，用来把"等真实时间流逝"换成"拨时间"。
 *
 * <p>为什么需要它：凡是状态迁移由**墙上时间**决定的组件，其测试就只能靠
 * {@code Thread.sleep} 去等。Batch 945 普查到 {@code LlmCircuitBreaker} 的
 * OPEN → HALF_OPEN 迁移就是这样——三处测试各睡 1100ms，合计 3.3 秒墙上时间，
 * 而且睡不够时，失败会以"断路器状态不对"的面貌出现，完全不指向时间。
 * 把 {@link Clock} 作为构造参数注入之后（见
 * {@code LlmCircuitBreaker(RagCircuitBreakerProperties, Clock)}），
 * 同一条迁移可以被精确驱动，而且不需要任何等待。
 *
 * <p>为什么 {@link #withZone} 共享同一份状态而不是各走各的：{@code withZone} 返回的
 * 是"同一个钟的另一个时区视图"。如果它复制一份时间，那么
 * {@code clock.withZone(UTC).instant()} 和 {@code clock.instant()} 会在推进之后
 * 给出两个不同的答案，而这种不一致要到某个依赖时区的断言上才会暴露——所以这里让
 * 视图共享一个 {@link AtomicReference}。
 *
 * <p>线程安全：推进与读取都走 {@link AtomicReference}，可以被多个线程同时读。
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    /** 以给定时刻为起点、UTC 时区。 */
    public static MutableClock startingAt(Instant start) {
        return new MutableClock(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    /** 以给定 epoch 毫秒为起点、UTC 时区。 */
    public static MutableClock startingAtEpochMilli(long epochMilli) {
        return startingAt(Instant.ofEpochMilli(epochMilli));
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /** 往前拨一段时间。可以多次调用。 */
    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    /** 往前拨给定的毫秒数。 */
    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public long millis() {
        return now.get().toEpochMilli();
    }

    @Override
    public String toString() {
        return "MutableClock[" + now.get() + " " + zone + "]";
    }
}