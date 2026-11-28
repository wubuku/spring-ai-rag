import { expect, test } from '@playwright/test';

const NAV_ITEMS = [
  { id: 'tenants', nav: '租户目录', heading: '租户目录' },
  { id: 'credentials', nav: '凭据治理', heading: '凭据治理' },
  { id: 'quotas', nav: '配额与用量', heading: '配额与用量' },
  { id: 'knowledge-health', nav: '知识库健康', heading: '知识库健康' },
  { id: 'compliance', nav: '模式与合规', heading: '模式与隔离合规' },
  { id: 'audit-support', nav: '审计与支持', heading: '审计与只读支持' },
] as const;

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('mock-honesty-badge')).toHaveText('原型 · mock · 未接真');
});

test('六个视图均可渲染，且状态标签全部中文化', async ({ page }) => {
  for (const item of NAV_ITEMS) {
    await page.getByTestId(`nav-${item.id}`).click();
    await expect(page.getByRole('heading', { name: item.heading })).toBeVisible();
  }

  await page.getByTestId('nav-tenants').click();
  await expect(page.locator('tbody').getByText('正常', { exact: true }).first()).toBeVisible();
  await expect(page.locator('tbody').getByText('已暂停', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '知识库 / 文档 / 向量分块' })).toBeVisible();
  await expect(page.getByText('ACTIVE', { exact: true })).toHaveCount(0);

  await page.getByTestId('nav-credentials').click();
  await expect(page.locator('tbody').getByText('有效', { exact: true }).first()).toBeVisible();
  await expect(page.locator('tbody').getByText('未签发密钥', { exact: true }).first()).toBeVisible();

  await page.getByTestId('nav-quotas').click();
  await expect(page.locator('tbody').getByText('已启用限额', { exact: true }).first()).toBeVisible();
  await expect(page.locator('tbody').getByText('ENABLED', { exact: true })).toHaveCount(0);
  await expect(page.locator('tbody').getByText('UNLIMITED', { exact: true })).toHaveCount(0);

  await page.getByTestId('nav-knowledge-health').click();
  await expect(page.locator('tbody').getByText(/召回正常/).first()).toBeVisible();
  await expect(page.locator('tbody').getByText(/待压测验证/).first()).toBeVisible();

  await page.getByTestId('nav-audit-support').click();
  await expect(page.locator('tbody').getByText('新建租户', { exact: true }).first()).toBeVisible();
  await expect(page.locator('tbody').getByText('成功', { exact: true }).first()).toBeVisible();
});

test('开户返回一次性 Key，弹窗支持 Esc 关闭，租户标识实时校验', async ({ page }) => {
  await page.getByTestId('create-tenant-btn').click();
  const idInput = page.getByTestId('new-tenant-id');
  await idInput.fill('Bad_ID');
  await expect(page.getByTestId('new-tenant-id-error')).toBeVisible();
  await expect(page.getByTestId('submit-create-tenant')).toBeDisabled();

  await idInput.fill('new-tenant-e2e');
  await page.getByTestId('new-tenant-name').fill('E2E 新租户');
  await page.getByTestId('new-tenant-reason').fill('E2E 开户验证');
  await expect(page.getByTestId('new-tenant-id-error')).toHaveCount(0);
  await page.getByTestId('submit-create-tenant').click();

  await expect(page.getByText('API Key 已签发（仅本次展示明文）')).toBeVisible();
  await expect(page.locator('.break-all').first()).toContainText('rag_sk_');
  await page.keyboard.press('Escape');
  await expect(page.getByText('API Key 已签发（仅本次展示明文）')).toHaveCount(0);
});

test('默认租户禁用与软删按钮置灰', async ({ page }) => {
  const defaultRow = page.getByTestId('tenant-row-default');
  await expect(defaultRow).toBeVisible();
  await defaultRow.click();
  const dialog = page.getByRole('dialog', { name: /租户|默认/ }).first();
  await expect(dialog.getByRole('button', { name: '禁用（终态）' })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '软删租户' })).toBeDisabled();
  await page.keyboard.press('Escape');
});

test('表头排序与空态可切换', async ({ page }) => {
  const header = page.getByRole('columnheader', { name: /今日 Token 配额/ });
  await header.getByRole('button').click();
  await expect(header).toHaveAttribute('aria-sort', 'ascending');
  await header.getByRole('button').click();
  await expect(header).toHaveAttribute('aria-sort', 'descending');

  await page.getByPlaceholder('搜索租户标识或租户名称…').fill('不存在的租户-zzz');
  await expect(page.getByTestId('empty-state')).toBeVisible();
  await page.getByTestId('empty-state').getByRole('button', { name: '清除筛选' }).click();
  await expect(page.getByTestId('tenant-row-default')).toBeVisible();
});

test('顶栏调试面板可打开并注入 403 负向状态', async ({ page }) => {
  await page.getByTestId('dev-panel-toggle').click();
  await expect(page.getByTestId('dev-panel')).toBeVisible();
  await page.getByTestId('dev-fault-select').selectOption('FORBIDDEN');
  await expect(page.locator('main').getByText(/白名单|无权限|Forbidden|FORBIDDEN|模拟 403/).first()).toBeVisible();
});

test('390px 移动端零横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const item of NAV_ITEMS) {
    await page.getByTestId(`mobile-nav-${item.id}`).click();
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `${item.id} 出现横向溢出`).toBeLessThanOrEqual(1);
  }
});
