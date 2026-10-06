import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { ApiKeys } from './ApiKeys';

// Batch 938. `formatDateTime` used to be
//
//   if (!dateStr) return '—';
//   try { return new Date(dateStr).toLocaleString(); } catch { return dateStr; }
//
// which reads as though it handles a value it cannot read. It cannot: `new Date` on a
// bad string returns an Invalid Date and `toLocaleString()` on one returns the string
// "Invalid Date" — so the catch was unreachable and the user read those words in the
// key table. These cases pin the rendered output, not the helper, because the defect
// was never in what the helper was asked to do.

const mockUseQuery = vi.fn();
const mockMutateFn = vi.fn();
const mockUseMutation = vi.fn(() => ({ mutate: mockMutateFn, isPending: false }));
const mockUseQueryClient = vi.fn(() => ({ invalidateQueries: vi.fn() }));
const mockShowToast = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useMutation: (...args: unknown[]) => mockUseMutation(...args),
  useQueryClient: (...args: unknown[]) => mockUseQueryClient(...args),
}));

vi.mock('../components/Toast', () => ({
  useToast: vi.fn(() => ({ showToast: mockShowToast })),
}));

// The envelope is the page's own, and the shape is `ApiPrincipalResponse` — whose
// `createdAt` is a Java `LocalDateTime`, so the wire value carries **no offset**.
// The sibling `ApiKeys.test.tsx` fixture has used `2026-04-12T03:00:00` throughout.
const principal = (overrides: Record<string, unknown> = {}) => ({
  principalId: 'rag_p_abc123',
  name: 'partner-a',
  createdAt: '2026-10-06T19:34:31',
  updatedAt: '2026-10-06T19:34:31',
  lastUsedAt: '2026-10-06T19:00:00',
  expiresAt: '2026-11-06T19:34:31',
  status: 'ACTIVE',
  role: 'ADMIN',
  policyVersion: 2,
  currentCredentialId: 'rag_k_abc123_v2',
  currentCredentialVersion: 2,
  requestsPerMinute: 120,
  capabilities: ['RAG_READ'],
  ...overrides,
});

const renderPage = async (principals: unknown[], present = 'partner-a') => {
  mockUseQuery.mockReturnValue({ data: { data: principals }, isPending: false });
  render(
    <BrowserRouter>
      <ApiKeys />
    </BrowserRouter>,
  );
  await waitFor(() => expect(screen.getByText(present)).toBeTruthy());
};

describe('ApiKeys timestamps', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseQuery.mockReturnValue({ data: undefined, isPending: true });
  });

  it('never shows "Invalid Date" for a timestamp it cannot read', async () => {
    await renderPage([principal({ lastUsedAt: 'not-a-date', expiresAt: 'garbage' })]);
    expect(screen.queryByText(/Invalid Date/u)).toBeNull();
    expect(document.body.textContent).not.toContain('Invalid');
  });

  it('never shows "Invalid Date" when the value is missing entirely', async () => {
    await renderPage([principal({ lastUsedAt: undefined, expiresAt: undefined })]);
    expect(document.body.textContent).not.toContain('Invalid');
  });

  it('still renders the key itself, so a bad date does not blank the row', async () => {
    // The old catch returned `dateStr`, printing the raw wire value into the table.
    await renderPage([principal({ lastUsedAt: 'not-a-date' })]);
    expect(screen.getByText('partner-a')).toBeTruthy();
    expect(document.body.textContent).not.toContain('not-a-date');
  });

  it('reads all three wire shapes without complaint', async () => {
    // `Instant`, `OffsetDateTime` and — the one with no offset at all — `LocalDateTime`.
    await renderPage([
      principal({ name: 'utc-shape', createdAt: '2026-10-06T11:34:31Z' }),
      principal({ name: 'offset-shape', createdAt: '2026-10-06T19:34:31+08:00' }),
      principal({ name: 'zoneless-shape', createdAt: '2026-10-06T19:34:31' }),
    ], 'utc-shape');
    expect(screen.getByText('utc-shape')).toBeTruthy();
    expect(screen.getByText('offset-shape')).toBeTruthy();
    expect(screen.getByText('zoneless-shape')).toBeTruthy();
    expect(document.body.textContent).not.toContain('Invalid');
  });
});