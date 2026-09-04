import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useChartTheme } from './useChartTheme';

describe('useChartTheme', () => {
  it('exposes a palette entry for every token the charts need', () => {
    const { result } = renderHook(() => useChartTheme());

    expect(Object.keys(result.current).sort()).toEqual(
      [
        'axisText',
        'gridStroke',
        'primary',
        'success',
        'tooltipBackground',
        'tooltipBorder',
        'warning',
      ].sort(),
    );
  });

  it('re-reads tokens when the document theme attribute flips', async () => {
    const { result } = renderHook(() => useChartTheme());
    const initial = result.current;

    // MutationObserver delivers callbacks as microtasks, so flush them.
    await act(async () => {
      document.documentElement.setAttribute('data-theme', 'dark');
    });
    const afterDark = result.current;
    expect(afterDark).not.toBe(initial);

    await act(async () => {
      document.documentElement.removeAttribute('data-theme');
    });
    expect(result.current).not.toBe(afterDark);
  });

  it('disconnects its observer on unmount', () => {
    const disconnectSpy = vi.spyOn(MutationObserver.prototype, 'disconnect');
    const { unmount } = renderHook(() => useChartTheme());

    expect(disconnectSpy).not.toHaveBeenCalled();
    unmount();
    expect(disconnectSpy).toHaveBeenCalledTimes(1);
    disconnectSpy.mockRestore();
  });
});
