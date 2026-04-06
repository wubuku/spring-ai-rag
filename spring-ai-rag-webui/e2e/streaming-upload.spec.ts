import { test, expect } from '@playwright/test';

const WEBUI_BASE = 'http://localhost:8081/webui';

/**
 * Chat SSE Streaming E2E Tests
 *
 * Tests verify that the Chat page loads with streaming UI elements.
 * Full SSE streaming requires a running backend with LLM API keys.
 */
test.describe('Chat SSE Streaming', () => {
  test.beforeEach(async ({ page }) => {
    // Mock health for speed
    page.route('/api/v1/rag/health', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'UP',
          components: { database: 'UP', pgvector: 'UP', cache: 'HIT' },
          timestamp: new Date().toISOString(),
        }),
      });
    });
    // Mock history
    page.route('/api/v1/rag/chat/history*', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ sessions: [] }),
      });
    });
  });

  test('chat page loads with textarea and send button', async ({ page }) => {
    await page.goto(`${WEBUI_BASE}/chat`, { waitUntil: 'networkidle' });
    // Wait for React to load and render the chat component
    await page.waitForSelector('textarea', { timeout: 20000 });

    // Verify main UI elements
    await expect(page.locator('textarea')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
  });

  test('textarea accepts input', async ({ page }) => {
    await page.goto(`${WEBUI_BASE}/chat`, { waitUntil: 'networkidle' });
    await page.waitForSelector('textarea', { timeout: 20000 });

    const textarea = page.locator('textarea');
    await textarea.fill('What is RAG?');
    await expect(textarea).toHaveValue('What is RAG?');
  });
});

/**
 * Documents Upload E2E Tests
 */
test.describe('Documents Upload', () => {
  test.beforeEach(async ({ page }) => {
    page.route('/api/v1/rag/health', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'UP',
          components: { database: 'UP', pgvector: 'UP', cache: 'HIT' },
          timestamp: new Date().toISOString(),
        }),
      });
    });
    page.route('/api/v1/rag/documents', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { documents: [], total: 0, page: 0, size: 20 } }),
      });
    });
  });

  test('upload zone is visible on documents page', async ({ page }) => {
    await page.goto(`${WEBUI_BASE}/documents`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#file-upload', { timeout: 15000 });

    await expect(page.getByText('Drop files here or click to upload')).toBeVisible();
    await expect(page.getByText('Supports: txt, md, json, xml, html, csv, log')).toBeVisible();
  });

  test('can select a file for upload', async ({ page }) => {
    await page.goto(`${WEBUI_BASE}/documents`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#file-upload', { timeout: 15000 });

    const fileInput = page.locator('input[type="file"]');
    await expect(fileInput).toBeAttached();
  });
});
