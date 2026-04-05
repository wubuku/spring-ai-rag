import { useState, useEffect, useRef } from 'react';
import styles from './Settings.module.css';

interface RetrievalConfig {
  vectorWeight: number;
  fulltextWeight: number;
  topK: number;
  rerankTopK: number;
}

interface CacheConfig {
  enabled: boolean;
  ttlMinutes: number;
  maxSize: number;
}

const SETTINGS_KEY = 'user_settings';

export function Settings() {
  const [activeTab, setActiveTab] = useState<'llm' | 'retrieval' | 'cache'>('llm');
  const [saved, setSaved] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  const lastSavedRef = useRef<{ retrieval: RetrievalConfig; cache: CacheConfig } | null>(null);

  const [llmConfig] = useState({
    provider: 'deepseek',
    model: 'deepseek-chat',
    apiKeyConfigured: true,
  });

  const [retrievalConfig, setRetrievalConfig] = useState<RetrievalConfig>(() => {
    try {
      const stored = localStorage.getItem(SETTINGS_KEY);
      if (stored) {
        const parsed = JSON.parse(stored);
        return {
          vectorWeight: parsed.vectorWeight ?? 0.7,
          fulltextWeight: parsed.fulltextWeight ?? 0.3,
          topK: parsed.topK ?? 10,
          rerankTopK: parsed.rerankTopK ?? 5,
        };
      }
    } catch {
      // ignore
    }
    return { vectorWeight: 0.7, fulltextWeight: 0.3, topK: 10, rerankTopK: 5 };
  });

  const [cacheConfig, setCacheConfig] = useState<CacheConfig>(() => {
    try {
      const stored = localStorage.getItem(SETTINGS_KEY);
      if (stored) {
        const parsed = JSON.parse(stored);
        return {
          enabled: parsed.enabled ?? true,
          ttlMinutes: parsed.ttlMinutes ?? 60,
          maxSize: parsed.maxSize ?? 1000,
        };
      }
    } catch {
      // ignore
    }
    return { enabled: true, ttlMinutes: 60, maxSize: 1000 };
  });

  useEffect(() => {
    const lastSaved = lastSavedRef.current;
    if (!lastSaved) return;
    const hasChanges =
      retrievalConfig.vectorWeight !== lastSaved.retrieval.vectorWeight ||
      retrievalConfig.fulltextWeight !== lastSaved.retrieval.fulltextWeight ||
      retrievalConfig.topK !== lastSaved.retrieval.topK ||
      retrievalConfig.rerankTopK !== lastSaved.retrieval.rerankTopK ||
      cacheConfig.enabled !== lastSaved.cache.enabled ||
      cacheConfig.ttlMinutes !== lastSaved.cache.ttlMinutes ||
      cacheConfig.maxSize !== lastSaved.cache.maxSize;
    setHasChanges(hasChanges);
  }, [retrievalConfig, cacheConfig]);

  const handleSave = () => {
    const settings = {
      vectorWeight: retrievalConfig.vectorWeight,
      fulltextWeight: retrievalConfig.fulltextWeight,
      topK: retrievalConfig.topK,
      rerankTopK: retrievalConfig.rerankTopK,
      enabled: cacheConfig.enabled,
      ttlMinutes: cacheConfig.ttlMinutes,
      maxSize: cacheConfig.maxSize,
    };
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
    lastSavedRef.current = { retrieval: retrievalConfig, cache: cacheConfig };
    setSaved(true);
    setHasChanges(false);
    setTimeout(() => setSaved(false), 2000);
  };

  const tabs = [
    { id: 'llm' as const, label: 'LLM Provider' },
    { id: 'retrieval' as const, label: 'Retrieval' },
    { id: 'cache' as const, label: 'Cache' },
  ];

  return (
    <div className={styles.container}>
      <h1 className="page-title">Settings</h1>

      <div className={styles.tabs}>
        {tabs.map(tab => (
          <button
            key={tab.id}
            className={`${styles.tab} ${activeTab === tab.id ? styles.active : ''}`}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className={styles.content}>
        {activeTab === 'llm' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>LLM Provider Configuration</h2>
            <p className={styles.sectionDesc}>
              Configure the language model used for RAG conversations and query rewriting.
            </p>

            <div className={styles.field}>
              <label className={styles.label}>Provider</label>
              <select className={styles.select} value={llmConfig.provider} disabled>
                <option value="deepseek">DeepSeek</option>
                <option value="anthropic">Anthropic</option>
                <option value="openai">OpenAI</option>
              </select>
              <span className={styles.hint}>Currently active provider</span>
            </div>

            <div className={styles.field}>
              <label className={styles.label}>Model</label>
              <input type="text" className={styles.input} value={llmConfig.model} disabled />
            </div>

            <div className={styles.field}>
              <label className={styles.label}>API Key</label>
              <div className={styles.apiKeyStatus}>
                <span className={styles.statusDot} data-ok={llmConfig.apiKeyConfigured} />
                <span>{llmConfig.apiKeyConfigured ? 'Configured' : 'Not configured'}</span>
              </div>
              <span className={styles.hint}>
                API key is managed via environment variables for security
              </span>
            </div>
          </div>
        )}

        {activeTab === 'retrieval' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>Retrieval Configuration</h2>
            <p className={styles.sectionDesc}>
              Configure hybrid search weights and retrieval parameters.
            </p>

            <div className={styles.field}>
              <label className={styles.label}>Vector Weight</label>
              <input
                type="range"
                min="0"
                max="1"
                step="0.1"
                value={retrievalConfig.vectorWeight}
                onChange={e =>
                  setRetrievalConfig(c => ({
                    ...c,
                    vectorWeight: parseFloat(e.target.value),
                  }))
                }
                className={styles.slider}
              />
              <span className={styles.value}>{retrievalConfig.vectorWeight}</span>
            </div>

            <div className={styles.field}>
              <label className={styles.label}>Full-text Weight</label>
              <input
                type="range"
                min="0"
                max="1"
                step="0.1"
                value={retrievalConfig.fulltextWeight}
                onChange={e =>
                  setRetrievalConfig(c => ({
                    ...c,
                    fulltextWeight: parseFloat(e.target.value),
                  }))
                }
                className={styles.slider}
              />
              <span className={styles.value}>{retrievalConfig.fulltextWeight}</span>
            </div>

            <div className={styles.field}>
              <label className={styles.label}>Top K (results to retrieve)</label>
              <input
                type="number"
                className={styles.input}
                value={retrievalConfig.topK}
                onChange={e =>
                  setRetrievalConfig(c => ({
                    ...c,
                    topK: parseInt(e.target.value) || 10,
                  }))
                }
                min="1"
                max="100"
              />
            </div>

            <div className={styles.field}>
              <label className={styles.label}>Rerank Top K</label>
              <input
                type="number"
                className={styles.input}
                value={retrievalConfig.rerankTopK}
                onChange={e =>
                  setRetrievalConfig(c => ({
                    ...c,
                    rerankTopK: parseInt(e.target.value) || 5,
                  }))
                }
                min="1"
                max="50"
              />
              <span className={styles.hint}>Number of results to include after reranking</span>
            </div>
          </div>
        )}

        {activeTab === 'cache' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>Cache Configuration</h2>
            <p className={styles.sectionDesc}>Configure embedding and query result caching.</p>

            <div className={styles.field}>
              <label className={styles.checkboxLabel}>
                <input
                  type="checkbox"
                  checked={cacheConfig.enabled}
                  onChange={e => setCacheConfig(c => ({ ...c, enabled: e.target.checked }))}
                  className={styles.checkbox}
                />
                <span>Enable caching</span>
              </label>
            </div>

            <div className={styles.field}>
              <label className={styles.label}>TTL (minutes)</label>
              <input
                type="number"
                className={styles.input}
                value={cacheConfig.ttlMinutes}
                onChange={e =>
                  setCacheConfig(c => ({
                    ...c,
                    ttlMinutes: parseInt(e.target.value) || 60,
                  }))
                }
                min="1"
                max="1440"
                disabled={!cacheConfig.enabled}
              />
            </div>

            <div className={styles.field}>
              <label className={styles.label}>Max Size</label>
              <input
                type="number"
                className={styles.input}
                value={cacheConfig.maxSize}
                onChange={e =>
                  setCacheConfig(c => ({
                    ...c,
                    maxSize: parseInt(e.target.value) || 1000,
                  }))
                }
                min="10"
                max="10000"
                disabled={!cacheConfig.enabled}
              />
              <span className={styles.hint}>Maximum number of cached items</span>
            </div>
          </div>
        )}

        <div className={styles.actions}>
          <button
            onClick={handleSave}
            className={styles.saveBtn}
            disabled={!hasChanges}
          >
            {saved ? '✓ Saved!' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>
  );
}
