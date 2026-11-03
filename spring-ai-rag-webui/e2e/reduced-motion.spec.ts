import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

/**
 * `prefers-reduced-motion: reduce` 必须在真实浏览器里真的让持续动画停下来。
 * 只检查 CSS 文件（check-reduced-motion.mjs）证明的是声明存在，不是浏览器
 * 生效；这里用 `emulateMedia` 把系统偏好交给 Chromium，再读 computed style。
 */
async function infiniteAnimations(page: import('@playwright/test').Page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll<HTMLElement>('*'))
      .filter(element => {
        const style = getComputedStyle(element);
        if (style.display === 'none' || style.visibility === 'hidden') return false;
        if (style.animationName === 'none') return false;
        return style.animationIterationCount
          .split(',')
          .some(value => value.trim() === 'infinite');
      })
      .map(element => `${element.tagName.toLowerCase()} ${element.className}`),
  );
}

test('skeleton shimmer stops under prefers-reduced-motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await mockAllApiCalls(page);

  let release!: () => void;
  const gate = new Promise<void>(resolve => {
    release = resolve;
  });
  await page.route(/\/api\/v1\/rag\/documents.*/, async route => {
    await gate;
    await route.fallback();
  });

  await openProtectedPage(page, '/webui/documents');
  const loading = page.getByTestId('documents-loading');
  await expect(loading).toBeVisible();

  expect(await infiniteAnimations(page)).toEqual([]);

  release();
  await expect(loading).toHaveCount(0);
});

test('streaming cursor does not blink under prefers-reduced-motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await mockAllApiCalls(page);
  await page.route('/api/v1/rag/chat/stream', async route => {
    await new Promise(resolve => setTimeout(resolve, 2500));
    await route.fallback();
  });

  await openProtectedPage(page, '/webui/chat');
  await page.getByRole('textbox', { name: /输入|message/i }).fill('What is RAG?');
  await page.getByRole('button', { name: /发送|Send/i }).click();

  const cursor = page.locator('[class*="cursor"]').first();
  await expect(cursor).toBeVisible();
  const cursorStyle = await cursor.evaluate(element => {
    const style = getComputedStyle(element);
    return { name: style.animationName, count: style.animationIterationCount };
  });
  expect(cursorStyle.name).toBe('none');

  expect(await infiniteAnimations(page)).toEqual([]);
});
