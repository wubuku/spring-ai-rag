#!/usr/bin/env node
/**
 * Single API base gate: the WebUI's API base path is written down once.
 *
 * Why this exists
 * ---------------
 * Batch 933 surveyed how this frontend names a route on the server and found
 * three ways, of which the route-contract test in `OpenApiContractTest` saw one:
 *
 *   1. `apiClient.<verb>('/path')` inside `src/api/*.ts` — 105 call sites
 *   2. `fetch('/api/v1/rag/…', { method })` outside `src/api` — 3 call sites
 *   3. an object URL from a `Blob`, which is not a route at all
 *
 * The three `fetch` sites — the SSE chat stream, the multipart upload, and the
 * client-error report — each spelled `/api/v1/rag` out in full. So the base path
 * lived in four places, and a version bump would have meant four edits with
 * nothing pointing at three of them. The error-boundary one is the worst of the
 * three: its `fetch` is wrapped in a `catch` that swallows everything on
 * purpose, so a wrong base there does not fail visibly, it just stops
 * reporting.
 *
 * The rule is therefore not "do not use `fetch`" — `fetch` is the only way to
 * stream a response body, and the SSE stream needs it. The rule is that the
 * prefix is stated once and imported everywhere else.
 *
 * How the base is found
 * ---------------------
 * By reading `src/api/client.ts`, never by carrying a copy of the string here.
 * A gate that holds its own copy of the value it enforces is a machine number
 * nothing recomputes, which is the exact rot this repository has already paid
 * for in a dozen other places. Change the base and this gate follows.
 *
 * Known limits, stated rather than discovered later
 * -------------------------------------------------
 *   * It matches a literal. A path assembled at runtime — `BASE_URL + '/x'`,
 *     which is what the three call sites now do, or `'/' + 'a' + '/b'` — is
 *     invisible to it. That is the point of the rule, but it does mean the gate
 *     proves "no second copy of this string", not "every request is built from
 *     the exported constant".
 *   * Comments are stripped first, so a comment that quotes the base path is not
 *     a violation. Without that, documenting the rule in the three files that
 *     follow it would fail the gate for following it correctly.
 *
 * Run:
 *   node scripts/check-single-api-base.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const SRC_DIR = join(projectRoot, 'src');
const CLIENT = join(SRC_DIR, 'api', 'client.ts');

/** Where the base is declared. Everything else imports it from here. */
export const BASE_DECLARATION = 'src/api/client.ts';

export const VIOLATION_KINDS = Object.freeze({
  BASE_PATH_LITERAL: 'api-base-path-literal',
});

/**
 * Reads the base path out of the client module.
 *
 * Throws rather than returning a default when the declaration is missing: a gate
 * that cannot find the value it is enforcing has no opinion, and reporting "no
 * violations" for an empty question is how a gate becomes decorative.
 */
export function readApiBase(clientSource) {
  const match = /export\s+const\s+BASE_URL\s*=\s*['"]([^'"]+)['"]/.exec(clientSource);
  if (match === null) {
    throw new Error(
      `${BASE_DECLARATION} must declare an exported BASE_URL; this gate enforces`
      + ' that one value and cannot enforce a value it cannot find',
    );
  }
  return match[1];
}

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path, out);
    else if (/\.(ts|tsx)$/.test(entry)) out.push(path);
  }
  return out;
}

/** Test fixtures and setup are not shipped code, and they legitimately quote routes. */
function isScannable(relPath) {
  if (/\.(test|spec)\.(ts|tsx)$/.test(relPath)) return false;
  if (relPath.startsWith('src/test/')) return false;
  return true;
}

/**
 * Every file outside the declaration that spells the base path out, in code
 * rather than in prose.
 *
 * Takes project-relative paths rather than absolute ones on purpose. The first
 * version took absolute paths and called `relative()` itself, which meant the
 * function could only be exercised against the real tree — and the first
 * self-test to run produced `../../../../../../repo/src/hooks/useSSE.ts`
 * instead of `src/hooks/useSSE.ts`. A rule that cannot be handed a fixture is a
 * rule whose tests are really tests of the filesystem.
 */
export function findBasePathCopies(sources, base) {
  const violations = [];
  for (const { relPath, source } of sources) {
    if (relPath === BASE_DECLARATION) continue;
    if (!isScannable(relPath)) continue;
    if (!stripComments(source).includes(base)) continue;
    violations.push({
      kind: VIOLATION_KINDS.BASE_PATH_LITERAL,
      detail: `${relPath} spells out '${base}'; import BASE_URL from`
        + ` '${BASE_DECLARATION}' instead, so the base path stays in one place`,
      file: relPath,
    });
  }
  return violations;
}

function main() {
  const base = readApiBase(readFileSync(CLIENT, 'utf8'));
  const sources = walk(SRC_DIR)
    .map(path => ({
      relPath: relative(projectRoot, path).split('\\').join('/'),
      source: readFileSync(path, 'utf8'),
    }))
    .filter(entry => isScannable(entry.relPath));
  const violations = findBasePathCopies(sources, base);

  if (violations.length > 0) {
    console.error('API base path copied outside its declaration:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      `\n'${base}' is declared once, in ${BASE_DECLARATION}. A second copy is a`
      + '\nsecond place a version bump has to be remembered, and nothing points'
      + '\nat the second one.',
    );
    process.exitCode = 1;
    return;
  }

  const scanned = sources.length;
  console.log(
    `API base policy passed; '${base}' is declared only in ${BASE_DECLARATION},`
    + ` and ${scanned} source file(s) reach it by import.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
