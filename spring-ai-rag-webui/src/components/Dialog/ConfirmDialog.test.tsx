import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfirmDialog } from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('renders title, description and both actions, and reports clicks', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const onClose = vi.fn();

    render(
      <ConfirmDialog
        open
        title="Delete document"
        description="This cannot be undone."
        confirmLabel="Delete now"
        cancelLabel="Keep it"
        onConfirm={onConfirm}
        onClose={onClose}
      />,
    );

    const dialog = screen.getByRole('dialog', { name: 'Delete document' });
    expect(dialog).toHaveAccessibleDescription('This cannot be undone.');

    await user.click(screen.getByRole('button', { name: 'Delete now' }));
    await user.click(screen.getByRole('button', { name: 'Keep it' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not render anything when closed', () => {
    render(
      <ConfirmDialog
        open={false}
        title="Hidden"
        description="Not open"
        confirmLabel="Confirm"
        cancelLabel="Cancel"
        onConfirm={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('disables both actions and keeps the dialog open while pending', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const onClose = vi.fn();

    render(
      <ConfirmDialog
        open
        title="Working"
        description="A mutation is running."
        confirmLabel="Confirm"
        cancelLabel="Cancel"
        onConfirm={onConfirm}
        onClose={onClose}
        pending
      />,
    );

    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Close' })).toBeDisabled();

    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Working' })).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('marks the confirm action as dangerous via inline error styling', () => {
    render(
      <ConfirmDialog
        open
        title="Dangerous"
        description="Irreversible."
        confirmLabel="Destroy"
        cancelLabel="Cancel"
        onConfirm={vi.fn()}
        onClose={vi.fn()}
        danger
      />,
    );

    const confirm = screen.getByRole('button', { name: 'Destroy' });
    expect(confirm.getAttribute('style')).toContain('--color-error');
  });
});
