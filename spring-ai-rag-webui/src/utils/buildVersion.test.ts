import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  BUILD_VERSION_META,
  fetchServedBuildVersion,
  readRunningBuildVersion,
} from './buildVersion';

function indexHtml(version: string): string {
  return `<!doctype html><html><head><meta name="webui-build-version" content="${version}"></head></html>`;
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.head.innerHTML = '';
});

describe('readRunningBuildVersion', () => {
  it('reads the meta tag injected into the running document', () => {
    document.head.innerHTML = `<meta name="${BUILD_VERSION_META}" content="abc123">`;
    expect(readRunningBuildVersion()).toBe('abc123');
  });

  it('returns null when the document has no build identity', () => {
    expect(readRunningBuildVersion()).toBeNull();
  });
});

describe('fetchServedBuildVersion', () => {
  it('parses the version from a freshly served index', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      text: async () => indexHtml('next-build'),
    }));

    await expect(fetchServedBuildVersion()).resolves.toBe('next-build');
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('webui-version-check='),
      expect.objectContaining({ cache: 'no-store' }),
    );
  });

  it('returns null for a 404 instead of throwing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => 'not found',
    }));
    await expect(fetchServedBuildVersion()).resolves.toBeNull();
  });

  it('returns null when the request fails (offline, timeout)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    await expect(fetchServedBuildVersion()).resolves.toBeNull();
  });

  it('returns null when the response has no build meta tag', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      text: async () => '<!doctype html><html><head></head><body>other app</body></html>',
    }));
    await expect(fetchServedBuildVersion()).resolves.toBeNull();
  });

  it('accepts a meta tag whose attributes are ordered differently', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      text: async () => '<html><head><meta content="reordered" name="webui-build-version"></head></html>',
    }));
    await expect(fetchServedBuildVersion()).resolves.toBe('reordered');
  });
});
