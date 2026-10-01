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
 * Run with --write-baseline to (re)record the current debt intentionally.
 */

import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildOutputs } from './build-design-tokens.mjs';

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
 * Replace block comments with equivalent newlines.
 *
 * Debt patterns must match code, not prose: a stylesheet is allowed to *write
 * down* that `transition: all` is banned. Preserving the newline count keeps
 * reported line numbers aligned with the real file.
 */
function stripBlockComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, comment => comment.replace(/[^\n]/g, ' '));
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
  const codeLines = stripBlockComments(source).split(/\r?\n/);
  const violations = [];
  // Tests do not ship, so they are not scanned for style debt.
  const exemptFromDebt = isTest || isGenerated;

  codeLines.forEach((line, index) => {
    const lineNumber = index + 1;
    const previousLine = index > 0 ? rawLines[index - 1] : '';
    const inlineAllow = ALLOW_COMMENT.exec(rawLines[index]) ?? ALLOW_COMMENT.exec(previousLine);
    const allowed = inlineAllow ? inlineAllow[1].trim() : null;

    if (allowed !== null && allowed.length < 8) {
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

function readBaseline() {
  try {
    return JSON.parse(readFileSync(baselinePath, 'utf8'));
  } catch {
    return { version: 1, entries: {} };
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
  const counts = new Map();
  for (const violation of violations) {
    const key = fingerprint(violation);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }

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
  const baselineEntries = baseline.entries ?? {};
  const errors = [];

  for (const [key, count] of counts) {
    const allowed = baselineEntries[key] ?? 0;
    if (count > allowed) {
      const sample = violations.find(violation => fingerprint(violation) === key);
      errors.push(
        `${key}: ${count} occurrence(s), baseline allows ${allowed}` +
          (sample ? ` (first at ${sample.file}:${sample.line})` : ''),
      );
    }
  }

  for (const [key, allowed] of Object.entries(baselineEntries)) {
    const actual = counts.get(key) ?? 0;
    if (actual < allowed) {
      errors.push(
        `${key}: baseline is stale (allows ${allowed}, found ${actual}). ` +
          'Lower the entry or drop it at zero so the debt cannot silently regrow.',
      );
    }
  }

  // The retired colour baseline must not come back to life.
  try {
    const legacy = JSON.parse(readFileSync(legacyBaselinePath, 'utf8'));
    if (Object.keys(legacy).length > 0) {
      errors.push(
        `scripts/design-token-color-baseline.json must stay empty; colour debt now lives in ` +
          `design-tokens/design-debt-baseline.json (found ${Object.keys(legacy).length} entr(ies)).`,
      );
    }
  } catch {
    // Missing legacy baseline is fine.
  }

  if (errors.length > 0) {
    console.error('Design system violations:');
    for (const error of errors) console.error(`- ${error}`);
    console.error(
      '\nFix the violations, or record a justified inline exemption with ' +
        '`/* design-token-allow: <reason> */` on the same or previous line.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Design system policy passed; ${definedVars.size} token/var names defined, ` +
      `${counts.size} grandfathered debt fingerprint(s) at baseline.`,
  );
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main();
}
