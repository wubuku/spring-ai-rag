import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Settings } from './Settings';

describe('Settings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title', () => {
    render(<Settings />);
    expect(screen.getByText('Settings')).toBeInTheDocument();
  });

  it('renders all three tabs', () => {
    render(<Settings />);
    expect(screen.getByRole('button', { name: /LLM Provider/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retrieval/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Cache/i })).toBeInTheDocument();
  });

  it('shows save button', () => {
    render(<Settings />);
    expect(screen.getByRole('button', { name: 'Save Changes' })).toBeInTheDocument();
  });

  it('save button shows confirmation after clicking', async () => {
    const user = userEvent.setup();
    render(<Settings />);
    await user.click(screen.getByRole('button', { name: 'Save Changes' }));
    expect(screen.getByText('✓ Saved!')).toBeInTheDocument();
  });
});
