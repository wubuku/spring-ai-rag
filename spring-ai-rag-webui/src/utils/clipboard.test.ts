import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyText } from './clipboard';

// Batch 939. The defect these pin is not "copying failed" — it is that copying failed
// **silently**. `ApiKeys` awaited `navigator.clipboard.writeText` with no `catch`, on the
// two buttons that hand over a raw API key exactly once. A rejected promise there, and a
// `TypeError` from a missing `navigator.clipboard`, both produced no toast at all.
//
// The contract being pinned is that `copyText` cannot throw or reject, so no call site
// can express "copy this" without also getting a way to learn that it did not happen.

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');

function setClipboard(value: unknown) {
  Object.defineProperty(navigator, 'clipboard', {
    value,
    configurable: true,
    writable: true,
  });
}

afterEach(() => {
  if (originalClipboard) {
    Object.defineProperty(navigator, 'clipboard', originalClipboard);
  } else {
    Reflect.deleteProperty(navigator, 'clipboard');
  }
  vi.restoreAllMocks();
});

describe('copyText', () => {
  it('reports success when the write resolves', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });

    await expect(copyText('rag_live_secret')).resolves.toBe('copied');
    expect(writeText).toHaveBeenCalledWith('rag_live_secret');
  });

  it('reports failure when the write is refused, instead of rejecting', async () => {
    // Permission denied and an unfocused document both land here.
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) });

    await expect(copyText('rag_live_secret')).resolves.toBe('failed');
  });

  it('reports failure when the clipboard API is absent, not a TypeError', async () => {
    // This is the non-secure context: a self-hosted UI opened over plain HTTP on a LAN
    // address has no `navigator.clipboard` at all. The old call sites dereferenced it
    // directly, so this threw a `TypeError` inside an async function with no handler.
    setClipboard(undefined);

    await expect(copyText('rag_live_secret')).resolves.toBe('failed');
  });

  it('reports failure when the clipboard object exists but cannot write', async () => {
    // A browser or extension can expose the namespace without `writeText`.
    setClipboard({});

    await expect(copyText('rag_live_secret')).resolves.toBe('failed');
  });

  it('refuses empty text without touching the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });

    await expect(copyText('')).resolves.toBe('failed');
    expect(writeText).not.toHaveBeenCalled();
  });

  it('passes the text through untouched, including a key with no length limit', async () => {
    // The raw key is opaque. Trimming or normalising it here would hand the user a
    // secret that does not work.
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    const key = `  rag_live_${'x'.repeat(64)}\n`;

    await expect(copyText(key)).resolves.toBe('copied');
    expect(writeText).toHaveBeenCalledWith(key);
  });

  it('reports failure when writeText throws synchronously', async () => {
    // Some implementations raise `InvalidStateError` by throwing rather than by
    // returning a rejected promise. Both shapes have to settle as a value, because the
    // whole contract is that no call site can produce an unhandled rejection.
    setClipboard({
      writeText: vi.fn(() => { throw new Error('InvalidStateError'); }),
    });

    await expect(copyText('secret')).resolves.toBe('failed');
  });
});
