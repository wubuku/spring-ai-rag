/* eslint-disable react-refresh/only-export-components */
// This file intentionally co-locates useChatSessions hook with ChatSidebar component.
// The hook manages localStorage for chat sessions and is used by Chat.tsx.
// Moving to a separate file would require updating 1+ consumer import paths.

import { useState } from 'react';
import styles from './ChatSidebar.module.css';

interface ChatSession {
  id: string;
  title: string;
  updatedAt: number;
}

const STORAGE_KEY = 'chat_sessions';

interface ChatSidebarProps {
  currentSessionId?: string;
  onSelectSession: (sessionId: string) => void;
  onNewChat: () => void;
}

export function useChatSessions() {
  const [sessions, setSessions] = useState<ChatSession[]>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      return stored ? JSON.parse(stored) : [];
    } catch {
      return [];
    }
  });

  const saveSessions = (newSessions: ChatSession[]) => {
    setSessions(newSessions);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(newSessions));
  };

  const addSession = (sessionId: string, title: string) => {
    const newSession: ChatSession = {
      id: sessionId,
      title,
      updatedAt: Date.now(),
    };
    saveSessions([newSession, ...sessions.filter(s => s.id !== sessionId)]);
  };

  const updateSession = (sessionId: string, title: string) => {
    saveSessions(
      sessions.map(s =>
        s.id === sessionId ? { ...s, title, updatedAt: Date.now() } : s
      )
    );
  };

  const deleteSession = (sessionId: string) => {
    saveSessions(sessions.filter(s => s.id !== sessionId));
  };

  return { sessions, addSession, updateSession, deleteSession };
}

export function ChatSidebar({ currentSessionId, onSelectSession, onNewChat }: ChatSidebarProps) {
  const { sessions, deleteSession } = useChatSessions();

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now.getTime() - date.getTime();

    if (diff < 60000) return 'Just now';
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
    return date.toLocaleDateString();
  };

  return (
    <div className={styles.sidebar}>
      <div className={styles.header}>
        <button onClick={onNewChat} className={styles.newChatBtn}>
          + New Chat
        </button>
      </div>
      <div className={styles.sessions}>
        {sessions.length === 0 && (
          <div className={styles.empty}>No conversations yet</div>
        )}
        {sessions.map(session => (
          <div
            key={session.id}
            className={`${styles.session} ${session.id === currentSessionId ? styles.active : ''}`}
          >
            <button
              className={styles.sessionBtn}
              onClick={() => onSelectSession(session.id)}
            >
              <span className={styles.sessionTitle}>{session.title}</span>
              <span className={styles.sessionTime}>{formatTime(session.updatedAt)}</span>
            </button>
            <button
              className={styles.deleteBtn}
              onClick={e => {
                e.stopPropagation();
                deleteSession(session.id);
              }}
              title="Delete"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
