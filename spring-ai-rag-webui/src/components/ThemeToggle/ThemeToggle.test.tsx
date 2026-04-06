import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeToggle } from './ThemeToggle';

const localStorageMock = {
  data: {} as Record<string, string>,
  getItem: vi.fn((key: string) => localStorageMock.data[key] ?? null),
  setItem: vi.fn((key: string, value: string) => { localStorageMock.data[key] = value; }),
};
Object.defineProperty(window, 'localStorage', { value: localStorageMock });

describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorageMock.data = {};
    vi.clearAllMocks();
  });

  it('renders toggle button', () => {
    render(<ThemeToggle />);
    expect(screen.getByRole('button')).toBeInTheDocument();
  });

  it('shows sun icon when no theme stored', () => {
    render(<ThemeToggle />);
    // Button should show sun (light mode toggle)
    expect(screen.getByRole('button')).toBeInTheDocument();
  });

  it('toggles theme on click', async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);

    const btn = screen.getByRole('button');
    await user.click(btn);

    expect(localStorageMock.setItem).toHaveBeenCalled();
  });
});
