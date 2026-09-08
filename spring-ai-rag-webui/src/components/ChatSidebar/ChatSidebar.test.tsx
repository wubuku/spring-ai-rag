import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useChatSessions, ChatSidebar } from './ChatSidebar';

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

describe('ChatSidebar', () => {
  beforeEach(() => {
    localStorageMock.data = {};
    vi.clearAllMocks();
  });

  it('exposes the delete action with an accessible name per session', () => {
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 's1', title: 'Session 1', updatedAt: 1234567890 },
      { id: 's2', title: 'Session 2', updatedAt: 2234567890 },
    ]);

    render(<ChatSidebar currentSessionId="s1" onSelectSession={vi.fn()} onNewChat={vi.fn()} />);

    // The i18n test mock drops interpolation, so both delete buttons share the key.
    expect(
      screen.getAllByRole('button', { name: 'chat.deleteSession' }),
    ).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'chat.newChat' })).toBeInTheDocument();
    expect(screen.queryByText('chat.noHistory')).not.toBeInTheDocument();
  });

  it('renders the empty-state message when no sessions exist', () => {
    render(<ChatSidebar currentSessionId="" onSelectSession={vi.fn()} onNewChat={vi.fn()} />);

    expect(screen.getByText('chat.noHistory')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'chat.deleteSession' })).not.toBeInTheDocument();
  });

  it('selects a session when its row button is clicked', async () => {
    const user = userEvent.setup();
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 's1', title: 'Session 1', updatedAt: 1234567890 },
      { id: 's2', title: 'Session 2', updatedAt: 2234567890 },
    ]);
    const onSelectSession = vi.fn();

    render(
      <ChatSidebar currentSessionId="" onSelectSession={onSelectSession} onNewChat={vi.fn()} />,
    );

    await user.click(screen.getByRole('button', { name: /Session 2/ }));
    expect(onSelectSession).toHaveBeenCalledWith('s2');
  });

  it('deletes a session from its delete button and persists the change', async () => {
    const user = userEvent.setup();
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 's1', title: 'Session 1', updatedAt: 1234567890 },
      { id: 's2', title: 'Session 2', updatedAt: 2234567890 },
    ]);

    render(<ChatSidebar currentSessionId="" onSelectSession={vi.fn()} onNewChat={vi.fn()} />);

    await user.click(screen.getAllByRole('button', { name: 'chat.deleteSession' })[0]);

    expect(screen.queryByText('Session 1')).not.toBeInTheDocument();
    const remaining = JSON.parse(localStorageMock.data['chat_sessions']);
    expect(remaining).toHaveLength(1);
    expect(remaining[0].id).toBe('s2');
  });
});

describe('ChatSidebar relative time rendering', () => {
  const renderSidebarWithSession = (updatedAt: number) => {
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 's-time', title: 'Timed Session', updatedAt },
    ]);
    return render(<ChatSidebar currentSessionId="" onSelectSession={vi.fn()} onNewChat={vi.fn()} />);
  };

  it('renders just-now for fresh sessions', () => {
    renderSidebarWithSession(Date.now() - 10_000);
    expect(screen.getByText('chat.timeJustNow')).toBeInTheDocument();
  });

  it('renders minutes-ago for sessions under an hour old', () => {
    renderSidebarWithSession(Date.now() - 5 * 60_000);
    expect(screen.getByText('chat.timeMinutesAgo')).toBeInTheDocument();
  });

  it('renders hours-ago for sessions under a day old', () => {
    renderSidebarWithSession(Date.now() - 3 * 3_600_000);
    expect(screen.getByText('chat.timeHoursAgo')).toBeInTheDocument();
  });

  it('falls back to the locale date for older sessions', () => {
    renderSidebarWithSession(Date.now() - 3 * 86_400_000);
    // 超过一天回退到 toLocaleDateString，不再使用相对时间 key。
    expect(screen.queryByText('chat.timeJustNow')).not.toBeInTheDocument();
    expect(screen.queryByText('chat.timeHoursAgo')).not.toBeInTheDocument();
    const time = document.querySelector('[class*="sessionTime"]');
    expect(time?.textContent?.length ?? 0).toBeGreaterThan(0);
  });
});

describe('useChatSessions corrupted storage', () => {
  it('returns empty sessions when localStorage holds unparseable JSON', () => {
    localStorageMock.data['chat_sessions'] = '{not-valid-json';

    const { result } = renderHook(() => useChatSessions());

    expect(result.current.sessions).toHaveLength(0);
  });

  it('keeps working after recovering from corrupted storage', () => {
    localStorageMock.data['chat_sessions'] = 'broken';
    const { result } = renderHook(() => useChatSessions());

    act(() => {
      result.current.addSession('s-recovered', 'Recovered Session');
    });

    expect(result.current.sessions).toHaveLength(1);
    expect(result.current.sessions[0].title).toBe('Recovered Session');
    expect(JSON.parse(localStorageMock.data['chat_sessions'])).toHaveLength(1);
  });

  it('deleteSession removes only the matching session', () => {
    localStorageMock.data['chat_sessions'] = JSON.stringify([
      { id: 'a', title: 'A', updatedAt: 1 },
      { id: 'b', title: 'B', updatedAt: 2 },
    ]);
    const { result } = renderHook(() => useChatSessions());

    act(() => {
      result.current.deleteSession('a');
    });

    expect(result.current.sessions).toHaveLength(1);
    expect(result.current.sessions[0].id).toBe('b');
  });
});
