import { expect, test } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';
import {
  expectEnglishDate,
  expectEnglishUi,
  openEnglishPage,
  useEnglishUi,
} from './support/english-ui';

const AGENT_TURN_ID = '55555555-5555-4555-8555-555555555555';
const PAGE_SIZE = 20;
const TOTAL_DOCUMENTS = 1_234;

// 固定时区，让“最后检查”的 12 小时制断言与运行机器所在时区无关。
test.use({ timezoneId: 'UTC' });

function englishDocuments(offset: number) {
  return Array.from({ length: Math.min(PAGE_SIZE, TOTAL_DOCUMENTS - offset) }, (_, index) => {
    const id = 1000 + offset + index;
    return {
      id,
      title: `Sample Document ${offset + index + 1}`,
      content: `Content of document ${offset + index + 1}`,
      source: 'webui',
      contentHash: `hash-${id}`,
      documentType: 'TEXT',
      metadata: {},
      createdAt: '2026-08-19T01:00:00Z',
      updatedAt: '2026-08-19T01:00:00Z',
      collectionId: 1,
      collectionKey: 'sample-collection',
      collectionName: 'Sample Collection',
      chunkCount: 1,
      processingStatus: 'COMPLETED',
      embeddingFresh: true,
      enabled: true,
    };
  });
}

test.describe('English UI (en)', () => {
  test('dashboard nav and absolute time follow English conventions', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page);
    await openEnglishPage(page, '/webui/dashboard');

    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    for (const name of ['Documents', 'Collections', 'Chat', 'Search', 'Settings']) {
      await expect(page.getByRole('link', { name, exact: true })).toBeVisible();
    }
    await expect(page.getByText('Last Check', { exact: true })).toBeVisible();
    // Backend mock is fixed at 2026-08-27T12:00:00Z; in UTC the English
    // rendering is "8/27/2026, 12:00:00 PM" — month/day/year and 12-hour.
    await expectEnglishDate(page, 8, 27, 2026, {
      hour12: 12,
      minute: 0,
      second: 0,
      meridiem: 'PM',
    });
    await expect(page.locator('body')).not.toContainText('2026/8/27');
    await expect(page.locator('body')).not.toContainText('2026-08-27');
    await expectEnglishUi(page);
  });

  test('documents list, pagination, numbers, and upload hint are English', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page);
    await page.route(/\/api\/v1\/rag\/documents(?:\?.*)?$/u, async route => {
      const offset = Number(new URL(route.request().url()).searchParams.get('offset') ?? 0);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documents: englishDocuments(offset),
          total: TOTAL_DOCUMENTS,
          offset,
          limit: PAGE_SIZE,
        }),
      });
    });

    await openEnglishPage(page, '/webui/documents?page=1');

    await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Document ID' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Created' })).toBeVisible();
    await expect(page.getByText('Drop files here or click to upload (TXT, PDF, MD)')).toBeVisible();
    // Thousands separator follows the English locale: 1,234 — not 1 234 / 1.234.
    await expect(page.getByText(/Page 2 — Total Documents: 1,234/u)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Previous' })).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Next' })).toBeEnabled();
    await expect(page.getByText('Sample Document 21', { exact: true })).toBeVisible();
    await expectEnglishDate(page, 8, 19, 2026);
    await expectEnglishUi(page);
  });

  test('search empty state and hints are English', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page);
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/u, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ query: 'test query', total: 0, results: [] }),
      });
    });
    await openEnglishPage(page, '/webui/search');

    await expect(page.getByRole('heading', { name: 'Search' })).toBeVisible();
    await expect(page.getByLabel('Search query')).toHaveAttribute('placeholder', 'Search documents…');
    await expect(page.getByRole('button', { name: 'Search', exact: true })).toBeVisible();

    await page.getByLabel('Search query').fill('test query');
    await page.getByRole('button', { name: 'Search', exact: true }).click();

    await expect(page.getByText('No results found for "test query"')).toBeVisible();
    await expect(page.getByText('Try different keywords or adjust your search.')).toBeVisible();
    await expectEnglishUi(page);
  });

  test('search error state shows English failure classification and retry', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page);
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/u, route => {
      route.fulfill({
        status: 400,
        contentType: 'application/problem+json',
        body: JSON.stringify({ code: 'INVALID_QUERY', message: 'bad query' }),
      });
    });
    await openEnglishPage(page, '/webui/search');

    await page.getByLabel('Search query').fill('failing query');
    await page.getByRole('button', { name: 'Search', exact: true }).click();

    await expect(
      page.getByText('The server rejected this request. Check the input and try again.'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    await expectEnglishUi(page);
  });

  test('chat SSE stream and relative times are English', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page, {
      chatSessions: [
        { id: 'recent-session', title: 'Recent session', minutesAgo: 5 },
        { id: 'older-session', title: 'Older session', minutesAgo: 120 },
      ],
    });
    await page.route('/api/v1/rag/chat/stream', route => {
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': AGENT_TURN_ID },
        body: [
          'event: tool_start',
          'data: {"tool":"searchKnowledge","query":"tone of voice"}',
          '',
          'event: tool_result',
          'data: {"tool":"searchKnowledge","resultCount":2,"elapsedMs":8}',
          '',
          'event: content',
          'data: {"content":"According to the knowledge base, the tone of voice is restrained and clear."}',
          '',
          'event: sources',
          'data: {"sessionId":"agent-session-en","sources":[{"citationId":"S1","documentId":7,"title":"Brand Voice Guide","collectionKey":"product-manual","documentType":"TEXT"}]}',
          '',
          'event: done',
          `data: {"sessionId":"agent-session-en","status":"complete","turnId":"${AGENT_TURN_ID}","mode":"AGENT","resolvedModel":"minimax/MiniMax-M2.7"}`,
          '',
        ].join('\n'),
      });
    });

    await openEnglishPage(page, '/webui/chat');

    await expect(page.getByRole('heading', { name: 'Chat' })).toBeVisible();
    await expect(page.getByText('No messages yet. Start a conversation!')).toBeVisible();
    await expect(page.locator('textarea')).toHaveAttribute(
      'placeholder',
      'Ask anything about your documents…',
    );
    await expect(page.getByRole('button', { name: 'Send' })).toBeDisabled();
    await page.getByRole('button', { name: 'History' }).click();
    await expect(page.getByText('5m ago', { exact: true })).toBeVisible();
    await expect(page.getByText('2h ago', { exact: true })).toBeVisible();

    await page.locator('textarea').fill('What does the knowledge base say?');
    await expect(page.getByRole('button', { name: 'Send' })).toBeEnabled();
    await page.getByRole('button', { name: 'Send' }).click();

    await expect(page.getByText('Retrieval activity')).toBeVisible();
    await expect(page.getByText(/Found 2 result\(s\) \(8 ms\)/u)).toBeVisible();
    await expect(
      page.getByText('According to the knowledge base, the tone of voice is restrained and clear.'),
    ).toBeVisible();
    await expect(page.getByText('Sources:', { exact: true })).toBeVisible();
    await expect(page.getByText('Brand Voice Guide')).toBeVisible();
    await expectEnglishUi(page);
  });
});
