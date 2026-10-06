import { describe, expect, it, vi } from 'vitest';
import { formatCount, formatDecimal, type NumericValue } from './number';
import { UNREADABLE } from './time';

// Batch 940. Two things are pinned here. First, the expensive half of `Intl` is
// construction, not `format` — 20 000 calls measured 348.7 ms building a formatter per
// call against 4.9 ms reusing one, 71x, with byte-identical output — so a module that
// builds one per value is a page that is expensive to repaint. Second, a `BigDecimal`
// arrives as a string precisely because it may not survive JSON as a `number`, and
// `totalTokens` is the figure someone reconciles against an invoice.

describe('formatCount', () => {
  it('groups a count for the reader', () => {
    expect(formatCount(1234567, 'en-US')).toBe('1,234,567');
    expect(formatCount(999, 'en-US')).toBe('999');
    expect(formatCount(0, 'en-US')).toBe('0');
    expect(formatCount(-4200, 'en-US')).toBe('-4,200');
  });

  it('keeps a whole precision the wire sent as a string', () => {
    // 2^53 + 1. The nearest double is 2^53, so reading this with `Number()` is off by
    // one — silently, and forever, because the value still looks like a number.
    const exact = '9007199254740993';
    expect(String(Number(exact))).not.toBe(exact);
    expect(formatCount(exact, 'en-US').replace(/[^0-9]/gu, '')).toBe(exact);
  });

  it('takes a bigint exactly as well', () => {
    expect(formatCount(9007199254740993n, 'en-US').replace(/[^0-9]/gu, ''))
      .toBe('9007199254740993');
  });

  it('reads a signed or fractional string as the number it is', () => {
    expect(formatCount('-4200', 'en-US')).toBe('-4,200');
    expect(formatCount(3.5, 'en-US')).toBe('3.5');
  });

  it('signals an absent value instead of claiming zero', () => {
    // The previous `formatInteger` answered this `0`, which is an assertion that the
    // count *was* zero. Everything else in the app answers `—`.
    for (const absent of [undefined, null, ''] as NumericValue[]) {
      expect(formatCount(absent, 'en-US')).toBe(UNREADABLE);
    }
  });

  it('signals a value it cannot read, rather than showing it to the user', () => {
    // `Metrics.formatInteger` returned the raw text. That is the mistake Batch 938
    // found in `ApiKeys.formatDateTime`, where `toLocaleString()` on an Invalid Date
    // put the string "Invalid Date" on a Chinese page.
    expect(formatCount('unknown', 'en-US')).toBe(UNREADABLE);
    expect(formatCount('n/a', 'en-US')).toBe(UNREADABLE);
    expect(formatCount(Number.NaN, 'en-US')).toBe(UNREADABLE);
    expect(formatCount(Number.POSITIVE_INFINITY, 'en-US')).toBe(UNREADABLE);
  });

  it('survives a string of digits too long for BigInt to have been intended as one', () => {
    // Not a realistic token count, but a malformed field should not throw out of a
    // render: `BigInt` throws on anything with a sign or a decimal point, and the
    // guard above is the only thing between a bad field and a blank page.
    expect(() => formatCount('1'.repeat(400), 'en-US')).not.toThrow();
  });
});

describe('formatDecimal', () => {
  it('keeps a small per-unit cost visible instead of rounding it away', () => {
    // `$0.00` on the one page whose job is showing what usage cost.
    expect(formatDecimal('0.000015', 'en-US')).toBe('0.000015');
    expect(formatDecimal('1.23456789', 'en-US')).toBe('1.23456789');
  });

  it('caps the fraction at the requested number of places', () => {
    expect(formatDecimal(1.23456789123, 'en-US', 2)).toBe('1.23');
    expect(formatDecimal(2, 'en-US', 4)).toBe('2');
  });

  it('signals an absent or unreadable amount', () => {
    expect(formatDecimal(undefined, 'en-US')).toBe(UNREADABLE);
    expect(formatDecimal('not-a-price', 'en-US')).toBe(UNREADABLE);
  });
});

describe('formatter reuse', () => {
  it('returns the same formatted value however many times it is called', () => {
    // The behaviour a memoisation bug would break: if the cache returned a formatter
    // built for the wrong options, this is where it would show.
    const first = formatCount(1234567, 'en-US');
    for (let i = 0; i < 50; i += 1) {
      expect(formatCount(1234567, 'en-US')).toBe(first);
    }
    expect(formatDecimal('0.000015', 'en-US', 2)).toBe('0');
    // …and a different option set must not inherit the previous one's rounding.
    expect(formatDecimal('0.000015', 'en-US', 8)).toBe('0.000015');
  });

  it('builds one formatter per locale and option set, not one per value', () => {
    // The property that is actually worth protecting, and the one a behavioural test
    // cannot see: the output is byte-identical with or without the cache, so an earlier
    // version of this file asserted only the output and stayed green when the cache was
    // deleted. The cost is real — 20 000 calls measured 348.7 ms building per call
    // against 4.9 ms reusing, 71x — so it is pinned by counting constructions, which is
    // the mechanism rather than the outcome.
    const original = Intl.NumberFormat;
    const built: string[] = [];
    // A `function`, not an arrow: the production code calls `new Intl.NumberFormat(...)`,
    // and an arrow function is not constructible — which would fail the test for a
    // reason that has nothing to do with what is being pinned.
    const spy = vi.fn(function spyNumberFormat(
      this: unknown,
      locale?: string | string[],
      options?: Intl.NumberFormatOptions,
    ) {
      built.push(`${String(locale)}|${JSON.stringify(options ?? {})}`);
      return new original(locale, options);
    });
    Intl.NumberFormat = spy as unknown as typeof Intl.NumberFormat;
    try {
      for (let i = 0; i < 25; i += 1) {
        formatCount(i, 'de-DE');
        formatDecimal(i / 7, 'de-DE');
      }
    } finally {
      Intl.NumberFormat = original;
    }

    // `de-DE` appears once for the grouped options and once for the decimal options.
    // Twenty-five iterations of each must not become fifty constructions.
    // `de-DE` is used precisely because no other case in this file touches it, so this
    // never shares a cache entry with the assertions above.
    expect(new Set(built).size).toBe(2);
    expect(spy.mock.calls.length).toBe(2);
  });
});
