const STORAGE_PREFIX = 'spring-ai-rag:webui:v1:';
const ROUTE_MEMORY_KEY = `${STORAGE_PREFIX}routes`;
const DEFAULT_MAX_BYTES = 8 * 1024;
const MAX_ROUTE_BYTES = 16 * 1024;

export const TOP_LEVEL_ROUTES = [
  '/dashboard',
  '/documents',
  '/collections',
  '/chat',
  '/search',
  '/metrics',
  '/evaluation',
  '/embeddings',
  '/alerts',
  '/abtest',
  '/api-keys',
  '/files',
  '/settings',
] as const;

export type TopLevelRoute = typeof TOP_LEVEL_ROUTES[number];
type RouteMemory = Partial<Record<TopLevelRoute, string>>;

const CREDENTIAL_PATTERN =
  /(?:sk-[a-z0-9_-]{12,}|authorization\s*[:=]\s*bearer|x-api-key\s*[:=]|api[_-]?key\s*[:=])/i;

function storage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

export function readWorkspaceState<T>(
  key: string,
  validate: (value: unknown) => value is T,
): T | null {
  const target = storage();
  if (!target) return null;
  const storageKey = `${STORAGE_PREFIX}${key}`;
  try {
    const raw = target.getItem(storageKey);
    if (!raw || byteLength(raw) > DEFAULT_MAX_BYTES) {
      if (raw) target.removeItem(storageKey);
      return null;
    }
    const value: unknown = JSON.parse(raw);
    if (!validate(value)) {
      target.removeItem(storageKey);
      return null;
    }
    return value;
  } catch {
    target.removeItem(storageKey);
    return null;
  }
}

export function writeWorkspaceState(
  key: string,
  value: unknown,
  maxBytes = DEFAULT_MAX_BYTES,
): boolean {
  const target = storage();
  if (!target) return false;
  const storageKey = `${STORAGE_PREFIX}${key}`;
  try {
    const raw = JSON.stringify(value);
    if (byteLength(raw) > maxBytes || CREDENTIAL_PATTERN.test(raw)) {
      target.removeItem(storageKey);
      return false;
    }
    target.setItem(storageKey, raw);
    return true;
  } catch {
    target.removeItem(storageKey);
    return false;
  }
}

export function removeWorkspaceState(key: string): void {
  storage()?.removeItem(`${STORAGE_PREFIX}${key}`);
}

export function clearWorkspaceState(): void {
  const target = storage();
  if (!target) return;
  for (let index = target.length - 1; index >= 0; index -= 1) {
    const key = target.key(index);
    if (key?.startsWith(STORAGE_PREFIX)) {
      target.removeItem(key);
    }
  }
}

export function topLevelRoute(pathname: string): TopLevelRoute | null {
  return TOP_LEVEL_ROUTES.find(route =>
    pathname === route || pathname.startsWith(`${route}/`)) ?? null;
}

function legalPath(route: TopLevelRoute, pathname: string): boolean {
  if (pathname === route) return true;
  if (route === '/chat') {
    return /^\/chat\/[^/?#]{1,256}$/.test(pathname);
  }
  if (route === '/abtest') {
    return /^\/abtest\/\d+$/.test(pathname);
  }
  return false;
}

function readRouteMemory(): RouteMemory {
  const target = storage();
  if (!target) return {};
  try {
    const raw = target.getItem(ROUTE_MEMORY_KEY);
    if (!raw || byteLength(raw) > MAX_ROUTE_BYTES) return {};
    const value: unknown = JSON.parse(raw);
    return value && typeof value === 'object' ? value as RouteMemory : {};
  } catch {
    target.removeItem(ROUTE_MEMORY_KEY);
    return {};
  }
}

export function rememberRoute(pathname: string, search: string): void {
  const route = topLevelRoute(pathname);
  if (!route || !legalPath(route, pathname) || search.length > 2048) return;
  const next = { ...readRouteMemory(), [route]: `${pathname}${search}` };
  writeWorkspaceState('routes', next, MAX_ROUTE_BYTES);
}

export function rememberedRoute(route: TopLevelRoute): string {
  const candidate = readRouteMemory()[route];
  if (!candidate) return route;
  try {
    const url = new URL(candidate, window.location.origin);
    return legalPath(route, url.pathname) && topLevelRoute(url.pathname) === route
      ? `${url.pathname}${url.search}`
      : route;
  } catch {
    return route;
  }
}

export function isStringRecord(value: unknown): value is Record<string, string> {
  return Boolean(value)
    && typeof value === 'object'
    && Object.values(value as Record<string, unknown>)
      .every(item => typeof item === 'string');
}
