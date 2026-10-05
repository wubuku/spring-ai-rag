// Find Java test assertions that pass no matter what the code under test does.
//
// What this is for
// ----------------
// Batch 908 measured two shapes of assertion that carry no information, and both
// had been sitting in this tree passing for years:
//
//   assertTrue(true)                                  // nothing can make it red
//   assertTrue(!hasAssistantRole || true, "…")         // top-level `|| true`
//
// The second one is the interesting one, and it is why a literal-only scan is
// not enough. It *looks* like a real assertion — it has a variable in it, it has
// a failure message in Chinese explaining what should hold — and it cost a
// reader real time to disprove. Worse, the message it carried was wrong: the
// surrounding file and the production code both declare that a MiniMax request
// is normalised `system → user`, while the assertion claimed the assistant role
// should not survive. The tautology is what kept the wrong sentence from being
// noticed, so this is not only "an assertion that checks nothing" — it is an
// assertion that hides a documentation bug next to it.
//
// The two rules
// -------------
// R1  A literal self-negating or self-satisfying argument: `assertTrue(true)`,
//     `assertFalse(false)`, `assertNull(null)`, `assertNotNull(<non-null literal>)`.
//
// R2  An assertion's first argument whose *top-level* connective is `|| true`
//     or `&& false`. Top-level is the whole rule: `a == false || true` is
//     constant even though its `||` is not the first operator you read, and
//     `x == false` is a perfectly good assertion even though it contains `== false`.
//
// Why there is no allowlist, and why `assertEquals(x, x)` is not one of the rules
// ---------------------------------------------------------------------------
// Both rules have zero correct instances — there is no reading under which
// `assertTrue(true)` is the assertion you meant to write — so neither needs an
// exemption, and an allowlist here would be a list of things this checker does
// not check (the reason `verify-json-assertions.mjs` refuses one).
//
// The census that produced these rules also produced eight `assertEquals(x, x)`
// hits, and *none* of them is this defect. JUnit resolves `assertEquals(a, a)`
// by calling `a.equals(a)`, so a hand-written `equals` that returned false for
// the same reference would fail it, and `assertEquals(x.hashCode(), x.hashCode())`
// fails if `hashCode` is not stable. They are weak contract checks, not
// tautologies. A rule that flagged them would be reporting a correct shape,
// which is how a gate earns an allowlist it does not need.
//
// Comment and string handling is not this file's job: callers pass text through
// `stripJavaComments` from `./java-source.mjs` first, which is the stripper the
// whole repository shares after Batch 905.

import fs from 'node:fs';
import path from 'node:path';
import { stripJavaComments } from './java-source.mjs';

/** Recursively list .java files under `dir`. */
function javaFiles(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) javaFiles(p, out);
    else if (entry.name.endsWith('.java')) out.push(p);
  }
  return out;
}

/**
 * First argument of a call whose opening paren is at `open`, as raw text.
 * Returns null when the call is unbalanced.
 */
function firstArgument(source, open) {
  let depth = 0;
  let quote = null;
  let comma = -1;
  for (let i = open; i < source.length; i++) {
    const c = source[i];
    if (quote !== null) {
      if (c === '\\') { i++; continue; }
      if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (c === '(' || c === '[' || c === '{') { depth++; continue; }
    if (c === ')' || c === ']' || c === '}') {
      depth--;
      if (depth === 0) return source.slice(open + 1, comma < 0 ? i : comma);
      continue;
    }
    if (c === ',' && depth === 1) comma = i;
  }
  return null;
}

/**
 * Drop balanced parentheses that wrap the whole expression. Without this,
 * `assertTrue((!x || true))` is a finding written one pair of parens away from
 * one the scanner accepts — which is the difference between a rule and a typo
 * detector.
 */
function unwrapOuterParens(text) {
  let out = text;
  while (out.startsWith('(') && out.endsWith(')')) {
    let depth = 0;
    let quote = null;
    let wraps = true;
    for (let i = 0; i < out.length; i++) {
      const c = out[i];
      if (quote !== null) {
        if (c === '\\') { i++; continue; }
        if (c === quote) quote = null;
        continue;
      }
      if (c === '"' || c === "'") { quote = c; continue; }
      if (c === '(') depth++;
      else if (c === ')') {
        depth--;
        if (depth === 0 && i !== out.length - 1) { wraps = false; break; }
      }
    }
    if (!wraps) break;
    out = out.slice(1, -1).trim();
  }
  return out;
}

/**
 * The leftmost `||` or `&&` that is not nested inside brackets, parens or a
 * string literal, together with everything to its right. Nested ones belong to
 * a sub-expression and say nothing about the whole expression.
 */
function topLevelConnective(arg) {
  let depth = 0;
  let quote = null;
  for (let i = 0; i < arg.length; i++) {
    const c = arg[i];
    if (quote !== null) {
      if (c === '\\') { i++; continue; }
      if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (c === '(' || c === '[' || c === '{') { depth++; continue; }
    if (c === ')' || c === ']' || c === '}') { depth--; continue; }
    if (depth !== 0) continue;
    if (c === '|' && arg[i + 1] === '|') {
      return { op: '||', rest: arg.slice(i + 2) };
    }
    if (c === '&' && arg[i + 1] === '&') {
      return { op: '&&', rest: arg.slice(i + 2) };
    }
  }
  return null;
}

/** R1: the argument is a literal that satisfies the assertion by existing. */
const LITERAL_RULES = [
  { name: 'assertTrue(true)', call: 'assertTrue', arg: 'true' },
  { name: 'assertFalse(false)', call: 'assertFalse', arg: 'false' },
  { name: 'assertNull(null)', call: 'assertNull', arg: 'null' },
];

const NOT_NULL_LITERAL = /^(?:true|false|-?\d+(?:\.\d+)?[fFdDlL]?|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')$/;

const CALL = /\b(assert[A-Z]\w*)\s*\(/g;

/**
 * Scan every `.java` file under `root` for assertions that cannot fail.
 *
 * @returns {{findings: Array<{file: string, line: number, rule: string,
 *   text: string, why: string}>, files: number, assertions: number}}
 */
export function scan(root) {
  const findings = [];
  let files = 0;
  let assertions = 0;

  for (const file of javaFiles(root)) {
    const source = stripJavaComments(fs.readFileSync(file, 'utf8'));
    files++;
    const relative = path.relative(root, file);

    CALL.lastIndex = 0;
    let call;
    while ((call = CALL.exec(source)) !== null) {
      const open = call.index + call[0].length - 1;
      const arg = firstArgument(source, open);
      if (arg === null) continue;
      assertions++;
      const trimmed = unwrapOuterParens(arg.trim());
      const line = source.slice(0, call.index).split('\n').length;

      const literal = LITERAL_RULES.find(
        (r) => call[1] === r.call && trimmed === r.arg);
      if (literal) {
        findings.push({
          file: relative,
          line,
          rule: literal.name,
          text: `${call[1]}(${trimmed})`,
          why: '这个实参是字面量，任何被测代码都会通过。',
        });
        continue;
      }

      if (call[1] === 'assertNotNull' && NOT_NULL_LITERAL.test(trimmed)) {
        findings.push({
          file: relative,
          line,
          rule: 'assertNotNull(<non-null literal>)',
          text: `assertNotNull(${trimmed})`,
          why: '非 null 字面量传给 assertNotNull，永远通过。',
        });
        continue;
      }

      const connective = topLevelConnective(trimmed);
      if (!connective) continue;
      const rest = connective.rest.trim();
      const constant = (connective.op === '||' && rest === 'true')
        || (connective.op === '&&' && rest === 'false');
      if (!constant) continue;
      findings.push({
        file: relative,
        line,
        rule: `top-level ${connective.op} ${rest}`,
        text: `${call[1]}(${trimmed.replace(/\s+/g, ' ')})`,
        why: `顶层的 ${connective.op} ${rest} 把左侧整个丢掉，表达式恒为`
          + `${connective.op === '||' ? '真' : '假'}。`,
      });
    }
  }

  return { findings, files, assertions };
}
