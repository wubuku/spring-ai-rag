import { useState, useRef, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useChatSSE } from '../hooks/useSSE';
import { ChatSidebar, useChatSessions } from '../components/ChatSidebar';
import { CollectionScopeSelector } from '../components/CollectionScopeSelector';
import { chatApi } from '../api/chat';
import { evaluationApi } from '../api/evaluation';
import { modelsApi } from '../api/models';
import { getSelectedModel, saveSelectedModel } from '../utils/modelPreference';
import type { ChatMode, ChatSource, CollectionScopeMode } from '../types/api';
import type {
  ChatDoneEvent,
  ChatToolResultEvent,
  ChatToolStartEvent,
} from '../hooks/useSSE';
import {
  readWorkspaceState,
  removeWorkspaceState,
  writeWorkspaceState,
} from '../utils/workspaceState';
import { useImeComposition } from '../utils/ime';
import styles from './Chat.module.css';
import { ChevronDown, PanelLeft, ThumbsUp, ThumbsDown } from 'lucide-react';
import { IconButton, PageHeader, QueryErrorBanner } from '../components/ui';
import { useToast } from '../components/Toast';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatSource[];
  mode?: ChatMode;
  toolActivity?: ChatToolActivity[];
  isStreaming?: boolean;
}

interface ChatToolActivity {
  id: string;
  tool: string;
  query?: string;
  resultCount?: number;
  elapsedMs?: number;
  status: 'running' | 'complete';
}

function readChatContext(search: string) {
  const params = new URLSearchParams(search);
  const modeParam = params.get('mode');
  const mode: ChatMode =
    modeParam === 'AGENT' || modeParam === 'PLAIN' ? modeParam : 'KNOWLEDGE';
  const scopeParam = params.get('scopeMode');
  const scopeMode: CollectionScopeMode =
    scopeParam === 'ANY_COLLECTION' || scopeParam === 'SELECTED_COLLECTIONS'
      ? scopeParam
      : 'CALLER_VISIBLE';
  return {
    mode,
    scopeMode,
    selectedCollectionKeys: mode === 'PLAIN'
      ? []
      : params.getAll('collectionKey').filter(Boolean).sort(),
  };
}

export function Chat() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { sessionId } = useParams();
  const urlContext = useMemo(
    () => readChatContext(location.search),
    [location.search],
  );
  const [messages, setMessages] = useState<Message[]>([]);
  const conversationId = sessionId || undefined;
  const draftKey = `chat-draft:${conversationId ?? 'new'}`;
  const [input, setInput] = useState(() =>
    readWorkspaceState(draftKey, (value): value is string => typeof value === 'string') ?? '');
  const [scopeMode, setScopeMode] =
    useState<CollectionScopeMode>(urlContext.scopeMode);
  const [selectedCollectionKeys, setSelectedCollectionKeys] =
    useState<string[]>(urlContext.selectedCollectionKeys);
  const [selectedModel, setSelectedModel] = useState<string>(getSelectedModel());
  const [mode, setMode] = useState<ChatMode>(urlContext.mode);
  const [showSidebar, setShowSidebar] = useState(false);
  const [showExportMenu, setShowExportMenu] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const ime = useImeComposition();
  const activeTurnIdRef = useRef<string | undefined>(undefined);
  const lastSentMessageRef = useRef<string | undefined>(undefined);
  const skipHistoryLoadForSessionRef = useRef<string | undefined>(undefined);
  const draftKeyRef = useRef(draftKey);
  const { addSession } = useChatSessions();
  const addSessionRef = useRef(addSession);
  addSessionRef.current = addSession;
  const { showToast } = useToast();

  const { data: modelsData, isError: modelsError, refetch: refetchModels } = useQuery({
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

  const { send, isConnected, stop } = useChatSSE({
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
    onSources: (sources, nextSessionId) => {
      setMessages(prev => {
        const lastMsg = prev[prev.length - 1];
        if (lastMsg?.isStreaming) {
          return prev.map(msg => (msg.id === lastMsg.id ? { ...msg, sources } : msg));
        }
        return prev;
      });
      // Keep the in-flight turn visible until the terminal `done` event.
      // Navigating here would race the history effect and can erase fast streams.
      void nextSessionId;
    },
    onToolStart: (event: ChatToolStartEvent) => {
      setMessages(prev => {
        const lastMsg = prev[prev.length - 1];
        if (!lastMsg?.isStreaming) return prev;
        const activity: ChatToolActivity = {
          id: event.toolCallId ?? crypto.randomUUID(),
          tool: event.tool,
          query: event.query,
          status: 'running',
        };
        return prev.map(msg => msg.id === lastMsg.id
          ? { ...msg, toolActivity: [...(msg.toolActivity ?? []), activity] }
          : msg);
      });
    },
    onToolResult: (event: ChatToolResultEvent) => {
      setMessages(prev => {
        const lastMsg = prev[prev.length - 1];
        if (!lastMsg?.isStreaming) return prev;
        return prev.map(msg => {
          if (msg.id !== lastMsg.id) return msg;
          const activities = msg.toolActivity ?? [];
          const targetId = event.toolCallId
            ? event.toolCallId
            : activities.find(activity =>
                activity.tool === event.tool
                && activity.status === 'running')?.id;
          return {
            ...msg,
            toolActivity: activities.map(activity =>
              activity.id === targetId
                ? {
                    ...activity,
                    resultCount: event.resultCount,
                    elapsedMs: event.elapsedMs,
                    status: 'complete' as const,
                  }
                : activity),
          };
        });
      });
    },
    onTurnClaimed: (turnId: string) => {
      activeTurnIdRef.current = turnId;
    },
    onRetry: () => {
      setMessages(prev => prev.map(msg =>
        msg.isStreaming
          ? { ...msg, content: '', sources: undefined, toolActivity: undefined }
          : msg));
    },
    onError: (error, event) => {
      // A bounded idempotency conflict is safe to retry manually. Keep the
      // original prompt visible so the user does not have to reconstruct it.
      if (event?.status === 409 && lastSentMessageRef.current) {
        setInput(lastSentMessageRef.current);
      } else {
        lastSentMessageRef.current = undefined;
      }
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
    onDone: (event: ChatDoneEvent) => {
      setMessages(prev =>
        prev.map(msg => (msg.isStreaming ? { ...msg, isStreaming: false } : msg))
      );
      if (event.sessionId && event.sessionId !== conversationId) {
        skipHistoryLoadForSessionRef.current = event.sessionId;
        navigate(
          `/chat/${encodeURIComponent(event.sessionId)}${location.search}`,
          { replace: true },
        );
      }
      activeTurnIdRef.current = undefined;
      lastSentMessageRef.current = undefined;
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

    if (skipHistoryLoadForSessionRef.current === conversationId) {
      skipHistoryLoadForSessionRef.current = undefined;
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
            sources: record.sources,
            mode: record.mode,
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
    setMode(urlContext.mode);
    setScopeMode(urlContext.scopeMode);
    setSelectedCollectionKeys(urlContext.selectedCollectionKeys);
  }, [urlContext]);

  useEffect(() => {
    setInput(
      readWorkspaceState(draftKey, (value): value is string => typeof value === 'string') ?? '',
    );
  }, [draftKey]);

  useEffect(() => {
    if (draftKeyRef.current !== draftKey) {
      draftKeyRef.current = draftKey;
      return;
    }
    if (input) writeWorkspaceState(draftKey, input);
    else removeWorkspaceState(draftKey);
  }, [draftKey, input]);

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
        || (mode !== 'PLAIN'
          && scopeMode === 'SELECTED_COLLECTIONS'
          && selectedCollectionKeys.length === 0)) {
      return;
    }
    const userMsg = input.trim();
    setInput('');
    removeWorkspaceState(draftKey);
    lastSentMessageRef.current = userMsg;
    const newId = crypto.randomUUID();
    setMessages(prev => [
      ...prev,
      { id: newId, role: 'user', content: userMsg },
      { id: crypto.randomUUID(), role: 'assistant', content: '', isStreaming: true },
    ]);
    send({
      message: userMsg,
      sessionId: conversationId,
      model: effectiveSelectedModel || undefined,
      mode,
      collectionScopeMode: mode !== 'PLAIN' ? scopeMode : undefined,
      collectionKeys: mode !== 'PLAIN' && scopeMode === 'SELECTED_COLLECTIONS'
        ? [...selectedCollectionKeys].sort()
        : undefined,
    });
  };

  const handleStop = () => {
    stop();
    if (lastSentMessageRef.current) {
      setInput(lastSentMessageRef.current);
    }
    setMessages(prev =>
      prev.map(msg =>
        msg.isStreaming
          ? {
              ...msg,
              isStreaming: false,
              toolActivity: msg.toolActivity?.map(activity =>
                activity.status === 'running'
                  ? { ...activity, status: 'complete' as const }
                  : activity),
            }
          : msg));
  };

  const submitFeedback = async (type: 'THUMBS_UP' | 'THUMBS_DOWN', queryHint?: string) => {
    try {
      await evaluationApi.submitFeedback({
        sessionId: conversationId,
        query: queryHint,
        feedbackType: type,
      });
    } catch {
      // 点赞是用户明确表达的一个判断。请求失败时静默，界面和点之前一模一样，
      // 用户会以为"系统收到了但没什么反应"或者干脆以为自己点歪了，
      // 而这条反馈已经没了。
      showToast(t('chat.feedbackError'), 'error');
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Enter' || ime.isComposing(e)) {
      return;
    }
    if (!e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleNewChat = () => {
    navigate(`/chat${location.search}`);
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
      // 点了"导出"却什么都没发生、也没有任何解释，是最容易被当成"这个功能坏了"
      // 的一种失败——文件下载没有可见的产物可以对照。
      showToast(t('chat.exportError'), 'error');
    }
  };

  const handleSelectSession = (sessionId: string) => {
    navigate(`/chat/${encodeURIComponent(sessionId)}${location.search}`);
    setShowSidebar(false);
  };

  const updateContext = (
    nextMode: ChatMode,
    nextScopeMode: CollectionScopeMode,
    nextCollectionKeys: string[],
  ) => {
    const normalizedCollectionKeys = nextMode === 'PLAIN'
      ? []
      : [...nextCollectionKeys].sort();
    setMode(nextMode);
    setScopeMode(nextScopeMode);
    setSelectedCollectionKeys(normalizedCollectionKeys);

    const params = new URLSearchParams();
    if (nextMode !== 'KNOWLEDGE') params.set('mode', nextMode);
    if (nextMode !== 'PLAIN') {
      if (nextScopeMode !== 'CALLER_VISIBLE') {
        params.set('scopeMode', nextScopeMode);
      }
      if (nextScopeMode === 'SELECTED_COLLECTIONS') {
        normalizedCollectionKeys.forEach(key => params.append('collectionKey', key));
      }
    }
    const search = params.toString();
    navigate(
      `${location.pathname}${search ? `?${search}` : ''}`,
      { replace: true },
    );
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
        <PageHeader
          title={t('chat.title')}
          leading={
            <IconButton
              label={t('chat.history')}
              onClick={() => setShowSidebar(!showSidebar)}
              aria-expanded={showSidebar}
            >
              <PanelLeft size={18} aria-hidden="true" />
            </IconButton>
          }
          actions={
            messages.length > 0 ? (
              <>
                <div className={styles.exportWrapper}>
                <button
                  type="button"
                  onClick={() => setShowExportMenu(!showExportMenu)}
                  className={styles.exportBtn}
                  aria-haspopup="menu"
                  aria-expanded={showExportMenu}
                >
                  {t('chat.export')}
                  <ChevronDown size={14} aria-hidden="true" />
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
            ) : undefined
          } description={t('chat.subtitle')} />

        <div className={styles.messages}>
          {messages.length === 0 && (
            <div className={styles.emptyState}>
              <p>{t('chat.noMessages')}</p>
              <p className={styles.hint}>
                {t('chat.hint')}
              </p>
            </div>
          )}

          {messages.map(msg => (
            <div
              key={msg.id}
              className={`${styles.msg} ${msg.role === 'user' ? styles.user : styles.assistant}`}
            >
              <div className={styles.role}>{msg.role === 'user' ? t('common.you') : t('common.assistant')}</div>
              <div className={styles.content}>
                {msg.content}
                {msg.isStreaming && <span className={styles.cursor}>|</span>}
              </div>
              {msg.sources && msg.sources.length > 0 && (
                <div className={styles.sources}>
                  <strong>{t('chat.sources')}:</strong>
                  {msg.sources.map((s, i) => (
                    <span key={s.citationId ?? `${s.documentId}-${i}`} className={styles.source}>
                      <span className={styles.citationId}>{s.citationId ?? `[S${i + 1}]`}</span>
                      <span>{s.title ?? t('chat.untitledSource')}</span>
                      {s.collectionKey && (
                        <span className={styles.sourceMeta}> · {s.collectionKey}</span>
                      )}
                      {s.documentType && (
                        <span className={styles.sourceMeta}> · {s.documentType}</span>
                      )}
                    </span>
                  ))}
                </div>
              )}
              {msg.toolActivity && msg.toolActivity.length > 0 && (
                <div className={styles.toolActivity} aria-label={t('chat.toolActivity')}>
                  <strong>{t('chat.toolActivity')}:</strong>
                  {msg.toolActivity.map(activity => (
                    <span key={activity.id} className={styles.toolItem}>
                      {activity.status === 'running'
                        ? t('chat.toolSearching')
                        : t('chat.toolFinished', {
                            count: activity.resultCount ?? 0,
                            ms: activity.elapsedMs ?? 0,
                          })}
                      {activity.query ? `: ${activity.query}` : ''}
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
                    aria-label={t('evaluation.thumbsUp')}
                    onClick={() => {
                      const prevUser = [...messages].reverse().find(m => m.role === 'user');
                      submitFeedback('THUMBS_UP', prevUser?.content);
                    }}
                  >
                    <ThumbsUp size={16} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className={styles.feedbackBtn}
                    title={t('evaluation.thumbsDown')}
                    aria-label={t('evaluation.thumbsDown')}
                    onClick={() => {
                      const prevUser = [...messages].reverse().find(m => m.role === 'user');
                      submitFeedback('THUMBS_DOWN', prevUser?.content);
                    }}
                  >
                    <ThumbsDown size={16} aria-hidden="true" />
                  </button>
                </div>
              )}
            </div>
          ))}
          <div ref={bottomRef} />
        </div>

        <div className={styles.composer}>
          {/* 失败时 availableModels 是空数组，模型下拉框会静默地变成禁用
              且没有任何说明：用户看到的是一个灰掉的控件，分不清是"没配
              模型"还是"模型列表没加载出来"。

              横幅放在 contextRow **之外**：contextRow 是一条 align-items:
              center 的紧凑横排，塞进去会把带重试按钮的提示挤成一条细缝，
              夹在 label 和 select 之间也读着别扭。它描述的是"这一整排控件
              都不可信"，不是"某一个下拉框坏了"。 */}
          {modelsError && (
            <QueryErrorBanner
              onRetry={() => void refetchModels()}
              retryLabel={t('common.retry')}
            >
              {t('chat.modelsLoadFailed')}
            </QueryErrorBanner>
          )}
          <div className={styles.contextRow}>
            {mode !== 'PLAIN' && (
              <div className={styles.scopeControl}>
                <CollectionScopeSelector
                  idPrefix="chat"
                  mode={scopeMode}
                  selectedKeys={selectedCollectionKeys}
                  onModeChange={next => updateContext(mode, next, selectedCollectionKeys)}
                  onSelectedKeysChange={next => updateContext(mode, scopeMode, next)}
                  disabled={isConnected}
                />
              </div>
            )}
            <div className={styles.contextControl}>
              <label htmlFor="chat-mode" className={styles.contextLabel}>
                {t('chat.mode')}
              </label>
              <select
                id="chat-mode"
                className={styles.contextSelect}
                value={mode}
                onChange={event => updateContext(
                  event.target.value as ChatMode,
                  scopeMode,
                  selectedCollectionKeys,
                )}
                disabled={isConnected}
                data-testid="chat-mode-select"
              >
                <option value="KNOWLEDGE">{t('chat.modeKnowledge')}</option>
                <option
                  value="AGENT"
                  disabled={!availableModels.some(model => model.capabilities?.toolCalling === true)}
                >
                  {t('chat.modeAgent')}
                </option>
                <option value="PLAIN">{t('chat.modePlain')}</option>
              </select>
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
              onCompositionStart={ime.handleCompositionStart}
              onCompositionEnd={ime.handleCompositionEnd}
              onKeyDown={handleKeyDown}
              placeholder={t('chat.placeholder')}
              aria-label={t('chat.inputLabel')}
              disabled={isConnected}
              className={styles.input}
              rows={1}
            />
            <button
              onClick={isConnected ? handleStop : handleSend}
              disabled={
                isConnected
                  ? false
                  : !input.trim()
                    || (mode !== 'PLAIN'
                      && scopeMode === 'SELECTED_COLLECTIONS'
                      && selectedCollectionKeys.length === 0)
              }
              className={styles.sendBtn}
            >
              {isConnected ? t('chat.stop') : t('chat.send')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
