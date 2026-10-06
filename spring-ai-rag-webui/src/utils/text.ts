/**
 * Shortening a string for a person to read happens in one place, and the reasons why
 * live with the numbers instead of being scattered across call sites.
 *
 * Why this file exists (Batch 939)
 * --------------------------------
 * A census of `src/` for a hard-coded two-number substring found **fifteen** call
 * sites and exactly **four** distinct numbers behind them:
 *
 *   | number | count | what it means                          | written as        |
 *   |--------|-------|----------------------------------------|-------------------|
 *   | `256`  | 9     | the longest search query we accept     | `slice(0, 256)`   |
 *   | `8`    | 3     | a short id / hash for display          | `slice(0, 8)`     |
 *   | `50`   | 1     | the chat sidebar's title preview       | `slice(0, 50)`    |
 *   | `16`   | 1     | the `datetime-local` input's own shape | `slice(0, 16)`    |
 *
 * The same four decisions were spelled four different ways on screen. `VersionHistoryModal`
 * wrote a real `…` (U+2026), `Documents` wrote three ASCII dots, `Chat` wrote three ASCII
 * dots *inside a ternary that re-implemented the length check by hand*, and `Embeddings`
 * cut the job id with no marker at all — so a reader could not tell a truncated value from
 * a short one. One of the four numbers is not a truncation at all: `ApiKeys` cut a wire
 * timestamp to `16` characters to fill a `<input type="datetime-local">`, which is a
 * *format conversion*, and it belongs with the other timestamp handling in
 * `src/utils/time.ts` (`toDateTimeLocalValue`).
 *
 * `scripts/check-text-truncation.mjs` keeps every remaining numeric slice out of the
 * tree, which is why the constants here are exported rather than inlined: a call site
 * that writes `slice(0, 256)` again has re-invented a number, and that is what the gate
 * catches.
 *
 * ## truncate and capLength are not the same function
 *
 * `truncate` is for a string a person is *reading*: cutting it marks the cut, because a
 * value that looks complete but is not is worse than one that admits it. `capLength` is
 * for a string a person is *typing*: the tail is discarded because it was never
 * meaningful, and an ellipsis there would be sent to the server as part of the query.
 * The three ASCII-dot sites were the symptom of a codebase that had only one of the two
 * ideas available and reached for it at both kinds of site.
 */

/** The one ellipsis this codebase writes. `…` (U+2026), not three ASCII dots. */
export const ELLIPSIS = '…';

/**
 * The longest search query the UI will keep.
 *
 * This is a UI-side bound, not a server limit: nothing in the API rejects a longer query,
 * and the browser is already the practical ceiling. It is one number because the same
 * value is used to cap what is typed, what is read back out of the URL, and what is
 * handed to the API — a mismatch between those three is how a search silently stops
 * matching.
 */
export const MAX_QUERY_LENGTH = 256;

/** Characters of an id or content hash shown before it is cut. */
export const SHORT_ID_LENGTH = 8;

/** Characters of a user message shown as its conversation title. */
export const CHAT_TITLE_PREVIEW_LENGTH = 50;

/**
 * Shorten a string for display, marking the cut.
 *
 * A string at or under `maxLength` is returned unchanged — this adds nothing to a value
 * that fits. `maxLength <= 0` yields an empty string rather than an ellipsis alone, so a
 * caller cannot turn a "show nothing" decision into a row of punctuation.
 */
export function truncate(text: string, maxLength: number): string {
  if (maxLength <= 0) return '';
  if (text.length <= maxLength) return text;
  return `${text.slice(0, maxLength)}${ELLIPSIS}`;
}

/**
 * Drop the tail of a string a person is typing, without marking anything.
 *
 * The cut is silent on purpose: the characters are gone from an input the user is still
 * editing, and an ellipsis in the middle of a search box reads as part of the query. This
 * is **not** {@link truncate} — using that here would put `…` into the `title=` parameter
 * of every request.
 */
export function capLength(text: string, maxLength: number = MAX_QUERY_LENGTH): string {
  if (text.length <= maxLength) return text;
  return text.slice(0, maxLength);
}
