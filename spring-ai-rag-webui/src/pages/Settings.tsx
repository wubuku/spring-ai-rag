import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t, i18n } = useTranslation();
  const [activeTab, setActiveTab] = useState<'llm' | 'retrieval' | 'cache' | 'language'>('llm');
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

  const handleLanguageChange = (lang: string) => {
    i18n.changeLanguage(lang);
    localStorage.setItem('language', lang);
  };

  const tabs = [
    { id: 'llm' as const, label: t('settings.llmProvider') },
    { id: 'retrieval' as const, label: t('settings.retrieval') },
    { id: 'cache' as const, label: t('settings.cache') },
    { id: 'language' as const, label: t('settings.title').split(' ')[0] === '设置' ? '语言' : 'Language' },
  ];

  return (
    <div className={styles.container}>
      <h1 className="page-title">{t('settings.title')}</h1>

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
            <h2 className={styles.sectionTitle}>{t('settings.llmProvider')}</h2>
            <p className={styles.sectionDesc}>
              {i18n.language === 'zh-CN'
                ? '配置用于 RAG 对话和查询改写的语言模型。'
                : 'Configure the language model used for RAG conversations and query rewriting.'}
            </p>

            <div className={styles.field}>
              <label className={styles.label}>{t('settings.provider')}</label>
              <select className={styles.select} value={llmConfig.provider} disabled>
                <option value="deepseek">DeepSeek</option>
                <option value="anthropic">Anthropic</option>
                <option value="openai">OpenAI</option>
              </select>
              <span className={styles.hint}>
                {i18n.language === 'zh-CN' ? '当前激活的提供商' : 'Currently active provider'}
              </span>
            </div>

            <div className={styles.field}>
              <label className={styles.label}>{t('settings.model')}</label>
              <input type="text" className={styles.input} value={llmConfig.model} disabled />
            </div>

            <div className={styles.field}>
              <label className={styles.label}>API Key</label>
              <div className={styles.apiKeyStatus}>
                <span className={styles.statusDot} data-ok={llmConfig.apiKeyConfigured} />
                <span>
                  {llmConfig.apiKeyConfigured ? t('settings.apiKeyConfigured') : t('settings.apiKeyNotSet')}
                </span>
              </div>
              <span className={styles.hint}>
                {i18n.language === 'zh-CN'
                  ? 'API Key 通过环境变量管理以保障安全'
                  : 'API key is managed via environment variables for security'}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'retrieval' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>{t('settings.retrieval')}</h2>
            <p className={styles.sectionDesc}>
              {i18n.language === 'zh-CN'
                ? '配置混合搜索权重和检索参数。'
                : 'Configure hybrid search weights and retrieval parameters.'}
            </p>

            <div className={styles.field}>
              <label className={styles.label}>{t('settings.vectorWeight')}</label>
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
              <label className={styles.label}>{t('settings.fulltextWeight')}</label>
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
              <label className={styles.label}>{t('settings.topK')}</label>
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
              <label className={styles.label}>{t('settings.rerankTopK')}</label>
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
              <span className={styles.hint}>
                {i18n.language === 'zh-CN' ? '重排后保留的结果数量' : 'Number of results to include after reranking'}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'cache' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>{t('settings.cache')}</h2>
            <p className={styles.sectionDesc}>
              {i18n.language === 'zh-CN' ? '配置嵌入和查询结果缓存。' : 'Configure embedding and query result caching.'}
            </p>

            <div className={styles.field}>
              <label className={styles.checkboxLabel}>
                <input
                  type="checkbox"
                  checked={cacheConfig.enabled}
                  onChange={e => setCacheConfig(c => ({ ...c, enabled: e.target.checked }))}
                  className={styles.checkbox}
                />
                <span>{t('settings.enabled')}</span>
              </label>
            </div>

            <div className={styles.field}>
              <label className={styles.label}>{t('settings.ttlMinutes')}</label>
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
              <label className={styles.label}>{t('settings.maxSize')}</label>
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
              <span className={styles.hint}>
                {i18n.language === 'zh-CN' ? '缓存项最大数量' : 'Maximum number of cached items'}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'language' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>
              {i18n.language === 'zh-CN' ? '语言设置' : 'Language Settings'}
            </h2>
            <p className={styles.sectionDesc}>
              {i18n.language === 'zh-CN'
                ? '选择您偏好的界面语言。更改将立即生效。'
                : 'Choose your preferred interface language. Changes take effect immediately.'}
            </p>
            <div className={styles.field}>
              <label className={styles.label}>
                {i18n.language === 'zh-CN' ? '当前语言' : 'Current Language'}
              </label>
              <div className={styles.languageOptions}>
                <button
                  className={`${styles.langBtn} ${i18n.language === 'en' ? styles.langActive : ''}`}
                  onClick={() => handleLanguageChange('en')}
                >
                  🇺🇸 English
                </button>
                <button
                  className={`${styles.langBtn} ${i18n.language === 'zh-CN' ? styles.langActive : ''}`}
                  onClick={() => handleLanguageChange('zh-CN')}
                >
                  🇨🇳 中文
                </button>
              </div>
            </div>
          </div>
        )}

        <div className={styles.actions}>
          <button
            onClick={handleSave}
            className={styles.saveBtn}
            disabled={!hasChanges}
          >
            {saved ? `✓ ${t('settings.saved')}` : t('settings.save')}
          </button>
        </div>
      </div>
    </div>
  );
}
