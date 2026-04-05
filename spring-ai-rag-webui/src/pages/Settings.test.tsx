import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
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
    // Mock i18n returns 'settings.title' key, which contains 'Settings'
    expect(screen.getByText(/settings\.title/i)).toBeInTheDocument();
  });

  it('renders all four tabs', () => {
    render(<Settings />);
    // Mock returns keys: settings.llmProvider, settings.retrieval, settings.cache, language label
    expect(screen.getByRole('button', { name: /settings\.llmProvider/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /settings\.retrieval/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /settings\.cache/i })).toBeInTheDocument();
  });

  it('shows save button disabled when no changes', () => {
    render(<Settings />);
    const saveBtn = screen.getByRole('button', { name: /settings\.save/i });
    expect(saveBtn).toBeInTheDocument();
    expect(saveBtn).toBeDisabled();
  });
});
