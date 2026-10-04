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

function Stacked() {
  const [outer, setOuter] = useState(false);
  const [inner, setInner] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOuter(true)}>Open outer</button>
      <Dialog open={outer} title="Outer" onClose={() => setOuter(false)}>
        <button type="button" onClick={() => setInner(true)}>Open inner</button>
      </Dialog>
      <Dialog open={inner} title="Inner" onClose={() => setInner(false)}>
        <button type="button">Inner action</button>
      </Dialog>
    </>
  );
}

function ClosableOuter() {
  const [outer, setOuter] = useState(false);
  const [inner, setInner] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOuter(true)}>Open outer</button>
      <Dialog open={outer} title="Outer" onClose={() => setOuter(false)}>
        <>
          <button type="button" onClick={() => setInner(true)}>Open inner</button>
          <button type="button" onClick={() => setOuter(false)}>Close outer</button>
        </>
      </Dialog>
      <Dialog open={inner} title="Inner" onClose={() => setInner(false)}>
        <button type="button">Inner action</button>
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
    expect(screen.getByTestId('dialog-close')).toHaveFocus();
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
    expect(screen.getByTestId('dialog-close')).toBeDisabled();
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
    expect(screen.getByTestId('dialog-close')).toHaveFocus();
  });

  it('wraps focus from the last focusable back to the first on Tab', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open settings' }));

    // 先把焦点移到最后一个（Close），再正向 Tab 环绕回首元素。
    await user.keyboard('{Shift>}{Tab}{/Shift}');
    expect(screen.getByTestId('dialog-close')).toHaveFocus();

    await user.tab();
    expect(screen.getByLabelText('Name')).toHaveFocus();
  });

  it('wraps focus from the first DOM focusable to the last via the trap handler', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open settings' }));

    // DOM 顺序中 header 内的 Close 按钮是面板第一个可聚焦元素。
    screen.getByTestId('dialog-close').focus();

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

  // ── 叠加对话框（Batch 788）───────────────────────────────────────────
  //
  // 对话框是会叠加的：Documents 页在版本历史弹窗里点"恢复版本"会再弹一个确认
  // 框，底层弹窗并不会因此关闭。修复前一次 Escape 关掉整摞，且各实例各存的
  // previousOverflow 互相覆盖，把 body 永久锁死。
  describe('stacked dialogs', () => {
    it('closes only the topmost dialog on Escape', async () => {
      const user = userEvent.setup();
      render(<Stacked />);
      await user.click(screen.getByRole('button', { name: 'Open outer' }));
      await user.click(screen.getByRole('button', { name: 'Open inner' }));
      expect(screen.getAllByRole('dialog')).toHaveLength(2);

      await user.keyboard('{Escape}');

      expect(screen.queryByRole('dialog', { name: 'Inner' })).not.toBeInTheDocument();
      expect(screen.getByRole('dialog', { name: 'Outer' })).toBeInTheDocument();
      // 焦点必须回到仍然打开的底层弹窗内，而不是页面上的触发按钮。
      expect(screen.getByRole('button', { name: 'Open inner' })).toHaveFocus();
    });

    it('releases the body scroll lock exactly once, after the last close', async () => {
      const user = userEvent.setup();
      render(<Stacked />);
      await user.click(screen.getByRole('button', { name: 'Open outer' }));
      await user.click(screen.getByRole('button', { name: 'Open inner' }));

      await user.keyboard('{Escape}');
      // 底层还开着，body 应当仍然是锁着的。
      expect(document.body.style.overflow).toBe('hidden');

      await user.keyboard('{Escape}');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      // 修复前这里是 'hidden'：两个对话框都关了，页面却再也滚不动。
      expect(document.body.style.overflow).toBe('');
    });

    it('does not let the lower dialog hijack Tab from the topmost one', async () => {
      const user = userEvent.setup();
      render(<Stacked />);
      await user.click(screen.getByRole('button', { name: 'Open outer' }));
      await user.click(screen.getByRole('button', { name: 'Open inner' }));
      const inner = screen.getByRole('dialog', { name: 'Inner' });

      // 修复前底层的 Tab 处理器也会响应，把焦点从顶层拽回底层弹窗。
      // 连续 Tab 几次，焦点必须始终留在顶层弹窗内。
      for (let i = 0; i < 4; i += 1) {
        await user.tab();
        expect(inner).toContainElement(document.activeElement as HTMLElement);
      }
    });

    it('releases the lock when a lower dialog is closed first', async () => {
      // 底层被程序化关闭（不是 Escape）时同样要正确出栈。
      const user = userEvent.setup();
      render(<ClosableOuter />);
      await user.click(screen.getByRole('button', { name: 'Open outer' }));
      await user.click(screen.getByRole('button', { name: 'Open inner' }));

      await user.click(screen.getByRole('button', { name: 'Close outer' }));
      expect(screen.getByRole('dialog', { name: 'Inner' })).toBeInTheDocument();
      expect(document.body.style.overflow).toBe('hidden');

      await user.keyboard('{Escape}');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(document.body.style.overflow).toBe('');
    });
  });
});
