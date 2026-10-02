import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import { ToastProvider, useToast } from './Toast';
import { TOAST_ICONS } from './constants';

/**
 * The semantic icon for a toast.
 *
 * Asserting on the icon used to mean matching an emoji code point, which
 * proved the glyph was still there. The component now exposes a
 * `data-toast-icon` hook instead, so a test states *which* semantic icon is
 * shown without pinning a Unicode character.
 */
function toastIcon(type: keyof typeof TOAST_ICONS) {
  return document.querySelector(`[data-toast-icon="${type}"]`);
}

// Test component that uses the toast
function TestConsumer() {
  const { showToast } = useToast();
  return (
    <div>
      <button onClick={() => showToast('Success!', 'success')}>Show Success</button>
      <button onClick={() => showToast('Error!', 'error')}>Show Error</button>
      <button onClick={() => showToast('Info', 'info')}>Show Info</button>
      <button onClick={() => showToast('Warning', 'warning')}>Show Warning</button>
    </div>
  );
}

describe('Toast', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders without crashing', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );
    expect(screen.getByText('Show Success')).toBeInTheDocument();
  });

  it('shows toast when showToast is called', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Success'));
    expect(screen.getByText('Success!')).toBeInTheDocument();
  });

  it('shows a success icon for success toasts', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Success'));
    const icon = toastIcon('success');
    expect(icon).not.toBeNull();
    // The icon carries no information a screen reader needs; the message does.
    expect(icon!.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('gives every semantic type its own icon', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    for (const type of ['success', 'error', 'info', 'warning'] as const) {
      fireEvent.click(screen.getByText(`Show ${type[0].toUpperCase()}${type.slice(1)}`));
      expect(toastIcon(type)).not.toBeNull();
    }
    // Distinct components, not one glyph reused under four names.
    expect(new Set(Object.values(TOAST_ICONS)).size).toBe(Object.keys(TOAST_ICONS).length);
  });

  it('tolerates a duplicate close click racing the timer cleanup', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Info'));
    const close = screen.getByRole('button', { name: 'Close notification' });
    // 同一 act 内连点两次：第二次 removeToast 时定时器登记已被
    // 首次点击清理（timer undefined 分支），不得抛错。
    act(() => {
      close.click();
      close.click();
    });
    expect(screen.queryByText('Info')).not.toBeInTheDocument();
  });

  it('shows an error icon for error toasts and alerts assistive tech', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Error'));
    expect(toastIcon('error')).not.toBeNull();
    // Errors interrupt; every other toast is a polite status update.
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('can close toast manually', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Success'));
    expect(screen.getByText('Success!')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Close notification' }));
    expect(screen.queryByText('Success!')).not.toBeInTheDocument();
  });

  it('auto-dismisses after 3 seconds', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Success'));
    expect(screen.getByText('Success!')).toBeInTheDocument();

    act(() => vi.advanceTimersByTime(4000));
    expect(screen.queryByText('Success!')).not.toBeInTheDocument();
  });

  it('throws error when useToast is used outside provider', () => {
    // Suppress console.error for this test
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    expect(() => {
      render(<TestConsumer />);
    }).toThrow();

    consoleSpy.mockRestore();
  });

  // ── live region（Batch 775） ────────────────────────────────────────
  //
  // 断言的是「常驻容器带 aria-live」，不是「每条 toast 带 role」。
  // 后者一直是绿的，却恰恰是缺陷本身：role 挂在随内容一起插入的节点上时，
  // 屏幕阅读器不会播报。两种写法都通过，等于什么都没测。

  it('exposes an always-mounted polite live region before any toast exists', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    const region = screen.getByTestId('toast-live-region');
    // 关键：还没显示任何 toast 时它就已经在 DOM 里。
    expect(region).toBeInTheDocument();
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toHaveAttribute('aria-atomic', 'false');
    expect(region).toBeEmptyDOMElement();
  });

  it('keeps the live region mounted and non-empty while a toast is shown', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    const region = screen.getByTestId('toast-live-region');
    fireEvent.click(screen.getByText('Show Success'));

    // 同一个容器仍在，只是内部多了内容——这正是插入式变更能被播报的前提。
    expect(screen.getByTestId('toast-live-region')).toBe(region);
    expect(region).not.toBeEmptyDOMElement();
    expect(screen.getByText('Success!')).toBeInTheDocument();
  });

  it('escalates error toasts to assertive while the region stays polite', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Error'));

    expect(screen.getByTestId('toast-live-region')).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  // ── 错误 toast 常驻（Batch 775） ────────────────────────────────────

  it('keeps an error toast on screen until it is dismissed', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Error'));
    expect(screen.getByText('Error!')).toBeInTheDocument();

    // 远超非错误 toast 的 4 秒自动消失窗口。
    act(() => vi.advanceTimersByTime(30000));
    expect(
      screen.getByText('Error!'),
      '错误消息是失败原因的唯一记录，不能被计时器抹掉'
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Close notification' }));
    expect(screen.queryByText('Error!')).not.toBeInTheDocument();
  });

  it.each(['success', 'info', 'warning'] as const)(
    'still auto-dismisses a %s toast',
    type => {
      render(
        <ToastProvider>
          <TestConsumer />
        </ToastProvider>
      );

      const trigger = { success: 'Show Success', info: 'Show Info', warning: 'Show Warning' }[type];
      const label = { success: 'Success!', info: 'Info', warning: 'Warning' }[type];
      fireEvent.click(screen.getByText(trigger));
      expect(screen.getByText(label)).toBeInTheDocument();

      act(() => vi.advanceTimersByTime(4000));
      expect(screen.queryByText(label)).not.toBeInTheDocument();
    }
  );
});
