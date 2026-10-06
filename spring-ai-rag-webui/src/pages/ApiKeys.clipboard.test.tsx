import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiKeys } from './ApiKeys';

// Batch 939. The two copy buttons in this page hand over a **raw API key that is shown
// exactly once** — close the dialog and it is unrecoverable. Both of them read:
//
//   await navigator.clipboard.writeText(rawKey);
//   showToast(t('apiKeys.copied'), 'success');
//
// with no `catch`. So when the write did not happen, the person clicking got no toast, no
// key on the clipboard, and no sign that anything had occurred, at the one moment where
// failing quietly costs the most. `navigator.clipboard` is not merely a promise that can
// reject: outside a secure context — a self-hosted UI reached over plain HTTP on a LAN
// address — it is `undefined`, and the line above throws a `TypeError` inside an async
// function with nobody awaiting it.
//
// These drive the rendered buttons, because the defect was never in what the copy helper
// was asked to do. It was that nothing downstream of it could report a no.

const mocks = vi.hoisted(() => ({
  showToast: vi.fn(),
  mutate: vi.fn(),
  /** Every `useMutation` config the page registers, newest last. */
  mutationConfigs: [] as Array<Record<string, unknown>>,
  useQuery: vi.fn(),
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}));

// `useQuery` is mocked synchronously, like every sibling `ApiKeys` test. The first draft
// of this file used a real `QueryClientProvider` instead, which meant a real async
// `listPrincipals` round trip inside a `waitFor` with the 1 s default — a real source of
// a load-dependent flake, which is worse than a slow test and much harder to read. What
// this file needs from the query layer is a rendered row; the double is synchronous.
vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mocks.useQuery(...args),
  useQueryClient: () => ({ invalidateQueries: vi.fn(), cancelQueries: vi.fn(),
    setQueryData: vi.fn() }),
  useMutation: (config: Record<string, unknown>) => {
    mocks.mutationConfigs.push(config);
    return { mutate: mocks.mutate, isPending: false };
  },
}));

vi.mock('../api/apikeys', () => ({
  apiKeysApi: {
    listPrincipals: vi.fn(),
    createKey: vi.fn(),
    revokeKey: vi.fn(),
    rotateKey: vi.fn(),
    prepareRotation: vi.fn(),
    getRotation: vi.fn(),
    completeRotation: vi.fn(),
    cancelRotation: vi.fn(),
    updatePolicy: vi.fn(),
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: { list: vi.fn().mockResolvedValue({ data: { collections: [] } }) },
}));

const principal = {
  principalId: 'rag_p_abc123',
  name: 'Production Server',
  role: 'NORMAL',
  policyVersion: 3,
  status: 'ACTIVE',
  createdAt: '2026-04-12T03:00:00',
  updatedAt: '2026-04-12T03:00:00',
  expiresAt: '2027-01-01T00:00:00',
  currentCredentialId: 'rag_k_abc123_v2',
  currentCredentialVersion: 2,
  capabilities: ['RAG_READ'],
};

const RAW_KEY = 'rag_live_2xJ9dKq7Wn4Tb8Yc';

const createdKeyResponse = {
  data: {
    principalId: 'rag_p_abc123',
    keyId: 'rag_k_abc123_v3',
    name: 'Copied Key',
    rawKey: RAW_KEY,
    expiresAt: '2027-01-01T00:00:00',
    capabilities: ['RAG_READ'],
  },
};

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');

function setClipboard(value: unknown) {
  Object.defineProperty(navigator, 'clipboard', {
    value,
    configurable: true,
    writable: true,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.mutationConfigs.length = 0;
  // Re-armed here rather than at declaration, because `clearAllMocks` drops the
  // implementation. An empty list renders a different toolbar button than a full one.
  mocks.useQuery.mockImplementation((options: { queryKey: unknown[] }) => (
    options.queryKey[0] === 'api-principals'
      ? { data: { data: [principal] }, isPending: false, isError: false, error: null }
      : { data: { data: { collections: [] } }, isPending: false, isError: false, error: null }
  ));
});

afterEach(() => {
  if (originalClipboard) {
    Object.defineProperty(navigator, 'clipboard', originalClipboard);
  } else {
    Reflect.deleteProperty(navigator, 'clipboard');
  }
});

/**
 * The create mutation, identified by the one thing only it does.
 *
 * It is the sole mutation on the page that carries an `onMutate` — cancelling the
 * principals query before the write is what stops the table from being refetched
 * mid-flight — and the only one registered with no `mutationKey` to match on.
 */
function createMutationConfig() {
  const found = [...mocks.mutationConfigs].reverse().find(config => config.onMutate);
  if (!found) throw new Error('the create mutation was never registered');
  return found;
}

function renderPage() {
  return render(<BrowserRouter><ApiKeys /></BrowserRouter>);
}

/** Open the create dialog, fill the required fields and submit. */
async function createKeyThroughTheDialog() {
  await waitFor(() => {
    expect(screen.getByText('Production Server')).toBeInTheDocument();
  });
  fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
  fireEvent.change(screen.getByPlaceholderText('apiKeys.namePlaceholder'), {
    target: { value: 'Copied Key' },
  });
  fireEvent.change(
    document.querySelector('#api-key-expires-at') as HTMLInputElement,
    { target: { value: '2027-01-01T00:00' } },
  );
  fireEvent.click(screen.getByRole('button', { name: 'apiKeys.create' }));
}

/** Reach the "shown once" panel, where the copy button lives. */
async function reachTheRawKeyPanel() {
  renderPage();
  await createKeyThroughTheDialog();
  const onSuccess = createMutationConfig().onSuccess as (r: unknown) => void;
  await act(async () => { onSuccess(createdKeyResponse); });
  return screen.getByRole('button', { name: 'apiKeys.copy' });
}

describe('copying a raw API key reports what happened', () => {
  it('confirms a successful copy', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });

    const copyButton = await reachTheRawKeyPanel();
    fireEvent.click(copyButton);

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith('apiKeys.copied', 'success');
    });
    expect(writeText).toHaveBeenCalledWith(RAW_KEY);
  });

  it('says so when the clipboard write is refused', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) });

    const copyButton = await reachTheRawKeyPanel();
    fireEvent.click(copyButton);

    await waitFor(() => {
      expect(mocks.showToast)
        .toHaveBeenCalledWith('apiKeys.copyFailed', 'error');
    });
    // And never the success message, which is what the old code would have shown had
    // the rejection not escaped into an unhandled promise.
    expect(mocks.showToast).not.toHaveBeenCalledWith('apiKeys.copied', 'success');
  });

  it('says so when the page is not in a secure context and there is no clipboard', async () => {
    // The case that produced the silent failure: `navigator.clipboard` is `undefined`,
    // so `navigator.clipboard.writeText(...)` is a `TypeError`, not a rejected promise.
    setClipboard(undefined);

    const copyButton = await reachTheRawKeyPanel();
    fireEvent.click(copyButton);

    await waitFor(() => {
      expect(mocks.showToast)
        .toHaveBeenCalledWith('apiKeys.copyFailed', 'error');
    });
  });

  it('does not report success when the clipboard is missing entirely', async () => {
    // jsdom has no clipboard, so this is the state the suite ran the old code in — and
    // the old code produced no toast at all. Pinning the absence is the point.
    Reflect.deleteProperty(navigator, 'clipboard');

    const copyButton = await reachTheRawKeyPanel();
    fireEvent.click(copyButton);

    await waitFor(() => {
      expect(mocks.showToast)
        .toHaveBeenCalledWith('apiKeys.copyFailed', 'error');
    });
  });
});
