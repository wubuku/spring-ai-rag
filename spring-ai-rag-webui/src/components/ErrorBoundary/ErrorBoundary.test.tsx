import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ErrorBoundary } from './ErrorBoundary';

describe('ErrorBoundary', () => {
  const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

  beforeEach(() => {
    consoleErrorSpy.mockClear();
  });

  const ThrowError = ({ shouldThrow }: { shouldThrow: boolean }) => {
    if (shouldThrow) {
      throw new Error('Test error message');
    }
    return <div>Normal content</div>;
  };

  it('renders children when there is no error', () => {
    render(
      <ErrorBoundary>
        <div data-testid="child">Child Content</div>
      </ErrorBoundary>
    );
    expect(screen.getByTestId('child')).toBeInTheDocument();
    expect(screen.getByText('Child Content')).toBeInTheDocument();
  });

  it('captures error and shows fallback UI when child throws', () => {
    const { container } = render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(screen.getByText('Test error message')).toBeInTheDocument();
    expect(screen.queryByText('Child Content')).not.toBeInTheDocument();
    expect(container.querySelector('[data-testid="child"]')).not.toBeInTheDocument();
  });

  it('shows default fallback when no fallback prop provided', () => {
    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(screen.getByText('Test error message')).toBeInTheDocument();
    expect(screen.getByText('Try Again')).toBeInTheDocument();
  });

  it('shows custom fallback when fallback prop is provided', () => {
    render(
      <ErrorBoundary fallback={<div data-testid="custom-fallback">Custom Error UI</div>}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByTestId('custom-fallback')).toBeInTheDocument();
    expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();
  });

  it('resets error state when Try Again button is clicked (child re-throws after reset)', () => {
    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Try Again'));
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
  });

  it('resets and recovers when child stops throwing after reset', () => {
    let shouldThrow = true;
    const DynamicThrow = () => {
      if (shouldThrow) throw new Error('Dynamic error');
      return <div data-testid="recovered">Recovered!</div>;
    };

    const { unmount } = render(
      <ErrorBoundary>
        <DynamicThrow />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Try Again'));
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();

    shouldThrow = false;
    fireEvent.click(screen.getByText('Try Again'));

    expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();
    expect(screen.getByTestId('recovered')).toBeInTheDocument();

    unmount();
  });

  it('logs error to console.error when child throws', () => {
    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      'ErrorBoundary caught an error:',
      expect.any(Error),
      expect.objectContaining({ componentStack: expect.any(String) })
    );
  });

  it('does not throw when backend report fails', () => {
    // Simulate fetch failing — ErrorBoundary should still show error UI (best-effort reporting)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')));

    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();

    vi.unstubAllGlobals();
  });

  it('shows "An unexpected error occurred" when error has no message', () => {
    const ThrowNoMessage = () => {
      throw new Error();
    };

    render(
      <ErrorBoundary>
        <ThrowNoMessage />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(screen.getByText('An unexpected error occurred')).toBeInTheDocument();
  });

  it('renders correctly with multiple children', () => {
    render(
      <ErrorBoundary>
        <div data-testid="child1">Child 1</div>
        <div data-testid="child2">Child 2</div>
      </ErrorBoundary>
    );

    expect(screen.getByTestId('child1')).toBeInTheDocument();
    expect(screen.getByTestId('child2')).toBeInTheDocument();
  });

  it('renders with undefined fallback prop', () => {
    // @ts-expect-error — testing runtime behavior with undefined fallback
    render(
      <ErrorBoundary fallback={undefined}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(screen.getByText('Try Again')).toBeInTheDocument();
  });
});
