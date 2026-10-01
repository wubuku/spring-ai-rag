import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusBadge } from './StatusBadge';
import type { StatusTone } from './StatusBadge';

const TONES: StatusTone[] = ['neutral', 'success', 'warning', 'error', 'info', 'primary'];

describe('StatusBadge', () => {
  it('renders its label as text', () => {
    render(<StatusBadge>Active</StatusBadge>);
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('defaults to the neutral tone', () => {
    render(<StatusBadge>Active</StatusBadge>);
    expect(screen.getByText('Active')).toHaveAttribute('data-tone', 'neutral');
  });

  it.each(TONES)('exposes %s as a styling hook', tone => {
    render(<StatusBadge tone={tone}>Label</StatusBadge>);
    expect(screen.getByText('Label')).toHaveAttribute('data-tone', tone);
  });

  it('keeps caller classes alongside its own', () => {
    render(
      <StatusBadge className="custom" tone="success">
        Active
      </StatusBadge>,
    );
    const badge = screen.getByText('Active');
    expect(badge.className).toContain('custom');
    expect(badge).toHaveAttribute('data-tone', 'success');
  });

  it('forwards standard span attributes such as title', () => {
    render(
      <StatusBadge tone="error" title="Credential was revoked">
        Revoked
      </StatusBadge>,
    );
    expect(screen.getByTitle('Credential was revoked')).toBeInTheDocument();
  });

  it('does not collapse distinct tones onto the same styling hook', () => {
    // Guards the refactor that replaced two per-page badge systems: a status
    // and a role must never render identically just because both are badges.
    const { container } = render(
      <>
        <StatusBadge tone="success">Active</StatusBadge>
        <StatusBadge tone="error">Expired</StatusBadge>
        <StatusBadge tone="primary">Admin</StatusBadge>
      </>,
    );
    const tones = [...container.querySelectorAll('span')].map(node => node.dataset.tone);
    expect(new Set(tones).size).toBe(tones.length);
  });
});
