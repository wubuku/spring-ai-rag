import { useRef, useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Dialog } from './Dialog';

function Harness() {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button ref={triggerRef} type="button" onClick={() => setOpen(true)}>
        Open settings
      </button>
      <Dialog
        open={open}
        title="Workspace settings"
        description="Change the active workspace."
        onClose={() => setOpen(false)}
        returnFocusRef={triggerRef}
        actions={<button type="button" onClick={() => setOpen(false)}>Save</button>}
      >
        <label>
          Name
          <input aria-label="Name" />
        </label>
      </Dialog>
    </>
  );
}

describe('Dialog', () => {
  it('provides modal semantics, focus containment, scroll lock and focus return', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const trigger = screen.getByRole('button', { name: 'Open settings' });

    await user.click(trigger);

    const dialog = screen.getByRole('dialog', { name: 'Workspace settings' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleDescription('Change the active workspace.');
    expect(document.body.style.overflow).toBe('hidden');
    expect(screen.getByLabelText('Name')).toHaveFocus();

    await user.tab();
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
    await user.tab();
    expect(screen.getByLabelText('Name')).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('');
    expect(trigger).toHaveFocus();
  });

  it('does not close while close is disabled', async () => {
    const user = userEvent.setup();
    render(
      <Dialog
        open
        title="Pending mutation"
        onClose={() => undefined}
        closeDisabled
      >
        <button type="button">Working</button>
      </Dialog>,
    );

    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Pending mutation' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Close' })).toBeDisabled();
  });
});
