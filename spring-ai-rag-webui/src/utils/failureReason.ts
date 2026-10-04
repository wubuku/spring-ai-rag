/**
 * The server's reason, formatted for appending to a failure message.
 *
 * Ten mutations across `Alerts`, `Embeddings` and `Evaluation` reported failure
 * as a fixed sentence — "Unable to cancel the job" — and stopped there. The
 * reason never left the browser: `api/client.ts` already lifts
 * `response.data.message` (or `detail`) into `new Error(message)`, so the
 * information was sitting in `mutation.error` the whole time, unread. A user
 * reporting "the cancel failed" tells an operator nothing about whether the
 * quota was exceeded, the key was revoked, or the job had already finished.
 *
 * ## Why this returns a fragment rather than a whole message
 *
 * The tempting version is `t(key, { message: error.message })`, and it is
 * worse than what it replaces whenever the server said nothing useful. A network
 * failure reaches the browser as `TypeError: Failed to fetch`, and an aborted
 * request as `AbortError`; rendering those after "Unable to cancel the job"
 * produces noise that is both unhelpful and slightly alarming, in place of a
 * sentence that already said everything the user can act on.
 *
 * So a reason is appended only when it is a real answer:
 *  - a transport-level message ("Failed to fetch", "NetworkError", "AbortError")
 *    is dropped, because it describes the connection rather than the write;
 *  - anything the server actually said is kept.
 *
 * `SENTINEL_MESSAGES` is the list. It is deliberately small and explicit: a
 * blocklist grows with evidence, a heuristic ("looks like a stack trace")
 * guesses.
 *
 * ## Batch 871: the other half of the same problem
 *
 * The list above only caught messages that arrive when there is **no response**.
 * The more common case is a response that carries no reason: Spring's own error
 * bodies are `{timestamp, status, error, path}` with neither `detail` nor
 * `message`, so `api/client.ts` falls through to axios's own string and rejects
 * with "Request failed with status code 404". That string then reaches
 * `failureMessage` and gets appended:
 *
 *     collections.deleteError (Request failed with status code 404)
 *
 * which is precisely the outcome this file exists to prevent — noise that is
 * both unhelpful and slightly alarming, in place of a sentence. The status code
 * is not the server's reason; it is the transport, wearing a server's clothes.
 *
 * It is matched as a pattern rather than added to the set, because the code
 * varies per response. The pattern is as narrow as it can be: the entire
 * message, three digits, nothing else. That is not a heuristic about what a
 * reason "looks like" — it recognises the exact output of one known generator.
 */

/**
 * Messages that describe the transport, not the operation. Appending one of
 * these tells the user something they already know and nothing they can act on.
 */
const SENTINEL_MESSAGES: ReadonlySet<string> = new Set<string>([
  'failed to fetch',
  'networkerror',
  'network request failed',
  'load failed',
  'aborterror',
  'aborted',
  'timeout',
  'request timed out',
  'the operation was aborted',
  'unknown error',
  'undefined',
  'null',
  '[object object]',
]);

/**
 * The string axios synthesizes when a response arrived but carried no reason.
 * See the header: this is the status code wearing a server's clothes.
 */
const SYNTHESIZED_STATUS = /^request failed with status code \d{3}$/;

/** Browsers cap `Error.message`; a longer one is a stack dump, not a reason. */
const MAX_REASON_LENGTH = 200;

function isUsable(message: unknown): boolean {
  if (typeof message !== 'string') return false;
  const trimmed = message.trim();
  if (trimmed.length === 0) return false;
  if (SENTINEL_MESSAGES.has(trimmed.toLowerCase())) return false;
  if (SYNTHESIZED_STATUS.test(trimmed.toLowerCase())) return false;
  // A stack trace is not something to put in front of a user.
  if (trimmed.includes('\n') && trimmed.split('\n').length > 3) return false;
  return trimmed.length <= MAX_REASON_LENGTH;
}

/**
 * @param {unknown} error the value a mutation puts in `.error`
 * @returns {string} a fragment to interpolate as `{{reason}}`, or `''`
 */
export function failureReason(error: unknown): string {
  if (!error) return '';
  const message = error instanceof Error ? error.message : undefined;
  if (!isUsable(message)) return '';
  return ` (${String(message).trim()})`;
}

/**
 * Convenience wrapper for the common call site:
 * `t('embeddings.cancelFailed', { reason: failureReason(mutation.error) })`.
 *
 * Returns the bare key's value unchanged when there is nothing to add, so a
 * page with no server reason renders byte-for-byte what it rendered before.
 */
export function failureMessage(
  t: (key: string) => string,
  key: string,
  error: unknown,
): string {
  const reason = failureReason(error);
  // Concatenated rather than passed as an i18n interpolation on purpose. The
  // reason is whatever the server said — it is not a translatable string, and
  // routing it through `t(key, { reason })` would make it look like one. The
  // cost is that the test double for `t` cannot render it, which is exactly the
  // property that lets these assertions read the reason off the screen at all.
  return reason === '' ? t(key) : `${t(key)}${reason}`;
}
