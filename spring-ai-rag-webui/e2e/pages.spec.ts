import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Collections', () => {
  test('renders collections page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/collections');
    await expect(page.getByRole('heading', { name: 'Collections' })).toBeVisible();
  });

  test('shows collections list or empty state', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/collections');
    const hasGrid = await page
      .locator('[class*="grid"]')
      .isVisible()
      .catch(() => false);
    const hasLoading = await page
      .getByText('Loading collections')
      .isVisible()
      .catch(() => false);
    const hasEmpty = await page
      .getByText('No collections found')
      .isVisible()
      .catch(() => false);
    expect(hasGrid || hasLoading || hasEmpty).toBeTruthy();
  });
});

test.describe('Settings', () => {
  test('renders settings page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/settings');
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  });

  test('shows settings tabs', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/settings');
    await expect(page.getByRole('button', { name: /LLM|Provider/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Retrieval/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Cache/i })).toBeVisible();
  });

  test('switches between tabs', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/settings');
    await page.getByRole('button', { name: /Retrieval/i }).click();
    await expect(page).toHaveURL(/\/webui\/settings\?tab=retrieval$/);
    await expect(page.getByText(/retrieval|top\s*k|vector|weight/i).first()).toBeVisible();
    await page.getByRole('button', { name: /Cache/i }).click();
    await expect(page).toHaveURL(/\/webui\/settings\?tab=cache$/);
    await expect(page.getByText(/cache|enabled|ttl/i).first()).toBeVisible();

    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/settings\?tab=retrieval$/);
    await expect(page.getByText(/retrieval|top\s*k|vector|weight/i).first()).toBeVisible();
  });

  test('shows save button', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/settings');
    await expect(page.getByRole('button', { name: /save/i })).toBeVisible();
  });

  test('selects a configured provider and model', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/settings');

    const provider = page.getByTestId('settings-provider-select');
    const model = page.getByTestId('settings-model-select');
    await expect(provider).toBeEnabled();
    await provider.selectOption('openrouter');
    await model.selectOption('openrouter/xiaomi/mimo-v2-pro');

    await expect(model).toHaveValue('openrouter/xiaomi/mimo-v2-pro');
    await expect(page.getByRole('button', { name: /save/i })).toBeEnabled();
  });
});

test.describe('Metrics', () => {
  test('renders metrics page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/metrics');
    await expect(page.getByRole('heading', { name: 'Metrics' })).toBeVisible();
  });

  test('shows loading or metrics content', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/metrics');
    const hasLoading = await page
      .getByText('Loading')
      .isVisible()
      .catch(() => false);
    const hasMetrics = await page
      .getByText('Call Volume')
      .isVisible()
      .catch(() => false);
    expect(hasLoading || hasMetrics).toBeTruthy();
  });

  test('loads durable usage JSON and renders summaries and breakdowns', async ({ page }) => {
    await mockAllApiCalls(page);
    const usageResponsePromise = page.waitForResponse(response =>
      response.url().endsWith('/api/v1/rag/usage')
      && response.request().method() === 'GET');

    await openProtectedPage(page, '/webui/metrics');

    const usageResponse = await usageResponsePromise;
    expect(usageResponse.status()).toBe(200);
    const usage = await usageResponse.json();
    expect(usage.scope.type).toBe('SELF');
    expect(usage.totals.invocationCount).toBe(5);
    expect(usage.costs[0].unit).toBe('USD_ESTIMATE');

    await expect(page.getByRole('heading', { name: 'Durable model usage' })).toBeVisible();
    await expect(page.getByText('5', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('mock/model', { exact: true })).toBeVisible();
    await expect(page.getByText('USD_ESTIMATE', { exact: true })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Models' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Purposes' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Modes' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'UTC day' })).toBeVisible();
    await expect(page.getByText('Configured cost estimates are operational guidance, not provider invoices.')).toBeVisible();
  });
});

test.describe('Alerts', () => {
  test('renders alerts page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/alerts');
    await expect(page.getByRole('heading', { name: 'Alerts' })).toBeVisible();
  });

  test('restores the selected alerts tab with browser navigation', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/alerts');

    await page.getByRole('button', { name: 'SLO Config', exact: true }).click();
    await expect(page).toHaveURL(/\/webui\/alerts\?tab=slo-configs$/);
    await page.getByRole('button', { name: 'Silence Plans' }).click();
    await expect(page).toHaveURL(/\/webui\/alerts\?tab=silence-schedules$/);

    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/alerts\?tab=slo-configs$/);
    await expect(
      page.getByRole('button', { name: 'SLO Config', exact: true }),
    ).toBeVisible();
  });
});

test.describe('Evaluation navigation', () => {
  test('restores evaluation tabs with browser navigation', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/evaluation');

    await page.getByRole('tab', { name: 'History' }).click();
    await expect(page).toHaveURL(/\/webui\/evaluation\?tab=history$/);
    await page.getByRole('tab', { name: 'Feedback' }).click();
    await expect(page).toHaveURL(/\/webui\/evaluation\?tab=feedback$/);

    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/evaluation\?tab=history$/);
    await expect(page.getByRole('tab', { name: 'History' })).toBeVisible();
  });
});

test.describe('A/B Test navigation', () => {
  test('uses an addressable experiment route and browser history', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/abtest');

    await page.getByRole('button', { name: 'Details' }).click();
    await expect(page).toHaveURL(/\/webui\/abtest\/1$/);
    await expect(page.getByText('Retrieval Ranking Trial')).toBeVisible();

    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/abtest$/);
    await expect(page.getByRole('button', { name: 'Details' })).toBeVisible();
  });
});
