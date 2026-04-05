import type { Page } from '@playwright/test';

// Shared API mocks for all tests
export function mockAllApiCalls(page: Page) {
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
        data: {
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
        },
      }),
    });
  });

  // Mock collections list
  page.route(/\/api\/v1\/rag\/collections.*/, route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          collections: [
            {
              id: 1,
              name: 'Sample Collection',
              embeddingModel: 'bge-m3',
              dimensions: 1024,
              documentCount: 5,
            },
          ],
          total: 1,
          page: 0,
          size: 20,
        },
      }),
    });
  });

  // Mock search endpoint
  page.route('/api/v1/rag/search', route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          query: 'test',
          total: 0,
          results: [],
        },
      }),
    });
  });

  // Mock metrics endpoint
  page.route('/api/v1/rag/metrics', route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          totalRequests: 42,
          avgLatencyMs: 150,
        },
      }),
    });
  });

  // Mock alerts endpoint
  page.route('/api/v1/rag/alerts', route => {
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

  // Default: allow any other requests to pass through (e.g. static assets)
  page.route('**', route => {
    if (
      route.request().url().startsWith('http://localhost:5173') ||
      route.request().url().startsWith('https://localhost:5173')
    ) {
      route.continue();
    } else {
      route.fulfill({ status: 200, body: '' });
    }
  });
}
