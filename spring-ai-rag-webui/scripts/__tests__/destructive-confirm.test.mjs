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
    // The ApiKeys shape — and note what Batch 875 changed here. The fixture used
    // to put `revokeM.mutate()` inside an `onClick` on a button nested in the
    // dialog, and it passed only because the word `confirmingRevoke` appeared
    // somewhere in the file. A dialog that is *open* is not a prompt: the
    // delete fired from an ordinary click, and the test name claimed otherwise.
    // The mutation proof flagged this fixture before any production code was
    // touched, which is the whole reason it is worth running.
    const source = `
      const [confirmingRevoke, setConfirmingRevoke] = useState(false);
      const revokeM = useMutation({ mutationFn: () => apiKeysApi.revokeKey(id) });
      return <ConfirmDialog open={confirmingRevoke}
        onConfirm={() => { setConfirmingRevoke(false); revokeM.mutate(); }}
        onClose={() => setConfirmingRevoke(false)}
      />;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a recorded, justified exemption', () => {
    // Batch 875: the exemption now anchors above the *invocation*, not above the
    // declaration, because that is where the user's decision is made. Both
    // fixtures below had to grow a `.mutate()` call to keep exercising the path
    // at all — without one the rule has nothing to judge and passes vacuously.
    const source = `
const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
// destructive-allow -- the row is already gone, the delete only clears local state
return <button onClick={() => removeM.mutate()}>Remove</button>;
`;
    expect(kinds(source)).toEqual([]);
  });

  it('still reports when the exemption is a bare keyword with no reason', () => {
    // A bare keyword is a switch anyone can type; the `-- <reason>` is what
    // forces a justification to sit next to it in review.
    const source = `
const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
// destructive-allow
return <button onClick={() => removeM.mutate()}>Remove</button>;
`;
    expect(kinds(source)).toEqual(['unconfirmed-destructive']);
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('unconfirmed-destructive');
  });
});

describe('the file-level miss, closed in Batch 875', () => {
  // This block used to assert `[]` — pinning the miss on purpose, so that
  // closing it would have to be a conscious edit to a visible line rather than
  // something that quietly invalidated a passing test. That edit is this one.
  //
  // Batch 875 found the miss was two problems at once. The vocabulary was too
  // weak — an unrelated `confirmLabel`, a confirmation state in a *different*
  // component, or a prop called `confirmPending` each satisfied the whole file.
  // And the predicate was looking in the wrong *place*: the destructive call
  // was located inside `mutationFn`, while the user's decision is made where
  // `.mutate()` is invoked, which is somewhere else entirely. All seven
  // invocations in the tree sit inside an `onConfirm` handler of a dialog, so
  // the check moved there.

  it('no longer lets one confirmation anywhere in a file vouch for every DELETE', () => {
    // Exactly Batch 870's fixture. It measured green then, and it measures red
    // now: the `confirmation` state belongs to an unrelated flow and the delete
    // is fired from a plain button.
    const source = `
      // Unrelated flow owns this one.
      const confirmation = useState('');
      const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
      return <button onClick={() => removeM.mutate()}>Remove</button>;
    `;
    expect(kinds(source)).toEqual(['unconfirmed-destructive']);
  });

  it('does not let a dialog vouch for a delete in another component', () => {
    const source = `
      function Header() {
        return <ConfirmDialog onConfirm={() => {}} />;
      }
      function Page() {
        const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
        return <button onClick={() => removeM.mutate()}>Remove</button>;
      }
    `;
    expect(kinds(source)).toEqual(['unconfirmed-destructive']);
  });

  it('does not let an onConfirm on a non-dialog element count as asking', () => {
    // The prop name alone is not the behaviour. The element has to be a dialog,
    // because that is what makes `onConfirm` a prompt rather than a callback.
    const source = `
      const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
      return <div onConfirm={() => removeM.mutate()} />;
    `;
    expect(kinds(source)).toEqual(['unconfirmed-destructive']);
  });

  it('reports a destructive call made directly, with no mutation to follow', () => {
    // The first version of the narrowed rule only understood the routed shape
    // and let every direct call through. Closing one blind spot by opening
    // another is not a trade worth making, and the mutation proof caught it.
    const source = `
      return <button onClick={() => collectionsApi.deleteByKey(key)}>D</button>;
    `;
    expect(kinds(source)).toEqual(['unconfirmed-destructive']);
  });

  it('accepts a direct call that is inside a dialog onConfirm', () => {
    const source = `<ConfirmDialog onConfirm={() => collectionsApi.deleteByKey(key)} />;`;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a call routed through a mutation inside a dialog onConfirm', () => {
    // All three real shapes: Collections, ApiKeys and Documents respectively.
    const collections = `
      const [deleteTarget, setDeleteTarget] = useState(null);
      const removeM = useMutation({ mutationFn: () => collectionsApi.deleteByKey(key) });
      return <ConfirmDialog open={deleteTarget !== null}
        onConfirm={() => { if (deleteTarget !== null) removeM.mutate(deleteTarget); }} />;
    `;
    const apiKeys = `
      const [confirmingRevoke, setConfirmingRevoke] = useState(false);
      const revokeM = useMutation({ mutationFn: () => apiKeysApi.revokeKey(id) });
      return <ConfirmDialog open={confirmingRevoke}
        onConfirm={() => { setConfirmingRevoke(false); revokeM.mutate(); }}
        onClose={() => setConfirmingRevoke(false)} />;
    `;
    const documents = `
      const removeM = useMutation({ mutationFn: (d) => documentsApi.delete(d) });
      return <ConfirmDialog open={!!confirmation}
        onConfirm={() => {
          if (confirmation.kind === 'delete') removeM.mutate(confirmation.document);
        }} />;
    `;
    for (const source of [collections, apiKeys, documents]) {
      expect(kinds(source)).toEqual([]);
    }
  });

  it('binds a call to the nearest declaration above it, not to the whole file', () => {
    // `Alerts.tsx` is the live instance: two components, each declaring its own
    // `const deleteMutation`. A whole-file search let each declaration claim
    // both call sites, so the first component's dialog vouched for the second
    // component's delete — the cross-component leak, reintroduced one level up.
    // The census that found it also corrected the count: four reported sites
    // were really two.
    const leaking = `
      function SloPanel() {
        const deleteMutation = useMutation({ mutationFn: (n) => alertsApi.deleteSloConfig(n) });
        return <ConfirmDialog onConfirm={() => deleteMutation.mutate(name)} />;
      }
      function SilencePanel() {
        const deleteMutation = useMutation({ mutationFn: (n) => alertsApi.deleteSilenceSchedule(n) });
        return <button onClick={() => deleteMutation.mutate(name)}>D</button>;
      }
    `;
    const reversed = `
      function First() {
        const deleteMutation = useMutation({ mutationFn: (n) => alertsApi.deleteSloConfig(n) });
        return <button onClick={() => deleteMutation.mutate(name)}>D</button>;
      }
      function Second() {
        const deleteMutation = useMutation({ mutationFn: (n) => alertsApi.deleteSilenceSchedule(n) });
        return <ConfirmDialog onConfirm={() => deleteMutation.mutate(name)} />;
      }
    `;
    const bothConfirmed = `
      function SloPanel() {
        const deleteMutation = useMutation({ mutationFn: (n) => alertsApi.deleteSloConfig(n) });
        return <ConfirmDialog onConfirm={() => deleteMutation.mutate(name)} />;
      }
      function SilencePanel() {
        const deleteMutation = useMutation({ mutationFn: (n) => alertsApi.deleteSilenceSchedule(n) });
        return <ConfirmDialog onConfirm={() => deleteMutation.mutate(name)} />;
      }
    `;
    expect(kinds(leaking)).toEqual(['unconfirmed-destructive']);
    expect(kinds(reversed)).toEqual(['unconfirmed-destructive']);
    expect(kinds(bothConfirmed)).toEqual([]);
  });

  it('does not report a mutation that touches nothing destructive', () => {
    const source = `
      const saveM = useMutation({ mutationFn: () => documentsApi.update(id, patch) });
      return <ConfirmDialog onConfirm={() => saveM.mutate()} />;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('KNOWN LIMIT: it reads prop names, so a component called SomethingDialog passes', () => {
    // The expectation is `[]` deliberately, exactly as the miss it replaces was.
    // This rule cannot tell a real <ConfirmDialog> from a component that happens
    // to end in "Dialog" and ignores its onConfirm prop. That last mile is a
    // behavioural question — it belongs to the Playwright suite, and a gate that
    // claimed to answer it would be asserting something it cannot see.
    const source = `
      const removeM = useMutation({ mutationFn: () => documentsApi.delete(doc.id) });
      return <NotADialog onConfirm={() => removeM.mutate()} />;
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
