/**
 * The locale the shared i18next double reports through `i18n.language`.
 *
 * Why this is a constant
 * ----------------------
 * `Dashboard.tsx` formats its last-check tile with
 * `formatAbsolute(timestamp, i18n.language)`, and `setup.ts` pins
 * `i18n.language` to `'en'` in every component test. A test that formats the
 * expected value with bare `new Date(...).toLocaleString()` reads the **host**
 * locale instead, so it passes on an en-US machine and fails on a zh-CN one
 * (observed: expected `2026/9/6 08:00:00`, rendered `9/6/2026, 8:00:00 AM`).
 *
 * The assertion and the double therefore have to agree on one value. Spelling
 * `'en'` in two files lets them drift silently; importing it from here makes the
 * drift a compile error instead.
 */
export const TEST_LOCALE = 'en';
