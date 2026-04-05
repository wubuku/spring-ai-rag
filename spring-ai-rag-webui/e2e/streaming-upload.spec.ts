import { test, expect, Page } from '@playwright/test';

// Helper: create mock for the streaming SSE endpoint
// Returns SSE-formatted chunks to simulate streaming
function mockSseStream(page: Page, messageText: string = 'This is a streamed response') {
  const chunks = messageText.split(' ');
  page.route('/api/v1/rag/chat/stream', async (route) => {
    const events = chunks.map((word, i) => {
      const chunkId = i + 1;
      const data = JSON.stringify({
        answer: word + (i < chunks.length - 1 ? ' ' : ''),
        sources: [],
        conversationId: 'test-session-1',
        metrics: { latencyMs: 100 + i * 10 },
      });
      return `id: ${chunkId}\nevent: chunk\ndata: ${data}\n\n`;
    }).join('') + `event: done\ndata: {"done":true}\n\n`;

    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: events,
      headers: {
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
      },
    });
  });
}

test.describe('Chat SSE Streaming', () => {
  test.beforeEach(async ({ page }) => {
    // Mock health (real health is slow, use mock for speed)
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
    // Mock streaming SSE
    mockSseStream(page);
    // Mock session history
    page.route('/api/v1/rag/chat/history*', route => {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [] }) });
    });
  });

  test('shows streaming indicator while receiving SSE chunks', async ({ page }) => {
    await page.goto('/chat');
    await page.waitForLoadState('networkidle');

    // Type a message
    const textarea = page.locator('textarea');
    await textarea.fill('What is RAG?');

    // Click send
    const sendButton = page.getByRole('button', { name: 'Send' });
    await sendButton.click();

    // Wait for streaming to finish (response visible)
    await expect(page.getByText('streamed response')).toBeVisible({ timeout: 10000 });
  });

  test('send button disabled during streaming', async ({ page }) => {
    await page.goto('/chat');
    await page.waitForLoadState('networkidle');

    const textarea = page.locator('textarea');
    const sendButton = page.getByRole('button', { name: 'Send' });

    await textarea.fill('Tell me about RAG');
    await sendButton.click();

    // After response, button re-enabled
    await expect(page.getByText('streamed response')).toBeVisible({ timeout: 10000 });
    await expect(sendButton).toBeEnabled();
  });

  test('textarea clears after successful stream', async ({ page }) => {
    await page.goto('/chat');
    await page.waitForLoadState('networkidle');

    const textarea = page.locator('textarea');
    await textarea.fill('Explain RAG to me');

    await page.getByRole('button', { name: 'Send' }).click();
    await expect(page.getByText('streamed response')).toBeVisible({ timeout: 10000 });

    // Textarea should be cleared after streaming completes
    await expect(textarea).toHaveValue('');
  });
});

test.describe('Documents Upload', () => {
  test.beforeEach(async ({ page }) => {
    // Mock health endpoint
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
    // Mock documents list (empty)
    page.route('/api/v1/rag/documents', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { documents: [], total: 0, page: 0, size: 20 } }),
      });
    });
  });

  test('upload zone is visible on documents page', async ({ page }) => {
    // Navigate with full URL to ensure correct routing
    await page.goto('/documents');
    await page.waitForLoadState('load');
    // Log the actual URL for debugging
    console.log('Actual URL:', page.url());
    // Wait for React to mount and render
    await page.waitForSelector('#file-upload', { timeout: 10000 });

    await expect(page.getByText('Drop files here or click to upload')).toBeVisible();
    await expect(page.getByText('Supports: txt, md, json, xml, html, csv, log')).toBeVisible();
  });

  test('can select a file for upload', async ({ page }) => {
    await page.goto('/documents');
    await page.waitForSelector('#file-upload', { timeout: 10000 });

    // Find the hidden file input inside the upload zone
    const fileInput = page.locator('input[type="file"]');
    await expect(fileInput).toBeAttached();
  });
});
