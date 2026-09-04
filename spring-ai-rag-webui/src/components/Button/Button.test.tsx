import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import { Button } from './Button';

describe('Button', () => {
  it('defaults to type="button" and the secondary variant', () => {
    render(<Button>Save</Button>);

    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).toHaveAttribute('type', 'button');
    expect(button.className).toContain('secondary');
    expect(button.className).toContain('button');
  });

  it('renders every variant class without clobbering extra classNames', () => {
    render(
      <>
        <Button variant="secondary">S</Button>
        <Button variant="danger">D</Button>
        <Button variant="link">L</Button>
        <Button className="extra">P</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'S' }).className).toContain('secondary');
    expect(screen.getByRole('button', { name: 'D' }).className).toContain('danger');
    expect(screen.getByRole('button', { name: 'L' }).className).toContain('link');
    expect(screen.getByRole('button', { name: 'P' }).className).toContain('extra');
  });

  it('passes through submit type, disabled state, clicks and refs', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    const ref = createRef<HTMLButtonElement>();

    render(
      <Button type="submit" form="some-form" disabled onClick={onClick} ref={ref}>
        Go
      </Button>,
    );

    const button = screen.getByRole('button', { name: 'Go' });
    expect(button).toHaveAttribute('type', 'submit');
    expect(button).toHaveAttribute('form', 'some-form');
    expect(button).toBeDisabled();
    expect(ref.current).toBe(button);

    await user.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('invokes onClick when enabled', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Enabled</Button>);

    await user.click(screen.getByRole('button', { name: 'Enabled' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
