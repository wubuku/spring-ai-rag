import { useState } from 'react';
import styles from './Settings.module.css';

interface LlmConfig {
  provider: string;
  model: string;
  apiKeyConfigured: boolean;
}

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

export function Settings() {
  const [activeTab, setActiveTab] = useState<'llm' | 'retrieval' | 'cache'>('llm');
  const [saved, setSaved] = useState(false);

  // Mock data - in real app, this would come from API
  const [llmConfig] = useState<LlmConfig>({
    provider: 'deepseek',
    model: 'deepseek-chat',
    apiKeyConfigured: true,
  });

  const [retrievalConfig, setRetrievalConfig] = useState<RetrievalConfig>({
    vectorWeight: 0.7,
    fulltextWeight: 0.3,
    topK: 10,
    rerankTopK: 5,
  });

  const [cacheConfig, setCacheConfig] = useState<CacheConfig>({
    enabled: true,
    ttlMinutes: 60,
    maxSize: 1000,
  });

  const handleSave = () => {
    // In real app, this would call the API to save settings
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const tabs = [
    { id: 'llm', label: 'LLM Provider' },
    { id: 'retrieval', label: 'Retrieval' },
    { id: 'cache', label: 'Cache' },
  ] as const;

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
          <button onClick={handleSave} className={styles.saveBtn}>
            {saved ? '✓ Saved!' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>
  );
}
