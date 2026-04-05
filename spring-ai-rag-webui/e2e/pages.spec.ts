import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Collections', () => {
  test('renders collections page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/collections');
    await expect(page.getByRole('heading', { name: 'Collections' })).toBeVisible();
  });

  test('shows collections list or empty state', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/collections');
    const hasGrid = await page.locator('[class*="grid"]').isVisible().catch(() => false);
    const hasLoading = await page.getByText('Loading collections').isVisible().catch(() => false);
    const hasEmpty = await page.getByText('No collections found').isVisible().catch(() => false);
    expect(hasGrid || hasLoading || hasEmpty).toBeTruthy();
  });
});

test.describe('Settings', () => {
  test('renders settings page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/settings');
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  });

  test('shows three tabs', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/settings');
    await expect(page.getByRole('button', { name: 'LLM Provider' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retrieval' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cache' })).toBeVisible();
  });

  test('switches between tabs', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/settings');
    await page.getByRole('button', { name: 'Retrieval' }).click();
    await expect(page.getByText('Retrieval Configuration')).toBeVisible();
    await page.getByRole('button', { name: 'Cache' }).click();
    await expect(page.getByText('Enable caching')).toBeVisible();
  });

  test('shows save button', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/settings');
    await expect(page.getByRole('button', { name: 'Save Changes' })).toBeVisible();
  });
});

test.describe('Metrics', () => {
  test('renders metrics page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/metrics');
    await expect(page.getByRole('heading', { name: 'Metrics' })).toBeVisible();
  });

  test('shows loading or metrics content', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/metrics');
    const hasLoading = await page.getByText('Loading').isVisible().catch(() => false);
    const hasPre = await page.locator('pre').isVisible().catch(() => false);
    expect(hasLoading || hasPre).toBeTruthy();
  });
});

test.describe('Alerts', () => {
  test('renders alerts page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/alerts');
    await expect(page.getByRole('heading', { name: 'Alerts' })).toBeVisible();
  });
});
