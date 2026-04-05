import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Settings } from './Settings';

// Mock localStorage for jsdom environment
const localStorageMock = {
  getItem: vi.fn(() => null),
  setItem: vi.fn(() => {}),
  removeItem: vi.fn(() => {}),
  clear: vi.fn(() => {}),
};
Object.defineProperty(window, 'localStorage', { value: localStorageMock });

describe('Settings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorageMock.getItem.mockReturnValue(null);
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

  it('shows save button (disabled when no changes)', () => {
    render(<Settings />);
    const saveBtn = screen.getByRole('button', { name: 'Save Changes' });
    expect(saveBtn).toBeInTheDocument();
    expect(saveBtn).toBeDisabled();
  });
});
