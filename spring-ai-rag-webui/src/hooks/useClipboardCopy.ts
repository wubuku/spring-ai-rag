import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
// Through the barrel, not `../components/Toast/Toast`: every other consumer imports the
// barrel, and that is the module a test replaces when it wants to assert on toasts.
import { useToast } from '../components/Toast';
import { copyText, type ClipboardResult } from '../utils/clipboard';

/** The two things a copy button has to be able to say, as i18n keys. */
export interface ClipboardMessages {
  /** Shown when the text reached the clipboard. */
  success: string;
  /** Shown when it did not. Must also work as a manual-copy instruction. */
  failure: string;
}

/**
 * Copy text and always say which of the two happened.
 *
 * Three call sites each wired this by hand, and only one of them remembered the failure
 * branch — see `src/utils/clipboard.ts` for what that cost. The point of
 * putting it in a hook rather than leaving each call site to write two lines is that the
 * failure branch cannot be left out: there is one function, and it shows a toast on both
 * paths. A caller that forgets the `catch` now has nothing to forget, because there is no
 * `catch` at the call site to omit.
 *
 * The messages are passed as keys rather than read from a shared namespace because the
 * three callers name different things — an import UUID, a freshly issued API key, a
 * rotated one — and a single generic "copied" would lose that.
 */
export function useClipboardCopy() {
  const { t } = useTranslation();
  const { showToast } = useToast();

  return useCallback(
    async (text: string, messages: ClipboardMessages): Promise<ClipboardResult> => {
      const result = await copyText(text);
      showToast(
        t(result === 'copied' ? messages.success : messages.failure),
        result === 'copied' ? 'success' : 'error',
      );
      return result;
    },
    [showToast, t],
  );
}
