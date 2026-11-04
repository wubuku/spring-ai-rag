import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import en from './locales/en.json';
import zhCN from './locales/zh-CN.json';

const SUPPORTED_LANGUAGES = ['en', 'zh-CN'] as const;
type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];

function isSupported(value: string | null): value is SupportedLanguage {
  return value !== null && (SUPPORTED_LANGUAGES as readonly string[]).includes(value);
}

/**
 * 与 i18next-browser-languagedetector 原先的 `localStorage → navigator`
 * 顺序一致，但只保留这个产品实际支持的两种语言。
 *
 * 不引入检测插件是为了首屏：插件本身约 6.6 KB（未压缩），而它做的事
 * 这里 20 行就能覆盖。设置页仍会主动写入 `language`，所以缓存语义不变。
 */
function detectLanguage(): SupportedLanguage {
  try {
    const stored = window.localStorage.getItem('language');
    if (isSupported(stored)) return stored;
  } catch {
    // 受限浏览器上下文可能禁用 localStorage；检测失败继续看 navigator。
  }

  const navigatorLanguage = typeof window.navigator === 'undefined'
    ? ''
    : (window.navigator.languages?.[0] ?? window.navigator.language ?? '');
  return navigatorLanguage.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en';
}

const resources = {
  en: { translation: en },
  'zh-CN': { translation: zhCN },
};

const language = detectLanguage();
try {
  window.localStorage.setItem('language', language);
} catch {
  // 与检测同理：缓存不可写不应阻止应用初始化。
}

i18n
  .use(initReactI18next)
  .init({
    resources,
    lng: language,
    fallbackLng: 'en',
    supportedLngs: SUPPORTED_LANGUAGES,
    interpolation: {
      escapeValue: false,
    },
  });

export default i18n;
