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
    // Batch 859 changed what this test means. It used to assert that
    // `showToast(t('alerts.sloConfigCreateError'), 'error')` is a satisfactory
    // answer, and for seven batches that *was* the house standard — the gate
    // only ever asked whether a handler existed and did something. It is
    // `unreasoned-failure` now, below.
    const source = `
      const createM = useMutation({
        mutationFn: () => alertsApi.createSloConfig(form),
        onSuccess: () => { resetForm(); onHideForm(); },
        onError: (error) => showToast(failureMessage(t, 'alerts.sloConfigCreateError', error), 'error'),
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

describe('unreasoned-failure', () => {
  // Batch 859. The three rules above all pass on
  //   onError: () => showToast(t('alerts.deleteError'), 'error')
  // which is a handler, is not a no-op, and tells the user a delete failed and
  // stops there. Eleven of them shipped in that shape while the gate's own
  // summary line read "every write action reports its failure".
  const mutation = (onError, extra = '') => `
    const deleteM = useMutation({
      mutationFn: () => collectionsApi.deleteByKey(key),
      onError: ${onError},
      ${extra}
    });
  `;

  it('rejects a failure announced as a fixed sentence and nothing else', () => {
    expect(kinds(mutation("() => showToast(t('collections.deleteError'), 'error')")))
      .toEqual(['unreasoned-failure']);
  });

  it('names the sentence the user is shown', () => {
    const [violation] = scanSource(
      'src/pages/Sample.tsx',
      mutation("() => showToast(t('collections.deleteError'), 'error')"),
    );
    expect(violation.message).toContain('collections.deleteError');
  });

  it('accepts the shared helper, where `t` is passed as a value', () => {
    // The reason is inside `failureMessage`; the component never calls `t()`
    // itself, which is exactly why the message argument is not a bare literal.
    expect(kinds(mutation("(error) => showToast(failureMessage(t, 'collections.deleteError', error), 'error')")))
      .toEqual([]);
  });

  it('accepts a local formatter that receives the error', () => {
    // The message argument is the *call*, not `t('...')`. Measured before the
    // rule was written: a cruder "the body mentions no `t('...')`" test flagged
    // these six ApiKeys sites and reported them as defects they are not.
    expect(kinds(mutation("(error) => showToast(formatMutationError(t('collections.deleteError'), error), 'error')")))
      .toEqual([]);
  });

  it('accepts an interpolated sentence, for a different reason', () => {
    // `unreasoned-failure` is satisfied here — the sentence does carry the
    // reason — and this test used to assert a clean result. Batch 861 added
    // `interpolated-reason`, which objects on entirely different grounds: the
    // reason arrives unfiltered. The kind list is now two entries deep, and
    // both are visible rather than one silently hiding the other.
    const source = `
      const importM = useMutation({
        mutationFn: () => filesApi.import(file),
        onError: (msg) => showToast(t('files.importError', { error: msg }), 'error'),
      });
    `;
    expect(kinds(source)).toEqual(['interpolated-reason']);
  });

  it('accepts a key the failure itself supplies', () => {
    // `Documents.tsx` relocation: the key is built from the server's error code,
    // so the sentence the user reads *is* the specific reason. A rule that
    // judged the surface syntax would flag it as the opposite of the defect.
    expect(kinds(mutation("(error) => showToast(t(`documents.relocationErrors.${code || 'DEFAULT'}`), 'error')")))
      .toEqual([]);
  });

  it('does not judge the success toast in the same options object', () => {
    // Scanning the whole options object — the shape the older rules use — reads
    // `onSuccess: () => showToast(t('collections.deleteSuccess'), 'success')`
    // as an unreasoned failure. The rule walks to the `onError` body only.
    const [violation] = scanSource('src/pages/Sample.tsx', `
      const deleteM = useMutation({
        mutationFn: () => collectionsApi.deleteByKey(key),
        onSuccess: () => showToast(t('collections.deleteSuccess'), 'success'),
        onError: (error) => showToast(failureMessage(t, 'collections.deleteError', error), 'error'),
      });
    `);
    expect(violation).toBeUndefined();
  });

  it('reports each handler once, not once per neighbouring onError', () => {
    // `onError: () => showToast(...),` carries no semicolon. Reading the body as
    // "everything up to the next `;`" runs past the end of the handler and
    // swallows the *next* mutation's toast, so one defect is counted twice and
    // reported on the wrong line. This is the bug the census hit first.
    const source = `
      const deleteM = useMutation({
        mutationFn: () => api.delete(a),
        onError: () => showToast(t('a.deleteError'), 'error'),
      });
      const createM = useMutation({
        mutationFn: () => api.create(b),
        onError: () => showToast(t('a.createError'), 'error'),
      });
    `;
    const violations = scanSource('src/pages/Sample.tsx', source);
    expect(violations.map(v => v.kind)).toEqual(['unreasoned-failure', 'unreasoned-failure']);
    expect(violations.map(v => v.message)).toEqual([
      expect.stringContaining('a.deleteError'),
      expect.stringContaining('a.createError'),
    ]);
  });

  it('does not read a handler quoted inside a comment', () => {
    // `Alerts.tsx` documents its own history in a comment containing the
    // literal text `onError: () => {}`. A raw-source scan matches the prose and
    // then keeps reading, because the backticks make the body start outside any
    // brace — which is how one site was counted twice before comments were
    // stripped.
    const source = `
      const deleteM = useMutation({
        mutationFn: () => api.delete(a),
        // 这四处曾经是 \`onError: () => {}\`。
        onError: () => showToast(t('a.deleteError'), 'error'),
      });
    `;
    const violations = scanSource('src/pages/Sample.tsx', source);
    expect(violations).toHaveLength(1);
  });

  it('accepts a recorded, justified exemption', () => {
    // The exemption goes on the line above the reported one, and the reported
    // line is the `onError` property. Anchoring at the `showToast` call instead
    // would have put the comment several lines below the decision being made,
    // and the hatch would have been unreachable — which is how the first
    // version of this rule failed its own exemption test.
    const source = `
      const deleteM = useMutation({
        mutationFn: () => localStore.drop(key),
        // mutation-error-allow: the server always answers 204, there is no reason to show
        onError: () => showToast(t('a.deleteError'), 'error'),
      });
    `;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.kind).toBe('unreasoned-failure');
    expect(violation.detail).toContain('no reason to show');
  });

  it('still calls a swallowing handler a no-op, not merely unreasoned', () => {
    // The empty handler is the more serious defect and keeps its own kind; a
    // handler that shows nothing has nothing to add a reason to.
    expect(kinds(mutation('() => {}'))).toEqual(['no-op-error-handler']);
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('unreasoned-failure');
  });
});

describe('interpolated-reason', () => {
  // Batch 861. `unreasoned-failure` asks whether the reason reaches the user.
  // This asks what happens on the paths where it does — and the answer was that
  // the message got built as `t('files.importError', { error: msg })`, which
  // arrives unfiltered. `api/client.ts:54` rejects a network failure with
  // `new Error('Failed to fetch')`, so the user read "Import failed: Failed to
  // fetch": a sentence about the connection, with no length bound, impossible
  // to tell apart from a server answer. Nine sites were written that way until
  // Batch 860 moved them onto `failureMessage`.
  it('rejects a catch that interpolates the reason into a translated sentence', () => {
    const source = `
      const open = async () => {
        try {
          const blob = await filesApi.getRawFile(path);
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          showToast(t('files.previewError', { error: msg }), 'error');
        }
      };
    `;
    expect(kinds(source)).toEqual(['interpolated-reason']);
  });

  it('rejects a failure sentence assembled by hand', () => {
    // The Collections purge dialog: `${t('collections.purge.applyError')}: ${message}`.
    // A template literal is a different shape from an options object and was
    // missed by the first version of this rule, which found 8 of the 9 sites.
    const source = `
      const applyM = useMutation({
        mutationFn: () => collectionsApi.applyPurge(payload),
        onError: (error) => {
          const message = errorMessage(error);
          showToast(\`\${t('collections.purge.applyError')}: \${message}\`, 'error');
        },
      });
    `;
    expect(kinds(source)).toEqual(['interpolated-reason']);
  });

  it('accepts the same interpolation outside a failure path', () => {
    // The load-bearing case, and the reason the rule is scoped to failure paths
    // rather than to interpolation. `files.embedFailed` interpolates
    // `result.embedMessage` — a field of a **200 response** saying why the
    // embedding did not complete. A status note about a request that succeeded
    // is not a failure reason, and running it through the transport-sentinel
    // list would be the mistake.
    const source = `
      const embedM = useMutation({
        mutationFn: () => filesApi.embed(id, force),
        onSuccess: () => {
          showToast(t('files.embedFailed', { message: result.embedMessage }), 'error');
        },
        onError: (error) => showToast(failureMessage(t, 'files.embedError', error), 'error'),
      });
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts the shared helper, where `t` is passed as a value', () => {
    expect(kinds(`
      const deleteM = useMutation({
        mutationFn: () => api.delete(id),
        onError: (error) => showToast(failureMessage(t, 'collections.deleteError', error), 'error'),
      });
    `)).toEqual([]);
  });

  it('reports the line of the interpolation, not of the mutation', () => {
    // A real bug in the first version: the handler's index is relative to the
    // options slice, not to the file, so adding only the slice's own offset
    // pointed the report at `useMutation(` — the one line the author is not
    // looking at. Caught by comparing the reported line against the known
    // position of the toast, not by any assertion about the message.
    const source = `const createM = useMutation({
      mutationFn: () => collectionsApi.create(form),
      onError: (error) => {
        showToast(t('collections.createError', { message: error.message }), 'error');
      },
    });`;
    const [violation] = scanSource('src/components/CreateCollectionModal.tsx', source);
    expect(violation.line).toBe(4);
    expect(violation.message).toContain('createM');
  });

  it('names the shape it rejected', () => {
    const interpolated = scanSource('src/pages/Sample.tsx', `
      try { await load(); } catch (err) { showToast(t('k', { error: msg }), 'error'); }
    `)[0];
    expect(interpolated.message).toContain('interpolates');

    const assembled = scanSource('src/pages/Sample.tsx', `
      const m = useMutation({
        mutationFn: () => api.go(),
        onError: (e) => showToast(\`\${t('k')}: \${e.message}\`, 'error'),
      });
    `)[0];
    expect(assembled.message).toContain('assembles');
  });

  it('accepts a recorded, justified exemption', () => {
    const source = `
      // mutation-error-allow: the server answers 200 with a partial-failure count
      try { await load(); } catch (err) { showToast(t('k', { error: err.message }), 'error'); }
    `;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.detail).toContain('partial-failure count');
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('interpolated-reason');
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
