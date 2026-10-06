/**
 * Every duration in the WebUI is a named decision.
 *
 * Why this file exists (Batch 941)
 * -------------------------------
 * A census of `src/` for a duration written as a bare number found **thirteen of them**
 * across **four unrelated decisions**:
 *
 *   | decision              | sites | value  | governed by                       |
 *   |-----------------------|-------|--------|-----------------------------------|
 *   | HTTP request timeout  | 1     | 30 000 | the server's slowest normal call   |
 *   | react-query poll      | 6     | 30 000 | how stale a dashboard may look    |
 *   | react-query staleness | 5     | 30 000 / 10 000 | whether a read re-fetches |
 *   | "saved" feedback      | 1     | 2 000  | how long a confirmation lingers   |
 *
 * Four decisions, and only one of them has anything to do with the others. They share a
 * number by coincidence, and the census is what makes that visible: **eleven of the
 * thirteen are 30 000 and one is 10 000, so a reader who greps for "30" cannot tell which
 * of the four they are looking at**, and two of the sites even spell it `30000` rather
 * than `30_000`, so a text search finds only half of them.
 *
 * ## They are deliberately not one constant
 *
 * The obvious shortcut is `export const THIRTY_SECONDS = 30_000` and have all eleven use
 * it. That would be worse than the duplication. It would make four independent
 * decisions *look* coupled, so the next person who wants the dashboard to poll every 10
 * seconds would have to work out whether that also changes how long a request may hang
 * and whether the documents list stops feeling fresh — and the answer, which is "no",
 * would no longer be visible anywhere in the tree.
 *
 * Putting them side by side in one module is for the opposite reason: so the coincidence
 * can be audited. A reader who wants to change one of them now sees all four and the
 * three that are unaffected.
 *
 * ## What was already right, and is the precedent
 *
 * Three of the durations in this codebase were already named when somebody thought about
 * it: `TOAST_AUTO_DISMISS_MS = 4000`, `useBlobUrlOpener(revokeDelayMs = 60_000)`,
 * `useSSE`'s `MAX_RETRY_WAIT_MS = 60_000`, and `DEFAULT_COMMIT_DELAY_MS = 250`. So the
 * house style is not "numbers are fine", it is "a duration someone chose is named" —
 * four of the thirteen simply never got the treatment.
 *
 * `scripts/check-timing-constants.mjs` keeps it that way: a duration at one of the four
 * positions where one appears in this codebase must be a name.
 */

/**
 * How long a single HTTP request may take before it is abandoned.
 *
 * A retrieval answer runs an embedding call and a chat call inside one request, so this
 * is not a round-trip budget — it is the ceiling for "model is thinking". It is unrelated
 * to {@link POLL_INTERVAL_MS}: a slow request failing at 30 s says nothing about how
 * often a dashboard should re-read, and lowering this does not make the UI feel fresher.
 */
export const HTTP_TIMEOUT_MS = 30_000;

/**
 * How often a page re-reads data that nothing invalidates on its own.
 *
 * Only used where the data can change without the browser knowing — a background
 * ingestion finishing, an embedding job settling, an alert firing. Anywhere a write
 * already calls `invalidateQueries`, this number does not apply, because an invalidation
 * re-fetches regardless of it. That is worth stating because eleven of the sites look
 * as though this governs writes, and it does not.
 */
export const POLL_INTERVAL_MS = 30_000;

/**
 * How long a cached read is served before a component mounting again re-reads it.
 *
 * The default, inherited by every query that does not say otherwise.
 */
export const STALE_TIME_MS = 30_000;

/**
 * The documents list treats itself as fresh for a third of {@link STALE_TIME_MS}.
 *
 * **This number is not explained anywhere, and it is kept rather than corrected** — it
 * was written in the same first WebUI commit as {@link STALE_TIME_MS} itself
 * (`d67f18d1`, 2026-04-05), so there is no earlier version it was a deliberate departure
 * from, and no test depends on the value.
 *
 * What *is* measurable: every write on this page calls
 * `invalidateQueries({ queryKey: ['documents'] })` — twelve of them — so the
 * write-then-read path never consults staleness at all. The only thing this governs is
 * picking up document lifecycle progress that the **server** advanced on its own, which
 * is a real difference between a list whose rows say `INDEXED` and one that still says
 * `PENDING` after a background job finishes.
 *
 * So the number may well be right; nobody has written down why, and that is the part
 * worth raising with whoever owns the page rather than quietly changing here.
 */
export const DOCUMENT_LIST_STALE_TIME_MS = 10_000;

/**
 * How long a "saved" confirmation stays on screen before it fades on its own.
 *
 * Not a network or cache number: it is how long a person has to notice the thing they
 * just did worked. Long enough to catch at the corner of your eye, short enough that it
 * is not still claiming success when you have moved on to something else.
 */
export const SAVED_FEEDBACK_MS = 2_000;
