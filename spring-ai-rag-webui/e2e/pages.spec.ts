import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Collections', () => {
  test('renders collections page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/collections', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Collections' })).toBeVisible();
  });

  test('shows collections list or empty state', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/collections', { waitUntil: 'networkidle' });
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
    await page.goto('/webui/settings', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  });

  test('shows settings tabs', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/settings', { waitUntil: 'networkidle' });
    await expect(page.getByRole('button', { name: /LLM|Provider/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Retrieval/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Cache/i })).toBeVisible();
  });

  test('switches between tabs', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/settings', { waitUntil: 'networkidle' });
    await page.getByRole('button', { name: /Retrieval/i }).click();
    await expect(page.getByText(/retrieval|top\s*k|vector|weight/i).first()).toBeVisible();
    await page.getByRole('button', { name: /Cache/i }).click();
    await expect(page.getByText(/cache|enabled|ttl/i).first()).toBeVisible();
  });

  test('shows save button', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/settings', { waitUntil: 'networkidle' });
    await expect(page.getByRole('button', { name: /save/i })).toBeVisible();
  });

  test('selects a configured provider and model', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/settings', { waitUntil: 'networkidle' });

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
    await page.goto('/webui/metrics', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Metrics' })).toBeVisible();
  });

  test('shows loading or metrics content', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/metrics', { waitUntil: 'networkidle' });
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
});

test.describe('Alerts', () => {
  test('renders alerts page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/alerts', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Alerts' })).toBeVisible();
  });
});
