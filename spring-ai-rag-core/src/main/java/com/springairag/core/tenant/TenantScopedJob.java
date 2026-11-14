package com.springairag.core.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * WP7：标记「租户私有数据作用域」的定时 / 异步方法（实施规划 §5.4 / §6 WP7）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li><b>SINGLE</b>：显式以 {@code default} 租户作用域执行一次（N=1 特例，
 *       不允许「没有租户上下文也能跑」的旁路——这是参考项目给出的关键教训：
 *       单租户下能跑通会掩盖多租户下静默读 0 行）；</li>
 *   <li><b>STRICT</b>：由平台枚举 ACTIVE 租户，逐租户独立作用域执行，单租户失败不阻断
 *       其余租户，失败汇总上报。</li>
 * </ul>
 *
 * <p>未标注的定时任务视为<strong>平台全局任务</strong>（PLATFORM_GLOBAL）：
 * 不绑定租户作用域，只能访问平台全局表；每个未标注方法都必须在
 * {@link TenantTaskInventory} 中登记豁免理由，否则静态门禁判红。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TenantScopedJob {
}
