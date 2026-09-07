import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  collectionsApi,
  type Collection,
  type CollectionPurgePreview,
} from '../api/collections';
import { Collections } from './Collections';

const showToast = vi.fn();

vi.mock('../api/collections', async importOriginal => {
  const actual = await importOriginal<typeof import('../api/collections')>();
  return {
    ...actual,
    collectionsApi: {
      list: vi.fn(),
      integrationCapabilities: vi.fn(),
      deleteByKey: vi.fn(),
      previewPurge: vi.fn(),
      applyPurge: vi.fn(),
    },
  };
});

vi.mock('../auth/ApiKeyAuthContext', () => ({
  useApiKeyAuth: () => ({
    identity: {
      principalType: 'ENVIRONMENT_ROOT',
      principalId: 'environment-root',
      capabilities: ['RAG_READ', 'RAG_WRITE', 'API_KEY_MANAGE'],
    },
    isUnlocked: true,
    unlock: vi.fn(),
    logout: vi.fn(),
  }),
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast }),
}));

const collection: Collection = {
  id: 1,
  collectionKey: 'sample-collection',
  name: 'Sample Collection',
  description: '',
  embeddingModel: 'bge-m3',
  dimensions: 1024,
  enabled: true,
  metadata: {},
  createdAt: '2026-08-27T00:00:00Z',
  updatedAt: '2026-08-27T00:00:00Z',
  documentCount: 5,
};

const preview: CollectionPurgePreview = {
  previewId: '33333333-3333-4333-8333-333333333333',
  collectionId: 1,
  collectionKey: collection.collectionKey,
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
  confirmationToken: 'one-time-secret-token',
  fingerprint: 'preview-fingerprint',
  previewExpiresAt: '2026-08-27T12:15:00Z',
  operationExpiresAt: '2026-08-27T12:30:00Z',
};

function response<T>(data: T) {
  return { data } as never;
}

function mockList(...collections: Collection[]) {
  vi.mocked(collectionsApi.list).mockResolvedValue(response({
    collections,
    total: collections.length,
    offset: 0,
    limit: 20,
  }));
}

function mockCapabilities(enabled: boolean) {
  vi.mocked(collectionsApi.integrationCapabilities).mockResolvedValue(response({
    principal: { principalType: 'ENVIRONMENT_ROOT' },
    features: { optional: { collectionPurge: enabled } },
  }));
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Collections />
      </BrowserRouter>
    </QueryClientProvider>,
  );
}

describe('Collections purge flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockList(collection);
    mockCapabilities(true);
  });

  it('hides the purge action when runtime capability is disabled', async () => {
    mockCapabilities(false);

    renderPage();

    expect(await screen.findByText('Sample Collection')).toBeInTheDocument();
    expect(screen.queryByRole('button', {
      name: 'collections.purge.action',
    })).not.toBeInTheDocument();
  });

  it('previews, requires the exact key, applies the frozen plan, and retains the result', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.previewPurge).mockResolvedValue(response(preview));
    vi.mocked(collectionsApi.applyPurge).mockResolvedValue(response({
      previewId: preview.previewId,
      status: 'RETIRED',
      collectionId: 1,
      collectionKey: collection.collectionKey,
      purgedDocumentCount: 5,
      purgedExternalDocumentCount: 2,
      purgedLocalDocumentCount: 3,
      deletedAt: '2026-08-27T12:01:00',
      purgedAt: '2026-08-27T12:01:00',
      collectionVersion: 8,
    }));
    vi.mocked(collectionsApi.list)
      .mockResolvedValueOnce(response({
        collections: [collection],
        total: 1,
        offset: 0,
        limit: 20,
      }))
      .mockResolvedValue(response({
        collections: [],
        total: 0,
        offset: 0,
        limit: 20,
      }));

    renderPage();
    await user.click(await screen.findByRole('button', {
      name: 'collections.purge.action',
    }));

    const dialog = await screen.findByRole('dialog', {
      name: 'collections.purge.title',
    });
    expect(await within(dialog).findByText('5')).toBeVisible();
    expect(within(dialog).queryByText(preview.confirmationToken))
      .not.toBeInTheDocument();

    const applyButton = within(dialog).getByRole('button', {
      name: 'collections.purge.confirmAction',
    });
    const confirmationInput = within(dialog).getByRole('textbox', {
      name: /collections\.purge\.confirmLabel/,
    });
    expect(applyButton).toBeDisabled();

    await user.type(confirmationInput, 'sample');
    expect(applyButton).toBeDisabled();
    await user.clear(confirmationInput);
    await user.type(confirmationInput, collection.collectionKey);
    expect(applyButton).toBeEnabled();
    await user.click(applyButton);

    await waitFor(() => {
      expect(collectionsApi.applyPurge).toHaveBeenCalledWith({
        collectionKey: collection.collectionKey,
        previewId: preview.previewId,
        confirmationToken: preview.confirmationToken,
        fingerprint: preview.fingerprint,
        expectedCollectionVersion: 7,
        expectedChatCommitFenceVersion: 12,
      });
    });
    expect(await within(dialog).findByText('collections.purge.resultTitle'))
      .toBeVisible();
    expect(dialog).toBeVisible();
    await waitFor(() => {
      expect(screen.queryByText('Sample Collection')).not.toBeInTheDocument();
    });
    expect(showToast).toHaveBeenCalledWith(
      'collections.purge.success',
      'success',
    );
  });

  it('keeps an apply conflict visible without automatically resubmitting', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.previewPurge).mockResolvedValue(response(preview));
    vi.mocked(collectionsApi.applyPurge)
      .mockRejectedValue(new Error('Collection purge plan changed'));

    renderPage();
    await user.click(await screen.findByRole('button', {
      name: 'collections.purge.action',
    }));
    const dialog = await screen.findByRole('dialog', {
      name: 'collections.purge.title',
    });
    await user.type(
      await within(dialog).findByRole('textbox', {
        name: /collections\.purge\.confirmLabel/,
      }),
      collection.collectionKey,
    );
    await user.click(within(dialog).getByRole('button', {
      name: 'collections.purge.confirmAction',
    }));

    expect(await within(dialog).findByText('Collection purge plan changed'))
      .toBeVisible();
    expect(collectionsApi.applyPurge).toHaveBeenCalledTimes(1);
    expect(within(dialog).getByRole('button', {
      name: 'collections.purge.confirmAction',
    })).toBeEnabled();
  });
});

  it('shows delete success and error toasts for the collection', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.deleteByKey).mockResolvedValue({} as never);
    mockList(collection);
    mockCapabilities(true);
    renderPage();

    const deleteButtons = await screen.findAllByRole('button', {
      name: 'collections.delete',
    });
    await user.click(deleteButtons[0]);

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith(
        'collections.deleteSuccess', 'success',
      );
    });

    // 失败路径：删除拒绝 → 错误 toast。
    vi.mocked(collectionsApi.deleteByKey).mockRejectedValue(
      new Error('in use'),
    );
    await user.click(
      (await screen.findAllByRole('button', { name: 'collections.delete' }))[0],
    );
    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith(
        'collections.deleteError', 'error',
      );
    });
  });


describe('Collections navigation, create modal and purge preview failure', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockList(collection);
    mockCapabilities(true);
    window.history.replaceState({}, '', '/');
  });

  it('navigates to documents and embeddings from the card actions', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(
      await screen.findByRole('button', { name: 'View Documents' }),
    );
    expect(window.location.pathname + window.location.search)
      .toBe('/documents?collectionKey=sample-collection');

    await user.click(
      screen.getByRole('button', { name: 'embeddings.openOperations' }),
    );
    expect(window.location.pathname + window.location.search)
      .toBe('/embeddings?collectionKey=sample-collection');
  });

  it('opens the create modal from the header button', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(
      await screen.findByRole('button', { name: '+ collections.create' }),
    );

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('shows the purge preview error panel and recovers via retry', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.previewPurge)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(response(preview));

    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'collections.purge.action' }),
    );

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('collections.purge.previewError');
    expect(alert).toHaveTextContent('boom');

    await user.click(
      within(alert).getByRole('button', { name: 'collections.purge.retryPreview' }),
    );

    // 重试成功后错误面板消失，进入确认流程。
    await waitFor(() => {
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });
});
