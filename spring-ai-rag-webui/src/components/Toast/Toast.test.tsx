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
});
