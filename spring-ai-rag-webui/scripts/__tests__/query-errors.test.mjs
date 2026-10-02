import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanSource, VIOLATION_KINDS } from '../check-query-errors.mjs';

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

describe('silent-query, named form', () => {
  it('reports a read that neither handles nor renders its error', () => {
    const source = `
      const readinessQ = useQuery({ queryKey: ['r'], queryFn: fetchIt });
      return <div>{readinessQ.data && <Panel data={readinessQ.data} />}</div>;
    `;
    // One defect, one report: the `{q.data && …}` shape is the *reason* this
    // read is silent, so it is reported under the more specific kind rather
    // than also appearing as a generic one.
    expect(kinds(source)).toEqual(['empty-panel-on-error']);
  });

  it('accepts a read whose failure the component renders', () => {
    const source = `
      const reportQ = useQuery({ queryKey: ['r'], queryFn: fetchIt });
      return reportQ.isError
        ? <div role="alert">Could not load the report</div>
        : <Report data={reportQ.data} />;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a read that handles the error in an onError option', () => {
    const source = `
      const jobsQ = useQuery({
        queryKey: ['j'],
        queryFn: fetchJobs,
        onError: (e: Error) => showToast(e.message, 'error'),
      });
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('does not accept a render that only checks a different query', () => {
    // Two queries in one file, one banner. The query that actually failed is
    // still unnamed, which is the same silent misreport in a subtler dress.
    // `historyQ` is genuinely covered, so only `reportQ` is reported.
    const source = `
      const reportQ = useQuery({ queryKey: ['r'], queryFn: fetchReport });
      const historyQ = useQuery({ queryKey: ['h'], queryFn: fetchHistory });
      return historyQ.isError ? <div role="alert">Failed</div> : <Report />;
    `;
    expect(kinds(source)).toEqual(['silent-query']);
  });

  it('does not accept a render that is commented out', () => {
    const source = `
      const reportQ = useQuery({ queryKey: ['r'], queryFn: fetchReport });
      // {reportQ.isError && <div role="alert">Failed</div>}
    `;
    expect(kinds(source)).toEqual(['silent-query']);
  });
});

describe('silent-query, destructured form', () => {
  // This is the branch the first version of this gate did not have. It matched
  // only `const name = useQuery(`, so it judged 17 of the 37 reads in `src/`
  // and printed "every read reports its failure". These cases exist so that
  // removing the destructured branch fails loudly instead of restoring that
  // silent 54% gap.

  it('reports a destructured read that binds no error at all', () => {
    // The Alerts.tsx defect, verbatim in shape: three tabs that render
    // "No active alerts" when the request failed.
    const source = `
      const { data, isPending } = useQuery({
        queryKey: ['alerts'],
        queryFn: () => alertsApi.listActive(),
        refetchInterval: 30_000,
      });
      if (isPending) return <Loading />;
      if (!data?.data?.length) return <EmptyState>No active alerts</EmptyState>;
    `;
    expect(kinds(source)).toEqual(['silent-query']);
  });

  it('reports a read that binds only data', () => {
    // The ReembedAllButton defect: `isLoading || !status` turns a failed
    // status request into a skeleton that never resolves.
    const source = `
      const { data: status, isLoading } = useQuery({ queryKey: ['s'], queryFn: fetchStatus });
      if (isLoading || !status) return <Skeleton />;
    `;
    expect(kinds(source)).toEqual(['silent-query']);
  });

  it('accepts a destructured read that binds isError', () => {
    const source = `
      const { data, isError, refetch } = useQuery({ queryKey: ['c'], queryFn: fetchCols });
      if (isError) return <QueryErrorBanner onRetry={() => refetch()}>Failed</QueryErrorBanner>;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a destructured read that binds error, renamed or not', () => {
    const source = `
      const { data: treeData, error } = useQuery({ queryKey: ['t'], queryFn: fetchTree });
      return error ? <p>{String(error)}</p> : <Tree data={treeData} />;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts an aliased error binding', () => {
    // `isError: collectionsError` is the shape this batch adopted where the
    // file already binds a different `error`; the checker must read the source
    // name, not the local one.
    const source = `
      const { data, isError: capabilityError, refetch } = useQuery({
        queryKey: ['capabilities'],
        queryFn: fetchCapabilities,
      });
      if (capabilityError) return <p>Failed</p>;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('does not let one query’s error binding excuse a sibling', () => {
    // The per-binding scope is the whole point: `error` in a destructure is
    // the only thing that can carry *that* query's failure.
    const source = `
      const { data: collectionsData } = useQuery({ queryKey: ['c'], queryFn: fetchCols });
      const { data: treeData, error } = useQuery({ queryKey: ['t'], queryFn: fetchTree });
      return error ? <p>{String(error)}</p> : <Tree data={treeData} collections={collectionsData} />;
    `;
    expect(kinds(source)).toEqual(['silent-query']);
  });

  it('reports a read that binds an error and then never reads it', () => {
    // Mutation testing found this pass: deleting the banner leaves
    // `isError` in the destructuring, so a gate that only asks "is it bound?"
    // reports full coverage over a component that is exactly as silent as it
    // was before. The binding has to be *read*, not merely named.
    const source = `
      const { data, isPending, isError } = useQuery({ queryKey: ['a'], queryFn: fetchA });
      if (isPending) return <Loading />;
      if (!data?.data?.length) return <EmptyState>No active alerts</EmptyState>;
    `;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.kind).toBe('silent-query');
    expect(violation.message).toContain('is never read');
  });

  it('accepts a destructured read that handles the error in an onError option', () => {
    const source = `
      const { data, isPending } = useQuery({
        queryKey: ['m'],
        queryFn: fetchModels,
        onError: (e: Error) => showToast(e.message, 'error'),
      });
    `;
    expect(kinds(source)).toEqual([]);
  });
});

describe('reporting', () => {
  it('names the declaration line, not the first line of the file', () => {
    const source = `
// leading comment that must not shift the reported line
const reportQ = useQuery({ queryKey: ['r'], queryFn: fetchReport });
`;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.line).toBe(3);
    expect(violation.message).toContain('reportQ');
  });

  it('lists the bindings it inspected, so a violation names its own query', () => {
    const source = `
      const { data, isPending } = useQuery({ queryKey: ['a'], queryFn: fetchA });
    `;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.message).toContain('data');
    expect(violation.message).toContain('isPending');
  });

  it('carries a recorded exemption as context without granting a pass', () => {
    // The exemption is a note for the human reading a red gate, exactly as in
    // the mutation gate. It must not turn the gate green on its own: a comment
    // that silences a check is a comment anyone can write.
    const source = `
      // query-error-allow: this read only fills an optional filter list
      const { data, isPending } = useQuery({ queryKey: ['f'], queryFn: fetchFilters });
    `;
    const [violation] = scanSource('src/pages/Sample.tsx', source);
    expect(violation.detail).toContain('optional filter list');
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('silent-query');
    expect(VIOLATION_KINDS).toContain('empty-panel-on-error');
  });
});

describe('misses, pinned so they stay visible', () => {
  it('cannot tell which sub-component a use belongs to', () => {
    // Mutation testing found this pass. `Alerts.tsx` holds three sub-components
    // that each destructure `isError`; delete the banner from one of them and
    // the name still occurs later in the file, so the gate is green. Stripping
    // the sibling *declarations* out of the searched region does not help,
    // because their JSX uses remain. Closing it needs scope analysis, and a
    // scope analyzer that guesses wrong emits false alarms — the one failure
    // mode a gate must not have. The behavioural tests cover it instead.
    //
    // The expectation is `[]` deliberately: this asserts the miss, so closing
    // the blind spot later has to be a conscious edit to this line rather than
    // something that quietly invalidates a passing test.
    const source = `
      function AlertsTab() {
        const { data, isPending, isError } = useQuery({ queryKey: ['a'], queryFn: fetchA });
        if (isPending) return <Loading />;
        if (!data?.data?.length) return <EmptyState>No active alerts</EmptyState>;
      }
      function SloConfigsTab() {
        const { data, isPending, isError } = useQuery({ queryKey: ['s'], queryFn: fetchS });
        if (isError) return <QueryErrorBanner>Failed</QueryErrorBanner>;
        return <List data={data} />;
      }
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('is file-scoped for the named form, so a sibling banner can excuse it', () => {
    // Known and accepted: `Alerts.tsx` holds four sub-components. A file-scoped
    // check cannot tell which one rendered the banner, so a query that is
    // genuinely silent can hide behind another's `isError`. It fails as a miss,
    // never as a false alarm, which is the right way round.
    const source = `
      const reportQ = useQuery({ queryKey: ['r'], queryFn: fetchReport });
      const historyQ = useQuery({ queryKey: ['h'], queryFn: fetchHistory });
      return historyQ.isError ? <div role="alert">Failed</div> : <Report data={reportQ.data} />;
    `;
    expect(kinds(source)).toEqual(['silent-query']);
  });
});

describe('the design-language document tracks this gate', () => {
  const documentSection = relativePath =>
    readFileSync(join(projectRoot, '..', relativePath), 'utf8').match(/## 9\.[^\n]*\n[\s\S]*?\n## 10\./);

  it('documents this gate in both languages', () => {
    for (const relativePath of [
      'docs/webui-design-language.md',
      'docs/webui-design-language-zh-CN.md',
    ]) {
      const section = documentSection(relativePath);
      expect(section, `${relativePath} has no section 9 for this gate`).not.toBeNull();
      expect(section[0]).toContain('npm run check:query-errors');
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

  it('surfaces every failed read', () => {
    // Batch 797's twenty-one findings, in two shapes: nine named reads in
    // Embeddings/Evaluation/Metrics, and twelve destructured ones across
    // Alerts, ABTest, Search, Chat, Collections, Documents, Files and
    // ReembedAllButton.
    expect(violations).toEqual([]);
  });

  it('actually found reads to judge, in both forms', () => {
    // Without this, deleting either branch of the checker would make the case
    // above pass for the wrong reason — which is exactly how the first version
    // of this gate reported full coverage while ignoring half the queries.
    const sources = files.map(path => readFileSync(path, 'utf8')).join('\n');
    const named = (sources.match(/const\s+[A-Za-z_$][\w$]*\s*=\s*useQuery\s*\(/g) ?? []).length;
    const destructured = (sources.match(/const\s*\{[^}]*\}\s*=\s*useQuery\s*\(/g) ?? []).length;
    expect(named).toBeGreaterThanOrEqual(17);
    expect(destructured).toBeGreaterThanOrEqual(20);
  });
});
