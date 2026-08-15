import { expect, type Page } from '@playwright/test';

export const MOCK_ROOT_API_KEY = 'root_test_0123456789_abcdefghijklmnopqrstuvwxyz';
export const MOCK_BUSINESS_API_KEY = 'rag_sk_business_test_0123456789abcdefghijklmnopqrstuvwxyz';

export async function openProtectedPage(page: Page, path: string) {
  await page.goto(path, { waitUntil: 'networkidle' });
  await expect(page).toHaveURL(/\/webui\/unlock$/);
  await page.getByTestId('root-api-key').fill(MOCK_ROOT_API_KEY);
  await page.getByRole('button', { name: 'Unlock' }).click();
  await expect(page).not.toHaveURL(/\/webui\/unlock$/);
  await expect(page.getByText('Loading…', { exact: true })).toHaveCount(0);
}

// Shared API mocks for all tests
export async function mockAllApiCalls(page: Page) {
  await page.route('/api/v1/rag/auth/me', async route => {
    const credential = route.request().headers()['x-api-key'];
    if (credential === MOCK_ROOT_API_KEY) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          principalType: 'ENVIRONMENT_ROOT',
          principalId: 'environment-root',
          rootMode: true,
          capabilities: ['RAG_READ', 'RAG_WRITE', 'API_KEY_MANAGE'],
        }),
      });
      return;
    }
    if (credential === MOCK_BUSINESS_API_KEY) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          principalType: 'DATABASE_API_KEY',
          principalId: 'rag_k_business',
          rootMode: true,
          capabilities: ['RAG_READ', 'RAG_WRITE'],
        }),
      });
      return;
    }
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'UNAUTHORIZED', message: 'Invalid API Key' }),
    });
  });

  await page.route(/\/api\/v1\/rag\/api-keys(?:\/.*)?$/, async route => {
    const method = route.request().method();
    if (method === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      return;
    }
    if (method === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          keyId: 'rag_k_mock',
          rawKey: MOCK_BUSINESS_API_KEY,
          name: 'Mock Key',
          expiresAt: '2026-11-12T12:00:00',
          warning: 'Store this key securely. It will not be shown again.',
        }),
      });
      return;
    }
    await route.fulfill({ status: 204, body: '' });
  });

  // Evaluation endpoints (P0-5)
  await page.route('**/api/v1/rag/evaluation/**', async route => {
    const url = route.request().url();
    if (url.includes('/report')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ avgMrr: 0.5, avgNdcg: 0.4, totalEvaluations: 1 }) });
      return;
    }
    if (url.includes('/history') || url.includes('/feedback/history')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
      return;
    }
    if (url.includes('/feedback/stats')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ thumbsUp: 1, thumbsDown: 0 }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({}) });
  });

  // Mock health endpoint
  page.route('/api/v1/rag/health', route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        status: 'UP',
        components: {
          database: 'UP',
          pgvector: 'UP',
          cache: 'HIT',
        },
        timestamp: new Date().toISOString(),
      }),
    });
  });

  // Mock documents list
  page.route(/\/api\/v1\/rag\/documents.*/, route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        documents: [
          {
            id: 1,
            title: 'Sample Document',
            documentType: 'TEXT',
            createdAt: new Date().toISOString(),
            contentHash: 'abc123def456',
          },
        ],
        total: 1,
        page: 0,
        size: 20,
      }),
    });
  });

  // Mock collections list
  page.route(/\/api\/v1\/rag\/collections.*/, route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        collections: [
          {
            id: 1,
            collectionKey: 'sample-collection',
            name: 'Sample Collection',
            embeddingModel: 'bge-m3',
            dimensions: 1024,
            documentCount: 5,
          },
        ],
        total: 1,
        page: 0,
        size: 20,
      }),
    });
  });

  page.route('**/api/v1/rag/models', route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        multiModelEnabled: true,
        defaultProvider: 'minimax',
        defaultModel: 'minimax/MiniMax-M2.7',
        availableProviders: ['minimax', 'openrouter'],
        fallbackChain: ['openrouter/xiaomi/mimo-v2-pro'],
        models: [
          {
            ref: 'minimax/MiniMax-M2.7',
            provider: 'minimax',
            providerName: 'MiniMax',
            modelId: 'MiniMax-M2.7',
            name: 'MiniMax M2.7',
            apiType: 'anthropic-messages',
            available: true,
          },
          {
            ref: 'openrouter/xiaomi/mimo-v2-pro',
            provider: 'openrouter',
            providerName: 'OpenRouter',
            modelId: 'xiaomi/mimo-v2-pro',
            name: 'MiMo V2 Pro',
            apiType: 'openai-completions',
            available: true,
          },
        ],
      }),
    });
  });

  // Mock search endpoint
  page.route('/api/v1/rag/search', route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        query: 'test',
        total: 0,
        results: [],
      }),
    });
  });

  page.route(/\/api\/v1\/rag\/files\/tree.*/, route => {
    const path = new URL(route.request().url()).searchParams.get('path') ?? '';
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        path,
        entries: path
          ? []
          : [
              {
                name: 'sample-pdf',
                path: 'sample-pdf/',
                type: 'directory',
                mimeType: null,
                size: 0,
              },
            ],
        total: path ? 0 : 1,
      }),
    });
  });

  page.route(/\/api\/v1\/rag\/files\/sample-pdf\/embed.*/, route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        documentId: 1,
        title: 'Sample PDF',
        newlyCreated: true,
        embedStatus: 'COMPLETED',
        embedMessage: null,
        chunksCreated: 3,
        uuid: 'sample-pdf',
        entryMarkdown: 'sample-pdf/document.md',
      }),
    });
  });

  // Mock metrics endpoint
  page.route('/api/v1/rag/metrics', route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        totalRequests: 42,
        avgLatencyMs: 150,
        totalRetrievals: 10,
        totalLlmCalls: 5,
      }),
    });
  });

  // Mock alerts endpoint
  page.route(/\/api\/v1\/rag\/alerts.*/, route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { alerts: [] } }),
    });
  });

  // Mock chat SSE endpoint
  page.route('/api/v1/rag/chat/stream', route => {
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: 'data: {"type":"content","content":"Test response"}\n\ndata: [DONE]\n\n',
    });
  });

  // Mock non-streaming chat endpoint
  page.route('/api/v1/rag/chat', route => {
    if (route.request().method() === 'POST') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          response: 'This is a mock RAG response from the knowledge base.',
          conversationId: 'mock-session-123',
          sources: [],
        }),
      });
    } else {
      route.continue();
    }
  });

  // Mock chat history endpoint (GET and DELETE)
  page.route(/\/api\/v1\/rag\/chat\/history\/.*/, route => {
    const method = route.request().method();
    if (method === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ messages: [] }),
      });
    } else if (method === 'DELETE') {
      route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    } else {
      route.continue();
    }
  });

}

// Evaluation mocks (P0-5)
export async function mockEvaluationApi(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/rag/evaluation/**', async route => {
    const url = route.request().url();
    if (url.includes('/report')) {
      await route.fulfill({ json: { avgMrr: 0.5, avgNdcg: 0.4, totalEvaluations: 1 } });
      return;
    }
    if (url.includes('/history')) {
      await route.fulfill({ json: [] });
      return;
    }
    if (url.includes('/feedback/stats')) {
      await route.fulfill({ json: { thumbsUp: 1, thumbsDown: 0 } });
      return;
    }
    if (url.includes('/feedback/history')) {
      await route.fulfill({ json: [] });
      return;
    }
    await route.fulfill({ json: {} });
  });
}
