import { beforeEach, describe, expect, it } from 'vitest';
import {
  TOP_LEVEL_ROUTES,
  clearWorkspaceState,
  readWorkspaceState,
  rememberRoute,
  rememberedRoute,
  writeWorkspaceState,
} from './workspaceState';

describe('workspaceState', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('remembers legal state for every top-level route', () => {
    const candidates = new Map([
      ['/dashboard', '/dashboard?view=operations'],
      ['/documents', '/documents?collectionKey=manual&page=2'],
      ['/collections', '/collections?sort=name'],
      ['/chat', '/chat/session-42?mode=AGENT'],
      ['/search', '/search?query=manual&hybrid=true'],
      ['/metrics', '/metrics?from=2026-08-01'],
      ['/evaluation', '/evaluation?tab=suites'],
      ['/embeddings', '/embeddings?status=FAILED'],
      ['/alerts', '/alerts?tab=notification-deliveries'],
      ['/abtest', '/abtest/42'],
      ['/api-keys', '/api-keys?filter=active'],
      ['/files', '/files?path=batch-1%2F&q=manual'],
      ['/settings', '/settings?tab=cache'],
    ]);

    for (const route of TOP_LEVEL_ROUTES) {
      const candidate = candidates.get(route)!;
      const url = new URL(candidate, window.location.origin);
      rememberRoute(url.pathname, url.search);
      expect(rememberedRoute(route)).toBe(candidate);
    }
  });

  it('rejects illegal deep routes and sensitive or oversized state', () => {
    rememberRoute('/files/private/path', '?q=secret');
    rememberRoute('/abtest/not-a-number', '');
    expect(rememberedRoute('/files')).toBe('/files');
    expect(rememberedRoute('/abtest')).toBe('/abtest');

    expect(writeWorkspaceState('draft', {
      value: 'Authorization: Bearer sk-sensitive-token-123456',
    })).toBe(false);
    expect(readWorkspaceState('draft', (): _ is object => true)).toBeNull();
    expect(writeWorkspaceState('large', 'x'.repeat(9 * 1024))).toBe(false);
  });

  it('clears only project workspace state', () => {
    writeWorkspaceState('draft', { value: 'safe' });
    window.sessionStorage.setItem('unrelated', 'keep');

    clearWorkspaceState();

    expect(readWorkspaceState('draft', (): _ is object => true)).toBeNull();
    expect(window.sessionStorage.getItem('unrelated')).toBe('keep');
  });
});
