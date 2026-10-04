import '@testing-library/jest-dom';

// ---------------------------------------------------------------------------
// i18next mock — provides a no-op translation function for unit tests
// ---------------------------------------------------------------------------
/**
 * `t` and `i18n` are built **once per factory call** and closed over, because
 * the real `useTranslation` returns referentially stable values and this double
 * did not: `t: (key) => key` inline means a brand-new function on every render.
 *
 * Batch 863 spent four mutation experiments failing to complete, and Batch 844
 * had already abandoned them, writing the cause down as machine load. It was not
 * the machine. Any effect listing `t` in its dependency array re-ran on
 * **every render** under this double, while in production it runs once per
 * change of the other dependencies. The gap stays invisible until a component's
 * effect stops being idempotent — and then the test does not get slow, it never
 * finishes:
 *
 *   `Chat.tsx` persists the draft in an effect keyed on
 *   `[draftKey, input, showToast, t]`. Removing the "warn once per run" guard
 *   — the first of the four mutations Batch 844 wanted run — makes the effect
 *   call `showToast`, which sets toast state, which re-renders, which yields a
 *   **new `t`**, which re-runs the effect, which calls `showToast` again. The
 *   log stopped growing on the last test before the draft cases and the process
 *   had to be killed.
 *
 * The guard was therefore the only thing making that effect idempotent under
 * this double, which is not a property worth relying on. In production `t` is
 * stable, so the loop was never reachable there: the double was describing an
 * environment the code does not run in.
 *
 * The factory stays self-contained because vitest hoists `vi.mock` above the
 * imports, so it cannot close over a module-level constant.
 */
vi.mock('react-i18next', () => {
  const t = (key: string) => key;
  const i18n = { language: 'en', changeLanguage: vi.fn() };
  return {
    default: vi.fn(),
    useTranslation: () => ({ t, i18n }),
    initReactI18next: { type: '3rdParty' },
  };
});

// ---------------------------------------------------------------------------
// Mock window.matchMedia
// ---------------------------------------------------------------------------

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

// Mock crypto.randomUUID
if (!globalThis.crypto?.randomUUID) {
  globalThis.crypto = globalThis.crypto ?? {};
  globalThis.crypto.randomUUID = () => 'test-uuid-' + Math.random().toString(36).slice(2);
}

// Mock scrollIntoView (not implemented in jsdom)
Element.prototype.scrollIntoView = vi.fn();

// Mock DataTransfer (not available in jsdom)
class MockDataTransfer {
  items: DataTransferItemList;
  files: FileList;
  types: string[];
  dropEffect: string = 'none';
  effectAllowed: string = 'all';
  data: Map<string, string> = new Map();

  constructor() {
    this.items = {
      length: 0,
      add: vi.fn(),
      remove: vi.fn(),
      clear: vi.fn(),
      [Symbol.iterator]: function* () {},
    } as unknown as DataTransferItemList;
    this.files = {
      length: 0,
      item: vi.fn(),
      [Symbol.iterator]: function* () {},
    } as unknown as FileList;
    this.types = [];
  }

  setData(format: string, data: string) {
    this.data.set(format, data);
    if (!this.types.includes(format)) this.types.push(format);
  }
  getData(format: string): string {
    return this.data.get(format) ?? '';
  }
  clearData(format?: string) {
    if (format) this.data.delete(format);
    else this.data.clear();
  }
}

(globalThis as unknown as { DataTransfer: typeof DataTransfer }).DataTransfer =
  MockDataTransfer as unknown as typeof DataTransfer;

// Mock EventSource (not available in jsdom)
class MockEventSource {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;

  url: string;
  readyState: number;
  withCredentials: boolean;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  ontimeout: ((event: Event) => void) | null = null;

  private _listeners: Map<string, EventListenerOrEventListenerObject[]> = new Map();

  constructor(url: string) {
    this.url = url;
    this.readyState = MockEventSource.CONNECTING;
    // Simulate async connection open
    setTimeout(() => {
      this.readyState = MockEventSource.OPEN;
      this.onopen?.(new Event('open'));
    }, 0);
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    if (!this._listeners.has(type)) {
      this._listeners.set(type, []);
    }
    this._listeners.get(type)!.push(listener);
  }

  removeEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    const listeners = this._listeners.get(type) ?? [];
    const idx = listeners.indexOf(listener);
    if (idx >= 0) listeners.splice(idx, 1);
  }

  close() {
    this.readyState = MockEventSource.CLOSED;
  }

  // Helper to simulate incoming message
  simulateMessage(data: string) {
    const event = new MessageEvent('message', { data });
    if (this.onmessage) {
      this.onmessage(event);
    }
    const listeners = this._listeners.get('message') ?? [];
    listeners.forEach(l => {
      if (typeof l === 'function') l(event);
      else if (typeof l === 'object' && 'handleEvent' in l)
        (l as EventListenerObject).handleEvent(event);
    });
  }

  // Helper to simulate done event
  simulateDone() {
    const listeners = this._listeners.get('done') ?? [];
    listeners.forEach(l => {
      const event = new Event('done');
      if (typeof l === 'function') l(event);
      else if (typeof l === 'object' && 'handleEvent' in l)
        (l as EventListenerObject).handleEvent(event);
    });
  }

  // Helper to simulate error
  simulateError() {
    const event = new Event('error');
    if (this.onerror) {
      this.onerror(event);
    }
    const listeners = this._listeners.get('error') ?? [];
    listeners.forEach(l => {
      if (typeof l === 'function') l(event);
      else if (typeof l === 'object' && 'handleEvent' in l)
        (l as EventListenerObject).handleEvent(event);
    });
  }
}

vi.stubGlobal('EventSource', MockEventSource);
