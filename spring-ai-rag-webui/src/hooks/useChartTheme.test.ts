import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useChartTheme } from './useChartTheme';

describe('useChartTheme', () => {
  beforeEach(() => {
    // jsdom does not load the generated stylesheet, so inject the token values
    // the bridge is expected to read. This also proves the hook maps each
    // palette field to the correct CSS custom property name.
    document.head.innerHTML = `<style>
      :root {
        --chart-axis: #6b7280;
        --chart-grid: #e5e7eb;
        --chart-tooltip-surface: #ffffff;
        --chart-tooltip-border: #d1d5db;
        --chart-category-1: #3b82f6;
        --chart-category-2: #22c55e;
        --chart-category-3: #f59e0b;
        --color-success: #16a34a;
        --color-warning: #d97706;
      }
    </style>`;
  });

  afterEach(() => {
    document.head.innerHTML = '';
  });

  it('exposes a palette entry for every token the charts need', () => {
    const { result } = renderHook(() => useChartTheme());

    expect(Object.keys(result.current).sort()).toEqual(
      [
        'axisText',
        'category',
        'gridStroke',
        'primary',
        'success',
        'tooltipBackground',
        'tooltipBorder',
        'warning',
      ].sort(),
    );
  });

  it('resolves chart fields from the chart tokens, not from a duplicated palette', () => {
    const { result } = renderHook(() => useChartTheme());

    expect(result.current.axisText).toBe('#6b7280');
    expect(result.current.gridStroke).toBe('#e5e7eb');
    expect(result.current.tooltipBackground).toBe('#ffffff');
    expect(result.current.tooltipBorder).toBe('#d1d5db');
    expect(result.current.primary).toBe('#3b82f6');
    expect(result.current.success).toBe('#16a34a');
    expect(result.current.warning).toBe('#d97706');
    expect(result.current.category).toEqual(['#3b82f6', '#22c55e', '#f59e0b']);
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
