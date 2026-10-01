import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { IconButton } from './IconButton';

function renderIconButton(props: Partial<Parameters<typeof IconButton>[0]> = {}) {
  return render(
    <IconButton label="Close sidebar" {...props}>
      <span aria-hidden="true">x</span>
    </IconButton>,
  );
}

describe('IconButton', () => {
  it('always exposes an accessible name even though it renders no text', () => {
    renderIconButton();
    expect(screen.getByRole('button', { name: 'Close sidebar' })).toBeInTheDocument();
  });

  it('defaults to type=button so it cannot submit a surrounding form by accident', () => {
    renderIconButton();
    expect(screen.getByRole('button')).toHaveAttribute('type', 'button');
  });

  it('honours an explicit submit type', () => {
    renderIconButton({ type: 'submit' });
    expect(screen.getByRole('button')).toHaveAttribute('type', 'submit');
  });

  it('falls back to the accessible label for the native tooltip', () => {
    renderIconButton();
    expect(screen.getByRole('button')).toHaveAttribute('title', 'Close sidebar');
  });

  it('prefers explicit tooltip text when provided', () => {
    renderIconButton({ tooltip: 'Closes the sidebar' });
    expect(screen.getByRole('button')).toHaveAttribute('title', 'Closes the sidebar');
  });

  it('exposes a fixed footprint per size so loading states do not resize it', () => {
    const { rerender } = renderIconButton();
    expect(screen.getByRole('button')).toHaveAttribute('data-size', '32');

    rerender(
      <IconButton label="Close sidebar" size={36}>
        <span aria-hidden="true">x</span>
      </IconButton>,
    );
    expect(screen.getByRole('button')).toHaveAttribute('data-size', '36');
  });

  it('defaults to the quiet ghost variant', () => {
    renderIconButton();
    expect(screen.getByRole('button')).toHaveAttribute('data-variant', 'ghost');
  });

  it('supports the secondary and danger variants', () => {
    const { rerender } = renderIconButton({ variant: 'secondary' });
    expect(screen.getByRole('button')).toHaveAttribute('data-variant', 'secondary');

    rerender(
      <IconButton label="Delete" variant="danger">
        <span aria-hidden="true">x</span>
      </IconButton>,
    );
    expect(screen.getByRole('button')).toHaveAttribute('data-variant', 'danger');
  });

  it('keeps the accessible name when disabled', () => {
    renderIconButton({ disabled: true });
    const button = screen.getByRole('button', { name: 'Close sidebar' });
    expect(button).toBeDisabled();
  });

  it('forwards the click handler', async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    renderIconButton({ onClick });

    await user.click(screen.getByRole('button'));

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not fire while disabled', async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    renderIconButton({ onClick, disabled: true });

    await user.click(screen.getByRole('button'));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('forwards a ref to the underlying button', () => {
    const ref = { current: null as HTMLButtonElement | null };
    render(
      <IconButton label="Close sidebar" ref={ref}>
        <span aria-hidden="true">x</span>
      </IconButton>,
    );
    expect(ref.current).toBeInstanceOf(HTMLButtonElement);
  });
});
