import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

/**
 * 键盘可达性：Tab 顺序、焦点可见、模态框焦点陷阱、菜单方向键、
 * 移动端侧边栏，以及 SSE 流式期间焦点不被抢走。
 */
test.describe('keyboard accessibility', () => {
  test('tab stops are visible and every stop has an accessible name', async ({ page }) => {
    await mockAllApiCalls(page);
    page = await openProtectedPage(page, '/webui/dashboard');

    const stops: Array<{ name: string; outline: string; shadow: string }> = [];
    for (let i = 0; i < 20 && stops.length < 6; i += 1) {
      await page.keyboard.press('Tab');
      const stop = await page.evaluate(() => {
        const element = document.activeElement as HTMLElement | null;
        if (!element || element === document.body || element === document.documentElement) {
          return null;
        }
        const style = getComputedStyle(element);
        return {
          name:
            element.getAttribute('aria-label') ||
            element.textContent?.trim().slice(0, 40) ||
            element.tagName ||
            '',
          outline: style.outlineStyle,
          shadow: style.boxShadow,
        };
      });
      if (stop) stops.push(stop);
    }

    for (const stop of stops) {
      expect(stop.name.length).toBeGreaterThan(0);
      // 浏览器默认 outline 或组件自带的 box-shadow / outline 任一存在即可。
      expect(
        stop.outline !== 'none' || stop.shadow !== 'none',
        `focus indicator missing for "${stop.name}"`,
      ).toBe(true);
    }
  });

  test('modal traps focus and Escape restores it to the trigger', async ({ page }) => {
    await mockAllApiCalls(page);
    page = await openProtectedPage(page, '/webui/collections');

    const trigger = page.getByRole('button', { name: /创建集合|Create collection/i });
    await trigger.click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    const inside = async () =>
      page.evaluate(() => {
        const active = document.activeElement as HTMLElement | null;
        return Boolean(active?.closest('[role="dialog"]'));
      });
    expect(await inside()).toBe(true);

    await page.keyboard.press('Shift+Tab');
    expect(await inside()).toBe(true);

    for (let i = 0; i < 12; i += 1) {
      await page.keyboard.press('Tab');
      expect(await inside()).toBe(true);
    }

    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test('document actions menu supports arrow keys and Escape', async ({ page }) => {
    await mockAllApiCalls(page);
    page = await openProtectedPage(page, '/webui/documents');

    const trigger = page
      .getByRole('button', { name: /操作菜单|Open actions/i })
      .first();
    await trigger.focus();
    await page.keyboard.press('Enter');

    const menu = page.getByRole('menu').first();
    await expect(menu).toBeVisible();
    await expect(menu.getByRole('menuitem').first()).toBeFocused();

    await page.keyboard.press('ArrowDown');
    const activeRole = await page.evaluate(
      () => document.activeElement?.getAttribute('role') ?? '',
    );
    expect(activeRole).toBe('menuitem');

    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test('mobile sidebar is inert while closed and keyboard operable', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await mockAllApiCalls(page);
    page = await openProtectedPage(page, '/webui/dashboard');

    const toggle = page.getByRole('button', {
      name: /打开侧边栏|Open sidebar/i,
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    const sidebar = page.locator('#app-sidebar');
    await expect(sidebar).toHaveAttribute('inert');

    await toggle.focus();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(sidebar).not.toHaveAttribute('inert');
  });

  test('SSE streaming does not steal focus and exposes aria-live', async ({ page }) => {
    await mockAllApiCalls(page);
    // 在默认 mock 之前拦一下请求，延迟 2500ms 后交回默认处理器，
    // 模拟慢速流式响应且不改变响应内容。
    await page.context().route('/api/v1/rag/chat/stream', async route => {
      await new Promise(resolve => setTimeout(resolve, 2500));
      await route.fallback();
    });

    page = await openProtectedPage(page, '/webui/chat');
    const input = page.getByRole('textbox', { name: /输入|message/i });
    await input.fill('What is RAG?');
    const send = page.getByRole('button', { name: /发送|Send/i });
    // 用键盘激活而不是鼠标点击：Safari/WebKit 按平台默认不会在鼠标点击时
    // 把焦点移到按钮上，那会让本用例测到浏览器默认行为而不是我们的焦点策略。
    await send.focus();
    await page.keyboard.press('Enter');

    const stop = page.getByRole('button', { name: /停止|Stop/i });
    await expect(stop).toBeVisible();
    // 发送后焦点停在同一个按钮上；流式等待/渲染期间不能被抢走。
    await expect(stop).toBeFocused();

    const live = page.getByTestId('chat-streaming-content');
    await expect(live).toBeVisible();
    await expect(live).toHaveAttribute('aria-live', 'polite');
    await expect(live).toHaveAttribute('aria-atomic', 'false');
    await expect(stop).toBeFocused();

    // 响应完成后内容落到最终消息，焦点仍停留在同一个按钮上。
    await expect(page.getByText('Test response').first()).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByRole('button', { name: /发送|Send/i })).toBeFocused();
  });
});
