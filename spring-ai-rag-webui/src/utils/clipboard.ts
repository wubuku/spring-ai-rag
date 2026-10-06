/**
 * One way to put text on the clipboard, which cannot fail silently.
 *
 * Why this file exists (Batch 939)
 * --------------------------------
 * Three places in `src/` wrote to the clipboard. One of them — `Files.tsx`, copying an
 * import UUID — wrapped the call in `try/catch` and showed a toast when it failed. The
 * other two, both in `ApiKeys.tsx`, did not:
 *
 *     const copyRawKey = async () => {
 *       const rawKey = preparedRotation?.rawKey ?? immediateKey?.rawKey;
 *       if (!rawKey) return;
 *       await navigator.clipboard.writeText(rawKey);
 *       showToast(t('apiKeys.copied'), 'success');
 *     };
 *
 * That is a silent failure in the one moment it matters most. `writeText` returns a
 * promise that **rejects** when the document is not focused or permission is denied, and
 * `navigator.clipboard` is **`undefined` outright** outside a secure context — a
 * self-hosted UI reached over plain HTTP on a LAN address, which is the normal way to run
 * this thing on a second machine. In that case the call is a `TypeError` on `undefined`
 * and the async function rejects with nobody awaiting it. The user's click produces no
 * toast, no key on the clipboard, and no indication that anything happened.
 *
 * These two are the copy buttons for a **raw API key shown exactly once**. The value is
 * unrecoverable after the dialog closes, so "no visible reaction" is the worst possible
 * outcome available.
 *
 * The contract below is deliberately narrow: this function never throws and never rejects,
 * so no caller can forget a `catch` — a failure mode that is invisible in review because
 * the code around it looks correct.
 *
 * `scripts/check-clipboard-calls.mjs` keeps `navigator.clipboard` confined to this file.
 */

/** Whether the text reached the clipboard. Failures are not distinguished — see below. */
export type ClipboardResult = 'copied' | 'failed';

/**
 * Copy `text`, reporting whether it worked.
 *
 * Both ways this can fail are reported as `failed` because they are the same thing to
 * the person who clicked: the text is not on the clipboard, and the remedy is the same —
 * select it and copy by hand. Telling them to retry would be wrong for a non-secure
 * context, where no amount of retrying will produce a clipboard; telling them to fix a
 * permission would be wrong for a `TypeError`. The distinction is kept here as a
 * comment because it is the reason a single message is honest, not because a caller acts
 * on it differently.
 */
export async function copyText(text: string): Promise<ClipboardResult> {
  if (text === '') return 'failed';

  try {
    // Not `navigator.clipboard?.writeText?.(...)`: the method has to be called with
    // `navigator.clipboard` as its receiver, or some browsers reject the call outright.
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') return 'failed';
    await clipboard.writeText(text);
    return 'copied';
  } catch {
    // Includes a `writeText` that throws synchronously rather than returning a rejected
    // promise, which a browser is free to do. The contract is that this function settles
    // as a value, so a documented guarantee is one that is actually kept.
    return 'failed';
  }
}
