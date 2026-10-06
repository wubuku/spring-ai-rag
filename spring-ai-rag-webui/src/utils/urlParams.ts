/**
 * A tab's identity and its label live in one declaration, so they cannot drift apart.
 *
 * Why this file exists (Batch 942)
 * -------------------------------
 * Three pages each wrote their tab vocabulary out in **two or three separate places**:
 * a `type Tab = 'a' | 'b' | …` union, a table of `[id, labelKey]` pairs for the tab
 * strip, and a hand-written chain validating the URL parameter —
 *
 *     const tab: Tab = tabParam === 'history' || tabParam === 'feedback' || … ? tabParam : 'report';
 *
 * and `Settings` did not even name its type, inlining the union into the annotation.
 *
 * The cost of that was measured, not argued. Adding a tab means editing the union; a
 * developer who edits it and forgets the chain gets **no error at all** — the ternary
 * simply narrows to a subset of `Tab`, which still assigns fine. So
 *
 *     type Tab = … | 'escalation-policies';
 *
 * with the chain untouched compiles clean, and `?tab=escalation-policies` silently falls
 * back to the first tab. The tab exists in the type system, in the types a person reads,
 * and is unreachable in the browser.
 *
 * Deriving the type from the label table removes the possibility rather than adding a
 * check for it. `keyof typeof TABS` means the valid set **is** the set of tabs that have a
 * label, so there is no second declaration left to forget — and the direction that used
 * to be silent is now the loud one: adding a member to a union without a label was never
 * a thing anybody could do here, because there is no union to edit.
 */

/** The translator shape this module needs — `t('key')` and `t('key', { name })`. */
export type Translate = (key: string, options?: Record<string, unknown>) => string;

/**
 * Pick a tab id out of a URL parameter, or fall back.
 *
 * `allowed` is the page's tab table — an object whose **keys** are the ids and whose
 * values are whatever that page needs (an i18n key here). Keys are what the function
 * reads, so passing the table directly is what keeps the ids and the labels in one
 * declaration.
 *
 * Membership is tested with `Object.hasOwn`, and the choice of that particular test is
 * the second thing these tests caught. `param in allowed` reads as though it asks "is this
 * one of the table's own keys", and it does not: `in` walks the prototype chain, so
 * `toString`, `constructor` and `__proto__` are all "in" a plain object literal. A
 * crafted `?tab=toString` would then resolve to a tab id the table never declared, and the
 * page would go looking for a panel that does not exist. The self-test for this file
 * asserts the inherited names fall back, which is how it was found — the adversarial case
 * was written before the fix, not after.
 *
 * `Object.hasOwn` rather than `hasOwnProperty` because this module's own table is a plain
 * object with a null-ish prototype story only if someone goes looking for one, and the
 * shorter form says which question is being asked.
 */
export function resolveTabParam<T extends string>(
  param: string | null | undefined,
  allowed: Record<T, unknown>,
  fallback: T,
): T {
  if (param !== null && param !== undefined && Object.hasOwn(allowed, param)) {
    return param as T;
  }
  return fallback;
}

/**
 * A tab table as the `Tabs` component wants it: ids in declaration order, labels
 * resolved through `t`.
 */
export function toTabItems<T extends string>(
  tabs: Record<T, string>,
  t: Translate,
): Array<{ id: T; label: string }> {
  // `Object.keys` cannot know the keys are `T[]`, so it takes one cast; going through
  // `Object.entries` instead would cost a second one on the *value*, which is the half
  // the compiler does know.
  return (Object.keys(tabs) as T[]).map(id => ({ id, label: t(tabs[id]) }));
}

/**
 * The tab table as query parameters, with the default tab written as "no tab at all".
 *
 * `Evaluation` had this special case inline — `next === 'report' ? {} : { tab: next }` —
 * so the first tab had a second, different representation depending on which direction
 * the user moved. Putting it here means a caller cannot forget it, and the rule is stated
 * once: **the default tab is the one that is not in the URL.**
 */
export function tabSearchParams<T extends string>(
  id: T,
  fallback: T,
): Record<string, string> {
  return id === fallback ? {} : { tab: id };
}
