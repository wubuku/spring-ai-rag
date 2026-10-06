/**
 * Numbers reach the screen through one module, and the expensive part is built once.
 *
 * Why this file exists (Batch 940)
 * -------------------------------
 * `Intl.NumberFormat` appeared in exactly one file — `Metrics.tsx` — in two
 * constructions, and one of the two ran inside a `.map()` over table rows, so **every
 * cell of the usage table built its own formatter on every render**. Measured on this
 * machine, 20 000 calls:
 *
 *     new Intl.NumberFormat()   348.7 ms
 *     reused formatter            4.9 ms     71x, byte-identical output
 *
 * Construction is the expensive half of `Intl`, not `format`. Reusing the object is
 * therefore not a micro-optimisation; it is the difference between a table that is
 * cheap to repaint and one that is not.
 *
 * The other thing this file settles is what a number looks like when the server did not
 * send one. The answer across the app is `—` (`Dashboard`'s `Metric` renders
 * `value ?? '—'`, `Documents` renders `doc.collectionName ?? '—'`, `ABTest` uses `'—'`).
 * `Metrics.formatInteger` was the exception: it returned `'0'` for `undefined`, which is
 * a *claim* rather than an absence — it says the count was zero.
 *
 * That branch turns out to be **unreachable from the real API**, and it is worth saying
 * how that was established rather than assumed: `LlmUsageResponse.Totals` carries
 * `BigDecimal` fields, which are nullable in Java, so null looked possible — but every
 * `SUM(` in `LlmUsageQueryRepository` is wrapped in `COALESCE(..., 0)` (4 occurrences,
 * 4 wrapped), and `LlmUsageQueryService.totals()` only forwards what that query returned.
 * The frontend type already said as much: `UsageNumericValue = number | string`, with no
 * null. So the branch was not showing users a false zero; it was a dead branch whose
 * signature invited the next caller to pass `undefined` and get one. It is removed here
 * rather than given a test, because a test that has to fabricate `undefined` to reach a
 * branch asserts that the branch exists, not that it is correct.
 *
 * ## Why a big integer takes its own path
 *
 * `totalTokens` is typed `UsageNumericValue = number | string` because a `BigDecimal`
 * with more precision than an IEEE double cannot survive JSON as a `number`. Reading it
 * with `Number()` would silently round a token count — the one quantity on this page
 * someone might reconcile against a provider's invoice. A string of digits is therefore
 * formatted through `BigInt`, which is exact at any length.
 */

import { UNREADABLE } from './time';

/** A value the API may send for a number. `string` carries `BigDecimal` precision. */
export type NumericValue = number | string | bigint | null | undefined;

/**
 * Formatter cache, keyed by locale and the options that change the output.
 *
 * Bounded because the key contains a locale string, and a locale reaching this module
 * from a query parameter is not a thing that happens today — but a cache that can only
 * grow is a cache with a failure mode nobody has to have met yet.
 */
const FORMATTER_CACHE = new Map<string, Intl.NumberFormat>();
const FORMATTER_CACHE_LIMIT = 32;

function formatter(
  locale: string | undefined,
  options: Intl.NumberFormatOptions,
): Intl.NumberFormat {
  const key = `${locale ?? ''}|${JSON.stringify(options)}`;
  const cached = FORMATTER_CACHE.get(key);
  if (cached) return cached;

  const created = new Intl.NumberFormat(locale, options);
  if (FORMATTER_CACHE.size >= FORMATTER_CACHE_LIMIT) {
    // Plain eviction rather than an LRU: a page uses one or two locales, so anything
    // past the limit is a caller that is churning, not one that is about to come back.
    FORMATTER_CACHE.clear();
  }
  FORMATTER_CACHE.set(key, created);
  return created;
}

/** All-digits, so `BigInt` will take it. `''` and `'-'` are not numbers. */
const DIGITS = /^\d+$/u;

/**
 * A count of things, grouped for the reader: `1234567` → `1,234,567`.
 *
 * A value that is not a number — absent, `NaN`, or a string the server should not have
 * sent — renders {@link UNREADABLE} rather than itself. Returning the offending text was
 * the previous behaviour, and it is the same mistake Batch 938 found in
 * `ApiKeys.formatDateTime`, where `toLocaleString()` on an Invalid Date handed the user
 * the string `"Invalid Date"` instead of signalling that the value was unreadable. The
 * page already has a place to record a number it could not obtain
 * (`usageUnavailableCount`), so nothing is lost by declining to render one.
 */
export function formatCount(
  value: NumericValue,
  locale?: string,
): string {
  if (value === null || value === undefined || value === '') return UNREADABLE;

  if (typeof value === 'bigint') return formatter(locale, {}).format(value);

  if (typeof value === 'number') {
    return Number.isFinite(value) ? formatter(locale, {}).format(value) : UNREADABLE;
  }

  const text = value.trim();
  if (DIGITS.test(text)) {
    // Exact at any length; `Number` would round past 2^53.
    return formatter(locale, {}).format(BigInt(text));
  }
  // A signed or fractional decimal is still a number, just not a whole count.
  const numeric = Number(text);
  return Number.isFinite(numeric)
    ? formatter(locale, {}).format(numeric)
    : UNREADABLE;
}

/**
 * A decimal amount, kept at up to `maximumFractionDigits` places.
 *
 * The default of 8 is not a taste decision: `configuredCost` is a `BigDecimal` per unit
 * (a per-million-token price is small but not zero), and rounding it to 2 would show a
 * real cost as `$0.00` on a page whose entire job is showing what usage cost.
 */
export function formatDecimal(
  value: NumericValue,
  locale?: string,
  maximumFractionDigits = 8,
): string {
  if (value === null || value === undefined || value === '') return UNREADABLE;

  // A bigint beyond 2^53 loses precision here on purpose: a price is a decimal an
  // operator reconciles by eye, and an exact-but-unreadable 40-digit figure is worse
  // than a rounded one. `formatCount` is the function that must not round.
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return UNREADABLE;

  return formatter(locale, {
    minimumFractionDigits: 0,
    maximumFractionDigits,
  }).format(numeric);
}
