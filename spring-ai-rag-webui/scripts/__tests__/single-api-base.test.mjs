import { describe, it, expect } from 'vitest';
import {
  findBasePathCopies,
  readApiBase,
  VIOLATION_KINDS,
  BASE_DECLARATION,
} from '../check-single-api-base.mjs';

// A gate that cannot fail is worse than no gate. Every case below is here
// because the first version of one of them was wrong:
//
//   * the first call-site pattern in the sibling route-contract test accepted
//     double quotes and backticks but not the single quotes the api modules
//     actually use, so it saw 47 of 105 call sites and reported a clean sweep
//     over the half it could see. A scanner that is quietly blind looks exactly
//     like a scanner that found nothing wrong.
//   * the two `?? 0` classes in `webui-design-language` §9.1 are the same shape
//     at the rule level: a pattern that cannot see a real case and a file that
//     is genuinely clean are the same observation.
//
// So the positive control comes first and the "it is fine" cases are checked
// against it, rather than the other way round.
const withCopy = (source) => [{ relPath: 'src/hooks/useSSE.ts', source }];

const clientSource = `import axios from 'axios';
export const BASE_URL = '/api/v1/rag';
export const apiClient = axios.create({ baseURL: BASE_URL });
`;

describe('api base is declared once', () => {
  it('reports a file that spells the base path out', () => {
    const violations = findBasePathCopies(
      withCopy(`await fetch('/api/v1/rag/chat/stream', { method: 'POST' });`),
      '/api/v1/rag',
    );
    expect(violations.map(v => v.kind)).toEqual([VIOLATION_KINDS.BASE_PATH_LITERAL]);
    expect(violations[0].file).toBe('src/hooks/useSSE.ts');
  });

  it('does not report the file that declares it', () => {
    const violations = findBasePathCopies(
      [{ relPath: BASE_DECLARATION, source: clientSource }],
      '/api/v1/rag',
    );
    expect(violations).toEqual([]);
  });

  it('does not report a file that imports the base and builds on it', () => {
    const violations = findBasePathCopies(
      withCopy(`import { BASE_URL } from '../api/client';
await fetch(BASE_URL + '/chat/stream', { method: 'POST' });`),
      '/api/v1/rag',
    );
    expect(violations).toEqual([]);
  });

  it('does not report a comment that quotes the base path', () => {
    // Otherwise the three call sites would fail this gate for documenting the
    // rule they are following. Comments are stripped first, and that is a
    // correctness requirement rather than a convenience.
    const violations = findBasePathCopies(
      withCopy(`// posts to /api/v1/rag/client-errors, so a wrong base here is silent
await fetch(BASE_URL + '/client-errors', { method: 'POST' });`),
      '/api/v1/rag',
    );
    expect(violations).toEqual([]);
  });

  it('ignores test files, which legitimately quote routes as fixtures', () => {
    const violations = findBasePathCopies(
      [{ relPath: 'src/hooks/useSSE.test.ts', source: "fetch('/api/v1/rag/chat/stream');" }],
      '/api/v1/rag',
    );
    expect(violations).toEqual([]);
  });

  it('reports a copy even when it is the only thing in the file', () => {
    // The sibling gate's floor assertion exists because a scanner that stops
    // matching anything reports "clean" over an empty set. This case is the
    // mirror: a one-line file must still be read.
    const violations = findBasePathCopies(
      withCopy("export const P = '/api/v1/rag/documents/upload';"),
      '/api/v1/rag',
    );
    expect(violations).toHaveLength(1);
  });
});

describe('the base is read, not carried', () => {
  it('takes the value from the client module', () => {
    expect(readApiBase(clientSource)).toBe('/api/v1/rag');
  });

  it('follows the declaration when the base changes', () => {
    // The whole point: a gate that held its own copy of the string would keep
    // enforcing /api/v1/rag after the project moved to /api/v2, and would
    // report every new call site as clean while the old prefix walked free.
    const v2 = clientSource.replace('/api/v1/rag', '/api/v2/rag');
    const base = readApiBase(v2);
    expect(base).toBe('/api/v2/rag');
    expect(findBasePathCopies(withCopy("fetch('/api/v2/rag/chat/stream');"), base)).toHaveLength(1);
    expect(findBasePathCopies(withCopy("fetch('/api/v1/rag/chat/stream');"), base)).toEqual([]);
  });

  it('refuses to report a clean tree when it cannot find the declaration', () => {
    // A gate that cannot find the value it enforces has no opinion. Returning
    // "no violations" for an empty question is how a gate becomes decorative,
    // so this throws instead.
    expect(() => readApiBase('const notExported = "/api/v1/rag";')).toThrow(/BASE_URL/);
  });
});
