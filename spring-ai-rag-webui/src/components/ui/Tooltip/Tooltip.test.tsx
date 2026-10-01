import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Tooltip } from './Tooltip';

function renderTooltip(props: { content?: string; placement?: 'top' | 'bottom' } = {}) {
  const { content = 'Re-run embedding', placement = 'top' } = props;
  return render(
    <Tooltip content={content} placement={placement}>
      <button type="button">Rerun</button>
    </Tooltip>,
  );
}

describe('Tooltip', () => {
  it('keeps its text out of the accessibility tree while closed', () => {
    renderTooltip();
    const tooltip = screen.getByRole('tooltip', { hidden: true });
    expect(tooltip).toHaveAttribute('aria-hidden', 'true');
    expect(screen.queryByRole('button', { name: 'Re-run embedding' })).not.toBeInTheDocument();
  });

  it('reveals on pointer hover and describes its trigger', async () => {
    const user = userEvent.setup();
    renderTooltip();

    await user.hover(screen.getByRole('button', { name: 'Rerun' }));

    const tooltip = screen.getByRole('tooltip');
    expect(tooltip).toBeVisible();
    expect(tooltip).toHaveTextContent('Re-run embedding');
    expect(screen.getByRole('button', { name: 'Rerun' })).toHaveAttribute(
      'aria-describedby',
      tooltip.id,
    );
  });

  it('reveals on keyboard focus, so it is not mouse-only', async () => {
    const user = userEvent.setup();
    renderTooltip();

    await user.tab();

    expect(screen.getByRole('tooltip')).toBeVisible();
  });

  it('hides again on blur', async () => {
    const user = userEvent.setup();
    renderTooltip();

    await user.tab();
    expect(screen.getByRole('tooltip')).toBeVisible();

    await user.tab();
    expect(screen.getByRole('tooltip', { hidden: true })).toHaveAttribute('aria-hidden', 'true');
  });

  it('hides on Escape without moving focus away from the trigger', async () => {
    const user = userEvent.setup();
    renderTooltip();

    await user.tab();
    expect(screen.getByRole('tooltip')).toBeVisible();

    await user.keyboard('{Escape}');

    expect(screen.getByRole('tooltip', { hidden: true })).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByRole('button', { name: 'Rerun' })).toHaveFocus();
  });

  it('hides when the pointer leaves', async () => {
    const user = userEvent.setup();
    renderTooltip();

    const trigger = screen.getByRole('button', { name: 'Rerun' });
    await user.hover(trigger);
    expect(screen.getByRole('tooltip')).toBeVisible();

    await user.unhover(trigger);
    expect(screen.getByRole('tooltip', { hidden: true })).toHaveAttribute('aria-hidden', 'true');
  });

  it('keeps the placement as a styling hook', () => {
    renderTooltip({ placement: 'bottom' });
    expect(screen.getByRole('tooltip', { hidden: true })).toHaveAttribute('data-placement', 'bottom');
  });

  it('does not require the trigger to have its own label, only to be a single element', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() =>
      render(
        <Tooltip content="x">
          {'not an element'}
        </Tooltip>,
      ),
    ).toThrow(/single React element/);
    spy.mockRestore();
  });
});
