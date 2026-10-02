import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanSource, VIOLATION_KINDS } from '../check-mutation-errors.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

/** Collect the kinds reported for a source snippet, for terse assertions. */
const kinds = (source, relativePath = 'src/pages/Sample.tsx') =>
  scanSource(relativePath, source).map(violation => violation.kind);

function walk(directory) {
  const entries = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) entries.push(...walk(path));
    else if (statSync(path).isFile() && /\.tsx?$/.test(entry.name)) entries.push(path);
  }
  return entries;
}

describe('silent-mutation', () => {
  it('reports a write action that neither toasts nor renders its error', () => {
    // The Evaluation.tsx SuitesPanel defect: two buttons, two mutations, and
    // no output at all when the server rejects the request. The user cannot
    // tell a rejected write from a broken button.
    const source = `
      const createM = useMutation({
        mutationFn: () => evaluationApi.createSuite({ suiteKey, name }),
      });
      return <button onClick={() => createM.mutate()}>Create</button>;
    `;
    expect(kinds(source)).toEqual(['silent-mutation']);
  });

  it('accepts a mutation that shows a toast on error', () => {
    const source = `
      const retryM = useMutation({
        mutationFn: () => api.retry(id),
        onError: (e: Error) => showToast(e.message, 'error'),
      });
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a mutation whose error the component renders', () => {
    // The shape the Batch 791 fixes use: an inline alert bound to `.isError`.
    const source = `
      const startM = useMutation({ mutationFn: () => evaluationApi.createRun({ suiteKey }) });
      return startM.isError ? <div role="alert">Unable to start the run</div> : null;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('does not accept a render that only checks a different mutation', () => {
    // The subtle version: a component with two mutations and one error banner
    // is a real defect, not a style choice — the failing action is unnamed.
    const source = `
      const cancelM = useMutation({ mutationFn: () => api.cancel(id) });
      const retryM = useMutation({ mutationFn: () => api.retry(id) });
      return retryM.isError ? <div role="alert">Failed</div> : null;
    `;
    expect(kinds(source)).toEqual(['silent-mutation']);
  });

  it('does not accept a render that is commented out', () => {
    // Comments are stripped before the scan, so a disabled error banner does
    // not read as coverage.
    const source = `
      const deleteM = useMutation({ mutationFn: () => api.remove(id) });
      return null; // {deleteM.isError && <div role="alert">Failed</div>}
    `;
    expect(kinds(source)).toEqual(['silent-mutation']);
  });

  it('names the declaration line, not the first line of the file', () => {
    const source = `
// leading comment that must not shift the reported line
const saveM = useMutation({ mutationFn: () => api.save() });
`;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.line).toBe(3);
    expect(violation.message).toContain('saveM');
  });

  it('accepts a recorded, justified exemption', () => {
    const source = `
      // mutation-error-allow: this mutation only ever writes to a local draft store
      const draftM = useMutation({ mutationFn: () => localStore.save() });
    `;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.detail).toContain('local draft store');
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('silent-mutation');
  });
});

describe('no-op-error-handler', () => {
  // Batch 798. The rule existed for seven batches and only ever asked whether
  // the key `onError` appeared, so `onError: () => {}` satisfied it. Four of
  // those shipped in `Alerts.tsx`. A handler that swallows is not a handler.
  it('rejects a write whose onError exists but does nothing', () => {
    const source = `
      const deleteM = useMutation({
        mutationFn: () => alertsApi.deleteSilenceSchedule(name),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: ['silence'] }),
        onError: () => {},
      });
    `;
    expect(kinds(source)).toEqual(['no-op-error-handler']);
  });

  it('rejects a handler whose body is null rather than empty', () => {
    const source = `
      const createM = useMutation({ mutationFn: () => api.create(x), onError: () => null });
    `;
    expect(kinds(source)).toEqual(['no-op-error-handler']);
  });

  it('judges the declaration, not whether anything fires it', () => {
    // Stated rather than assumed: this rule reads the options object and never
    // checks for a `.mutate(` call, the same scope the `silent-mutation` rule
    // has always had. A dead declaration still carries a handler that would
    // swallow if it were ever wired up, and the fix is the same either way.
    const source = `
      const unusedM = useMutation({ mutationFn: () => api.create(x), onError: () => {} });
      return <div>{nothing.toString()}</div>;
    `;
    expect(kinds(source)).toEqual(['no-op-error-handler']);
  });

  it('accepts the same mutation once the handler reports the failure', () => {
    const source = `
      const createM = useMutation({
        mutationFn: () => alertsApi.createSloConfig(form),
        onSuccess: () => { resetForm(); onHideForm(); },
        onError: () => showToast(t('alerts.sloConfigCreateError'), 'error'),
      });
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('does not treat a sibling mutation’s empty handler as coverage', () => {
    const source = `
      const deleteM = useMutation({ mutationFn: () => api.delete(a), onError: () => {} });
      const createM = useMutation({ mutationFn: () => api.create(b), onError: () => showToast('x') });
    `;
    expect(kinds(source)).toEqual(['no-op-error-handler']);
  });
});

describe('swallowed-rejection', () => {
  it('rejects a catch that discards the reason without saying why', () => {
    const source = `
      try {
        localStorage.setItem(KEY, value);
      } catch {}
    `;
    expect(kinds(source)).toEqual(['swallowed-rejection']);
  });

  it('accepts a catch that states why discarding is acceptable', () => {
    // Every legitimate one in `src/` is in this shape. The comment is the whole
    // rule: it costs one line, at the moment the decision is being made.
    const source = `
      try {
        localStorage.setItem(KEY, value);
      } catch {
        // Persisting is best-effort: the theme still applies for this tab.
      }
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('does not demand a reason from a catch that does something', () => {
    const source = `
      try {
        await submit(x);
      } catch {
        showToast(t('chat.feedbackError'), 'error');
      }
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('leaves writing to the console alone', () => {
    // A console trace is a decision with a visible trail. Whether one of these
    // deserves a user-facing message is a product call this gate should not
    // make on its own.
    const source = `
      try {
        await load();
      } catch (err) {
        console.error('Failed to fetch document content:', err);
      }
    `;
    expect(kinds(source)).toEqual([]);
  });
});

describe('the design-language document tracks this gate', () => {
  // The design gate shipped a document claiming "ten classes" while the
  // checker enforced eleven, and the accessibility gate inherited the same
  // protection only after making the same mistake twice. So the new gate
  // inherits it too: both language versions must name the command and the
  // kind, or a rule can quietly stop existing.
  const documentSection = (relativePath) =>
    readFileSync(join(projectRoot, '..', relativePath), 'utf8').match(/## 6\.[^\n]*\n[\s\S]*?\n## 7\./);

  it('documents this gate in both languages', () => {
    for (const relativePath of [
      'docs/webui-design-language.md',
      'docs/webui-design-language-zh-CN.md',
    ]) {
      const section = documentSection(relativePath);
      expect(section, `${relativePath} has no section 6 for this gate`).not.toBeNull();
      expect(section[0]).toContain('npm run check:mutation-errors');
      for (const kind of VIOLATION_KINDS) {
        expect(section[0], `${relativePath} does not document ${kind}`).toContain(kind);
      }
    }
  });
});

describe('the real component tree', () => {
  const files = walk(sourceRoot).filter(path => !/\.(test|spec)\.[jt]sx?$/.test(path));
  const violations = files.flatMap(path =>
    scanSource(path.replace(`${projectRoot}/`, ''), readFileSync(path, 'utf8')),
  );

  it('surfaces every failed write', () => {
    // Batch 791's six findings: cancelM, retryM, applyRepairM in Embeddings.tsx;
    // createM, versionM, startM in Evaluation.tsx.
    expect(violations).toEqual([]);
  });

  it('actually found mutations to judge', () => {
    // Without this, deleting the whole rule would make the case above pass for
    // the wrong reason.
    const source = readFileSync(join(sourceRoot, 'pages', 'Evaluation.tsx'), 'utf8');
    expect((source.match(/useMutation\(/g) ?? []).length).toBeGreaterThanOrEqual(5);
  });
});
