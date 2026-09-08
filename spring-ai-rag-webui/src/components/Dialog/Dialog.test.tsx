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

function BareHarness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>Open bare</button>
      <Dialog
        open={open}
        title="Bare"
        onClose={() => setOpen(false)}
        closeDisabled
      >
        {null}
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

describe('Dialog focus trap wrap-around', () => {
  it('wraps focus from the first focusable back to the last on Shift+Tab', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open settings' }));

    // 焦点初始在第一个可聚焦元素（Name 输入）上。
    expect(screen.getByLabelText('Name')).toHaveFocus();

    // Shift+Tab 从首个元素环绕到最后一个（Close 按钮）。
    await user.keyboard('{Shift>}{Tab}{/Shift}');
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
  });

  it('wraps focus from the last focusable back to the first on Tab', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open settings' }));

    // 先把焦点移到最后一个（Close），再正向 Tab 环绕回首元素。
    await user.keyboard('{Shift>}{Tab}{/Shift}');
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();

    await user.tab();
    expect(screen.getByLabelText('Name')).toHaveFocus();
  });

  it('wraps focus from the first DOM focusable to the last via the trap handler', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open settings' }));

    // DOM 顺序中 header 内的 Close 按钮是面板第一个可聚焦元素。
    screen.getByRole('button', { name: 'Close' }).focus();

    // 处理器 preventDefault 原生环绕并把焦点送到最后一个（Save）。
    await user.keyboard('{Shift>}{Tab}{/Shift}');
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
  });

  it('focuses the panel itself when the dialog has no focusable elements', async () => {
    const user = userEvent.setup();
    render(<BareHarness />);
    await user.click(screen.getByRole('button', { name: 'Open bare' }));

    await user.tab();
    expect(screen.getByRole('dialog')).toHaveFocus();
  });

  it('closes when the backdrop itself is pressed', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open settings' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await user.click(screen.getByTestId('dialog-backdrop'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
