// The same rule as json-assertion-check.mjs, for Python.
//
// Batch 899. Batch 896's gate reads `scripts/**/*.sh`. Batch 898 found the
// identical defect in Python — `float(baseline_metrics.get(name, 0.0))` in the
// one gate whose job is catching a regression — and the only reason it was found
// was that somebody read that script. **A rule that looks at one language is a
// rule about that language.** This is the other language.
//
// The shape is narrower here than in jq, and deliberately so. A falsy literal as
// the default of a lookup is not a defect by itself: `os.environ.get(name, "")`
// is correct, and `(x or []).get(...)` is idiomatic. What makes it fail open is
// the *use* — the value flowing into a comparison whose other side can also be
// falsy, so "the reader could not obtain it" and "it is correct" are the same
// answer.
//
// Measured on this repository before the gate existed: 97 Python sources
// (standalone files and the `<<'PY'` heredocs the shell gates embed), 4122 lines,
// 88 raw falsy-default lookups, and **0** of the dangerous narrow shape. Zero
// findings is what makes this a gate rather than a baseline: there is nothing to
// exempt, so the rule can simply say no.

import fs from 'node:fs';
import path from 'node:path';

/** Standalone .py files plus every `<<'PY'` heredoc body, with line offsets. */
export function pythonSources(root) {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.py')) out.push({ file: path.relative(root, full), text: fs.readFileSync(full, 'utf8'), firstLine: 1 });
      else if (entry.name.endsWith('.sh')) {
        const lines = fs.readFileSync(full, 'utf8').split('\n');
        for (let i = 0; i < lines.length; i += 1) {
          if (!/<<'PY'\s*$/.test(lines[i])) continue;
          const start = i + 1;
          let j = start;
          while (j < lines.length && lines[j] !== 'PY') j += 1;
          out.push({
            file: `${path.relative(root, full)}:${start}`,
            text: lines.slice(start, j).join('\n'),
            firstLine: start + 1,
          });
          i = j;
        }
      }
    }
  };
  walk(root);
  return out;
}

/** Remove comments and string literals so a match cannot come from prose. */
export function stripPythonNoise(src) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === '#') { while (i < src.length && src[i] !== '\n') i += 1; out += '\n'; continue; }
    if ((c === '"' || c === "'")) {
      const triple = src.startsWith(c.repeat(3), i);
      const close = triple ? c.repeat(3) : c;
      i += triple ? 3 : 1;
      while (i < src.length && !src.startsWith(close, i)) { if (src[i] === '\\') i += 1; i += 1; }
      i += close.length;
      out += '""';
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

const FALSY = '(?:0|0\\.0|False|None|""|\'\'|\\[\\]|\\{\\})';
const FALSY_DEFAULT = new RegExp(
  `\\.\\s*get\\([^,()]+,\\s*${FALSY}\\s*\\)|\\bor\\s+(?:0|0\\.0|""|''|\\[\\]|\\{\\})(?!\\w|\\.)`,
);
// "Absent" satisfies the comparison: the other side is falsy too.
//
// `not in` is deliberately NOT here, and the first draft had it there. A
// containment check of the form `if token not in (item.get("f") or "")` fails
// closed — an unreadable field becomes "", the token is not in it, and the gate
// fails — so including it reported two gates as broken when both are correct,
// one of them not even about a metric but about filtering env-var lines. The
// sign of the assertion decides the direction, exactly as in the jq rule.
const FALSY_EXPECTED = new RegExp(
  `(?:==|!=)\\s*(?:0|0\\.0|""|''|\\[\\]|\\{\\}|None|False)\\b|<=\\s*(?:0|0\\.0)\\b|>=\\s*(?:0|0\\.0)\\b`,
);

/** Findings for one source; each names the line it can be fixed on. */
export function sourceFindings(source) {
  const stripped = stripPythonNoise(source.text);
  return stripped.split('\n')
    .map((line, idx) => ({ line, number: source.firstLine + idx }))
    .filter(({ line }) => FALSY_DEFAULT.test(line) && FALSY_EXPECTED.test(line))
    .map(({ line, number }) => ({
      file: source.file,
      line: number,
      text: line.trim(),
      reason: 'a falsy default feeds a comparison the absent value can satisfy, '
        + 'so "the reader could not obtain it" and "it is correct" are one answer',
    }));
}

export function scanPython(root) {
  const sources = pythonSources(root);
  const findings = sources.flatMap(sourceFindings);
  return {
    findings,
    sources: sources.length,
    lines: sources.reduce((n, s) => n + s.text.split('\n').length, 0),
  };
}
