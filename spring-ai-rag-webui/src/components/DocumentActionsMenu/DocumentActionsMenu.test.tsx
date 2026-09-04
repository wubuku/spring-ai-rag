import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DocumentActionsMenu } from './DocumentActionsMenu';
import type { Document } from '../../api/documents';

function makeDocument(overrides: Partial<Document> = {}): Document {
  return {
    id: 1,
    title: 'Doc A',
    content: 'content',
    source: 'uploads/note.txt',
    contentHash: 'hash-1',
    documentType: 'txt',
    metadata: {},
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    collectionId: null,
    collectionKey: null,
    collectionName: null,
    chunkCount: 1,
    ...overrides,
  };
}

interface RenderOptions {
  document?: Document;
  embeddingPending?: boolean;
  mutationPending?: boolean;
}

function setup(overrides: RenderOptions = {}) {
  const handlers = {
    onPreview: vi.fn(),
    onVersions: vi.fn(),
    onEdit: vi.fn(),
    onRetryEmbedding: vi.fn(),
    onDisable: vi.fn(),
    onRestore: vi.fn(),
    onPermanentDelete: vi.fn(),
    onRelocate: vi.fn(),
    onViewDirectory: vi.fn(),
    onViewIndexedFile: vi.fn(),
    onOpenOriginalFile: vi.fn(),
  };
  const utils = render(
    <DocumentActionsMenu
      ragDocument={overrides.document ?? makeDocument()}
      embeddingPending={overrides.embeddingPending ?? false}
      mutationPending={overrides.mutationPending ?? false}
      {...handlers}
    />,
  );
  return { ...utils, handlers };
}

async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  const trigger = screen.getByRole('button', { name: 'documents.openActions' });
  await user.click(trigger);
  return trigger;
}

describe('DocumentActionsMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('toggles the menu via the trigger with correct aria state', async () => {
    const user = userEvent.setup();
    setup();

    const trigger = screen.getByRole('button', { name: 'documents.openActions' });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    await user.click(trigger);
    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'true');

    await user.click(trigger);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('shows local document actions for a plain enabled document', async () => {
    const user = userEvent.setup();
    const { handlers } = setup();

    await openMenu(user);
    const menu = screen.getByRole('menu');

    await user.click(within(menu).getByRole('menuitem', { name: 'documents.preview' }));
    expect(handlers.onPreview).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'documents.openActions' })).toHaveFocus();

    await openMenu(user);
    const reopened = screen.getByRole('menu');
    expect(
      within(reopened).getByRole('menuitem', { name: 'versions.button' }),
    ).toBeInTheDocument();
    expect(
      within(reopened).getByRole('menuitem', { name: 'documents.edit' }),
    ).toBeInTheDocument();
    expect(
      within(reopened).getByRole('menuitem', { name: 'documents.disable' }),
    ).toBeInTheDocument();
    expect(
      within(reopened).getByRole('menuitem', { name: 'documents.permanentDelete' }),
    ).toBeInTheDocument();
    expect(
      within(reopened).queryByRole('menuitem', { name: 'documents.restore' }),
    ).not.toBeInTheDocument();
    expect(
      within(reopened).queryByRole('menuitem', { name: 'documents.relocate' }),
    ).not.toBeInTheDocument();

    const provenanceItem = within(reopened).getByRole('menuitem', {
      name: 'documents.sourceTraceability',
    });
    expect(provenanceItem).toBeDisabled();
  });

  it('offers restore instead of disable for a disabled document', async () => {
    const user = userEvent.setup();
    setup({ document: makeDocument({ enabled: false }) });

    await openMenu(user);
    const menu = screen.getByRole('menu');
    expect(
      within(menu).getByRole('menuitem', { name: 'documents.restore' }),
    ).toBeInTheDocument();
    expect(
      within(menu).queryByRole('menuitem', { name: 'documents.disable' }),
    ).not.toBeInTheDocument();
  });

  it('shows retry embedding when the embedding is stale', async () => {
    const user = userEvent.setup();
    const { handlers } = setup({
      document: makeDocument({ embeddingFresh: false }),
    });

    await openMenu(user);
    const menu = screen.getByRole('menu');
    const retry = within(menu).getByRole('menuitem', { name: 'documents.retryEmbedding' });
    expect(retry).toBeEnabled();

    await user.click(retry);
    expect(handlers.onRetryEmbedding).toHaveBeenCalledTimes(1);
  });

  it('shows externally managed actions with identity notice and hides local mutations', async () => {
    const user = userEvent.setup();
    setup({
      document: makeDocument({
        externalId: 'ext-42',
        sourceNamespace: 'crm',
        source: 'crm/ext-42',
      }),
    });

    await openMenu(user);
    const menu = screen.getByRole('menu');
    expect(
      within(menu).getByRole('menuitem', { name: 'documents.relocate' }),
    ).toBeInTheDocument();
    expect(
      within(menu).queryByRole('menuitem', { name: 'documents.edit' }),
    ).not.toBeInTheDocument();
    expect(
      within(menu).queryByRole('menuitem', { name: 'documents.disable' }),
    ).not.toBeInTheDocument();
    expect(
      within(menu).queryByRole('menuitem', { name: 'documents.permanentDelete' }),
    ).not.toBeInTheDocument();
    expect(within(menu).getByText('documents.externallyManaged')).toBeInTheDocument();
    expect(within(menu).getByText('documents.externalIdentity')).toBeInTheDocument();
  });

  it('exposes pdf provenance submenu entries wired to their handlers', async () => {
    const user = userEvent.setup();
    const { handlers } = setup({
      document: makeDocument({ source: 'pdf-import:imports/uuid-1/default.md' }),
    });

    await openMenu(user);
    const menu = screen.getByRole('menu');
    const provenanceItem = within(menu).getByRole('menuitem', {
      name: 'documents.sourceTraceability',
    });
    expect(provenanceItem).toBeEnabled();

    await user.click(provenanceItem);
    const submenu = screen.getAllByRole('menu').at(-1)!;
    expect(
      within(submenu).getByRole('menuitem', { name: 'documents.viewFileDirectory' }),
    ).toBeInTheDocument();

    await user.click(
      within(submenu).getByRole('menuitem', { name: 'documents.viewIndexedFile' }),
    );
    expect(handlers.onViewIndexedFile).toHaveBeenCalledWith(
      'imports/uuid-1/',
      'imports/uuid-1/default.md',
    );
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('disables local mutations while a mutation is pending', async () => {
    const user = userEvent.setup();
    setup({ mutationPending: true });

    await openMenu(user);
    const menu = screen.getByRole('menu');
    expect(
      within(menu).getByRole('menuitem', { name: 'documents.edit' }),
    ).toBeDisabled();
    expect(
      within(menu).getByRole('menuitem', { name: 'documents.permanentDelete' }),
    ).toBeDisabled();
  });

  it('closes on Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup();
    setup();

    const trigger = await openMenu(user);
    expect(screen.getByRole('menu')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('closes on pointer down outside the menu', async () => {
    const user = userEvent.setup();
    setup();

    await openMenu(user);
    expect(screen.getByRole('menu')).toBeInTheDocument();

    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
