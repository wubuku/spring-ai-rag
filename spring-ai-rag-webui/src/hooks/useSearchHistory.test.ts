import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useSearchHistory } from './useSearchHistory';

const STORAGE_KEY = 'rag_search_history';

const fakeLocalStorage = {
  data: {} as Record<string, string>,
  getItem(key: string) {
    return this.data[key] ?? null;
  },
  setItem(key: string, value: string) {
    this.data[key] = value;
  },
  removeItem(key: string) {
    delete this.data[key];
  },
};

Object.defineProperty(window, 'localStorage', { value: fakeLocalStorage });

describe('useSearchHistory', () => {
  beforeEach(() => {
    fakeLocalStorage.data = {};
  });

  const setup = () => renderHook(() => useSearchHistory());

  it('initializes with empty history', () => {
    const { result } = setup();
    expect(result.current.history).toEqual([]);
  });

  it('loads history from localStorage', () => {
    fakeLocalStorage.data[STORAGE_KEY] = JSON.stringify([
      { query: 'test query', useHybrid: true, timestamp: 1000 },
    ]);
    const { result } = setup();
    expect(result.current.history).toHaveLength(1);
    expect(result.current.history[0].query).toBe('test query');
  });

  it('adds a query to history', async () => {
    const { result } = setup();
    act(() => {
      result.current.addQuery('hello world', true);
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(1);
    });
    expect(result.current.history[0].query).toBe('hello world');
    expect(result.current.history[0].useHybrid).toBe(true);
  });

  it('deduplicates by query + useHybrid combination', async () => {
    const { result } = setup();
    act(() => {
      result.current.addQuery('hello', true);
      result.current.addQuery('hello', true);
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(1);
    });
    expect(result.current.history[0].query).toBe('hello');
  });

  it('keeps separate entries for different useHybrid values', async () => {
    const { result } = setup();
    act(() => {
      result.current.addQuery('hello', true);
      result.current.addQuery('hello', false);
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(2);
    });
  });

  it('caps history at MAX_HISTORY items', async () => {
    const { result } = setup();
    act(() => {
      for (let i = 0; i < 25; i++) {
        result.current.addQuery(`query ${i}`, true);
      }
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(20);
    });
  });

  it('removes item by timestamp', async () => {
    const { result } = setup();

    // Add first item with useHybrid=true
    act(() => {
      result.current.addQuery('first query', true);
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(1);
    });
    const tsToRemove = result.current.history[0].timestamp;

    // Add second item in a SEPARATE act() to ensure a different timestamp.
    // React 18+ batches setState calls within the same synchronous act() block,
    // so without this separation both addQuery calls see the same prev=[] state
    // and get identical Date.now() timestamps, causing deduplication to misfire.
    await waitFor(() => {
      expect(result.current.history).toHaveLength(1);
    });
    act(() => {
      result.current.addQuery('second query', false);
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(2);
    });
    // Newest first
    expect(result.current.history[0].query).toBe('second query');
    expect(result.current.history[1].query).toBe('first query');

    // Remove first item
    act(() => {
      result.current.removeItem(tsToRemove);
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(1);
    });
    expect(result.current.history[0].query).toBe('second query');
  });

  it('clears all history', async () => {
    const { result } = setup();
    act(() => {
      result.current.addQuery('one', true);
      result.current.addQuery('two', true);
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(2);
    });
    act(() => {
      result.current.clearHistory();
    });
    await waitFor(() => {
      expect(result.current.history).toHaveLength(0);
    });
  });

  it('toggles showHistory', () => {
    const { result } = setup();
    expect(result.current.showHistory).toBe(false);
    act(() => {
      result.current.setShowHistory(true);
    });
    expect(result.current.showHistory).toBe(true);
  });
});
