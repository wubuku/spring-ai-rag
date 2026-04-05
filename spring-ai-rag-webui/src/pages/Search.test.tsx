import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Search } from './Search';

const mockRefetch = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: vi.fn(() => ({
    data: undefined,
    isPending: false,
    refetch: mockRefetch,
  })),
}));

describe('Search', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title', () => {
    render(<Search />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('search.title');
  });

  it('shows search input', () => {
    render(<Search />);
    expect(screen.getByPlaceholderText(/search.placeholder/)).toBeInTheDocument();
  });

  it('shows hybrid checkbox', () => {
    render(<Search />);
    expect(screen.getByLabelText(/Hybrid/)).toBeInTheDocument();
  });

  it('shows search button', () => {
    render(<Search />);
    expect(screen.getByRole('button', { name: /search.searchButton/ })).toBeInTheDocument();
  });

  it('search button is disabled when input is empty', () => {
    render(<Search />);
    expect(screen.getByRole('button', { name: /search.searchButton/ })).toBeDisabled();
  });

  it('search button is enabled when input has text', () => {
    render(<Search />);
    const input = screen.getByPlaceholderText(/search.placeholder/);
    fireEvent.change(input, { target: { value: 'test' } });
    expect(screen.getByRole('button', { name: /search.searchButton/ })).not.toBeDisabled();
  });

  it('calls refetch when form is submitted', () => {
    render(<Search />);
    const input = screen.getByPlaceholderText(/search.placeholder/);
    fireEvent.change(input, { target: { value: 'test query' } });
    fireEvent.click(screen.getByRole('button', { name: /search.searchButton/ }));
    expect(mockRefetch).toHaveBeenCalled();
  });
});
