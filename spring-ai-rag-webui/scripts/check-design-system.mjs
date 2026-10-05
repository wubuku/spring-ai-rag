#!/usr/bin/env node
/**
 * Design-system gate.
 *
 * Extends the original token checker into a full design-debt gate covering CSS,
 * TS, TSX and SVG:
 *
 *   1. undefined-variable     var(--x) with no definition
 *   2. numeric-z-index        literal stacking order instead of --z-* tokens
 *   3. raw-color              hex/rgb/hsl/named colour outside the token source
 *   4. transition-all         `transition: all` (must enumerate properties)
 *   5. letter-spacing         non-zero tracking
 *   6. important              `!important` without a stated reason
 *   7. cross-page-import      one page importing another page's CSS module
 *   8. legacy-alias           call sites of compatibility aliases
 *   9. emoji-glyph            emoji or dingbats used as interface icons
 *  10. css-syntax              a stylesheet that does not parse
 *
 * Existing debt is grandfathered through design-tokens/design-debt-baseline.json
 * using the stable fingerprint `file|kind|value`. A violation that is already
 * baselined must not increase its count; new violations and stale (over-sized)
 * baseline entries both fail, so debt can only shrink.
 *
 * Narrow, justified exemptions use an inline
 *   /* design-token-allow: <reason> *\/
 * comment on the same or the previous line. A reason is mandatory.
 *
 * **Batch 878 found the exemption had never worked.** `scanFile` recorded the
 * reason on every violation it found, and the one consumer of that field was
 * the line deciding whether to print a hint — the counts this gate fails on
 * were built from *all* violations, waived or not. A line carrying a perfectly
 * justified exemption still failed, and the error message told the reader to go
 * and write one. The tree has never used the escape hatch, which is the only
 * reason this survived; the success line also reported a number labelled
 * "grandfathered debt fingerprint(s) at baseline" that was in fact the count of
 * distinct violations found, a coincidence that only holds at zero.
 *
 * The debt contract — "counts may only decrease", "a stale entry fails", "an
 * unreadable baseline is a failed gate, not an empty one" — is now extracted
 * into `compareToBaseline` and `readBaseline` and covered by tests. It had none.
 *
 * Run with --write-baseline to (re)record the current debt intentionally.
 */

import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import postcss from 'postcss';
import { buildOutputs } from './build-design-tokens.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const baselinePath = join(projectRoot, 'design-tokens/design-debt-baseline.json');
const legacyBaselinePath = join(projectRoot, 'scripts/design-token-color-baseline.json');

const SCAN_EXTENSIONS = ['.css', '.ts', '.tsx', '.svg'];
const SKIP_DIRECTORIES = new Set(['node_modules', 'dist', 'coverage', 'playwright-report', 'test-results']);
const isTestFile = path => /\.(test|spec)\.[cm]?[jt]sx?$/i.test(path);

// Only the token source and its generated outputs may contain literals.
const GENERATED_SOURCES = new Set(['src/styles/tokens.css']);

// CSS-wide keywords and SVG structural values are not colour escapes.
const STRUCTURAL_KEYWORDS = new Set([
  'transparent',
  'currentcolor',
  'none',
  'inherit',
  'initial',
  'unset',
  'revert',
  'revert-layer',
]);

const NAMED_COLORS = new Set(
  (
    'aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue ' +
    'blueviolet brown burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk ' +
    'crimson cyan darkblue darkcyan darkgoldenrod darkgray darkgreen darkgrey darkkhaki ' +
    'darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen ' +
    'darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue ' +
    'dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite ' +
    'gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki ' +
    'lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan ' +
    'lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen ' +
    'lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen ' +
    'linen magenta maroon mediumaquamarine mediumblue mediumorchid mediumpurple ' +
    'mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred ' +
    'midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab ' +
    'orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip ' +
    'peachpuff peru pink plum powderblue purple rebeccapurple red rosybrown royalblue ' +
    'saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue slateblue ' +
    'slategray slategrey snow springgreen steelblue tan teal thistle tomato turquoise violet ' +
    'wheat white whitesmoke yellow yellowgreen'
  ).split(' '),
);

const COLOR_LITERAL_PATTERNS = [
  /#[0-9a-f]{3,4}\b/gi,
  /#[0-9a-f]{6}\b/gi,
  /#[0-9a-f]{8}\b/gi,
  /\brgba?\(/gi,
  /\bhsla?\(/gi,
];

const ALLOW_COMMENT = /design-token-allow:\s*(.+?)\s*(?:\*\/)?$/;

// Emoji and dingbats must not be used as interface icons: they render at
// different metrics per platform, cannot inherit colour, and cannot be
// asserted on in a test except by matching a Unicode character. Use the
// tree-shaken lucide set instead.
//
// The ranges are deliberately narrow — pictographs plus the dingbat blocks that
// browsers still render as standalone glyphs (arrows, carets, geometric
// shapes, check/cross marks, stars). Box-drawing U+2500-257F and CJK punctuation
// are excluded: they are layout characters, not icons.
const GLYPH_RANGES = [
  [0x00d7, 0x00d7], // × multiplication sign, still used as a close affordance
  [0x203c, 0x203c], // ‼
  [0x2049, 0x2049], // ⁉
  [0x2139, 0x2139], // ℹ
  [0x2190, 0x21ff], // ← ↑ → ↓ ↻ ↺ ⇄ ⇅
  [0x2300, 0x23ff], // ⌃ ⌄ ⌕ ⌘ ⌫ ⌧ ⌨
  [0x25a0, 0x25ff], // ▾ ▸ ◀ ▶ ■ □ ▲ ▼ ◊ ○ ●
  [0x2600, 0x27bf], // ☀ ✓ ✔ ✗ ✖ ✕ ⚠ ⚡
  [0x2b00, 0x2bff], // ⬆ ⬛ ⭐
  [0xfe0f, 0xfe0f], // VS16 presentation selector
  [0x1f000, 0x1faff], // pictographs
];

const GLYPH_PATTERN = new RegExp(
  `[${GLYPH_RANGES.map(([from, to]) =>
    from === to ? `\\u{${from.toString(16)}}` : `\\u{${from.toString(16)}}-\\u{${to.toString(16)}}`,
  ).join('')}]`,
  'gu',
);

function walk(directory) {
  const entries = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRECTORIES.has(entry.name)) continue;
      entries.push(...walk(join(directory, entry.name)));
    } else if (SCAN_EXTENSIONS.some(extension => entry.name.endsWith(extension))) {
      entries.push(join(directory, entry.name));
    }
  }
  return entries;
}

/** Collect every `--name:` definition declared outside the generated token CSS. */
function collectBaseDefinitions(paths) {
  const defined = new Set();
  for (const path of paths) {
    if (!path.endsWith('.css')) continue;
    const relativePath = relative(projectRoot, path);
    if (GENERATED_SOURCES.has(relativePath)) continue;
    for (const match of readFileSync(path, 'utf8').matchAll(/(--[a-z0-9-]+)\s*:/gi)) {
      defined.add(match[1]);
    }
  }
  return defined;
}

function countColorLiterals(line) {
  let count = 0;
  for (const pattern of COLOR_LITERAL_PATTERNS) {
    count += line.split(pattern).length - 1;
  }
  return count;
}

/** Named colours only count in CSS declaration value position, to avoid identifier noise. */
function findNamedColors(line) {
  if (!line.includes(':')) return [];
  const declaration = line.slice(line.indexOf(':') + 1);
  const found = [];
  for (const word of declaration.matchAll(/\b([a-z]{3,20})\b/gi)) {
    const candidate = word[1].toLowerCase();
    if (NAMED_COLORS.has(candidate) && !STRUCTURAL_KEYWORDS.has(candidate)) {
      found.push(candidate);
    }
  }
  return found;
}

/**
 * Replace comments with spaces, leaving strings and real code untouched.
 *
 * Debt patterns must match code, not prose: a stylesheet is allowed to *write
 * down* that `transition: all` is banned, and a Chinese comment is allowed to
 * use `→` when explaining a data flow. Preserving the newline count keeps
 * reported line numbers aligned with the real file.
 *
 * String literals are scanned, not masked, because a string is exactly how an
 * interface glyph reaches the DOM (`{open ? '⌃' : '⌄'}`). The scan is
 * string-aware so a `//` inside a string is not mistaken for a comment.
 *
 * Known limitation: a regular-expression literal is not modelled, so a
 * pattern containing a bare `//` (e.g. `/\/\//`) masks the rest of its line.
 * That can only hide a glyph on the same line, never invent a finding.
 */
export function stripComments(source) {
  let out = '';
  let index = 0;
  let quote = null;
  while (index < source.length) {
    const char = source[index];
    if (quote !== null) {
      if (char === '\\') {
        out += source.slice(index, index + 2);
        index += 2;
        continue;
      }
      if (char === quote) quote = null;
      out += char;
      index += 1;
      continue;
    }
    if (char === '"' || char === "'" || char === '`') {
      quote = char;
      out += char;
      index += 1;
      continue;
    }
    if (char === '/' && source[index + 1] === '/') {
      while (index < source.length && source[index] !== '\n') {
        out += ' ';
        index += 1;
      }
      continue;
    }
    if (char === '/' && source[index + 1] === '*') {
      while (index < source.length && !(source[index] === '*' && source[index + 1] === '/')) {
        out += source[index] === '\n' ? '\n' : ' ';
        index += 1;
      }
      out += '  ';
      index += 2;
      continue;
    }
    out += char;
    index += 1;
  }
  return out;
}

/**
 * Parse a stylesheet and return its syntax errors.
 *
 * A stylesheet that does not parse is not a style-debt problem, so it is
 * reported separately and cannot be waived with `design-token-allow`: there is
 * no reason a broken stylesheet is acceptable, only a reason to fix it.
 *
 * Until this rule existed, `npm run build` was the only thing that noticed.
 * Vitest stubs CSS modules and the line-based rules above cannot see a stray
 * brace, so `typecheck`, `lint` and the whole 765-test suite all passed on a
 * `FilePreview.module.css` that shipped an extra `}`.
 */
function findCssSyntaxErrors(source) {
  const errors = [];
  try {
    postcss.parse(source);
  } catch (error) {
    const line = error.line ?? 1;
    const reason = String(error.reason ?? error.message ?? 'unparsable stylesheet')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 120);
    errors.push({ line, reason });
  }
  return errors;
}

/**
 * Scan one file into violation records.
 * @returns {{file: string, kind: string, value: string, line: number, allowed: string|null}[]}
 */
export function scanFile(path, context) {
  const relativePath = relative(projectRoot, path);
  return scanSource(relativePath, readFileSync(path, 'utf8'), context);
}

/**
 * Pure variant used by the focused tests: same rules, but the caller supplies
 * the source text instead of hitting the filesystem.
 * @param {string} relativePath project-relative path, used for reporting and exemptions
 * @param {string} source file contents
 * @param {{definedVars: Set<string>}} context
 */
export function scanSource(relativePath, source, context) {
  const isTest = isTestFile(relativePath);
  const isCss = relativePath.endsWith('.css');
  const isGenerated = GENERATED_SOURCES.has(relativePath);
  // Debt rules read the comment-free source; the allow-reason lookup still sees
  // the raw line, because a justification lives in a comment by definition.
  const rawLines = source.split(/\r?\n/);
  const codeLines = stripComments(source).split(/\r?\n/);
  const violations = [];
  // Tests do not ship, so they are not scanned for style debt.
  const exemptFromDebt = isTest || isGenerated;

  // A stylesheet that does not parse is reported before anything else, because
  // every line-based rule below would otherwise read a file the browser cannot
  // load and quietly approve it.
  if (isCss && !isTest) {
    for (const { line, reason } of findCssSyntaxErrors(source)) {
      violations.push({
        file: relativePath,
        kind: 'css-syntax',
        value: reason,
        line,
        // Deliberately not waivable: see findCssSyntaxErrors.
        allowed: null,
      });
    }
  }

  codeLines.forEach((line, index) => {
    const lineNumber = index + 1;
    const previousLine = index > 0 ? rawLines[index - 1] : '';
    const allowHere = ALLOW_COMMENT.exec(rawLines[index]);
    const inlineAllow = allowHere ?? ALLOW_COMMENT.exec(previousLine);
    const allowed = inlineAllow ? inlineAllow[1].trim() : null;

    // Batch 878. Whether a justification is a real reason is a property of the
    // comment, not of each line it governs. Reading the previous line as well
    // meant a thin reason written *above* a declaration was reported twice —
    // once on the comment and once on the line below it — and the two records
    // had different `value` fields, so they were two baseline fingerprints for
    // one bad sentence. A comment on the same line as its code was reported
    // once, which is why the existing case never saw it.
    if (allowHere && allowed.length < 8) {
      violations.push({
        file: relativePath,
        kind: 'weak-allow-reason',
        value: line.trim().slice(0, 60),
        line: lineNumber,
        allowed: null,
      });
    }

    for (const match of line.matchAll(/var\((--[a-z0-9-]+)/gi)) {
      if (!context.definedVars.has(match[1])) {
        violations.push({
          file: relativePath,
          kind: 'undefined-variable',
          value: match[1],
          line: lineNumber,
          allowed: null,
        });
      }
    }

    if (!exemptFromDebt) {
      if (/z-index\s*:\s*-?\d+\s*;/.test(line)) {
        violations.push({
          file: relativePath,
          kind: 'numeric-z-index',
          value: line.trim().slice(0, 40),
          line: lineNumber,
          allowed,
        });
      }

      if (isCss) {
        const literals = countColorLiterals(line);
        for (let i = 0; i < literals; i += 1) {
          violations.push({
            file: relativePath,
            kind: 'raw-color',
            value: 'literal',
            line: lineNumber,
            allowed,
          });
        }
        for (const name of findNamedColors(line)) {
          violations.push({
            file: relativePath,
            kind: 'raw-color',
            value: name,
            line: lineNumber,
            allowed,
          });
        }
      }

      if (/transition\s*:[^;]*\ball\b/.test(line)) {
        violations.push({
          file: relativePath,
          kind: 'transition-all',
          value: line.trim().slice(0, 40),
          line: lineNumber,
          allowed,
        });
      }

      if (/letter-spacing\s*:\s*(-?[0-9.]+(?:px|em|rem|ex|ch|%)?)/.test(line)) {
        const match = /letter-spacing\s*:\s*(-?[0-9.]+(?:px|em|rem|ex|ch|%)?)/.exec(line);
        if (match && Number.parseFloat(match[1]) !== 0) {
          violations.push({
            file: relativePath,
            kind: 'letter-spacing',
            value: match[1],
            line: lineNumber,
            allowed,
          });
        }
      }

      if (/!important/.test(line) && !line.includes('design-token-allow')) {
        violations.push({
          file: relativePath,
          kind: 'important',
          value: line.trim().slice(0, 40),
          line: lineNumber,
          allowed,
        });
      }

      for (const match of line.matchAll(/var\((--(?:color-background|color-bg-secondary|color-hover|color-text-secondary|bg-primary|bg-secondary|border-color|text-secondary))\)/g)) {
        violations.push({
          file: relativePath,
          kind: 'legacy-alias',
          value: match[1],
          line: lineNumber,
          allowed,
        });
      }

      // An interface icon must be a component, not a glyph typed into markup.
      for (const match of line.matchAll(GLYPH_PATTERN)) {
        // U+FE0F only requests emoji presentation for the base glyph that
        // precedes it, and that base glyph is already reported on its own.
        if (match[0] === '\u{FE0F}') continue;
        violations.push({
          file: relativePath,
          kind: 'emoji-glyph',
          value: match[0],
          line: lineNumber,
          allowed,
        });
      }
    }

    // A page must not import another page's CSS module.
    for (const match of line.matchAll(/from\s+'([^']*\.module\.css)'/g)) {
      const specifier = match[1];
      const importingFile = relativePath.split('/').pop();
      const ownsModule = specifier === `./${importingFile.replace('.tsx', '.module.css')}`;
      const importsAnotherPage = specifier.includes('../pages/') || specifier.includes('/pages/');
      if (!ownsModule && importsAnotherPage) {
        violations.push({
          file: relativePath,
          kind: 'cross-page-import',
          value: specifier,
          line: lineNumber,
          allowed,
        });
      }
    }
  });

  return violations;
}

export function fingerprint(violation) {
  return `${violation.file}|${violation.kind}|${violation.value}`;
}

/**
 * Count unresolved violations per fingerprint.
 *
 * Batch 878. This counted *every* violation, and the inline
 * `design-token-allow:` exemption therefore did nothing at all: the record
 * carried its `allowed` reason, and the only thing that ever read that field
 * was the line deciding whether to print a hint. A line with a perfectly
 * justified exemption still failed the gate — and the gate's own error message
 * told the reader to go and write one. The exemption was a no-op that the gate
 * was actively recommending.
 *
 * `css-syntax` is unaffected by this because it is recorded with
 * `allowed: null` unconditionally: a stylesheet the browser cannot load has no
 * legitimate exemption, only a reason to fix it.
 *
 * @param {{file: string, kind: string, value: string, line: number, allowed: string|null}[]} violations
 * @returns {Map<string, number>}
 */
export function countUnresolved(violations) {
  const counts = new Map();
  for (const violation of violations) {
    if (violation.allowed !== null) continue;
    const key = fingerprint(violation);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

/**
 * The whole debt contract, as a pure function: what the code has, what the
 * baseline allows, and every way the two can disagree.
 *
 * Batch 878. This was inline in `main()` and had no test at all — around seventy
 * lines of gate logic, including two promises the surrounding comments make
 * loudly ("counts may only decrease", "a stale over-sized entry fails"), and
 * nothing in the suite touched any of it. A gate nobody exercises is the exact
 * failure shape this repository has produced before.
 *
 * @param {Map<string, number>} counts fingerprints actually present in the code
 * @param {Record<string, number>} entries what the baseline allows
 * @param {{file: string, line: number}[]} violations for locating the first hit
 * @returns {string[]} one message per disagreement, empty when the gate passes
 */
export function compareToBaseline(counts, entries, violations) {
  const errors = [];
  for (const [key, count] of counts) {
    const allowed = entries[key] ?? 0;
    if (count > allowed) {
      const sample = violations.find(violation => fingerprint(violation) === key);
      errors.push(
        `${key}: ${count} occurrence(s), baseline allows ${allowed}` +
          (sample ? ` (first at ${sample.file}:${sample.line})` : ''),
      );
    }
  }
  for (const [key, allowed] of Object.entries(entries)) {
    const actual = counts.get(key) ?? 0;
    if (actual < allowed) {
      errors.push(
        `${key}: baseline is stale (allows ${allowed}, found ${actual}). ` +
          'Lower the entry or drop it at zero so the debt cannot silently regrow.',
      );
    }
  }
  return errors;
}

/**
 * Read the debt baseline, distinguishing "absent" from "corrupt".
 *
 * The previous version collapsed both into an empty baseline. A corrupt file
 * therefore read as "no debt recorded", which silently disabled the staleness
 * check — the half of the contract that stops an over-sized entry from hiding
 * debt that was already paid off. A gate that cannot tell the difference
 * between "nothing to check" and "could not read what to check" is a gate that
 * can go quiet.
 *
 * Batch 878 takes the path as an argument. It accepted a parameter before and
 * then read the module constant anyway, so the parameter was a lie and the only
 * way to exercise "absent" and "corrupt" against real files was to damage the
 * checked-in one.
 */
export function readBaseline(path = baselinePath) {
  let text;
  try {
    text = readFileSync(path, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return { version: 1, entries: {} };
    throw new Error(
      `Cannot read ${relative(projectRoot, path)}: ${error.message}. ` +
        'An unreadable baseline is a failed gate, not an empty one.',
    );
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(
      `${relative(projectRoot, baselinePath)} is not valid JSON: ${error.message}. ` +
        'Fix or delete the file; a corrupt baseline cannot be trusted to record debt.',
    );
  }
}

function main() {
  const writeBaseline = process.argv.includes('--write-baseline');
  // Canonical token vars come from the source, not from scanning the output.
  const { model } = buildOutputs(readFileSync(join(projectRoot, 'design-tokens/tokens.json'), 'utf8'));
  const paths = walk(sourceRoot);
  const definedVars = new Set([
    ...model.cssVarToToken.keys(),
    ...collectBaseDefinitions(paths),
  ]);
  const context = { definedVars };

  const violations = paths.flatMap(path => scanFile(path, context));
  const counts = countUnresolved(violations);
  const waived = violations.length - [...counts.values()].reduce((a, b) => a + b, 0);

  if (writeBaseline) {
    const entries = Object.fromEntries([...counts.entries()].sort(([a], [b]) => a.localeCompare(b)));
    writeFileSync(
      baselinePath,
      `${JSON.stringify(
        {
          version: 1,
          description:
            'Versioned design-debt baseline. Fingerprint = file|kind|value. Counts may only decrease; ' +
            'new violations, increased counts and stale over-sized entries all fail `npm run check:design-system`.',
          entries,
        },
        null,
        2,
      )}\n`,
    );
    console.log(
      `Recorded ${Object.keys(entries).length} design-debt fingerprint(s) in design-debt-baseline.json`,
    );
    return;
  }

  const baseline = readBaseline();
  const errors = compareToBaseline(counts, baseline.entries ?? {}, violations);

  // The retired colour baseline must not come back to life. An absent file is
  // fine; an unreadable or unparsable one is not, because that is exactly how
  // the "must stay empty" check would be switched off without anyone noticing.
  let legacyText;
  try {
    legacyText = readFileSync(legacyBaselinePath, 'utf8');
  } catch (error) {
    if (error.code !== 'ENOENT') {
      errors.push(
        `Cannot read ${relative(projectRoot, legacyBaselinePath)}: ${error.message}. ` +
          'Treat it as failed, not as absent.',
      );
    }
  }
  if (legacyText !== undefined) {
    try {
      const legacy = JSON.parse(legacyText);
      if (Object.keys(legacy).length > 0) {
        errors.push(
          `scripts/design-token-color-baseline.json must stay empty; colour debt now lives in ` +
            `design-tokens/design-debt-baseline.json (found ${Object.keys(legacy).length} entr(ies)).`,
        );
      }
    } catch (error) {
      errors.push(
        `${relative(projectRoot, legacyBaselinePath)} is not valid JSON: ${error.message}. ` +
          'A file that cannot be parsed cannot be asserted empty.',
      );
    }
  }

  if (errors.length > 0) {
    console.error('Design system violations:');
    for (const error of errors) console.error(`- ${error}`);
    if (violations.some(v => v.allowed !== null)) {
      console.error(
        '\nFix the violations, or record a justified inline exemption with ' +
          '`/* design-token-allow: <reason> */` on the same or previous line.',
      );
    }
    // A stylesheet that does not parse has no legitimate exemption: it is not a
    // style preference, it is a file the browser cannot load.
    if (violations.some(v => v.kind === 'css-syntax')) {
      console.error('\ncss-syntax violations cannot be waived. Fix the stylesheet.');
    }
    process.exitCode = 1;
    return;
  }

  // Batch 878. This used to read "N grandfathered debt fingerprint(s) at
  // baseline", where N was `counts.size` — the number of distinct *violations
  // found in the code*, which has nothing to do with what the baseline records.
  // At zero they coincide, which is why it read plausibly for so long; the
  // first person to waive one violation would have seen a number that meant
  // neither of the two things its sentence claimed.
  const waivedNote = waived > 0 ? `, ${waived} occurrence(s) waived inline` : '';
  console.log(
    `Design system policy passed; ${definedVars.size} token/var names defined, `
      + `${counts.size} unresolved debt fingerprint(s)${waivedNote}, `
      + `${Object.keys(baseline.entries ?? {}).length} baselined.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
