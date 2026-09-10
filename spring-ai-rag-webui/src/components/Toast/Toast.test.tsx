import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import { ToastProvider, useToast } from './Toast';
import { TOAST_ICONS } from './constants';

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

  it('shows success icon for success toasts', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Success'));
    expect(screen.getByText(TOAST_ICONS.success)).toBeInTheDocument();
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

  it('shows error icon for error toasts', () => {
    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByText('Show Error'));
    expect(screen.getByText(TOAST_ICONS.error)).toBeInTheDocument();
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
