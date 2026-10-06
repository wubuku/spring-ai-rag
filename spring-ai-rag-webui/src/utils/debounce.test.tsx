import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { DEFAULT_COMMIT_DELAY_MS, useDebouncedCommit } from './debounce';

// Batch 939. `Documents` and `Files` committed a search from `onChange`, so the keyword —
// which is part of the react-query `queryKey` — changed once per character: six letters,
// six server requests, and in `Files` six `localStorage` read-merge-write cycles through
// `rememberRoute`. These pin the replacement, including the case that made the obvious
// implementation wrong.

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useDebouncedCommit', () => {
  it('waits for the pause instead of committing on every keystroke', () => {
    const onCommit = vi.fn();
    const { rerender } = renderHook(
      ({ value }: { value: string }) =>
        useDebouncedCommit(value, '', onCommit),
      { initialProps: { value: '' } },
    );

    // Six keystrokes, each one starting a new timer.
    for (const value of ['s', 'sp', 'spr', 'spri', 'sprin', 'spring']) {
      rerender({ value });
      act(() => { vi.advanceTimersByTime(50); });
    }

    // 300 ms of typing, none of it paused for the full delay.
    expect(onCommit).not.toHaveBeenCalled();

    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS); });
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith('spring');
  });

  it('does not commit before the delay has elapsed', () => {
    const onCommit = vi.fn();
    renderHook(() => useDebouncedCommit('abc', '', onCommit));

    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS - 1); });
    expect(onCommit).not.toHaveBeenCalled();

    act(() => { vi.advanceTimersByTime(1); });
    expect(onCommit).toHaveBeenCalledWith('abc');
  });

  it('does nothing when the value already equals what is committed', () => {
    // Mount, and every value that arrives from outside: the browser Back button, a
    // bookmark, a link from another page. `Documents` deletes `page` on commit, so a
    // hook that wrote an externally-arrived value straight back would reset a Back that
    // landed on page 3 to page 1.
    const onCommit = vi.fn();
    renderHook(() => useDebouncedCommit('spring', 'spring', onCommit));

    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS * 4); });
    expect(onCommit).not.toHaveBeenCalled();
  });

  it('commits once when the caller passes the trimmed shape it actually passes', () => {
    // All three call sites debounce `draft.trim()` against the trimmed value the URL
    // holds, so the two sides of the comparison have the same shape and the effect goes
    // quiet the moment the write lands.
    const onCommit = vi.fn();
    const { rerender } = renderHook(
      ({ value, committed }: { value: string; committed: string }) =>
        useDebouncedCommit(value, committed, onCommit),
      { initialProps: { value: 'spring', committed: '' } },
    );

    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS); });
    expect(onCommit).toHaveBeenCalledTimes(1);

    rerender({ value: 'spring', committed: 'spring' });
    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS * 4); });
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it('settles instead of looping when the two sides differ in whitespace', () => {
    // A caller that forgot to trim leaves a mismatch the write can never close: the draft
    // keeps a trailing space, the URL never will. The re-arm is bounded — the effect
    // only re-runs when `value` or `committed` changes, and neither does — so the cost is
    // one redundant write, not a request loop. That bound is the property worth pinning.
    const onCommit = vi.fn();
    const { rerender } = renderHook(
      ({ value, committed }: { value: string; committed: string }) =>
        useDebouncedCommit(value, committed, onCommit),
      { initialProps: { value: 'spring ', committed: '' } },
    );

    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS); });
    expect(onCommit).toHaveBeenCalledTimes(1);

    rerender({ value: 'spring ', committed: 'spring' });
    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS); });
    expect(onCommit).toHaveBeenCalledTimes(2);

    // Nothing changed since, so no further timer is ever armed.
    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS * 20); });
    expect(onCommit).toHaveBeenCalledTimes(2);
  });

  it('keeps a blocked value pending so a later flush still delivers it', () => {
    // The IME case. A timer that fires mid-composition must neither commit half a word
    // nor discard the value: dropping it is how a search box ends up filtering by text
    // the user never finished typing.
    const onCommit = vi.fn();
    let composing = true;
    const { result, rerender } = renderHook(
      ({ value }: { value: string }) =>
        useDebouncedCommit(value, '', onCommit, { isBlocked: () => composing }),
      { initialProps: { value: '' } },
    );

    rerender({ value: 'zhongwen' });
    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS * 2); });
    expect(onCommit).not.toHaveBeenCalled();

    composing = false;
    act(() => { result.current.flush(); });
    expect(onCommit).toHaveBeenCalledExactlyOnceWith('zhongwen');
  });

  it('flushes on demand, which is what blurring out of a search box needs', () => {
    const onCommit = vi.fn();
    const { result, rerender } = renderHook(
      ({ value }: { value: string }) =>
        useDebouncedCommit(value, '', onCommit),
      { initialProps: { value: '' } },
    );

    rerender({ value: 'spring' });
    act(() => { result.current.flush(); });
    expect(onCommit).toHaveBeenCalledExactlyOnceWith('spring');

    // The scheduled timer must not fire a second time afterwards.
    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS * 4); });
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it('flushes an explicit override, which the end of a composition needs', () => {
    // Engines disagree on whether the final `input` event lands before or after
    // `compositionend`, so the pending value can still be missing the last characters.
    const onCommit = vi.fn();
    const { result, rerender } = renderHook(
      ({ value }: { value: string }) =>
        useDebouncedCommit(value, '', onCommit),
      { initialProps: { value: '' } },
    );

    rerender({ value: 'zhong' });
    act(() => { result.current.flush('zhongwen'); });

    expect(onCommit).toHaveBeenCalledExactlyOnceWith('zhongwen');
    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS * 4); });
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it('does not restart the debounce when the callback identity changes', () => {
    // The host component re-renders for unrelated reasons and hands over a fresh
    // closure. If `onCommit` were a dependency the timer would restart every render and
    // a busy page would never commit at all.
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(
      ({ onCommit }: { onCommit: (value: string) => void }) =>
        useDebouncedCommit('spring', '', onCommit),
      { initialProps: { onCommit: first } },
    );

    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS - 100); });
    rerender({ onCommit: second });
    act(() => { vi.advanceTimersByTime(100); });

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledExactlyOnceWith('spring');
  });

  it('does not restart the debounce when the blocked-check identity changes', () => {
    // Same hazard through a different door: `isBlocked` is an inline arrow at every
    // call site, so it is a new function on every render by construction.
    const onCommit = vi.fn();
    const { rerender } = renderHook(
      ({ tick }: { tick: number }) =>
        useDebouncedCommit('spring', '', onCommit, { isBlocked: () => tick < 0 }),
      { initialProps: { tick: 0 } },
    );

    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS - 100); });
    rerender({ tick: 1 });
    act(() => { vi.advanceTimersByTime(100); });

    expect(onCommit).toHaveBeenCalledExactlyOnceWith('spring');
  });

  it('drops the pending value when the component unmounts', () => {
    const onCommit = vi.fn();
    const { rerender, unmount } = renderHook(
      ({ value }: { value: string }) =>
        useDebouncedCommit(value, '', onCommit),
      { initialProps: { value: '' } },
    );

    rerender({ value: 'spring' });
    unmount();
    act(() => { vi.advanceTimersByTime(DEFAULT_COMMIT_DELAY_MS * 4); });

    expect(onCommit).not.toHaveBeenCalled();
  });
});
