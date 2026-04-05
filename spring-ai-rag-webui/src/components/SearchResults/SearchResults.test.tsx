import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SearchResults, SearchResultItem } from './SearchResults';

describe('SearchResults', () => {
  const mockResults: SearchResultItem[] = [
    { documentId: 1, title: 'Doc One', content: 'Content of document one', score: 0.95 },
    { documentId: 2, title: 'Doc Two', content: 'Content of document two', score: 0.87 },
  ];

  it('renders empty state when no results', () => {
    render(<SearchResults results={[]} query="test" />);
    expect(screen.getByText(/No results found/i)).toBeInTheDocument();
    expect(screen.getByText(/test/)).toBeInTheDocument();
  });

  it('renders result count', () => {
    render(<SearchResults results={mockResults} query="test" />);
    expect(screen.getByText(/2 results for "test"/i)).toBeInTheDocument();
  });

  it('renders single result count correctly', () => {
    render(<SearchResults results={[mockResults[0]]} query="test" />);
    expect(screen.getByText(/1 result for "test"/i)).toBeInTheDocument();
  });

  it('renders result titles and scores', () => {
    render(<SearchResults results={mockResults} query="test" />);
    expect(screen.getByText('Doc One')).toBeInTheDocument();
    expect(screen.getByText('Doc Two')).toBeInTheDocument();
    expect(screen.getByText(/95\.0%/)).toBeInTheDocument();
    expect(screen.getByText(/87\.0%/)).toBeInTheDocument();
  });

  it('renders result content snippets', () => {
    render(<SearchResults results={mockResults} query="test" />);
    expect(screen.getByText('Content of document one')).toBeInTheDocument();
    expect(screen.getByText('Content of document two')).toBeInTheDocument();
  });
});
