import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { collectionsApi } from '../../api/collections';
import type { CollectionScopeMode } from '../../types/api';
import { CollectionScopeSelector } from './CollectionScopeSelector';

vi.mock('../../api/collections', () => ({
  collectionsApi: {
    list: vi.fn(),
  },
}));

const collection = (id: number, key = `collection-${id}`) => ({
  id,
  collectionKey: key,
  name: `Collection ${id}`,
  description: '',
  embeddingModel: 'bge-m3',
  dimensions: 1024,
  enabled: true,
  metadata: {},
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
  documentCount: id,
});

function Harness({
  initialMode = 'CALLER_VISIBLE',
  initialSelected = [],
  disabled = false,
}: {
  initialMode?: CollectionScopeMode;
  initialSelected?: string[];
  disabled?: boolean;
}) {
  const [mode, setMode] = useState<CollectionScopeMode>(initialMode);
  const [selectedKeys, setSelectedKeys] = useState(initialSelected);
  return (
    <CollectionScopeSelector
      idPrefix="test"
      mode={mode}
      selectedKeys={selectedKeys}
      onModeChange={setMode}
      onSelectedKeysChange={setSelectedKeys}
      disabled={disabled}
    />
  );
}

function renderSelector(props?: Parameters<typeof Harness>[0]) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <Harness {...props} />
      </QueryClientProvider>,
    ),
  };
}

describe('CollectionScopeSelector', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (collectionsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        collections: [collection(1)],
        total: 1,
        offset: 0,
        limit: 50,
      },
    });
  });

  it('defaults to caller-visible without loading collection options', () => {
    renderSelector();

    expect(screen.getByTestId('test-scope-CALLER_VISIBLE')).toBeChecked();
    expect(collectionsApi.list).not.toHaveBeenCalled();
  });

  it('switches modes and rejects an empty selected scope', async () => {
    renderSelector();

    fireEvent.click(screen.getByTestId('test-scope-SELECTED_COLLECTIONS'));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'collectionScope.selectionRequired',
    );
    expect(collectionsApi.list).toHaveBeenCalledWith({
      page: 0,
      size: 50,
      query: undefined,
    });
  });

  it('searches by name or collection key', async () => {
    renderSelector({ initialMode: 'SELECTED_COLLECTIONS' });

    fireEvent.change(screen.getByTestId('test-collection-query'), {
      target: { value: 'manual:v3' },
    });

    await waitFor(
      () => {
        expect(collectionsApi.list).toHaveBeenCalledWith({
          page: 0,
          size: 50,
          query: 'manual:v3',
        });
      },
      { timeout: 1000 },
    );
  });

  it('retains selections across pages', async () => {
    (collectionsApi.list as ReturnType<typeof vi.fn>).mockImplementation(
      ({ page }: { page?: number }) => Promise.resolve({
        data: {
          collections: [collection((page ?? 0) + 1)],
          total: 51,
          offset: (page ?? 0) * 50,
          limit: 50,
        },
      }),
    );
    renderSelector({ initialMode: 'SELECTED_COLLECTIONS' });

    const first = await screen.findByRole('checkbox', { name: /Collection 1/ });
    fireEvent.click(first);
    fireEvent.click(screen.getByRole('button', { name: 'collectionScope.next' }));

    const second = await screen.findByRole('checkbox', { name: /Collection 2/ });
    fireEvent.click(second);

    expect(screen.getByTestId('test-selected-count')).toHaveTextContent(
      'collectionScope.selectedCount',
    );
    fireEvent.click(screen.getByRole('button', { name: 'collectionScope.previous' }));
    expect(await screen.findByRole('checkbox', { name: /Collection 1/ })).toBeChecked();
  });

  it('disables unselected options after reaching the 100 collection limit', async () => {
    const selected = Array.from({ length: 100 }, (_, index) => `selected-${index}`);
    renderSelector({
      initialMode: 'SELECTED_COLLECTIONS',
      initialSelected: selected,
    });

    expect(await screen.findByRole('checkbox', { name: /Collection 1/ })).toBeDisabled();
    expect(screen.getByText('collectionScope.selectionLimit')).toBeInTheDocument();
  });

  it('renders loading, error, and empty states', async () => {
    let resolvePending: ((value: unknown) => void) | undefined;
    (collectionsApi.list as ReturnType<typeof vi.fn>).mockReturnValueOnce(
      new Promise(resolve => {
        resolvePending = resolve;
      }),
    );
    const pending = renderSelector({ initialMode: 'SELECTED_COLLECTIONS' });
    expect(screen.getByText('common.loading')).toBeInTheDocument();
    pending.unmount();
    await act(async () => {
      resolvePending?.({
        data: { collections: [], total: 0, offset: 0, limit: 50 },
      });
    });

    (collectionsApi.list as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error('load failed'),
    );
    const failed = renderSelector({ initialMode: 'SELECTED_COLLECTIONS' });
    expect(await screen.findByText('collectionScope.loadError')).toBeInTheDocument();
    failed.unmount();

    (collectionsApi.list as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      data: { collections: [], total: 0, offset: 0, limit: 50 },
    });
    renderSelector({ initialMode: 'SELECTED_COLLECTIONS' });
    expect(await screen.findByText('collectionScope.empty')).toBeInTheDocument();
  });

  it('disables all controls while the parent is streaming', async () => {
    renderSelector({
      initialMode: 'SELECTED_COLLECTIONS',
      disabled: true,
    });

    expect(screen.getByTestId('test-scope-CALLER_VISIBLE')).toBeDisabled();
    expect(screen.getByTestId('test-collection-query')).toBeDisabled();
  });
});

