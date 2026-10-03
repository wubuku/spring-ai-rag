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

// 非根主体那条分支需要换一个 principalType，而 mock 工厂被 vitest 提升到
// import 之前，普通模块级 let 会在工厂求值时撞上 TDZ。vi.hoisted 是官方解法。
const authState = vi.hoisted(() => ({ principalType: 'ENVIRONMENT_ROOT' }));

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
      principalType: authState.principalType,
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

describe('Collections delete flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockList(collection);
    mockCapabilities(true);
  });

  it('shows delete success and error toasts for the collection', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.deleteByKey).mockResolvedValue({} as never);

    renderPage();

    const deleteButtons = await screen.findAllByRole('button', {
      name: 'collections.delete',
    });
    await user.click(deleteButtons[0]);
    await user.click(screen.getByRole('button', { name: 'common.delete' }));

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
    await user.click(screen.getByRole('button', { name: 'common.delete' }));
    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith(
        'collections.deleteError', 'error',
      );
    });
  });

  it('keeps the collection when the delete confirmation is cancelled', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.deleteByKey).mockResolvedValue({} as never);

    renderPage();

    await user.click(
      (await screen.findAllByRole('button', { name: 'collections.delete' }))[0],
    );
    expect(collectionsApi.deleteByKey).not.toHaveBeenCalled();
    expect(screen.getByText('collections.deleteConfirm')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'common.cancel' }));

    expect(collectionsApi.deleteByKey).not.toHaveBeenCalled();
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
      await screen.findByRole('button', { name: 'collections.viewDocuments' }),
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

describe('Collections dialog dismissal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockList(collection);
    mockCapabilities(true);
    window.history.replaceState({}, '', '/');
  });

  it('closes the create modal via its close button', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: '+ collections.create' }),
    );
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Close' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('closes the purge dialog via cancel without applying', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'collections.purge.action' }),
    );
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('collections.purge');

    await user.click(
      within(dialog).getByRole('button', { name: 'common.cancel' }),
    );

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(collectionsApi.applyPurge).not.toHaveBeenCalled();
  });
});

/**
 * 破坏性能力门控与"读失败 ≠ 空结果"。
 *
 * 页面自己写着两条设计意图，但原来一条都没有用例钉着：
 * ① capability 读取失败时 `purgeVisible` 保持 false（fail-closed），
 *    同时必须给出一句解释，否则用户会以为权限被降级；
 * ② 列表读取失败要显示可重试的错误横幅，而不是滑进"暂无集合"空态。
 * 这两条都是"看起来有实现、实际无人验证"的类型。
 */
describe('Collections fail-closed reads', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.principalType = 'ENVIRONMENT_ROOT';
    mockList(collection);
    mockCapabilities(true);
  });

  it('hides the purge action and explains the vanished button when the capability read fails', async () => {
    vi.mocked(collectionsApi.integrationCapabilities)
      .mockRejectedValue(new Error('capability endpoint down'));

    renderPage();

    expect(await screen.findByText('Sample Collection')).toBeInTheDocument();
    // fail-closed：读不到能力就不给破坏性入口。
    expect(screen.queryByRole('button', {
      name: 'collections.purge.action',
    })).not.toBeInTheDocument();

    // 但"按钮不见了"本身要有解释，否则与"权限被降级"无法区分。
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('collections.capabilityLoadFailed');
  });

  it('restores the purge action when retrying the capability read succeeds', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.integrationCapabilities)
      .mockRejectedValueOnce(new Error('capability endpoint down'))
      .mockResolvedValueOnce(response({
        principal: { principalType: 'ENVIRONMENT_ROOT' },
        features: { optional: { collectionPurge: true } },
      }));

    renderPage();

    const alert = await screen.findByRole('alert');
    expect(screen.queryByRole('button', {
      name: 'collections.purge.action',
    })).not.toBeInTheDocument();

    await user.click(within(alert).getByRole('button', {
      name: 'common.retry',
    }));

    expect(await screen.findByRole('button', {
      name: 'collections.purge.action',
    })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('never issues the capability read for a non-root principal and keeps purge hidden', async () => {
    authState.principalType = 'API_KEY';

    renderPage();

    expect(await screen.findByText('Sample Collection')).toBeInTheDocument();
    // enabled 门控：非根主体连这个请求都不该发出去。
    expect(collectionsApi.integrationCapabilities).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', {
      name: 'collections.purge.action',
    })).not.toBeInTheDocument();
  });

  it('shows a retryable error rather than an empty state when the list read fails', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.list).mockRejectedValue(new Error('offline'));

    renderPage();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('collections.loadFailed');
    // 关键区分：读失败不是"没有集合"。
    expect(screen.queryByText('collections.noCollections'))
      .not.toBeInTheDocument();

    vi.mocked(collectionsApi.list).mockResolvedValue(response({
      collections: [collection],
      total: 1,
      offset: 0,
      limit: 20,
    }));
    await user.click(within(alert).getByRole('button', { name: 'common.retry' }));

    expect(await screen.findByText('Sample Collection')).toBeInTheDocument();
  });

  it('renders the empty state only for a genuinely empty list', async () => {
    mockList();

    renderPage();

    expect(await screen.findByText('collections.noCollections'))
      .toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('does not show the empty state alongside a populated list', async () => {
    // 上一条只证明了"空列表会显示空态"，没证明"非空列表不会也显示空态"——
    // 变异实验把 `length === 0` 改成 `!== undefined` 时测试仍然全绿，就是这个缺口。
    renderPage();

    expect(await screen.findByText('Sample Collection')).toBeInTheDocument();
    expect(screen.queryByText('collections.noCollections'))
      .not.toBeInTheDocument();
  });

  it('shows neither an empty state nor an error while the list is still loading', async () => {
    vi.mocked(collectionsApi.list).mockReturnValue(new Promise(() => {}));

    const { container } = renderPage();

    // 挂起态：既不能闪"暂无集合"，也不能闪错误横幅，并且要真的画出骨架卡。
    // 骨架卡没有可访问名，用仓库既有的 `[class*="skeleton"]` 约定定位（见 Dashboard.test.tsx）。
    await waitFor(() => {
      expect(
        container.querySelectorAll('[class*="skeleton"]').length,
      ).toBe(12); // 3 张卡 × 每张 4 块
    });
    expect(screen.queryByText('collections.noCollections'))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Collections purge in-flight lock', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.principalType = 'ENVIRONMENT_ROOT';
    mockList(collection);
    mockCapabilities(true);
  });

  it('locks the purge dialog shut while apply is in flight and relabels the secondary action afterwards', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.previewPurge).mockResolvedValue(response(preview));

    let releaseApply: (() => void) | undefined;
    vi.mocked(collectionsApi.applyPurge).mockReturnValue(new Promise(resolve => {
      releaseApply = () => resolve(response({
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
    }));

    renderPage();
    await user.click(await screen.findByRole('button', {
      name: 'collections.purge.action',
    }));
    const dialog = await screen.findByRole('dialog');
    await user.type(
      await within(dialog).findByRole('textbox', {
        name: /collections\.purge\.confirmLabel/,
      }),
      collection.collectionKey,
    );
    await user.click(within(dialog).getByRole('button', {
      name: 'collections.purge.confirmAction',
    }));

    // 永久清除进行中：不能半途关窗跑掉。
    const secondary = await within(dialog).findByRole('button', {
      name: 'collections.purge.applying',
    });
    await waitFor(() => {
      expect(within(dialog).getByRole('button', { name: 'Close' }))
        .toBeDisabled();
    });
    expect(secondary).toBeDisabled();

    releaseApply?.();

    // 成功后二次按钮从"取消"变成"关闭"，确认入口消失。
    expect(await within(dialog).findByRole('button', {
      name: 'common.close',
    })).toBeEnabled();
    expect(within(dialog).queryByRole('button', {
      name: 'collections.purge.confirmAction',
    })).not.toBeInTheDocument();
  });
});
