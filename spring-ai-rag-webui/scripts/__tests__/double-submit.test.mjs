import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanSource, VIOLATION_KINDS } from '../check-double-submit.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

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

describe('unguarded-write', () => {
  it('reports a mutation that is fired but never observed as pending', () => {
    // The Evaluation.tsx SuitesPanel defect: nothing anywhere in the file reads
    // createM.isPending, so a second click sends a second create request.
    const source = `
      const createM = useMutation({ mutationFn: () => api.createSuite({ suiteKey, name }) });
      return <button onClick={() => createM.mutate()}>Create</button>;
    `;
    expect(kinds(source)).toEqual(['unguarded-write']);
  });

  it('accepts a mutation whose pending state disables the control', () => {
    const source = `
      const startM = useMutation({ mutationFn: () => api.start(suiteKey) });
      return <button onClick={() => startM.mutate()} disabled={startM.isPending}>Start</button>;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a pending guard that lives in a sibling expression', () => {
    // The check asks a file-level question on purpose. A guard expressed
    // anywhere counts, which is what keeps the rule free of false alarms.
    const source = `
      const retryM = useMutation({ mutationFn: () => api.retry(id) });
      const busy = retryM.isPending;
      return <button disabled={busy} onClick={() => retryM.mutate()}>Retry</button>;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a guard that spreads across many lines of JSX', () => {
    // Regression for the survey script that produced two false positives: it
    // parsed the <button> opening tag and gave up on nested braces, reporting
    // ApiKeys.tsx:1042 as unguarded when the next line is exactly the guard.
    const source = `
      const immediateMutation = useMutation({ mutationFn: () => api.rotate() });
      return (
        <Button
          variant="danger"
          onClick={() => immediateMutation.mutate()}
          disabled={immediateMutation.isPending}
        >
          {immediateMutation.isPending ? t('common.loading') : t('apiKeys.rotateImmediately')}
        </Button>
      );
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('ignores a mutation that is never fired', () => {
    const source = `
      const unusedM = useMutation({ mutationFn: () => api.thing() });
      return <p>static</p>;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('does not confuse one mutation with another', () => {
    // Only the mutations that are actually fired are judged; reading another
    // mutation's isPending is not evidence about this one.
    const source = `
      const saveM = useMutation({ mutationFn: () => api.save() });
      const deleteM = useMutation({ mutationFn: () => api.remove() });
      return (
        <>
          <button onClick={() => saveM.mutate()} disabled={deleteM.isPending}>Save</button>
          <button onClick={() => deleteM.mutate()} disabled={deleteM.isPending}>Delete</button>
        </>
      );
    `;
    expect(kinds(source)).toEqual(['unguarded-write']);
  });

  it('does not read a commented-out guard as coverage', () => {
    const source = `
      const startMut = useMutation({ mutationFn: () => api.start() });
      return <button onClick={() => startMut.mutate()}>Start</button>;
      // {startMut.isPending && <Spinner />}
    `;
    expect(kinds(source)).toEqual(['unguarded-write']);
  });

  it('counts mutateAsync as firing too', () => {
    const source = `
      const syncM = useMutation({ mutationFn: () => api.sync() });
      return <button onClick={() => { void syncM.mutateAsync(); }}>Sync</button>;
    `;
    expect(kinds(source)).toEqual(['unguarded-write']);
  });

  it('names the declaration line and the mutation', () => {
    const source = `
// leading comment that must not shift the reported line
const publishM = useMutation({ mutationFn: () => api.publish() });
return <button onClick={() => publishM.mutate()}>Publish</button>;
`;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.line).toBe(3);
    expect(violation.message).toContain('publishM');
  });

  // Batch 868. This case used to be named "accepts a recorded, justified
  // exemption" while asserting only `violation.detail` — it never checked that
  // the finding disappeared, because the hatch did not work. It reported the
  // violation anyway and pasted the reason into the message. The gate's own
  // error message told people to write this comment, so following the
  // documented remedy could never turn the gate green. The identical
  // misnamed case was copy-pasted into mutation-errors.test.mjs, where
  // annotating *is* the intended behaviour.
  //
  // These four cases pin the fixed semantics: a reason written after `--`
  // silences the finding; a bare keyword does not.
  it('drops the finding when the line above carries a justified exemption', () => {
    const source = `
// double-submit-allow -- fire-and-forget audit ping, harmless if sent twice
const auditM = useMutation({ mutationFn: () => api.ping() });
return <button onClick={() => auditM.mutate()}>Ping</button>;
`;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts the block-comment form of the exemption too', () => {
    const source = `
/* double-submit-allow -- metrics beacon, duplicate calls are idempotent */
const beaconM = useMutation({ mutationFn: () => api.beacon() });
return <button onClick={() => beaconM.mutate()}>Beacon</button>;
`;
    expect(kinds(source)).toEqual([]);
  });

  it('still reports when the comment is a bare keyword with no reason', () => {
    const source = `
// double-submit-allow
const auditM = useMutation({ mutationFn: () => api.ping() });
return <button onClick={() => auditM.mutate()}>Ping</button>;
`;
    expect(kinds(source)).toEqual(['unguarded-write']);
  });

  it('still reports when the reason is only a colon and no separator', () => {
    // The old `double-submit-allow: <reason>` spelling. Accepting it would
    // reopen the one-keyword hole the `--` separator exists to close.
    const source = `
// double-submit-allow: harmless
const auditM = useMutation({ mutationFn: () => api.ping() });
return <button onClick={() => auditM.mutate()}>Ping</button>;
`;
    expect(kinds(source)).toEqual(['unguarded-write']);
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('unguarded-write');
  });
});

describe('the known blind spot of this gate', () => {
  // Mutation T during Batch 796: deleting the four `disabled={x.isPending}`
  // guards from ABTest.tsx left the gate green, because the same file still
  // reads `x.isPending` in the button *label*
  // (`{x.isPending ? t('common.loading') : t('abtest.start')}`). The control
  // shows "Loading…" and is still perfectly clickable.
  //
  // That is a genuine miss, not a passing test pretending to be a guard. It is
  // pinned here so it stays visible: anyone who later makes this check
  // stricter will see this case flip, and anyone who removes the
  // file-level looseness on purpose sees why.
  it('cannot see a pending read that does not block the click', () => {
    const source = `
      const startMut = useMutation({ mutationFn: () => api.start(id) });
      return (
        <Button onClick={() => startMut.mutate()}>
          {startMut.isPending ? 'Loading…' : 'Start'}
        </Button>
      );
    `;
    expect(kinds(source)).toEqual([]);

    // The control is still clickable while pending, so the real defence has to
    // come from a behavioural test rather than from this gate.
    expect(source).not.toContain('disabled=');
  });
});

describe('the real component tree', () => {
  const files = walk(sourceRoot).filter(path => !/\.(test|spec)\.[jt]sx?$/.test(path));
  const violations = files.flatMap(path =>
    scanSource(path.replace(`${projectRoot}/`, ''), readFileSync(path, 'utf8')),
  );

  it('guards every write action that is fired', () => {
    // Batch 796's nine findings: ABTest (startMut x2, pauseMut, stopMut),
    // Embeddings (cancelM, retryM), Evaluation (createM, versionM, startM).
    expect(violations).toEqual([]);
  });

  it('actually found mutations to judge', () => {
    const source = readFileSync(join(sourceRoot, 'pages', 'ABTest.tsx'), 'utf8');
    expect((source.match(/useMutation\(/g) ?? []).length).toBeGreaterThanOrEqual(3);
    expect(source).toContain('startMut.isPending');
  });
});
