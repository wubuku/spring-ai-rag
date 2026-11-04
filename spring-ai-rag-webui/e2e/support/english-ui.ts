import { expect, type Page } from '@playwright/test';
import { MOCK_ROOT_API_KEY } from '../api-mocks';

/**
 * Reusable en assertions for the mock E2E suite (mirrors `chinese-ui.ts`).
 *
 * The unit tests pin the locale to `en`, so they can only prove the English
 * strings exist. They cannot prove that no zh-CN chrome survives, that a
 * formatter did not fall back to a year-first date, or that the clock is
 * 12-hour. This helper drives the real browser in `en` and fails loud on all
 * three shapes below.
 */

const LANGUAGE_STORAGE_KEY = 'language';
const CHAT_SESSIONS_STORAGE_KEY = 'chat_sessions';

/** A namespace-prefixed key that leaked because no translation was found. */
const I18N_KEY_LEAK = new RegExp(
  String.raw`\b(?:nav|common|unlock|dashboard|documents|collections|chat|search|files|settings|metrics|alerts|abtest|evaluation|embeddings|apiKeys|failures)\.[A-Za-z][A-Za-z0-9_]*\b`,
);

/** Any CJK character means a zh-CN translation leaked into the English UI. */
const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/u;

/** `2026/8/27` or `2026-08-27` — year-first, which `en` never renders. */
const YEAR_FIRST_DATE = /\b\d{4}[/-]\d{1,2}[/-]\d{1,2}\b/u;

/** `20:00` / `20:00:00` — a 24-hour clock, which `en` does not use. */
const TWENTY_FOUR_HOUR = /(?:^|\s)(?:1[3-9]|2[0-3]):\d{2}(?::\d{2})?(?=\s|$)/u;

/** Chinese chrome that must never survive an English render. */
const DEFAULT_CHINESE_UI = [
  '加载中…',
  '第 ',
  '文档总数',
  '最后检查',
  '暂无消息',
  '发送',
  '重试',
  '下一页',
  '上一页',
  '解锁',
  '参考资料',
  '检索过程',
  '拖拽文件到此处',
  '搜索文档内容',
];

export interface EnglishUiOptions {
  /**
   * Seed the chat sidebar with deterministic sessions. Offsets are resolved in
   * the page so the relative-time assertions cannot drift with wall-clock time.
   */
  chatSessions?: Array<{ id: string; title: string; minutesAgo: number }>;
}

/** Select `en` and seed localStorage *before* the app boots. */
export async function useEnglishUi(page: Page, options: EnglishUiOptions = {}) {
  const sessions = options.chatSessions?.map(({ id, title, minutesAgo }) => ({
    id,
    title,
    updatedAt: Date.now() - minutesAgo * 60_000,
  }));
  await page.addInitScript(
    ({ languageKey, sessionsKey, chatSessions }) => {
      localStorage.setItem(languageKey, 'en');
      if (chatSessions) localStorage.setItem(sessionsKey, JSON.stringify(chatSessions));
    },
    {
      languageKey: LANGUAGE_STORAGE_KEY,
      sessionsKey: CHAT_SESSIONS_STORAGE_KEY,
      chatSessions: sessions ?? null,
    },
  );
}

/** Open a protected page in English, unlocking with the English button. */
export async function openEnglishPage(page: Page, path: string) {
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/webui\/unlock$/);
  await page.getByTestId('root-api-key').fill(MOCK_ROOT_API_KEY);
  await page.getByRole('button', { name: 'Unlock' }).click();
  await expect(page).not.toHaveURL(/\/webui\/unlock$/);
  await expect(page.getByText('Loading…', { exact: true })).toHaveCount(0);
}

/**
 * Assert the visible page has no Chinese residue, no untranslated key, and no
 * year-first or 24-hour timestamp that marks a host-locale fallback.
 */
export async function expectEnglishUi(
  page: Page,
  options: { forbidden?: string[] } = {},
) {
  const text = await page.locator('body').innerText();
  expect(text, '页面应包含英文文案').toMatch(/\b[A-Za-z]{3,}\b/u);
  expect(text, '英文界面残留中文').not.toMatch(CJK);
  expect(text, 'i18n key 未被翻译').not.toMatch(I18N_KEY_LEAK);
  expect(text, '日期落回了年-月-日顺序').not.toMatch(YEAR_FIRST_DATE);
  expect(text, '时间落回了 24 小时制').not.toMatch(TWENTY_FOUR_HOUR);
  for (const phrase of options.forbidden ?? DEFAULT_CHINESE_UI) {
    expect(text, `残留中文 UI 文案: ${phrase}`).not.toContain(phrase);
  }
}

/**
 * Assert an `en` numeric month-day-year date (`8/27/2026`) and, optionally,
 * a 12-hour clock (`8:00:00 PM`). Pair with `test.use({ timezoneId: 'UTC' })`
 * so the wall-clock part is deterministic.
 */
export async function expectEnglishDate(
  page: Page,
  month: number,
  day: number,
  year: number,
  time?: { hour12: number; minute: number; second: number; meridiem: 'AM' | 'PM' },
) {
  const text = await page.locator('body').innerText();
  const pattern = new RegExp(
    String.raw`\b${month}/${day}/${year}\b`,
    'u',
  );
  expect(text, `应为英文月/日/年格式 ${month}/${day}/${year}`).toMatch(pattern);
  if (time) {
    const clock = new RegExp(
      String.raw`\b${time.hour12}:${String(time.minute).padStart(2, '0')}:`
      + String.raw`${String(time.second).padStart(2, '0')}\s${time.meridiem}\b`,
      'u',
    );
    expect(text, `应为英文 12 小时制 ${time.hour12}:${time.minute} ${time.meridiem}`)
      .toMatch(clock);
  }
}
