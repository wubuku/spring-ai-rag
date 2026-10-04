import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  scanSource,
  destructiveActions,
  apiSources,
  VIOLATION_KINDS,
} from '../check-destructive-confirm.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

const destructive = destructiveActions(apiSources());
const kinds = (source) =>
  scanSource('src/pages/Sample.tsx', source, destructive).map(v => v.kind);

describe('the destructive set is derived, not maintained', () => {
  it('reads DELETE out of the API layer instead of trusting a hand-written list', () => {
    // Every name here comes from a method whose body issues `apiClient.delete(`.
    for (const name of ['delete', 'deleteByKey', 'revokeKey', 'clearHistory',
      'deleteExperiment', 'deleteSloConfig', 'deleteSilenceSchedule', 'removeDocuments']) {
      expect(destructive.has(name)).toBe(true);
    }
  });

  it('does not treat a POST as destructive even when the name sounds scary', () => {
    // Purge apply, batch embed and alert resolve are POSTs. Deciding which
    // POSTs need a prompt is a product call; guessing is how a gate cries wolf.
    for (const name of ['applyPurge', 'batchEmbed', 'resolveAlert']) {
      expect(destructive.has(name)).toBe(false);
    }
  });

  it('keeps working when a new DELETE-backed endpoint appears', () => {
    const derived = destructiveActions([
      { source: 'export const thing = { wipe: (id: string) => apiClient.delete(`/x/${id}`) };' },
    ]);
    expect([...derived]).toEqual(['wipe']);
  });
});

describe('unconfirmed-destructive', () => {
  it('reports a DELETE that fires with nothing asking first', () => {
    const source = `
      const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
      return <button onClick={() => removeM.mutate()}>Remove</button>;
    `;
    expect(kinds(source)).toEqual(['unconfirmed-destructive']);
  });

  it('accepts a file that opens a typed confirmation', () => {
    // The Collections shape: a target state plus a confirm dialog.
    const source = `
      const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
      const removeM = useMutation({ mutationFn: () => collectionsApi.deleteByKey(key) });
      return <ConfirmDialog open={deleteTarget !== null}
        onConfirm={() => removeM.mutate()} />;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a file that asks with a dialog', () => {
    // The ApiKeys shape.
    const source = `
      const [confirmingRevoke, setConfirmingRevoke] = useState(false);
      const revokeM = useMutation({ mutationFn: () => apiKeysApi.revokeKey(id) });
      return <Dialog open={confirmingRevoke}><button onClick={() => revokeM.mutate()} /></Dialog>;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a recorded, justified exemption', () => {
    const source = `
// destructive-allow -- the row is already gone, the delete only clears local state
const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
`;
    expect(kinds(source)).toEqual([]);
  });

  it('still reports when the exemption is a bare keyword with no reason', () => {
    // A bare keyword is a switch anyone can type; the `-- <reason>` is what
    // forces a justification to sit next to it in review.
    const source = `
// destructive-allow
const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
`;
    expect(kinds(source)).toEqual(['unconfirmed-destructive']);
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('unconfirmed-destructive');
  });
});

describe('misses, pinned so they stay visible', () => {
  it('lets one confirmation anywhere in a file vouch for every DELETE in it', () => {
    // Batch 870, measured rather than assumed. Renaming the confirmation state
    // in a real file does **not** turn this gate red, because the file still
    // contains a confirmation-ish word belonging to a different flow.
    //
    // `Collections.tsx` is the live instance: the purge flow owns a
    // `confirmation` state, so it would keep the gate quiet even if the
    // separate collection-delete lost its own `deleteTarget` prompt. Same for
    // `ApiKeys.tsx`, `Documents.tsx` and `Alerts.tsx` — all four measured green
    // when their confirmation variable was renamed.
    //
    // Closing it needs scope analysis, and a scope analyser that guesses wrong
    // reports correct code as broken. The miss is the acceptable failure mode
    // here; what is not acceptable is leaving it undocumented, which is what
    // this case is for.
    const source = `
      // Unrelated flow owns this one.
      const confirmation = useState('');
      const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
      return <button onClick={() => removeM.mutate()}>Remove</button>;
    `;
    expect(kinds(source)).toEqual([]);
  });
});

describe('the real component tree', () => {
  const walk = d => readdirSync(d, { withFileTypes: true }).flatMap(e => {
    const p = join(d, e.name);
    return e.isDirectory() ? walk(p) : (statSync(p).isFile() && /\.tsx?$/.test(e.name) ? [p] : []);
  });
  const apiDir = join(sourceRoot, 'api');
  const files = walk(sourceRoot)
    .filter(p => !p.startsWith(apiDir))
    .filter(p => !/\.(test|spec)\.tsx?$/.test(p));
  const violations = files.flatMap(path =>
    scanSource(path.replace(`${projectRoot}/`, ''), readFileSync(path, 'utf8'), destructive),
  );

  it('asks before every DELETE it fires', () => {
    // Batch 812 had to retrofit this by hand on the two alert lists; the other
    // three were brought in line by Batch 770. This is the assertion that stops
    // the next destructive action from arriving without a prompt.
    expect(violations).toEqual([]);
  });

  it('actually found destructive calls to judge', () => {
    // Without this, the case above would also pass if the call-site matcher
    // silently stopped matching anything.
    const found = files
      .map(path => readFileSync(path, 'utf8'))
      .filter(source => [...destructive].some(name => source.includes(`.${name}(`)));
    expect(found.length).toBeGreaterThanOrEqual(3);
  });
});
