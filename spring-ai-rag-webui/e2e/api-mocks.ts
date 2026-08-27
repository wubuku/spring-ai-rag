import { expect, type Page } from '@playwright/test';

export const MOCK_ROOT_API_KEY = 'root_test_0123456789_abcdefghijklmnopqrstuvwxyz';
export const MOCK_BUSINESS_API_KEY = 'rag_sk_business_test_0123456789abcdefghijklmnopqrstuvwxyz';
export const MOCK_CHAT_TURN_ID = '22222222-2222-4222-8222-222222222222';

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
          principalRole: null,
          collectionAccessMode: 'UNRESTRICTED',
          allowedCollectionKeys: null,
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
          credentialId: 'rag_k_business_v1',
          credentialVersion: 1,
          policyVersion: 1,
          principalRole: 'NORMAL',
          collectionAccessMode: 'UNRESTRICTED',
          allowedCollectionKeys: null,
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
          capabilities: ['RAG_READ', 'RAG_WRITE'],
          warning: 'Store this key securely. It will not be shown again.',
        }),
      });
      return;
    }
    await route.fulfill({ status: 204, body: '' });
  });

  await page.route('/api/v1/rag/integration-capabilities', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        protocol: {
          name: 'spring-ai-rag-integration',
          version: '1.1',
          apiVersion: '1.0.0',
        },
        principal: {
          principalType: 'ENVIRONMENT_ROOT',
          principalRole: null,
          capabilities: ['RAG_READ', 'RAG_WRITE', 'API_KEY_MANAGE'],
          collectionAccessMode: 'UNRESTRICTED',
          allowedCollectionKeys: null,
        },
        features: {
          optional: {
            collectionPurge: true,
          },
        },
        limits: {
          collectionPurge: {
            maxDocuments: 10000,
            maxEmbeddings: 100000,
            maxVersions: 100000,
            maxDerivedRows: 250000,
            maxAffectedChatSessions: 1000,
            maxChatRows: 50000,
          },
        },
      }),
    });
  });

  await page.route('**/api/v1/rag/embedding-jobs**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [{
          id: '11111111-1111-1111-1111-111111111111',
          status: 'QUEUED',
          origin: 'API',
          documentId: 9,
          attemptCount: 0,
          maxAttempts: 3,
        }],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      }),
    });
  });
  await page.route('**/api/v1/rag/collections/embedding-readiness**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        collectionKey: 'furniture',
        enabledDocuments: 1,
        freshDocuments: 1,
        queuedDocuments: 0,
        runningDocuments: 0,
        failedDocuments: 0,
        staleOrMissingDocuments: 0,
      }),
    });
  });
  await page.route('**/api/v1/rag/retrieval-traces**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [{
          traceId: '22222222-2222-2222-2222-222222222222',
          citationStatus: 'VALID',
          outcomeCode: 'HITS',
        }],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      }),
    });
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
    if (url.includes('/suites')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ id: 'suite-1', suiteKey: 'furniture-quality', name: 'Furniture' }]),
      });
      return;
    }
    if (url.includes('/runs')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ id: 'run-1', status: 'PENDING' }),
      });
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

  // Mock document lifecycle APIs.
  page.route(/\/api\/v1\/rag\/documents.*/, async route => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname;
    const localDocument = {
      id: 2,
      title: 'Local Lifecycle Document',
      content: 'Original local content',
      source: 'webui',
      documentType: 'TEXT',
      collectionId: 1,
      collectionKey: 'sample-collection',
      collectionName: 'Sample Collection',
      createdAt: '2026-08-19T01:00:00Z',
      updatedAt: '2026-08-19T01:00:00Z',
      contentHash: 'local123def456',
      documentRevision: 4,
      processingStatus: 'COMPLETED',
      processingError: null,
      embeddingFresh: true,
      enabled: true,
      lifecycle: {
        documentState: 'ACTIVE',
        searchability: 'READY',
        localIndexStatus: 'READY',
        embeddingStatus: 'READY',
        retryable: false,
      },
    };
    const disabledDocument = {
      ...localDocument,
      id: 3,
      title: 'Disabled Lifecycle Document',
      contentHash: 'disabled123def456',
      documentRevision: 8,
      embeddingFresh: false,
      enabled: false,
      lifecycle: {
        documentState: 'DISABLED',
        searchability: 'DISABLED',
        localIndexStatus: 'DISABLED',
        embeddingStatus: 'DISABLED',
        retryable: false,
      },
    };
    const keywordOnlyDocument = {
      ...localDocument,
      id: 4,
      title: 'Keyword Only Lifecycle Document',
      contentHash: 'keyword123def456',
      documentRevision: 6,
      embeddingFresh: false,
      lifecycle: {
        documentState: 'ACTIVE',
        searchability: 'KEYWORD_ONLY',
        localIndexStatus: 'READY',
        embeddingStatus: 'FAILED',
        lastError: 'provider unavailable',
        retryable: true,
      },
    };
    const externalDocument = {
      ...localDocument,
      id: 1,
      title: 'Sample Document',
      source: 'pdf-import:sample-pdf/default.md',
      content: 'External current content',
      contentHash: 'abc123def456',
      externalId: 'cms:sample:1',
      sourceNamespace: 'cms-main',
      sourceRevision: 'etag:sample-1',
      documentRevision: 3,
      collectionKey: 'sample-collection',
      collectionName: 'Sample Collection',
      embeddingFresh: false,
      lifecycle: {
        documentState: 'ACTIVE',
        searchability: 'FAILED',
        localIndexStatus: 'FAILED',
        embeddingStatus: 'FAILED',
        lastError: 'provider unavailable',
        retryable: true,
      },
    };

    if (method === 'POST' && path === '/api/v1/rag/documents/relocate') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documentId: 1,
          sourceCollectionKey: 'sample-collection',
          targetCollectionKey: 'product-manual',
          sourceNamespace: 'cms-main',
          externalId: 'cms:sample:1',
          sourceRevision: 'etag:sample-1',
          action: 'RELOCATED',
          documentRevision: 4,
          versionNumber: 3,
          contentChanged: false,
          derivationAction: 'PRESERVED',
          lifecycle: externalDocument.lifecycle,
        }),
      });
      return;
    }

    if (method === 'POST' && path.endsWith('/embed')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documentId: 1,
          status: 'COMPLETED',
          chunks: 3,
          embeddings: 3,
        }),
      });
      return;
    }

    if (method === 'GET' && path === '/api/v1/rag/documents/2') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(localDocument),
      });
      return;
    }

    if (method === 'GET' && path === '/api/v1/rag/documents/1') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(externalDocument),
      });
      return;
    }

    if (method === 'PATCH' && path === '/api/v1/rag/documents/2') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documentId: 2,
          action: 'UPDATED',
          documentRevision: 5,
          versionNumber: 2,
          contentChanged: true,
          metadataChanged: false,
          scopeChanged: false,
          embeddingAction: 'QUEUED',
          lifecycle: {
            documentState: 'ACTIVE',
            searchability: 'INDEXING',
            localIndexStatus: 'INDEXING',
            embeddingStatus: 'INDEXING',
            retryable: false,
          },
        }),
      });
      return;
    }

    if (method === 'POST' && path === '/api/v1/rag/documents/2/disable') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documentId: 2,
          action: 'DISABLED',
          documentRevision: 5,
          lifecycle: disabledDocument.lifecycle,
        }),
      });
      return;
    }

    if (method === 'POST' && path === '/api/v1/rag/documents/3/restore') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documentId: 3,
          action: 'RESTORED',
          documentRevision: 9,
          lifecycle: {
            documentState: 'ACTIVE',
            searchability: 'INDEXING',
            localIndexStatus: 'INDEXING',
            embeddingStatus: 'INDEXING',
            retryable: false,
          },
        }),
      });
      return;
    }

    if (method === 'DELETE' && path === '/api/v1/rag/documents/2') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documentId: 2,
          deletedEmbeddings: 3,
          deletedJobs: 1,
        }),
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        documents: [
          localDocument,
          disabledDocument,
          keywordOnlyDocument,
          externalDocument,
        ],
        total: 4,
        page: 0,
        size: 20,
      }),
    });
  });

  // Mock collections list
  page.route(/\/api\/v1\/rag\/collections.*/, async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/by-key/purge/preview')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          previewId: '33333333-3333-4333-8333-333333333333',
          collectionId: 1,
          collectionKey: 'sample-collection',
          collectionVersion: 7,
          chatCommitFenceVersion: 12,
          status: 'PREVIEWED',
          documentCount: 5,
          externalDocumentCount: 2,
          localDocumentCount: 3,
          embeddingCount: 9,
          embeddingJobCount: 2,
          versionCount: 6,
          keywordChunkCount: 10,
          repairPreviewCount: 0,
          repairItemCount: 0,
          derivedRowCount: 31,
          documentIdempotencyOperationCount: 2,
          feedbackCount: 1,
          feedbackDocumentReferenceCount: 1,
          documentAuditCount: 2,
          collectionAuditCount: 1,
          relocationMarkerCount: 1,
          affectedChatSessionCount: 2,
          chatHistoryCount: 4,
          chatMemoryCount: 4,
          chatSummaryCount: 1,
          chatTurnOperationCount: 2,
          activeSyncRunCount: 0,
          activeDerivationRepairCount: 0,
          activeChatSessionCount: 0,
          unindexedChatReferenceCount: 0,
          unindexedFeedbackReferenceCount: 0,
          confirmationToken: 'mock-confirmation-token',
          fingerprint: 'mock-purge-fingerprint',
          previewExpiresAt: '2026-08-27T12:15:00Z',
          operationExpiresAt: '2026-08-27T12:30:00Z',
        }),
      });
      return;
    }
    if (path.endsWith('/by-key/purge')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          previewId: '33333333-3333-4333-8333-333333333333',
          status: 'RETIRED',
          collectionId: 1,
          collectionKey: 'sample-collection',
          purgedDocumentCount: 5,
          purgedExternalDocumentCount: 2,
          purgedLocalDocumentCount: 3,
          deletedAt: '2026-08-27T12:01:00',
          purgedAt: '2026-08-27T12:01:00',
          collectionVersion: 8,
        }),
      });
      return;
    }
    if (path.endsWith('/derivation-readiness')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          collectionKey: 'sample-collection',
          activeEmbeddingProfileKey: 'bge-m3-1024',
          enabledDocuments: 4,
          readyDocuments: 1,
          keywordOnlyDocuments: 1,
          indexingDocuments: 0,
          localUnavailableDocuments: 1,
          vectorRepairNeededDocuments: 2,
          notRequestedDocuments: 0,
          corruptDocuments: 1,
          disabledDocuments: 1,
          scannedAt: '2026-08-21T00:00:00Z',
        }),
      });
      return;
    }
    if (path.endsWith('/derivation-repairs/preview')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          repairId: '33333333-3333-3333-3333-333333333333',
          collectionKey: 'sample-collection',
          previewFingerprint: 'preview-fingerprint',
          previewToken: 'opaque-preview-token',
          expiresAt: '2026-08-21T00:15:00Z',
          items: [{
            documentId: 4,
            action: 'REBUILD_LOCAL_AND_QUEUE_VECTOR',
            reasonCode: 'LOCAL_PHYSICAL_INTEGRITY_FAILED',
          }],
          actionCounts: { REBUILD_LOCAL_AND_QUEUE_VECTOR: 1 },
          skippedDocuments: 0,
        }),
      });
      return;
    }
    if (path.endsWith('/derivation-repairs/apply')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          repairId: '33333333-3333-3333-3333-333333333333',
          collectionKey: 'sample-collection',
          status: 'COMPLETED',
          items: [{
            documentId: 4,
            action: 'REBUILD_LOCAL_AND_QUEUE_VECTOR',
            status: 'SUCCEEDED',
            localActionStatus: 'SUCCEEDED',
            vectorActionStatus: 'SUCCEEDED',
            embeddingJobId: '11111111-1111-1111-1111-111111111111',
            resultCode: 'QUEUED_VECTOR',
          }],
        }),
      });
      return;
    }
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
          {
            id: 2,
            collectionKey: 'product-manual',
            name: 'Product Manual',
            embeddingModel: 'bge-m3',
            dimensions: 1024,
            documentCount: 8,
          },
        ],
        total: 2,
        offset: 0,
        limit: 50,
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
            capabilities: {
              streaming: true,
              toolCalling: true,
            },
          },
          {
            ref: 'openrouter/xiaomi/mimo-v2-pro',
            provider: 'openrouter',
            providerName: 'OpenRouter',
            modelId: 'xiaomi/mimo-v2-pro',
            name: 'MiMo V2 Pro',
            apiType: 'openai-completions',
            available: true,
            capabilities: {
              streaming: true,
              toolCalling: true,
            },
          },
        ],
      }),
    });
  });

  // Mock search endpoint
  page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/, route => {
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
    const importedPdfEntries = [
      {
        name: 'default.md',
        path: 'sample-pdf/default.md',
        type: 'file',
        mimeType: 'text/markdown',
        size: 128,
        createdAt: '2026-08-15T09:00:00Z',
      },
      {
        name: 'original.pdf',
        path: 'sample-pdf/original.pdf',
        type: 'file',
        mimeType: 'application/pdf',
        size: 1024,
        createdAt: '2026-08-15T09:00:00Z',
      },
    ];
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        path,
        entries: path
          ? path === 'sample-pdf/' ? importedPdfEntries : []
          : [
              {
                name: 'older-pdf',
                path: 'older-pdf/',
                type: 'directory',
                mimeType: null,
                size: 0,
                createdAt: '2026-08-14T09:00:00Z',
              },
              {
                name: 'sample-pdf',
                path: 'sample-pdf/',
                type: 'directory',
                mimeType: null,
                size: 0,
                createdAt: '2026-08-15T09:00:00Z',
              },
              {
                name: 'newest-pdf',
                path: 'newest-pdf/',
                type: 'directory',
                mimeType: null,
                size: 0,
                createdAt: '2026-08-16T09:00:00Z',
              },
            ],
        total: path ? 0 : 3,
      }),
    });
  });

  page.route(/\/api\/v1\/rag\/files\/preview.*/, route => {
    route.fulfill({
      status: 200,
      contentType: 'text/html',
      body: '<div><h1>Sample PDF</h1><p>Indexed Markdown</p></div>',
    });
  });

  page.route(/\/api\/v1\/rag\/files\/raw.*/, route => {
    route.fulfill({
      status: 200,
      contentType: 'application/pdf',
      body: '%PDF-1.4 mock',
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

  // Mock durable model usage endpoint
  page.route('/api/v1/rag/usage', route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        recordingEnabled: true,
        localLostEventsSinceStart: 0,
        scope: { type: 'SELF', principalId: 'db:mock-user' },
        from: '2026-08-01',
        to: '2026-08-27',
        totals: {
          logicalExecutionCount: 3,
          invocationCount: 5,
          succeededCount: 4,
          failedCount: 1,
          cancelledCount: 0,
          promptTokens: '1200',
          completionTokens: '450',
          totalTokens: '1650',
          usageAvailableCount: 4,
          usageUnavailableCount: 1,
          pricingUnavailableCount: 1,
          costUnavailableCount: 1,
        },
        costs: [{
          unit: 'USD_ESTIMATE',
          configuredCost: '0.00420000',
          invocationCount: 5,
          costAvailableCount: 4,
        }],
        byModel: [{
          modelRef: 'mock/model',
          totals: {
            logicalExecutionCount: 3,
            invocationCount: 5,
            succeededCount: 4,
            failedCount: 1,
            cancelledCount: 0,
            promptTokens: '1200',
            completionTokens: '450',
            totalTokens: '1650',
            usageAvailableCount: 4,
            usageUnavailableCount: 1,
            pricingUnavailableCount: 1,
            costUnavailableCount: 1,
          },
        }],
        byPurpose: [{
          purpose: 'CHAT',
          totals: {
            logicalExecutionCount: 3,
            invocationCount: 5,
            succeededCount: 4,
            failedCount: 1,
            cancelledCount: 0,
            promptTokens: '1200',
            completionTokens: '450',
            totalTokens: '1650',
            usageAvailableCount: 4,
            usageUnavailableCount: 1,
            pricingUnavailableCount: 1,
            costUnavailableCount: 1,
          },
        }],
        byMode: [{
          mode: 'KNOWLEDGE',
          totals: {
            logicalExecutionCount: 3,
            invocationCount: 5,
            succeededCount: 4,
            failedCount: 1,
            cancelledCount: 0,
            promptTokens: '1200',
            completionTokens: '450',
            totalTokens: '1650',
            usageAvailableCount: 4,
            usageUnavailableCount: 1,
            pricingUnavailableCount: 1,
            costUnavailableCount: 1,
          },
        }],
        byDay: [{
          day: '2026-08-27',
          totals: {
            logicalExecutionCount: 3,
            invocationCount: 5,
            succeededCount: 4,
            failedCount: 1,
            cancelledCount: 0,
            promptTokens: '1200',
            completionTokens: '450',
            totalTokens: '1650',
            usageAvailableCount: 4,
            usageUnavailableCount: 1,
            pricingUnavailableCount: 1,
            costUnavailableCount: 1,
          },
        }],
      }),
    });
  });

  // Mock alerts endpoints
  page.route(/\/api\/v1\/rag\/alerts.*/, route => {
    const pathname = new URL(route.request().url()).pathname;
    const body = pathname.endsWith('/active')
      || pathname.endsWith('/slos/configs')
      || pathname.endsWith('/silence-schedules')
      ? []
      : {};
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });
  });

  // Mock A/B experiment endpoints
  page.route(/\/api\/v1\/rag\/experiments(?:\/.*)?(?:\?.*)?$/, route => {
    const pathname = new URL(route.request().url()).pathname;
    const experiment = {
      id: 1,
      experimentName: 'Retrieval Ranking Trial',
      description: 'Compare retrieval ranking configurations',
      status: 'DRAFT',
      targetMetric: 'retrieval_precision',
      sampleCount: 0,
      createdAt: '2026-08-17T08:00:00Z',
    };
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        pathname.endsWith('/experiments') ? [experiment] : experiment,
      ),
    });
  });

  // Mock chat SSE endpoint
  page.route('/api/v1/rag/chat/stream', route => {
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
      body: [
        'event: tool_start',
        'data: {"tool":"searchKnowledge","toolCallId":"mock-tool-call-1","query":"What is RAG?"}',
        '',
        'event: tool_result',
        'data: {"tool":"searchKnowledge","toolCallId":"mock-tool-call-1","resultCount":1,"elapsedMs":12}',
        '',
        'event: content',
        'data: {"content":"Test response"}',
        '',
        'event: sources',
        'data: {"sessionId":"mock-session-123","sources":[{"citationId":"S1","documentId":1,"title":"RAG Guide","collectionKey":"sample-collection","documentType":"TEXT"}]}',
        '',
        'event: done',
        `data: {"sessionId":"mock-session-123","status":"complete","turnId":"${MOCK_CHAT_TURN_ID}","mode":"KNOWLEDGE","resolvedModel":"minimax/MiniMax-M2.7"}`,
        '',
      ].join('\n'),
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
        body: JSON.stringify([]),
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
    if (url.includes('/suites')) {
      await route.fulfill({ json: [] });
      return;
    }
    if (url.includes('/runs')) {
      await route.fulfill({ json: { id: 'run-1', status: 'PENDING' } });
      return;
    }
    await route.fulfill({ json: {} });
  });
}
