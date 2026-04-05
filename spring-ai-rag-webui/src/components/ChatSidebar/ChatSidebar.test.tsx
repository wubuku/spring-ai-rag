import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useChatSessions } from './ChatSidebar';

const localStorageMock = {
  data: {} as Record<string, string>,
  getItem: vi.fn((key: string) => localStorageMock.data[key] ?? null),
  setItem: vi.fn((key: string, value: string) => { localStorageMock.data[key] = value; }),
  removeItem: vi.fn((key: string) => { delete localStorageMock.data[key]; }),
};
Object.defineProperty(window, 'localStorage', { value: localStorageMock });

describe('useChatSessions', () => {
  beforeEach(() => {
    localStorageMock.data = {};
    vi.clearAllMocks();
  });

  it('loads sessions from localStorage on init', () => {
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 's1', title: 'Session 1', updatedAt: 1234567890 },
    ]);

    const { result } = renderHook(() => useChatSessions());
    expect(result.current.sessions).toHaveLength(1);
    expect(result.current.sessions[0].title).toBe('Session 1');
  });

  it('returns empty array when localStorage is empty', () => {
    const { result } = renderHook(() => useChatSessions());
    expect(result.current.sessions).toHaveLength(0);
  });

  it('adds new session', () => {
    const { result } = renderHook(() => useChatSessions());

    act(() => {
      result.current.addSession('s1', 'New Chat');
    });

    expect(result.current.sessions).toHaveLength(1);
    expect(result.current.sessions[0].title).toBe('New Chat');
    expect(localStorageMock.setItem).toHaveBeenCalled();
  });

  it('deletes session by id', () => {
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 's1', title: 'Session 1', updatedAt: 1234567890 },
      { id: 's2', title: 'Session 2', updatedAt: 1234567891 },
    ]);

    const { result } = renderHook(() => useChatSessions());
    expect(result.current.sessions).toHaveLength(2);

    act(() => {
      result.current.deleteSession('s1');
    });

    expect(result.current.sessions).toHaveLength(1);
    expect(result.current.sessions[0].id).toBe('s2');
  });

  it('updates session title', () => {
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 's1', title: 'Old Title', updatedAt: 1234567890 },
    ]);

    const { result } = renderHook(() => useChatSessions());

    act(() => {
      result.current.updateSession('s1', 'New Title');
    });

    expect(result.current.sessions[0].title).toBe('New Title');
  });
});
