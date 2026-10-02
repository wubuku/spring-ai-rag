import { CircleCheck, CircleX, Info, TriangleAlert, type LucideIcon } from 'lucide-react';

/**
 * Toast 语义的唯一事实源。
 *
 * 这里过去同时承担了三件事：字面 emoji 映射、类型联合、以及一个被
 * `Toast.tsx` 与 `ToastContext.ts` 各自重复抄写一遍的 `ToastType`。
 * 现在三者合并为一处：新增一种 toast 只需在这里加一项，类型、图标与
 * CSS tone 会一起跟随。
 */
export type ToastType = 'success' | 'error' | 'info' | 'warning';

/**
 * 每个语义的图标。用 lucide 组件而不是 emoji 字形，原因有三：
 *   1. emoji 在各平台度量不同，无法与相邻文字对齐；
 *   2. emoji 不能继承 `currentColor`，因此无法跟随 tone 变色；
 *   3. emoji 只能靠匹配 Unicode 字符来断言，测试非常脆弱。
 * 详见 `docs/developer-reference.md` 的 emoji 禁令。
 */
export const TOAST_ICONS = {
  success: CircleCheck,
  error: CircleX,
  info: Info,
  warning: TriangleAlert,
} as const satisfies Record<ToastType, LucideIcon>;

/** 非错误 toast 的自动消失时长（毫秒）。 */
export const TOAST_AUTO_DISMISS_MS = 4000;

/**
 * 哪些 toast 会自动消失。
 *
 * 错误 toast **不自动消失**。它承载的是一次失败操作的唯一原因
 * （`Re-embed failed: ...`、`Failed to create collection: ...`、轮换失败原因等），
 * 4 秒后自动抹掉等于让用户来不及读完，也让失败在事后无从追溯。
 * 成功与提示类消息没有这类信息量，自动消失可以接受。
 */
export const AUTO_DISMISS_TYPES: ReadonlySet<ToastType> = new Set<ToastType>([
  'success',
  'info',
  'warning',
]);
