/* eslint-disable react-refresh/only-export-components */
// This file intentionally co-locates useChatSessions hook with ChatSidebar component.
// The hook manages localStorage for chat sessions and is used by Chat.tsx.
// Moving to a separate file would require updating 1+ consumer import paths.

import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import styles from './ChatSidebar.module.css';
import { EmptyState, IconButton } from '../ui';
import { formatRelative } from '../../utils/time';

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
  const { t, i18n } = useTranslation();
  const { sessions, deleteSession } = useChatSessions();

  const formatTime = (timestamp: number) => formatRelative(timestamp, t, i18n.language);

  return (
    <div className={styles.sidebar}>
      <div className={styles.header}>
        <button onClick={onNewChat} className={styles.newChatBtn}>
          {t('chat.newChat')}
        </button>
      </div>
      <div className={styles.sessions}>
        {sessions.length === 0 && (
          <EmptyState>{t('chat.noHistory')}</EmptyState>
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
            <IconButton
              className={styles.deleteBtn}
              onClick={e => {
                e.stopPropagation();
                deleteSession(session.id);
              }}
              label={t('chat.deleteSession', { title: session.title })}
              variant="danger"
              size={32}
            >
              <X size={16} aria-hidden="true" />
            </IconButton>
          </div>
        ))}
      </div>
    </div>
  );
}
