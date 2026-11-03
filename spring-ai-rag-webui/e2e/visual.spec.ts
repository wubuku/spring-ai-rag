import { test, expect, type Page } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

/**
 * 视觉回归：抓关键页面的真实外观。
 *
 * 语义测试只能证明"元素在、文案对"，改 design token、间距、换行或布局不会让它们变红。
 * 这里对 Dashboard / Documents / Chat / Collections / Files 各拍桌面与移动两档基准图。
 *
 * 判定口径：
 *  - `threshold: 0.2` 是单像素颜色容差（YIQ），用来吸收字体抗锯齿差异；
 *  - `maxDiffPixelRatio: 0.0015` 是整图像素差比例上限。1440x900 下约 1,900 像素，
 *    足以放过抗锯齿噪声，但任何一个控件位移 100x20 就会超阈值。
 *
 * 基准图按平台自动分文件名（`*-chromium-darwin.png` / `*-chromium-linux.png`），
 * 所以 macOS 本地与 Linux CI 各用各自的基准。更新流程见
 * `docs/testing-guide-zh-CN.md` 的「视觉回归基准」章节。
 */

const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
] as const;

const PAGES = [
  { key: 'dashboard', path: '/webui/dashboard', heading: 'Dashboard' },
  { key: 'documents', path: '/webui/documents', heading: 'Documents' },
  { key: 'chat', path: '/webui/chat', heading: 'Chat' },
  { key: 'collections', path: '/webui/collections', heading: 'Collections' },
  { key: 'files', path: '/webui/files', heading: 'Files' },
] as const;

/**
 * 截图前把页面收敛到确定状态：
 *  - 固定浅色主题，避免跟随宿主机 `prefers-color-scheme`；
 *  - 关闭动画与光标（`toHaveScreenshot` 自身也会处理，这里提前稳定 React 动画）；
 *  - 等字体加载完成，避免用 fallback 字体拍出"半成品"基准；
 *  - 等 React Query 的首屏骨架屏结束。
 */
async function settle(page: Page, heading: string) {
  await expect(
    page.getByRole('heading', { name: heading, exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByText('Loading…', { exact: true })).toHaveCount(0);
  await page.waitForFunction(() => document.fonts.status === 'loaded');
  // React Query 的卡片/表格在 heading 之后还会渲染一帧；给有界的收敛窗口，
  // 而不是用固定长等待（页面数据都来自 Mock，最多一个微任务批次）。
  await page.waitForTimeout(250);
}

for (const viewport of VIEWPORTS) {
  test.describe(`visual ${viewport.name}`, () => {
    for (const target of PAGES) {
      test(`${target.key} matches its visual baseline`, async ({ page }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height });
        await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
        await mockAllApiCalls(page);
        page = await openProtectedPage(page, target.path);
        await settle(page, target.heading);

        await expect(page).toHaveScreenshot(`${target.key}-${viewport.name}.png`, {
          animations: 'disabled',
          caret: 'hide',
          threshold: 0.2,
          maxDiffPixelRatio: 0.0015,
          fullPage: true,
        });
      });
    }
  });
}
