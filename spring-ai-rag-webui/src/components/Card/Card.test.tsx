import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Card } from './Card';

describe('Card', () => {
  it('renders the card surface class and keeps extra classNames', () => {
    render(
      <Card className="extra" data-testid="card">
        <p>Content</p>
      </Card>,
    );

    const card = screen.getByTestId('card');
    expect(card.className).toContain('card');
    expect(card.className).toContain('extra');
    expect(card).toHaveTextContent('Content');
  });

  it('renders without extra className and forwards clicks', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<Card onClick={onClick}>Plain</Card>);

    await user.click(screen.getByText('Plain'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
