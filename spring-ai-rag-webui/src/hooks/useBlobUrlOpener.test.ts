import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useBlobUrlOpener } from './useBlobUrlOpener';

describe('useBlobUrlOpener', () => {
  it('opens the url in a new tab and revokes after the delay', () => {
    vi.useFakeTimers();
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockReturnValue(undefined);
    const { result } = renderHook(() => useBlobUrlOpener());

    act(() => {
      result.current('blob:preview');
    });

    expect(openSpy).toHaveBeenCalledWith('blob:preview', '_blank', 'noopener,noreferrer');
    expect(revokeSpy).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(revokeSpy).toHaveBeenCalledWith('blob:preview');

    vi.useRealTimers();
    openSpy.mockRestore();
    revokeSpy.mockRestore();
  });

  it('cancels pending revocations when the component unmounts', () => {
    vi.useFakeTimers();
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockReturnValue(undefined);
    const { result, unmount } = renderHook(() => useBlobUrlOpener());

    act(() => {
      result.current('blob:a');
      result.current('blob:b');
    });
    unmount();
    act(() => {
      vi.advanceTimersByTime(120_000);
    });

    expect(revokeSpy).not.toHaveBeenCalled();

    vi.useRealTimers();
    openSpy.mockRestore();
    revokeSpy.mockRestore();
  });
});
