import { useState, useCallback } from 'react';

const STORAGE_KEY = 'rag_search_history';
const MAX_HISTORY = 20;

export interface SearchHistoryItem {
  query: string;
  useHybrid: boolean;
  timestamp: number;
}

function loadHistory(): SearchHistoryItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveHistory(items: SearchHistoryItem[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  } catch {
    // localStorage unavailable — ignore
  }
}

export function useSearchHistory() {
  const [history, setHistory] = useState<SearchHistoryItem[]>(() => loadHistory());
  const [showHistory, setShowHistory] = useState(false);

  const addQuery = useCallback((query: string, useHybrid: boolean) => {
    if (!query.trim()) return;
    setHistory(prev => {
      const filtered = prev.filter(
        item => !(item.query === query.trim() && item.useHybrid === useHybrid)
      );
      const next = [
        { query: query.trim(), useHybrid, timestamp: Date.now() },
        ...filtered,
      ].slice(0, MAX_HISTORY);
      saveHistory(next);
      return next;
    });
  }, []);

  const removeItem = useCallback((timestamp: number) => {
    setHistory(prev => {
      const next = prev.filter(item => item.timestamp !== timestamp);
      saveHistory(next);
      return next;
    });
  }, []);

  const clearHistory = useCallback(() => {
    saveHistory([]);
    setHistory([]);
  }, []);

  return { history, addQuery, removeItem, clearHistory, showHistory, setShowHistory };
}
