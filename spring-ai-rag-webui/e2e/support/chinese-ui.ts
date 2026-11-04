import { expect, type Page } from '@playwright/test';
import { MOCK_ROOT_API_KEY } from '../api-mocks';

/**
 * Reusable zh-CN assertions for the mock E2E suite.
 *
 * The unit tests pin the i18n locale to `en`, so a missing zh-CN translation or a
 * formatter that falls back to the host locale (`9/6/2026, 8:00:00 AM`) stays
 * invisible there. This helper drives the real browser in zh-CN and makes the
 * three failure shapes below fail loudly instead of silently producing English.
 */

const LANGUAGE_STORAGE_KEY = 'language';
const CHAT_SESSIONS_STORAGE_KEY = 'chat_sessions';

/** A namespace-prefixed key that leaked because no translation was found. */
const I18N_KEY_LEAK = new RegExp(
  String.raw`\b(?:nav|common|unlock|dashboard|documents|collections|chat|search|files|settings|metrics|alerts|abtest|evaluation|embeddings|apiKeys|failures)\.[A-Za-z][A-Za-z0-9_]*\b`,
);

/** `9/6/2026, 8:00:00 AM` — a 12-hour clock is the host locale, not zh-CN. */
const ENGLISH_CLOCK = /\b\d{1,2}:\d{2}(?::\d{2})?\s?(?:AM|PM)\b/iu;

/** `Aug 27, 2026` — the host locale's month-day-year order. */
const ENGLISH_MONTH_DATE =
  /\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},\s+\d{4}\b/u;

/** English chrome that must never survive a zh-CN render. */
const DEFAULT_ENGLISH_UI = [
  'Loading…',
  'Page ',
  'Total Documents',
  'Last Check',
  'No messages yet',
  'Send',
  'Retry',
  'Next',
  'Previous',
  'Unlock',
  'Sources:',
  'Retrieval activity',
];

export interface ChineseUiOptions {
  /**
   * Seed the chat sidebar with deterministic sessions. Offsets are resolved in
   * the page so the relative-time assertions cannot drift with wall-clock time.
   */
  chatSessions?: Array<{ id: string; title: string; minutesAgo: number }>;
}

/** Select zh-CN and seed localStorage *before* the app boots. */
export async function useChineseUi(page: Page, options: ChineseUiOptions = {}) {
  const sessions = options.chatSessions?.map(({ id, title, minutesAgo }) => ({
    id,
    title,
    updatedAt: Date.now() - minutesAgo * 60_000,
  }));
  await page.addInitScript(
    ({ languageKey, sessionsKey, chatSessions }) => {
      localStorage.setItem(languageKey, 'zh-CN');
      if (chatSessions) localStorage.setItem(sessionsKey, JSON.stringify(chatSessions));
    },
    {
      languageKey: LANGUAGE_STORAGE_KEY,
      sessionsKey: CHAT_SESSIONS_STORAGE_KEY,
      chatSessions: sessions ?? null,
    },
  );
}

/** Open a protected page in Chinese, unlocking with the localized button. */
export async function openChinesePage(page: Page, path: string) {
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/webui\/unlock$/);
  await page.getByTestId('root-api-key').fill(MOCK_ROOT_API_KEY);
  await page.getByRole('button', { name: '解锁' }).click();
  await expect(page).not.toHaveURL(/\/webui\/unlock$/);
  await expect(page.getByText('加载中…', { exact: true })).toHaveCount(0);
}

/**
 * Assert the visible page has no untranslated key, no English date/time format,
 * and none of the English chrome phrases that mark a half-localized render.
 */
export async function expectChineseUi(
  page: Page,
  options: { forbidden?: string[] } = {},
) {
  const text = await page.locator('body').innerText();
  expect(text, '页面应至少包含中文文案').toMatch(/[\u4e00-\u9fff]/u);
  expect(text, 'i18n key 未被翻译').not.toMatch(I18N_KEY_LEAK);
  expect(text, '日期/时间落回了英文 12 小时制').not.toMatch(ENGLISH_CLOCK);
  expect(text, '日期落回了英文月-日顺序').not.toMatch(ENGLISH_MONTH_DATE);
  for (const phrase of options.forbidden ?? DEFAULT_ENGLISH_UI) {
    expect(text, `残留英文 UI 文案: ${phrase}`).not.toContain(phrase);
  }
}

/** Assert a Chinese-style absolute date (2026/8/27 or 2026年8月27日). */
export async function expectChineseDate(page: Page, year: number, month: number, day: number) {
  const text = await page.locator('body').innerText();
  const pattern = new RegExp(
    String.raw`${year}[/年]${month}[/月]${day}`,
    'u',
  );
  expect(text, `应为中文日期格式 ${year}/${month}/${day}`).toMatch(pattern);
}
