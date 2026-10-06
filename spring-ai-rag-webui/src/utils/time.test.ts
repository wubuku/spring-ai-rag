import { describe, expect, it } from 'vitest';
import {
  formatAbsolute,
  formatDate,
  formatRelative,
  formatTime,
  isZoneless,
  parseTimestamp,
  UNREADABLE,
  ZONELESS_NOTE,
} from './time';

// A BCP-47 **locale**, not a timezone. The first draft of this file passed
// `'Asia/Shanghai'` and `toLocaleDateString` threw `RangeError: Incorrect locale
// information provided` — a timezone is not a locale, and the parameter is the
// reader's language. The reader's *zone* is what `toLocaleString` already applies.
const LOCALE = 'zh-CN';

describe('parseTimestamp', () => {
  it('reads the three shapes Jackson actually writes', () => {
    // `Instant` → UTC with a Z.
    expect(parseTimestamp('2026-10-06T11:34:31Z')?.toISOString()).toBe('2026-10-06T11:34:31.000Z');
    // `OffsetDateTime` → with an offset.
    expect(parseTimestamp('2026-10-06T19:34:31+08:00')?.toISOString()).toBe('2026-10-06T11:34:31.000Z');
    // `LocalDateTime` → **no offset at all**, which is the whole problem.
    expect(parseTimestamp('2026-10-06T19:34:31')?.getHours()).toBe(19);
  });

  it('names the zoneless shape rather than swallowing it', () => {
    // ECMAScript reads a date-time form with no offset in the local zone. Asserting
    // it is what makes ZONELESS_NOTE a checked statement rather than a comment
    // somebody might delete.
    expect(isZoneless('2026-10-06T19:34:31')).toBe(true);
    expect(isZoneless('2026-10-06T19:34:31Z')).toBe(false);
    expect(isZoneless('2026-10-06T19:34:31+08:00')).toBe(false);
    expect(ZONELESS_NOTE).toMatch(/browser timezone/u);
  });

  it('reads epoch milliseconds and a Date instance', () => {
    expect(parseTimestamp(1_772_000_000_000)?.toISOString()).toBe('2026-02-25T06:13:20.000Z');
    expect(parseTimestamp(new Date('2026-10-06T11:34:31Z'))?.toISOString())
      .toBe('2026-10-06T11:34:31.000Z');
  });

  it('returns null rather than an Invalid Date', () => {
    // The defect this exists for: `new Date('garbage').toLocaleString()` does not
    // throw, it returns the string "Invalid Date". A caller that wraps the call in a
    // try/catch — as `ApiKeys.tsx` did — has a catch that cannot fire.
    for (const bad of ['', '   ', 'garbage', 'not-a-date', 'null', undefined, null]) {
      expect(parseTimestamp(bad)).toBeNull();
    }
    expect(parseTimestamp(Number.NaN)).toBeNull();
    expect(parseTimestamp(Number.POSITIVE_INFINITY)).toBeNull();
    expect(parseTimestamp(new Date('nope'))).toBeNull();
  });

  it('does not guess that a bare integer string is an epoch', () => {
    // No DTO field is a `Long`, so there is nothing to guess between. Reading one as
    // milliseconds would render 1970 in front of a user, and reading it as seconds
    // would render 1970 by another route.
    expect(parseTimestamp('1772000000000')).toBeNull();
    expect(parseTimestamp('1772000000')).toBeNull();
  });

  it('never returns a Date whose time is NaN', () => {
    for (const value of ['garbage', '', 1, new Date('x')]) {
      const parsed = parseTimestamp(value as never);
      if (parsed !== null) expect(Number.isNaN(parsed.getTime())).toBe(false);
    }
  });
});

describe('formatAbsolute / formatDate / formatTime', () => {
  const stamp = '2026-10-06T19:34:31+08:00';

  it('renders a readable value in the given locale', () => {
    for (const rendered of [
      formatAbsolute(stamp, LOCALE),
      formatDate(stamp, LOCALE),
      formatTime(stamp, LOCALE),
    ]) {
      expect(rendered).not.toBe(UNREADABLE);
      expect(rendered).not.toContain('Invalid');
    }
  });

  it('shows the placeholder, and never "Invalid Date", for a value it cannot read', () => {
    for (const bad of ['', 'garbage', undefined, null]) {
      expect(formatAbsolute(bad, LOCALE)).toBe(UNREADABLE);
      expect(formatDate(bad, LOCALE)).toBe(UNREADABLE);
      expect(formatTime(bad, LOCALE)).toBe(UNREADABLE);
    }
  });

  it('keeps date-only and time-only genuinely different', () => {
    // `Documents.tsx` shows a date column and `Search.tsx` a time column; if both
    // collapsed to the same string the columns would stop meaning anything.
    const absolute = formatAbsolute(stamp, LOCALE);
    expect(formatDate(stamp, LOCALE)).not.toBe(absolute);
    expect(formatTime(stamp, LOCALE)).not.toBe(absolute);
  });
});

describe('formatRelative', () => {
  const t = (key: string, options?: Record<string, unknown>) => {
    if (key === 'chat.timeJustNow') return 'just now';
    if (key === 'chat.timeMinutesAgo') return `${options?.count}m ago`;
    if (key === 'chat.timeHoursAgo') return `${options?.count}h ago`;
    throw new Error(`unexpected key ${key}`);
  };
  const now = new Date('2026-10-06T12:00:00Z');

  it('keeps the ladder ChatSidebar had', () => {
    expect(formatRelative('2026-10-06T11:59:30Z', t, LOCALE, now)).toBe('just now');
    expect(formatRelative('2026-10-06T11:45:00Z', t, LOCALE, now)).toBe('15m ago');
    expect(formatRelative('2026-10-06T09:00:00Z', t, LOCALE, now)).toBe('3h ago');
  });

  it('falls back to a date once it is more than a day old', () => {
    const old = formatRelative('2026-10-01T12:00:00Z', t, LOCALE, now);
    expect(old).not.toBe(UNREADABLE);
    expect(old).not.toContain('ago');
  });

  it('shows the placeholder rather than "Invalid Date" for a bad value', () => {
    expect(formatRelative('garbage', t, LOCALE, now)).toBe(UNREADABLE);
    expect(formatRelative('', t, LOCALE, now)).toBe(UNREADABLE);
  });

  it('absorbs small clock skew as "just now"', () => {
    // A timestamp a few minutes ahead is server/browser skew, not an event in the
    // future worth spelling out — and every product this borrows from renders it as
    // "just now". The first draft of this test asserted the opposite without deciding
    // it; this states the decision.
    expect(formatRelative('2026-10-06T12:05:00Z', t, LOCALE, now)).toBe('just now');
    // Far enough ahead to be a real clock disagreement, and still not a negative age.
    const far = formatRelative('2026-10-07T12:00:00Z', t, LOCALE, now);
    expect(far).not.toMatch(/-\d/u);
    expect(far).not.toBe(UNREADABLE);
  });
});