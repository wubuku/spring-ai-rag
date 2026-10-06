/**
 * One place that turns a server timestamp into something a person can read.
 *
 * Why this file exists (Batch 938)
 * --------------------------------
 * Nine places in `src/` each called `toLocaleString()` / `toLocaleDateString()` /
 * `toLocaleTimeString()` on a value they had just made a `Date` out of, and the
 * repository had no shared formatter at all. What that cost, measured rather than
 * assumed:
 *
 *   - **"Invalid Date" reached the screen.** `ApiKeys.tsx` wrapped its call in a
 *     `try/catch`, which reads as though it handles a bad value. It cannot:
 *     `new Date('garbage')` does not throw, it returns an `Invalid Date`, and
 *     `InvalidDate.prototype.toLocaleString()` returns the **string** `"Invalid Date"`.
 *     The catch was unreachable and the user saw those eleven characters. Four of the
 *     nine sites had no validity check at all.
 *
 *   - **One page showed relative time and the rest showed absolute**, because
 *     `ChatSidebar` had hand-rolled its own ladder out of `chat.timeJustNow` /
 *     `chat.timeMinutesAgo` / `chat.timeHoursAgo`. That presentation is deliberate and
 *     worth keeping; having it *written twice* is not.
 *
 *   - **The wire carries four different shapes and the frontend typed all of them as
 *     `string`.** The API's DTOs use `LocalDateTime` (73 fields), `OffsetDateTime`
 *     (31), `ZonedDateTime` (15) and `Instant` (8) for the same question — "when did
 *     this happen". `Instant` and `OffsetDateTime` serialise with an offset; a
 *     `LocalDateTime` does not, so it arrives as `2026-10-06T19:34:31`.
 *
 * ## What this file can and cannot decide
 *
 * `2026-10-06T19:34:31` **has no zone**, so by the language rules it is read in the
 * browser's own timezone. That is the best available reading and it is what the user
 * sees — but it is not the truth unless the server's wall clock is the browser's
 * timezone. This function therefore does *not* pretend: it parses that shape
 * consistently, and `ZONELESS_NOTE` records why. Making the offset travel on the wire
 * is an API change with external business clients, so it is a decision for a person,
 * not something a formatter may quietly decide. See `docs/rest-api.md` §Timestamps.
 *
 * `scripts/check-time-formatting.mjs` keeps every call site going through here, so
 * this file is the one place that has to be right.
 */

/** Shown instead of a time that cannot be read. Kept a constant so it can be matched. */
export const UNREADABLE = '—';

/**
 * An ISO-8601 date-time with **no** zone — what Jackson writes for a `LocalDateTime`.
 * Recognised so the note below can name it; parsing needs no special case, because
 * ECMAScript already reads such a value in the local zone.
 */
const ZONELESS = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/u;

/**
 * How a zoneless `LocalDateTime` is read. Not a preference: `2026-10-06T19:34:31` has
 * no zone, and ECMAScript reads such a value in the local zone. Calling it out here
 * means a reader of this file learns it instead of learning it from a bug report.
 */
export const ZONELESS_NOTE =
  'A timestamp with no offset is read in the browser timezone, per ECMAScript. '
  + 'That is correct only if the server wrote wall-clock time in the same zone.';

/** True when the wire value carried no offset — see {@link ZONELESS_NOTE}. */
export function isZoneless(value: TimestampInput): boolean {
  return typeof value === 'string' && ZONELESS.test(value.trim());
}

/** The three shapes the API actually sends, plus epoch milliseconds and `Date`. */
export type TimestampInput = string | number | Date | null | undefined;

/**
 * Parse a server timestamp, or `null` when it cannot be read.
 *
 * `null` is the whole point: every caller used to have to remember that
 * `toLocaleString()` on a bad `Date` does not throw, it returns a string that says
 * "Invalid Date".
 */
export function parseTimestamp(value: TimestampInput): Date | null {
  if (value === null || value === undefined || value === '') return null;

  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value;
  }

  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return null;
    const fromEpoch = new Date(value);
    return Number.isNaN(fromEpoch.getTime()) ? null : fromEpoch;
  }

  const text = value.trim();
  if (text === '') return null;

  // `Date.parse` on a zoneless ISO value is already local-zone per the language
  // rules, so there is nothing to add here — but saying so beats letting the next
  // reader wonder whether an offset was meant.
  const parsed = Date.parse(text);
  if (Number.isFinite(parsed)) return new Date(parsed);

  // A bare integer string is **not** treated as an epoch. There is no evidence the API
  // sends one — the DTO fields are Java time types, all of which serialise to ISO —
  // and guessing between seconds and milliseconds is the kind of guess that renders
  // 1970 in front of a user. `number` stays supported for the one caller that has a
  // number, and it is documented as milliseconds.
  return null;
}

/** Full date and time in the reader's locale, or `—`. */
export function formatAbsolute(value: TimestampInput, locale?: string): string {
  const date = parseTimestamp(value);
  if (date === null) return UNREADABLE;
  return date.toLocaleString(locale);
}

/** Date only, for columns where the time of day is noise. */
export function formatDate(value: TimestampInput, locale?: string): string {
  const date = parseTimestamp(value);
  if (date === null) return UNREADABLE;
  return date.toLocaleDateString(locale);
}

/** Time of day only, for a list of things that happened today. */
export function formatTime(value: TimestampInput, locale?: string): string {
  const date = parseTimestamp(value);
  if (date === null) return UNREADABLE;
  return date.toLocaleTimeString(locale);
}

/** The translator shape this module needs — `t('key')` and `t('key', { count })`. */
export type Translate = (key: string, options?: Record<string, unknown>) => string;

const MINUTE = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

/**
 * "just now" / "12m ago" / "3h ago" / an absolute date once it is more than a day old.
 *
 * The cut-offs match the ladder `ChatSidebar` had, deliberately: a conversation list
 * wants recency, a records table wants a timestamp. **Presentation differs on
 * purpose; the implementation does not.**
 */
export function formatRelative(
  value: TimestampInput,
  t: Translate,
  locale?: string,
  now: Date = new Date(),
): string {
  const date = parseTimestamp(value);
  if (date === null) return UNREADABLE;

  const diff = now.getTime() - date.getTime();
  if (diff < MINUTE) return t('chat.timeJustNow');
  if (diff < HOUR) return t('chat.timeMinutesAgo', { count: Math.floor(diff / MINUTE) });
  if (diff < DAY) return t('chat.timeHoursAgo', { count: Math.floor(diff / HOUR) });
  return date.toLocaleDateString(locale);
}