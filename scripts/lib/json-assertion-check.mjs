// Analysis behind scripts/verify-json-assertions.mjs.
//
// Batch 895 found this defect by reading two jq predicates by hand:
//
//   all(.[]; .alertType != "API_PRINCIPAL_EXPIRY" or .metrics.principalId != $p)
//
// A missing field is `null`; `null != $p` holds; so "this alert is not firing for
// this principal" is reported for an alert that is firing and whose field has been
// renamed. The same sentence is true of a second shape, and it is the shape this
// repository reaches for everywhere as a *fix*:
//
//   all(.[]; ((.chunkText // "") | contains($token) | not))
//
// The `// ""` keeps the predicate from erroring, which is exactly what makes it
// unsafe: an unreadable field becomes "", which contains nothing, so "no chunk
// still contains the token" is asserted about chunks whose text the reader cannot
// see. Inside `any(...)` the identical expression fails closed. The difference is
// the sign of the assertion, never the guard.
//
// So the rule is one sentence: **inside a quantifier, a negative assertion is
// satisfied by a field that reads as absent — unless the same program requires
// that field to be present.** The compliance form is `<field> != null` somewhere
// in the same program, which is exactly what makes the negative assertion
// meaningful: it then ranges over "the field is readable and says something
// else", not over "I could not see it".
//
// Why that is a rule and not a warning: a fail-open predicate cannot be
// exempted without an allowlist, and an allowlist is not a check. Making the
// predicate require the field removes the need for one — see
// scripts/lib/alert-payload.sh, where the very predicate Batch 895 had to guard
// from outside is now closed from inside and needs no exemption.
//
// Scope: shell only. These scripts are the ones that decide whether a gate
// passes. A `.mjs` file that embedded a jq program in a template literal would
// be invisible here; `quantifierProgramsInNonShellFiles()` exists so that claim
// is measured rather than asserted.

import fs from 'node:fs';
import path from 'node:path';

/**
 * Strip shell comments with a character-level scan instead of a regex.
 *
 * A first version of this scanner located jq programs with `/'([^']*)'/g`, and
 * it reported the known-bad predicate as *prose in a comment* while swallowing
 * the real one into a mis-paired region. A `#` inside a quoted string is data,
 * and these files quote jq programs that contain backticks and apostrophes, so
 * the lexer has to track quoting state. The positive control in
 * scripts/test-support/json-assertions-self-test.mjs is what caught it, and it
 * checks for a match in *code*, not merely for a match.
 */
export function stripShellComments(src) {
  let out = '';
  let i = 0;
  let state = null; // null | "'" | '"' | '`'
  while (i < src.length) {
    const c = src[i];
    if (state === null) {
      if (c === "'" || c === '"' || c === '`') { state = c; out += c; i += 1; continue; }
      if (c === '\\') { out += c + (src[i + 1] ?? ''); i += 2; continue; }
      if (c === '#') {
        // Only a `#` at the start of a word opens a comment.
        const before = out[out.length - 1];
        if (before === undefined || /[\s;|&(){}]/.test(before)) {
          while (i < src.length && src[i] !== '\n') i += 1;
          continue;
        }
      }
      out += c; i += 1; continue;
    }
    if (c === '\\' && state !== "'") { out += c + (src[i + 1] ?? ''); i += 2; continue; }
    if (c === state) state = null;
    out += c; i += 1;
  }
  return out;
}

/** Single-quoted regions of comment-stripped shell, with their offsets. */
export function singleQuotedPrograms(src) {
  const out = [];
  let i = 0;
  let inProgram = false;
  let buf = '';
  let start = 0;
  while (i < src.length) {
    const c = src[i];
    if (!inProgram) {
      if (c === "'") { inProgram = true; buf = ''; start = i; }
      i += 1;
      continue;
    }
    // Shell single quotes have no escapes: the next quote closes the program.
    if (c === "'") { out.push({ text: buf, index: start }); inProgram = false; }
    else buf += c;
    i += 1;
  }
  return out;
}

/** Every `.sh` file under `root`, recursively. */
export function shellFiles(root) {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.sh')) out.push(full);
    }
  };
  walk(root);
  return out.sort();
}

const QUANTIFIER = /\b(?:any|all)\s*\(/;
const FIELD_NE = /(\.[A-Za-z_][\w.]*\??)\s*!=\s*(?!null\b)[^\s)&|]+/g;
// ((.f // "") | … | not) — the guard keeps it from erroring, and the `not` is
// what turns "cannot see" into "is not there".
const GUARDED_NEGATIVE = /\(\(\s*(\.[A-Za-z_][\w.]*)\s*\/\/\s*(?:"[^"]*"|null)\s*\)\s*\|[^|]*\|\s*not\b/g;

/**
 * Strip `#` comments from a jq program.
 *
 * jq has its own comment syntax, so a predicate quoted inside a shell
 * single-quoted program is still prose. The shell lexer cannot see it — that
 * text is inside a string as far as shell is concerned — and this batch's own
 * gate shipped a nine-line explanation of the fix inside its jq program, which
 * a text assertion then read back as the unfixed code it described.
 *
 * Known limitation: `#` inside a jq string literal would also be stripped.
 * These programs quote strings with `"` and none contains a `#`; the limitation
 * is written here rather than left to be discovered.
 */
export function stripJqComments(program) {
  return program.replace(/#[^\n]*/g, '');
}

/**
 * Findings for one jq program: the negative assertions that an absent field
 * would satisfy, minus the ones the same program already closes.
 */
export function programFindings(rawProgram) {
  const program = stripJqComments(rawProgram);
  if (!QUANTIFIER.test(program)) return [];
  const closed = new Set(
    [...program.matchAll(/(\.[A-Za-z_][\w.]*\??)\s*!=\s*null\b/g)].map((m) => m[1]),
  );
  const findings = [];
  const seen = new Set();
  FIELD_NE.lastIndex = 0;
  for (const m of program.matchAll(FIELD_NE)) {
    const field = m[1];
    const key = `ne:${field}`;
    if (closed.has(field) || seen.has(key)) continue;
    seen.add(key);
    findings.push({
      field,
      shape: 'field != value',
      reason: 'a missing field is null, and null != any value, so this negative '
        + 'assertion is satisfied by a field the reader cannot see',
    });
  }
  GUARDED_NEGATIVE.lastIndex = 0;
  for (const m of program.matchAll(GUARDED_NEGATIVE)) {
    const field = m[1];
    const key = `guard:${field}`;
    if (closed.has(field) || seen.has(key)) continue;
    seen.add(key);
    findings.push({
      field,
      shape: '(.f // "") | … | not',
      reason: 'the // "" guard turns an unreadable field into the empty string, '
        + 'which contains nothing, so this negative assertion is satisfied by a '
        + 'field the reader cannot see',
    });
  }
  return findings;
}

/** Scan `root` and return findings with file, line and the program text. */
export function scan(root) {
  const findings = [];
  let quantifierPrograms = 0;
  for (const file of shellFiles(root)) {
    const stripped = stripShellComments(fs.readFileSync(file, 'utf8'));
    for (const { text, index } of singleQuotedPrograms(stripped)) {
      if (!QUANTIFIER.test(stripJqComments(text))) continue;
      quantifierPrograms += 1;
      for (const finding of programFindings(text)) {
        findings.push({
          file: path.relative(root, file),
          line: stripped.slice(0, index).split('\n').length,
          ...finding,
          program: text.replace(/\s+/g, ' ').trim(),
        });
      }
    }
  }
  return { findings, quantifierPrograms, files: shellFiles(root).length };
}
