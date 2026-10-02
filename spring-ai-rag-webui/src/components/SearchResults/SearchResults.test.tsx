import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { SearchResults, SearchResultItem } from './SearchResults';

const { tSpy } = vi.hoisted(() => ({ tSpy: vi.fn((key: string) => key) }));

vi.mock('react-i18next', () => ({
  default: vi.fn(),
  useTranslation: () => ({
    t: tSpy,
    i18n: { language: 'en', changeLanguage: vi.fn() },
  }),
  initReactI18next: { type: '3rdParty' },
}));

describe('SearchResults', () => {
  const mockResults: SearchResultItem[] = [
    {
      documentId: 1,
      title: 'Doc One',
      content: 'Content of document one',
      score: 0.5,
      vectorScore: 0.95,
      fulltextScore: 0,
    },
    {
      documentId: 2,
      title: 'Doc Two',
      content: 'Content of document two',
      score: 0.45,
      vectorScore: 0.87,
      fulltextScore: 0.62,
    },
  ];

  it('renders empty state when no results', () => {
    render(<SearchResults results={[]} query="test" />);
    expect(screen.getByText('search.noResults')).toBeInTheDocument();
  });

  it('renders result count', () => {
    render(<SearchResults results={mockResults} query="test" />);
    expect(screen.getByText('search.resultsCount')).toBeInTheDocument();
  });

  it('renders single result count correctly', () => {
    render(<SearchResults results={[mockResults[0]]} query="test" />);
    expect(screen.getByText('search.resultsCount')).toBeInTheDocument();
  });

  it('passes the count and query through to the count key', () => {
    // The shared setup mock is `t: (key) => key`, so no other unit test can see
    // an interpolation value. Plural rendering is carried by
    // search.resultsCount_one/_other and covered end-to-end against the real
    // dictionary; what this pins is that the right count and query arrive.
    tSpy.mockClear();
    const { unmount } = render(<SearchResults results={[mockResults[0]]} query="alpha" />);
    expect(tSpy).toHaveBeenCalledWith('search.resultsCount', { count: 1, query: 'alpha' });
    unmount();

    tSpy.mockClear();
    render(<SearchResults results={mockResults} query="alpha" />);
    expect(tSpy).toHaveBeenCalledWith('search.resultsCount', { count: 2, query: 'alpha' });
  });

  it('renders result titles and plain-language match bases', () => {
    render(<SearchResults results={mockResults} query="test" />);
    expect(screen.getByText('Doc One')).toBeInTheDocument();
    expect(screen.getByText('Doc Two')).toBeInTheDocument();
    expect(screen.getByText('search.matchChannel.semantic')).toBeInTheDocument();
    expect(screen.getByText('search.matchChannel.hybrid')).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it('renders result content snippets', () => {
    render(<SearchResults results={mockResults} query="test" />);
    expect(screen.getByText('Content of document one')).toBeInTheDocument();
    expect(screen.getByText('Content of document two')).toBeInTheDocument();
  });

  it('does not turn a vector-only result into a misleading zero percent', () => {
    render(
      <SearchResults
        results={[{
          documentId: 19,
          title: 'Vector result',
          score: 0.5,
          vectorScore: 0.7089,
          fulltextScore: 0,
        }]}
        query="Spring AI"
      />,
    );

    expect(screen.getByText('search.matchChannel.semantic')).toBeInTheDocument();
    expect(screen.queryByText('0.0%')).not.toBeInTheDocument();
  });

  it('shows keyword matches and omits the indicator when component scores are absent', () => {
    render(
      <SearchResults
        results={[
          { documentId: 3, vectorScore: 0, fulltextScore: 0.4 },
          { documentId: 4, score: 0.5 },
        ]}
        query="keyword"
      />,
    );

    expect(screen.getByText('search.matchChannel.keyword')).toBeInTheDocument();
    expect(screen.getByText('Document 4')).toBeInTheDocument();
  });

  it('shows traceability actions only for file-backed results', () => {
    const onViewDirectory = vi.fn();
    const onViewIndexedFile = vi.fn();
    const onOpenOriginalFile = vi.fn();
    render(
      <SearchResults
        results={[
          {
            documentId: 5,
            title: 'PDF result',
            originalFilename: 'manual.pdf',
            fileDirectoryPath: 'uuid-5/',
            indexedFilePath: 'uuid-5/default.md',
            originalFilePath: 'uuid-5/original.pdf',
          },
          {
            documentId: 6,
            title: 'Ordinary result',
          },
        ]}
        query="manual"
        onViewDirectory={onViewDirectory}
        onViewIndexedFile={onViewIndexedFile}
        onOpenOriginalFile={onOpenOriginalFile}
      />,
    );

    expect(screen.getByText('manual.pdf')).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(3);

    fireEvent.click(screen.getByRole('button', {
      name: 'search.viewFileDirectory',
    }));
    fireEvent.click(screen.getByRole('button', {
      name: 'search.viewIndexedFile',
    }));
    fireEvent.click(screen.getByRole('button', {
      name: 'search.openOriginalPdf',
    }));

    expect(onViewDirectory).toHaveBeenCalledWith('uuid-5/');
    expect(onViewIndexedFile).toHaveBeenCalledWith(
      'uuid-5/',
      'uuid-5/default.md',
    );
    expect(onOpenOriginalFile).toHaveBeenCalledWith('uuid-5/original.pdf');
  });
});
