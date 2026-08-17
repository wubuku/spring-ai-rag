import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useChatSSE } from '../hooks/useSSE';
import { ChatSidebar, useChatSessions } from '../components/ChatSidebar';
import { CollectionScopeSelector } from '../components/CollectionScopeSelector';
import { chatApi } from '../api/chat';
import { evaluationApi } from '../api/evaluation';
import { modelsApi } from '../api/models';
import { getSelectedModel, saveSelectedModel } from '../utils/modelPreference';
import type { ChatSource, CollectionScopeMode } from '../types/api';
import styles from './Chat.module.css';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatSource[];
  isStreaming?: boolean;
}

export function Chat() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { sessionId } = useParams();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const conversationId = sessionId || undefined;
  const [scopeMode, setScopeMode] =
    useState<CollectionScopeMode>('CALLER_VISIBLE');
  const [selectedCollectionKeys, setSelectedCollectionKeys] =
    useState<string[]>([]);
  const [selectedModel, setSelectedModel] = useState<string>(getSelectedModel());
  const [showSidebar, setShowSidebar] = useState(false);
  const [showExportMenu, setShowExportMenu] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const { addSession } = useChatSessions();
  const addSessionRef = useRef(addSession);
  addSessionRef.current = addSession;

  const { data: modelsData } = useQuery({
    queryKey: ['chat-models'],
    queryFn: async () => {
      const res = await modelsApi.list();
      return res.data;
    },
  });
  const availableModels = modelsData?.models.filter(model => model.available) ?? [];
  const effectiveSelectedModel =
    availableModels.find(model => model.ref === selectedModel)?.ref ??
    availableModels.find(model => model.ref === modelsData?.defaultModel)?.ref ??
    availableModels[0]?.ref ??
    '';

  const { send, isConnected } = useChatSSE({
    onChunk: (content: string) => {
      setMessages(prev => {
        const lastMsg = prev[prev.length - 1];
        if (lastMsg?.isStreaming) {
          return prev.map(msg =>
            msg.id === lastMsg.id
              ? { ...msg, content: msg.content + content }
              : msg
          );
        }
        return prev;
      });
    },
    onSources: sources => {
      setMessages(prev => {
        const lastMsg = prev[prev.length - 1];
        if (lastMsg?.isStreaming) {
          return prev.map(msg => (msg.id === lastMsg.id ? { ...msg, sources } : msg));
        }
        return prev;
      });
    },
    onError: error => {
      setMessages(prev => {
        const lastMsg = prev[prev.length - 1];
        if (lastMsg?.isStreaming) {
          return [
            ...prev.slice(0, -1),
            { ...lastMsg, content: `Error: ${error}`, isStreaming: false },
          ];
        }
        return prev;
      });
    },
    onDone: nextConversationId => {
      setMessages(prev =>
        prev.map(msg => (msg.isStreaming ? { ...msg, isStreaming: false } : msg))
      );
      if (nextConversationId && nextConversationId !== conversationId) {
        navigate(`/chat/${encodeURIComponent(nextConversationId)}`, { replace: true });
      }
    },
  });

  useEffect(() => {
    let active = true;
    if (!conversationId) {
      setMessages([]);
      return () => {
        active = false;
      };
    }

    chatApi.getHistory(conversationId)
      .then(response => {
        if (!active) return;
        const historyMessages = [...response.data].reverse().flatMap(record => [
          {
            id: `history-${record.id}-user`,
            role: 'user' as const,
            content: record.userMessage,
          },
          {
            id: `history-${record.id}-assistant`,
            role: 'assistant' as const,
            content: record.aiResponse,
          },
        ]);
        setMessages(historyMessages);
      })
      .catch(() => {
        if (active) setMessages([]);
      });

    return () => {
      active = false;
    };
  }, [conversationId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (conversationId && messages.length > 0) {
      const userMsg = messages.find(m => m.role === 'user');
      if (userMsg) {
        const title = userMsg.content.slice(0, 50) + (userMsg.content.length > 50 ? '...' : '');
        addSessionRef.current(conversationId, title);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId, messages.length]);

  const handleSend = () => {
    if (!input.trim()
        || isConnected
        || (scopeMode === 'SELECTED_COLLECTIONS'
          && selectedCollectionKeys.length === 0)) {
      return;
    }
    const userMsg = input.trim();
    setInput('');
    const newId = crypto.randomUUID();
    setMessages(prev => [
      ...prev,
      { id: newId, role: 'user', content: userMsg },
      { id: crypto.randomUUID(), role: 'assistant', content: '', isStreaming: true },
    ]);
    send({
      message: userMsg,
      conversationId,
      model: effectiveSelectedModel || undefined,
      collectionScopeMode: scopeMode,
      collectionKeys: scopeMode === 'SELECTED_COLLECTIONS'
        ? [...selectedCollectionKeys].sort()
        : undefined,
    });
  };

  const submitFeedback = async (type: 'THUMBS_UP' | 'THUMBS_DOWN', queryHint?: string) => {
    try {
      await evaluationApi.submitFeedback({
        sessionId: conversationId,
        query: queryHint,
        feedbackType: type,
      });
    } catch {
      // ignore feedback errors in UI
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleNewChat = () => {
    navigate('/chat');
    setShowSidebar(false);
  };

  const handleExport = async (format: 'json' | 'md') => {
    setShowExportMenu(false);
    if (!conversationId) return;
    try {
      const blob = await chatApi.exportConversation(conversationId, format);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `conversation-${conversationId}.${format}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch {
      // ignore download errors
    }
  };

  const handleSelectSession = (sessionId: string) => {
    navigate(`/chat/${encodeURIComponent(sessionId)}`);
    setShowSidebar(false);
  };

  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value);
    const textarea = e.target;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 120)}px`;
  };

  return (
    <div className={styles.layout}>
      {showSidebar && (
        <ChatSidebar
          currentSessionId={conversationId}
          onSelectSession={handleSelectSession}
          onNewChat={handleNewChat}
        />
      )}
      <div className={styles.container}>
        <div className={styles.header}>
          <div className={styles.headerLeft}>
            <button
              className={styles.sidebarToggle}
              onClick={() => setShowSidebar(!showSidebar)}
              title="Toggle history"
            >
              ☰
            </button>
            <h1 className="page-title">{t('chat.title')}</h1>
          </div>
          {messages.length > 0 && (
            <>
              <div className={styles.exportWrapper}>
                <button onClick={() => setShowExportMenu(!showExportMenu)} className={styles.exportBtn}>
                  {t('chat.export')} ▾
                </button>
                {showExportMenu && (
                  <div className={styles.exportMenu}>
                    <button onClick={() => handleExport('json')}>{t('chat.exportJson')}</button>
                    <button onClick={() => handleExport('md')}>{t('chat.exportMarkdown')}</button>
                  </div>
                )}
              </div>
              <button onClick={handleNewChat} className={styles.newChatBtn}>
                {t('chat.newChat')}
              </button>
            </>
          )}
        </div>

        <div className={styles.messages}>
          {messages.length === 0 && (
            <div className={styles.emptyState}>
              <p>{t('chat.noMessages')}</p>
              <p className={styles.hint}>
                {t('chat.hint') || 'I will search through your knowledge base to find the most relevant information.'}
              </p>
            </div>
          )}

          {messages.map(msg => (
            <div
              key={msg.id}
              className={`${styles.msg} ${msg.role === 'user' ? styles.user : styles.assistant}`}
            >
              <div className={styles.role}>{msg.role === 'user' ? 'You' : 'Assistant'}</div>
              <div className={styles.content}>
                {msg.content}
                {msg.isStreaming && <span className={styles.cursor}>|</span>}
              </div>
              {msg.sources && msg.sources.length > 0 && (
                <div className={styles.sources}>
                  <strong>{t('chat.sources')}:</strong>
                  {msg.sources.map((s, i) => (
                    <span key={i} className={styles.source}>
                      [{s.title ?? 'Document'} ({((s.score ?? 0) * 100).toFixed(0)}%)]
                    </span>
                  ))}
                </div>
              )}
              {msg.role === 'assistant' && !msg.isStreaming && msg.content && (
                <div className={styles.feedbackRow}>
                  <button
                    type="button"
                    className={styles.feedbackBtn}
                    title={t('evaluation.thumbsUp')}
                    onClick={() => {
                      const prevUser = [...messages].reverse().find(m => m.role === 'user');
                      submitFeedback('THUMBS_UP', prevUser?.content);
                    }}
                  >
                    👍
                  </button>
                  <button
                    type="button"
                    className={styles.feedbackBtn}
                    title={t('evaluation.thumbsDown')}
                    onClick={() => {
                      const prevUser = [...messages].reverse().find(m => m.role === 'user');
                      submitFeedback('THUMBS_DOWN', prevUser?.content);
                    }}
                  >
                    👎
                  </button>
                </div>
              )}
            </div>
          ))}
          <div ref={bottomRef} />
        </div>

        <div className={styles.composer}>
          <div className={styles.contextRow}>
            <div className={styles.scopeControl}>
              <CollectionScopeSelector
                idPrefix="chat"
                mode={scopeMode}
                selectedKeys={selectedCollectionKeys}
                onModeChange={setScopeMode}
                onSelectedKeysChange={setSelectedCollectionKeys}
                disabled={isConnected}
              />
            </div>
            <div className={styles.contextControl}>
              <label htmlFor="chat-model" className={styles.contextLabel}>
                {t('chat.model')}
              </label>
              <select
                id="chat-model"
                className={styles.contextSelect}
                value={effectiveSelectedModel}
                onChange={event => {
                  const modelRef = event.target.value;
                  setSelectedModel(modelRef);
                  saveSelectedModel(modelRef);
                }}
                disabled={isConnected || availableModels.length === 0}
                data-testid="chat-model-select"
              >
                {availableModels.map(model => (
                  <option key={model.ref} value={model.ref}>
                    {model.providerName}: {model.name}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className={styles.inputRow}>
            <textarea
              ref={textareaRef}
              value={input}
              onChange={handleInput}
              onKeyDown={handleKeyDown}
              placeholder={t('chat.placeholder')}
              disabled={isConnected}
              className={styles.input}
              rows={1}
            />
            <button
              onClick={handleSend}
              disabled={
                isConnected
                || !input.trim()
                || (scopeMode === 'SELECTED_COLLECTIONS'
                  && selectedCollectionKeys.length === 0)
              }
              className={styles.sendBtn}
            >
              {isConnected ? '...' : t('chat.send')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
