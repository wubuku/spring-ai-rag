import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { modelsApi, type ModelInfo } from '../api/models';
import { getSelectedModel, saveSelectedModel } from '../utils/modelPreference';
import styles from './Settings.module.css';
import { PageHeader } from '../components/ui';
import { Tabs, tabDomIds } from '../components/ui';
import { Check } from 'lucide-react';

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

interface LlmConfig {
  provider: string;
  model: string;
}

const SETTINGS_KEY = 'user_settings';

export function Settings() {
  const { t, i18n } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const activeTab: 'llm' | 'retrieval' | 'cache' | 'language' =
    tabParam === 'retrieval' || tabParam === 'cache' || tabParam === 'language'
      ? tabParam
      : 'llm';
  const [saved, setSaved] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (savedTimerRef.current) {
        clearTimeout(savedTimerRef.current);
      }
    },
    [],
  );
  const lastSavedRef = useRef<{
    llm: LlmConfig;
    retrieval: RetrievalConfig;
    cache: CacheConfig;
  } | null>(null);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [modelsLoading, setModelsLoading] = useState(true);
  const [modelsError, setModelsError] = useState(false);
  const [llmConfig, setLlmConfig] = useState<LlmConfig>({
    provider: '',
    model: getSelectedModel(),
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
    let active = true;
    modelsApi.list()
      .then(response => {
        if (!active) return;
        const availableModels = response.data.models.filter(model => model.available);
        const preferred = getSelectedModel();
        const selected =
          availableModels.find(model => model.ref === preferred) ??
          availableModels.find(model => model.ref === response.data.defaultModel) ??
          availableModels[0];
        setModels(response.data.models);
        if (selected) {
          setLlmConfig({ provider: selected.provider, model: selected.ref });
        }
        setModelsError(false);
      })
      .catch(() => {
        if (active) setModelsError(true);
      })
      .finally(() => {
        if (active) setModelsLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!modelsLoading && !lastSavedRef.current) {
      lastSavedRef.current = {
        llm: llmConfig,
        retrieval: retrievalConfig,
        cache: cacheConfig,
      };
      setHasChanges(false);
    }
  }, [modelsLoading, llmConfig, retrievalConfig, cacheConfig]);

  useEffect(() => {
    const lastSaved = lastSavedRef.current;
    if (!lastSaved) return;
    const hasChanges =
      llmConfig.provider !== lastSaved.llm.provider ||
      llmConfig.model !== lastSaved.llm.model ||
      retrievalConfig.vectorWeight !== lastSaved.retrieval.vectorWeight ||
      retrievalConfig.fulltextWeight !== lastSaved.retrieval.fulltextWeight ||
      retrievalConfig.topK !== lastSaved.retrieval.topK ||
      retrievalConfig.rerankTopK !== lastSaved.retrieval.rerankTopK ||
      cacheConfig.enabled !== lastSaved.cache.enabled ||
      cacheConfig.ttlMinutes !== lastSaved.cache.ttlMinutes ||
      cacheConfig.maxSize !== lastSaved.cache.maxSize;
    setHasChanges(hasChanges);
  }, [llmConfig, retrievalConfig, cacheConfig]);

  const handleSave = () => {
    const settings = {
      vectorWeight: retrievalConfig.vectorWeight,
      fulltextWeight: retrievalConfig.fulltextWeight,
      topK: retrievalConfig.topK,
      rerankTopK: retrievalConfig.rerankTopK,
      enabled: cacheConfig.enabled,
      ttlMinutes: cacheConfig.ttlMinutes,
      maxSize: cacheConfig.maxSize,
      llmModel: llmConfig.model,
    };
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
    saveSelectedModel(llmConfig.model);
    lastSavedRef.current = {
      llm: llmConfig,
      retrieval: retrievalConfig,
      cache: cacheConfig,
    };
    setSaved(true);
    setHasChanges(false);
    if (savedTimerRef.current) {
      clearTimeout(savedTimerRef.current);
    }
    savedTimerRef.current = setTimeout(() => setSaved(false), 2000);
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

  const availableModels = models.filter(model => model.available);
  const providers = Array.from(
    new Map(availableModels.map(model => [
      model.provider,
      model.providerName || model.provider,
    ])).entries()
  );
  const providerModels = availableModels.filter(
    model => model.provider === llmConfig.provider
  );
  const selectedModel = availableModels.find(
    model => model.ref === llmConfig.model
  );

  return (
    <div className={styles.container}>
      <PageHeader title={t('settings.title')} description={t('settings.subtitle')} />

      {/* The tab strip comes from the shared primitive, which also supplies
          arrow-key navigation and the tab/panel id pair. The panel below is a
          large block this page already owns, so it is wired up by hand with
          the same ids instead of being moved into a render prop. */}
      <Tabs
        idPrefix="settings-tabs"
        ariaLabel={t('settings.title')}
        items={tabs}
        activeId={activeTab}
        onChange={next => setSearchParams(next === 'llm' ? {} : { tab: next })}
      />

      <div
        className={styles.content}
        role="tabpanel"
        id={tabDomIds('settings-tabs', activeTab).panelId}
        aria-labelledby={tabDomIds('settings-tabs', activeTab).tabId}
        tabIndex={0}
      >
        {activeTab === 'llm' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>{t('settings.llmProvider')}</h2>
            <p className={styles.sectionDesc}>
              {t('settings.llmProviderDesc')}
            </p>

            <div className={styles.field}>
              <label className={styles.label} htmlFor="settings-provider">{t('settings.provider')}</label>
              <select
                id="settings-provider"
                className={styles.select}
                value={llmConfig.provider}
                disabled={modelsLoading || providers.length === 0}
                data-testid="settings-provider-select"
                onChange={event => {
                  const provider = event.target.value;
                  const firstModel = availableModels.find(
                    model => model.provider === provider
                  );
                  setLlmConfig({
                    provider,
                    model: firstModel?.ref ?? '',
                  });
                }}
              >
                {providers.map(([provider, providerName]) => (
                  <option key={provider} value={provider}>
                    {providerName}
                  </option>
                ))}
              </select>
              <span className={styles.hint}>
                {modelsError
                  ? t('settings.modelsLoadError')
                  : t('settings.availableProvider')}
              </span>
            </div>

            <div className={styles.field}>
              <label className={styles.label} htmlFor="settings-model">{t('settings.model')}</label>
              <select
                id="settings-model"
                className={styles.select}
                value={llmConfig.model}
                disabled={modelsLoading || providerModels.length === 0}
                data-testid="settings-model-select"
                onChange={event => setLlmConfig(config => ({
                  ...config,
                  model: event.target.value,
                }))}
              >
                {providerModels.map(model => (
                  <option key={model.ref} value={model.ref}>
                    {model.name} ({model.modelId})
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.field}>
              {/* 状态说明，不是表单控件：label 必须指向控件才有意义。 */}
              <div className={styles.label}>{t('settings.apiKey')}</div>
              <div className={styles.apiKeyStatus}>
                <span className={styles.statusDot} data-ok={Boolean(selectedModel?.available)} />
                <span>
                  {selectedModel?.available
                    ? t('settings.apiKeyConfigured')
                    : t('settings.apiKeyNotSet')}
                </span>
              </div>
              <span className={styles.hint}>
                {t('settings.apiKeyEnvHint')}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'retrieval' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>{t('settings.retrieval')}</h2>
            <p className={styles.sectionDesc}>
              {t('settings.retrievalDesc')}
            </p>

            <div className={styles.field}>
              <label className={styles.label} htmlFor="settings-vector-weight">{t('settings.vectorWeight')}</label>
              <input
                id="settings-vector-weight"
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
              <label className={styles.label} htmlFor="settings-fulltext-weight">{t('settings.fulltextWeight')}</label>
              <input
                id="settings-fulltext-weight"
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
              <label className={styles.label} htmlFor="settings-top-k">{t('settings.topK')}</label>
              <input
                id="settings-top-k"
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
              <label className={styles.label} htmlFor="settings-rerank-top-k">{t('settings.rerankTopK')}</label>
              <input
                id="settings-rerank-top-k"
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
                {t('settings.rerankTopKHint')}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'cache' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>{t('settings.cache')}</h2>
            <p className={styles.sectionDesc}>
              {t('settings.cacheDesc')}
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
              <label className={styles.label} htmlFor="settings-ttl-minutes">{t('settings.ttlMinutes')}</label>
              <input
                id="settings-ttl-minutes"
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
              <label className={styles.label} htmlFor="settings-max-size">{t('settings.maxSize')}</label>
              <input
                id="settings-max-size"
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
                {t('settings.cacheMaxItemsHint')}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'language' && (
          <div className={styles.section}>
            <h2 className={styles.sectionTitle}>
              {t('settings.languageSection')}
            </h2>
            <p className={styles.sectionDesc}>
              {t('settings.languageSectionDesc')}
            </p>
              {/* 语言是一组按钮而不是单个控件：label 无从指向，
                  用 fieldset/legend 表达"这组选项"的语义。 */}
              <fieldset
                className={styles.field}
                style={{ border: 'none', padding: 0, margin: 0 }}
              >
                <legend className={styles.label}>
                  {t('settings.currentLanguage')}
                </legend>
                <div className={styles.languageOptions}>
                  <button
                    className={`${styles.langBtn} ${i18n.language === 'en' ? styles.langActive : ''}`}
                    onClick={() => handleLanguageChange('en')}
                  >
                    English
                  </button>
                  <button
                    className={`${styles.langBtn} ${i18n.language === 'zh-CN' ? styles.langActive : ''}`}
                    onClick={() => handleLanguageChange('zh-CN')}
                  >
                    中文
                  </button>
                </div>
              </fieldset>
          </div>
        )}

        <div className={styles.actions}>
          <button
            onClick={handleSave}
            className={styles.saveBtn}
            disabled={!hasChanges}
          >
            {saved ? (
              <>
                <Check size={16} aria-hidden="true" /> {t('settings.saved')}
              </>
            ) : (
              t('settings.save')
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
