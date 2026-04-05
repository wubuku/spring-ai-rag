import { useState, useRef, useEffect } from 'react';
import { useChatSSE } from '../hooks/useSSE';
import type { ChatSource } from '../types/api';
import styles from './Chat.module.css';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatSource[];
  isStreaming?: boolean;
}

export function Chat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState<string | undefined>();
  const bottomRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const { send, isConnected } = useChatSSE({
    onChunk: () => {
      // Content updates are handled via the streaming message detection
    },
    onSources: (sources, convId) => {
      setConversationId(convId);
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
    onDone: () => {
      setMessages(prev =>
        prev.map(msg => (msg.isStreaming ? { ...msg, isStreaming: false } : msg))
      );
    },
  });

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSend = () => {
    if (!input.trim() || isConnected) return;
    const userMsg = input.trim();
    setInput('');
    const newId = crypto.randomUUID();
    setMessages(prev => [
      ...prev,
      { id: newId, role: 'user', content: userMsg },
      { id: crypto.randomUUID(), role: 'assistant', content: '', isStreaming: true },
    ]);
    send(userMsg, undefined, conversationId);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleNewChat = () => {
    setMessages([]);
    setConversationId(undefined);
  };

  // Auto-resize textarea
  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value);
    const textarea = e.target;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 120)}px`;
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h1 className="page-title">RAG Chat</h1>
        {messages.length > 0 && (
          <button onClick={handleNewChat} className={styles.newChatBtn}>
            New Chat
          </button>
        )}
      </div>

      <div className={styles.messages}>
        {messages.length === 0 && (
          <div className={styles.emptyState}>
            <p>Ask me anything about your documents!</p>
            <p className={styles.hint}>
              I&apos;ll search through your knowledge base to find the most relevant information.
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
              {msg.isStreaming && <span className={styles.cursor}>▍</span>}
            </div>
            {msg.sources && msg.sources.length > 0 && (
              <div className={styles.sources}>
                <strong>Sources:</strong>
                {msg.sources.map((s, i) => (
                  <span key={i} className={styles.source}>
                    [{s.title} ({(s.score * 100).toFixed(0)}%)]
                  </span>
                ))}
              </div>
            )}
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      <div className={styles.inputRow}>
        <textarea
          ref={textareaRef}
          value={input}
          onChange={handleInput}
          onKeyDown={handleKeyDown}
          placeholder="Ask a question... (Enter to send, Shift+Enter for new line)"
          disabled={isConnected}
          className={styles.input}
          rows={1}
        />
        <button
          onClick={handleSend}
          disabled={isConnected || !input.trim()}
          className={styles.sendBtn}
        >
          {isConnected ? '...' : 'Send'}
        </button>
      </div>
    </div>
  );
}
