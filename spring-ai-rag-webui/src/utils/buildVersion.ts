/**
 * Build identity for the running page.
 *
 * `index.html` carries a `webui-build-version` meta tag injected by the Vite
 * build (see `vite.config.ts`). The value is derived from the asset names in
 * that index, so it changes exactly when the set of served chunks changes —
 * which is the only condition under which an old tab can request a file that
 * no longer exists.
 *
 * The running page cannot read its own response headers, so the ETag is not
 * available to JavaScript. A meta tag travels with the document and is visible
 * to the code that document loaded; the same meta tag is also present in the
 * freshly served copy, which is what the version check compares against.
 */

export const BUILD_VERSION_META = 'webui-build-version';

const META_NAME = new RegExp(`name=["']${BUILD_VERSION_META}["']`, 'i');
const META_CONTENT = /content=["']([^"']+)["']/i;

/** Read the build version of the document that is currently running. */
export function readRunningBuildVersion(doc: Document = document): string | null {
  const meta = doc.querySelector(`meta[name="${BUILD_VERSION_META}"]`);
  const value = meta?.getAttribute('content')?.trim();
  return value ? value : null;
}

/**
 * Read the build version the server would serve right now.
 *
 * Returns `null` for every failure shape — offline, 404, HTML that is not the
 * app, a timeout, a malformed tag. The caller treats "unknown" as "nothing to
 * report": a version check is a background nicety and must never surface as an
 * error of its own.
 */
export async function fetchServedBuildVersion(
  signal?: AbortSignal,
): Promise<string | null> {
  try {
    // `cache: 'no-store'` keeps the HTTP cache and any shared proxy from
    // answering with the copy this tab loaded. The query parameter is a second
    // guard for intermediaries that ignore `no-store` on HTML.
    const base = import.meta.env.BASE_URL;
    const separator = base.includes('?') ? '&' : '?';
    const url = `${base}${separator}webui-version-check=${Date.now()}`;
    const response = await fetch(url, {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'text/html' },
      signal,
    });
    if (!response.ok) return null;

    const text = await response.text();
    const tag = text.match(/<meta\b[^>]*>/gi)?.find(candidate => META_NAME.test(candidate));
    if (!tag) return null;
    const content = tag.match(META_CONTENT)?.[1]?.trim();
    return content || null;
  } catch {
    return null;
  }
}
