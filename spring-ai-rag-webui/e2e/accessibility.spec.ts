import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

/**
 * WCAG 2.1 AA 基线扫描。
 *
 * <p>覆盖 14 个页面：13 个受保护页面 + unlock 页。只拦截 critical / serious
 * 违规；moderate / minor 作为已知基线记录在 docs/webui-accessibility-*.md。
 * 每页的违规一次性聚合断言，失败消息包含 rule id、help 与受影响节点，
 * 便于逐条修复而不是关闭规则。
 */
const PAGES: Array<{ name: string; path: string; unlock?: boolean }> = [
  { name: 'unlock', path: '/webui/unlock', unlock: true },
  { name: 'dashboard', path: '/webui/dashboard' },
  { name: 'documents', path: '/webui/documents' },
  { name: 'collections', path: '/webui/collections' },
  { name: 'chat', path: '/webui/chat' },
  { name: 'search', path: '/webui/search' },
  { name: 'metrics', path: '/webui/metrics' },
  { name: 'evaluation', path: '/webui/evaluation' },
  { name: 'embeddings', path: '/webui/embeddings' },
  { name: 'alerts', path: '/webui/alerts' },
  { name: 'abtest', path: '/webui/abtest' },
  { name: 'api-keys', path: '/webui/api-keys' },
  { name: 'files', path: '/webui/files' },
  { name: 'settings', path: '/webui/settings' },
];

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

for (const target of PAGES) {
  test(`${target.name} has zero critical/serious WCAG 2.1 AA violations`, async ({ page }) => {
    await mockAllApiCalls(page);
    if (target.unlock) {
      await page.goto(target.path, { waitUntil: 'networkidle' });
    } else {
      page = await openProtectedPage(page, target.path);
    }
    await expect(page.getByText('Loading…', { exact: true })).toHaveCount(0);

    const results = await new AxeBuilder({ page })
      .withTags(WCAG_TAGS)
      .analyze();

    const blocking = results.violations.filter(
      violation => violation.impact === 'critical' || violation.impact === 'serious',
    );

    expect(
      blocking,
      blocking
        .map(
          violation =>
            `${violation.id} (${violation.impact}): ${violation.help} → ${violation.nodes
              .slice(0, 3)
              .map(node => node.target.join(' '))
              .join(' | ')}`,
        )
        .join('\n'),
    ).toEqual([]);
  });
}
