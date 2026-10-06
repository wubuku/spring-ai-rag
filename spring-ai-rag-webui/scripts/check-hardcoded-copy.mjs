#!/usr/bin/env node
/**
 * Hardcoded-copy gate: a string a user can read must come from the locale files.
 *
 * `check-i18n-keys` covers the failure modes of a key that *is* used: missing
 * from a locale, asymmetric between the two locales, or hidden behind a
 * `t(...) || fallback` that can never fire. It has nothing to say about a
 * component that never calls `t` at all — which is how a Chinese user ends up
 * reading "Something went wrong" on the one screen that appears when the app has
 * already broken, and "Try different keywords or adjust your search." in the
 * empty state.
 *
 * What Batch 808 found with a single sweep of the source tree:
 *
 *   - `ErrorBoundary` and `MetricsCharts` had **zero** calls to `t`. Thirteen of
 *     MetricsCharts' strings were English, including the Recharts `name` values
 *     that end up on the X axis and in tooltips, so the charts were unreadable
 *     in any other language regardless of the surrounding page.
 *   - Eight strings already had translations. `common.loading`, `common.retry`,
 *     `search.noResults`, `search.resultsCount`, `search.collection`,
 *     `search.allCollections`, `documents.collection` and `alerts.unit` were
 *     maintained in both locale files, and `search.noResults` /
 *     `search.resultsCount` had **no caller at all** — the markup had been
 *     building the same sentences by hand for as long as they existed.
 *
 * Detection deliberately runs on an expanded surface. Two sweeps were needed to
 * find everything, and the second one found things the first missed:
 *
 *   - JSX text nodes            `<h3>Call Volume</h3>`
 *   - chart labels              `{ name: 'Retrievals' }`, `<Bar name="Calls" />`
 *   - accessible names          `aria-label="Notifications"`
 *   - titles and placeholders   `title="Generate UUID"`,
 *                               `placeholder="UUID or business key"` — both
 *                               announced or shown, both missed by the first
 *                               sweep, which is why this one is written down.
 *
 * Rules:
 *   1. component-without-i18n  the file shows user-visible English and never
 *                              reaches for i18n at all. Strongest signal, and
 *                              the one that caught both untranslated files.
 *   2. hardcoded-user-copy     the file *does* use i18n but this string does not
 *                              go through it.
 *
 * The allowlist below is not an amnesty — it is the seven strings that should
 * stay in English, each with the reason it is correct rather than merely
 * tolerated.
 *
 * **Batch 880 made that last sentence enforceable.** "An entry that is no longer
 * needed is removed, not left to rot" was a promise about a discipline rather
 * than a check, and nothing enforced it. A rotted entry is not inert: the key is
 * `path:copy`, so once the string is gone the entry goes on silently allowing
 * the *next* occurrence of the same text in the same file, justified by a reason
 * written months ago for a string that no longer exists. An entry nobody uses is
 * now a failure. The success line also stopped conflating two numbers: it
 * counted hits and printed them as an entry count, so one entry matching twice
 * and another matching nothing still read "7".
 *
 * Run:
 *   node scripts/check-hardcoded-copy.mjs
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
// Batch 905: this file had its own copy of the comment stripper, and it was
// the regex shape — a `//` inside a string is a comment to it. The correct one
// already existed here, exported by check-design-system and imported by seven
// sibling checks, which is exactly the duplication that let the bad copy live.
// No current source in this package is damaged by it (measured: 0 literals), so
// this removes a trap rather than fixing a live false negative.
import { stripComments } from './check-design-system.mjs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { collectAccessibleNameProps } from './lib/accessible-name-props.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const SOURCE_ROOT = join(projectRoot, 'src');

export const VIOLATION_KINDS = Object.freeze({
  NO_I18N_AT_ALL: 'component-without-i18n',
  HARDCODED_COPY: 'hardcoded-user-copy',
});

/**
 * Strings that stay English on purpose, as `path:copy` → reason.
 *
 * The path is relative to `src/` and the copy is the literal; a line number is
 * deliberately not used because it drifts on every unrelated edit.
 */
export const ALLOWED = Object.freeze({
  'pages/Alerts.tsx:RECURRING':
    'select option whose visible text is its own enum value; the label the API ' +
    'stores is RECURRING, so showing anything else would desync the two.',
  'pages/Documents.tsx:ASYNC':
    'select option whose visible text is its own enum value (batch embedding mode).',
  'pages/Documents.tsx:SYNC':
    'select option whose visible text is its own enum value (batch embedding mode).',
  'pages/Documents.tsx:SKIP':
    'select option whose visible text is its own enum value (batch embedding mode).',
  'pages/Embeddings.tsx:QUEUED':
    'title attribute carrying a job state enum, not prose.',
  'pages/Evaluation.tsx:MRR':
    'metric abbreviation standing beside nDCG; both are the accepted symbols, ' +
    'and translating only MRR would split the header row.',
  'pages/Settings.tsx:English':
    'language picker labels each language in that language, which is the ' +
    'convention this picker follows; a Chinese user looking for 中文 must find it.',
});

const PATTERNS = [
  // `%` joined this class in Batch 931, and the reason is worth keeping: the
  // only literal user-visible string left in the WebUI was
  // `<h3>Cache Hit Rate (%)</h3>` in MetricsCharts, and this pattern could not
  // see it. The character class has to match every character up to the closing
  // tag, so one absent character made the whole string invisible — and a rule
  // that cannot see a real violation is indistinguishable from a clean file.
  //
  // `%` is the only addition, and it is the only one with a proven instance. The
  // first attempt here also added `<`, `>` and a dozen other characters, which
  // was wrong in a way only the real tree showed: the class describes what can
  // appear *inside* a text node, so letting it match `<` let the greedy match
  // run past the closing tag and produce the copy `RECURRING</option>` instead
  // of `RECURRING`. `auditAllowlist` keys on the exact copy, so five honest
  // allowlist entries were reported stale on the spot. Every other character
  // needs its own instance before it earns a place here.
  { kind: 'jsx-text', re: />\s*([A-Z][A-Za-z0-9 ,.'’!?/&()%\-:]{2,})\s*</ },
  { kind: 'chart-data', re: /\bname:\s*'([A-Z][A-Za-z0-9 ()]{2,})'/ },
  { kind: 'chart-prop', re: /\bname="([A-Z][A-Za-z0-9 ()]{2,})"/ },
  {
    kind: 'attribute',
    re: /\b(?:aria-label|title|placeholder|alt)="([A-Z][A-Za-z0-9 ,.'’!?()\-:]{2,})"/,
  },
  // A validation message is built by assigning prose to a field, never by
  // putting it in JSX — `errors.name = 'Name is required'` renders through a
  // later `{errors.name}`. No earlier pattern could see it, so a whole
  // component's user-facing text could sit in literals while this gate
  // reported it clean. Requiring a space and a trailing `;` is what keeps
  // machine constants (`STATE = 'ACTIVE'`) out; measured over all 45 scanned
  // component sources this pattern reports 5 hits and zero false positives.
  //
  // The `m` flag is load-bearing: `findHardcodedCopy` only appends `g`, so a
  // bare `^` would anchor to the start of the whole file and match nothing.
  {
    kind: 'assigned-copy',
    re: /^[ \t]*[\w.[\]]+\s*=\s*'([A-Z][A-Za-z0-9 ,.'’!?/&()\-:]{2,}[ ][A-Za-z0-9 ,.'’!?/&()\-:]{2,})'\s*;/m,
  },
  // Toast copy is handed to a sink, not rendered inline, so neither JSX nor
  // the expression-container scan reaches it. The quoted form needs no more
  // than a non-greedy body; the template form goes through
  // findToastTemplateCopy below, because deciding whether a template carries
  // prose of its own is not a shape a single regex can express.
  {
    kind: 'toast-copy',
    re: /showToast\(\s*'([^'\\]*(?:\\.[^'\\]*)*)'/,
  },
];

/**
 * Prose a template literal carries *itself*, as opposed to what it interpolates.
 *
 * ``showToast(`${fileName} ${t('documents.uploaded')}`)`` is already
 * translated — every word a user reads comes from the locale, and the two
 * interpolations are a filename and a translated string. Reporting it would
 * send the next reader hunting for copy that is not there. The tell is
 * mechanical: blank out every `${…}`, then look for a capitalised word in what
 * is left. `` `Re-embed failed: ${err.message}` `` leaves "Re-embed failed:"
 * and is reported; `` `${fileName}: ${errorMsg}` `` leaves ": " and is not.
 *
 * Splitting the quoted and template forms into separate patterns matters for a
 * different reason: a `(?:'…'|`…`)` alternation makes the capture the whole
 * argument list, so `Collection created successfully', 'success` is what gets
 * reported.
 *
 * The word test allows punctuation between words on purpose. `Re-embedded: 3
 * success` is prose; a test that demanded a space right after the first word
 * would read the colon as the end of it and let the string through.
 */
export function findToastTemplateCopy(code) {
  const found = [];
  const rx = /showToast\(\s*`([^`]*)`/g;
  let m;
  while ((m = rx.exec(code)) !== null) {
    const own = m[1].replace(/\$\{[^}]*\}/g, ' ').trim();
    if (!/[A-Z][A-Za-z0-9'-]{2,}(?:[ :,.!?—–-]+[a-zA-Z0-9'’-]+)+/.test(own)) continue;
    found.push({
      copy: m[1].trim(),
      kind: 'toast-copy',
      line: code.slice(0, m.index).split('\n').length,
    });
  }
  return found;
}

/**
 * Attributes whose value is a machine identifier rather than something a user
 * reads. `role={state === 'error' ? 'alert' : 'status'}` is not untranslated
 * copy — 'alert' and 'status' are ARIA tokens, and translating them would break
 * the accessibility tree rather than improve it.
 */
const MACHINE_VALUE_ATTRIBUTES = new Set([
  'role', 'type', 'variant', 'size', 'id', 'name', 'as', 'to', 'href', 'form',
  'method', 'target', 'rel', 'event', 'viewBox', 'fillRule', 'clipRule',
  'strokeWidth', 'data-testid', 'data-test', 'data-state', 'data-tone',
]);

/**
 * A string that is one of the *branches* of a conditional or logical expression.
 *
 * This is the load-bearing restriction. An earlier draft accepted any capitalised
 * string inside any `{...}` and reported 70, of which most were not rendered text
 * at all: `principal.status !== 'ACTIVE'`, `event.key === 'ArrowRight'`,
 * `new Error('Tooltip expects a single React element as its trigger')`, and — the
 * real culprit — every `{ ... }` *block* and destructuring pattern in the file,
 * which a `{...}` regex cannot tell apart from a JSX container. Requiring the
 * literal to sit behind `?`, `:`, `&&`, `||` or `??` is what "this string is a
 * possible rendered value" actually means, and it drops 70 to a number worth
 * reading.
 */
const SINGLE = "'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'";
const DOUBLE = '"([^"\\\\]*(?:\\\\.[^"\\\\]*)*)"';
const BRANCH_STRING = new RegExp(
  `(\\?|:|&&|\\|\\||\\?\\?)\\s*(?:${SINGLE}|${DOUBLE})`, 'g',
);

/**
 * Blanks out every `t(...)` call, arguments and all.
 *
 * `t('nav.closeSidebar', 'Close sidebar')` — the second argument is a fallback
 * for a missing key, not rendered copy: i18next only reaches for it when the key
 * is absent, which `check-i18n-keys` reports separately. Counting it as
 * hardcoded copy would be a second gate answering the same question with a
 * worse answer.
 *
 * Regex over the argument list was not enough. The shapes actually in this tree
 * include `t('k')`, `t('k', 'Close sidebar')`, `t('k', { defaultValue: '…' })`
 * and `` t(`prefix.${code || 'DEFAULT'}`) ``, and a pattern that matched only
 * quoted arguments reported eight phantom strings on Settings.tsx — every one of
 * them a fallback `defaultValue` that `check-i18n-keys` already owns. None of
 * them is rendered text, so a scanner that balances parentheses is the honest
 * way to exclude the whole class rather than a longer pattern.
 *
 * A `t(` is only a call when it is not part of a longer identifier (`format(`,
 * `split(`), which is why the identifier boundary is checked explicitly.
 */
export function maskTranslationCalls(code) {
  let out = '';
  let i = 0;
  while (i < code.length) {
    const ch = code[i];
    if (ch === '"' || ch === "'" || ch === '`') {
      const quote = ch;
      out += ch;
      i += 1;
      while (i < code.length && code[i] !== quote) {
        if (code[i] === '\\') {
          out += code[i] ?? '';
          i += 1;
        }
        out += code[i] ?? '';
        i += 1;
      }
      out += quote;
      i += 1;
      continue;
    }
    if (ch === 't' && code[i + 1] === '(' && !/[\w$.]/.test(code[i - 1] ?? '')) {
      let depth = 0;
      let j = i + 1;
      for (; j < code.length; j += 1) {
        const inner = code[j];
        if (inner === '"' || inner === "'" || inner === '`') {
          const quote = inner;
          j += 1;
          while (j < code.length && code[j] !== quote) {
            if (code[j] === '\\') j += 1;
            j += 1;
          }
          continue;
        }
        if (inner === '(') depth += 1;
        else if (inner === ')') {
          depth -= 1;
          if (depth === 0) break;
        }
      }
      out += ' '.repeat(j - i + 1);
      i = j + 1;
      continue;
    }
    out += ch;
    i += 1;
  }
  return out;
}

/** `{ method: 'POST', headers: { ... } }` is a JavaScript object, not markup. */
const OBJECT_LITERAL = /^\s*['"]?[\w-]+['"]?\s*:/;

export function findExpressionContainerCopy(source) {
  // Template interpolations first: `${code || 'DEFAULT'}` is a key segment, and
  // the container regex would otherwise see that inner `{...}` on its own.
  // This is a deliberate false negative — copy rendered through a template
  // interpolation that also holds a conditional is not checked.
  const code = source.replace(/\$\{[^{}]*\}/g, ' ');
  const masked = maskTranslationCalls(code);
  const found = [];
  const container = /([A-Za-z][\w-]*)\s*=\s*\{([^{}]*)\}|\{([^{}]*)\}/g;
  let m;
  while ((m = container.exec(masked)) !== null) {
    const attribute = m[1];
    if (attribute && MACHINE_VALUE_ATTRIBUTES.has(attribute)) continue;
    // A bare `{...}` only counts as a JSX expression container when what stands
    // before it is markup: the end of a tag, the end of a sibling expression, an
    // opening paren of a returned fragment, or the start of a nested one.
    // Without this the scan reads ordinary functions — it reported the
    // `'DISABLED' : 'READY' : 'NOT_REQUESTED'` branches of lifecycleClass(),
    // which build a CSS class name and are never rendered.
    const before = masked.slice(0, m.index).replace(/\s+$/, '').slice(-1);
    if (!attribute && !['>', '}', '(', '{'].includes(before)) continue;
    const body = m[2] ?? m[3] ?? '';
    if (OBJECT_LITERAL.test(body)) continue;
    for (const literal of body.matchAll(BRANCH_STRING)) {
      const copy = (literal[2] ?? literal[3] ?? '').trim();
      if (!/^[A-Z]/.test(copy)) continue;
      found.push({
        copy,
        kind: 'jsx-expression',
        line: code.slice(0, m.index).split('\n').length,
      });
    }
  }
  return found;
}

/**
 * Strips comments so a string inside prose cannot be read as rendered copy.
 *
 * Newlines inside a block comment are preserved. Replacing a multi-line comment
 * with a single space also collapses its line breaks, and every line number in
 * this file is computed by counting `\n` in the stripped text — so a file with
 * a block comment above the offending line reported a line that was 20 lines
 * off. That is the failure mode a reader hits first: the gate says
 * `Dialog.tsx:164` and line 164 is an unrelated `role="dialog"`. The offender
 * was line 184. Masking with spaces of equal length keeps offsets and line
 * numbers addressing the same character as the original file.
 */
export function usesI18n(source) {
  return /useTranslation\b/.test(source)
    || /withTranslation\b/.test(source)
    || /<Trans[\s>]/.test(source);
}

/**
 * @param {string} relPath
 * @param {string} source
 * @param {Map<string, Set<string>>} [proseProps] component → prose prop names
 * @returns {{copy: string, kind: string, line: number}[]}
 */
export function findHardcodedCopy(relPath, source, proseProps) {
  const code = stripComments(source);
  const found = [];
  for (const { kind, re } of PATTERNS) {
    const rx = new RegExp(re.source, re.flags.includes('g') ? re.flags : `${re.flags}g`);
    let m;
    while ((m = rx.exec(code)) !== null) {
      found.push({
        copy: m[1].trim(),
        kind,
        line: code.slice(0, m.index).split('\n').length,
      });
    }
  }
  found.push(...findExpressionContainerCopy(code));
  found.push(...findToastTemplateCopy(code));
  found.push(...findProsePropCopy(code, proseProps));
  return found.sort((a, b) => a.line - b.line);
}

export function checkFile(relPath, source, allowed = ALLOWED, proseProps) {
  const hits = findHardcodedCopy(relPath, source, proseProps);
  if (hits.length === 0) return [];
  const hasI18n = usesI18n(source);
  const violations = [];
  for (const hit of hits) {
    const key = `${relPath}:${hit.copy}`;
    if (Object.prototype.hasOwnProperty.call(allowed, key)) continue;
    violations.push({
      kind: hasI18n ? VIOLATION_KINDS.HARDCODED_COPY : VIOLATION_KINDS.NO_I18N_AT_ALL,
      detail:
        `${relPath}:${hit.line} renders "${hit.copy}" (${hit.kind}) as literal text. ` +
        (hasI18n
          ? 'The file uses i18n elsewhere, so this string is a gap rather than an untranslated component.'
          : 'The file never calls useTranslation, so none of its user-visible text can be translated.'),
    });
  }
  return violations;
}

/**
 * How many times each allowlist entry is actually used, and which entries are
 * not used at all.
 *
 * Batch 880. The header has always said "an entry that is no longer needed is
 * removed, not left to rot", which is a promise about a discipline rather than a
 * check — and nothing enforced it. A rotted entry is not inert: the key is
 * `path:copy`, so once the string is gone from the file the entry keeps
 * silently allowing the *next* occurrence of the same text in the same file,
 * justified by a reason written months earlier for a string that no longer
 * exists. That is the same staleness contract the design-debt baseline
 * enforces and this list did not.
 *
 * Pure, so the check itself can be tested. The real tree currently has no
 * rotted entry; the point is that the next one cannot appear quietly.
 *
 * @param {{relPath: string, source: string}[]} files
 * @param {Record<string, string>} allowed
 * @returns {{used: Map<string, number>, stale: string[]}}
 */
export function auditAllowlist(files, allowed = ALLOWED) {
  const proseProps = collectProseProps(files);
  const used = new Map();
  for (const file of files) {
    for (const hit of findHardcodedCopy(file.relPath, file.source, proseProps)) {
      const key = `${file.relPath}:${hit.copy}`;
      if (Object.prototype.hasOwnProperty.call(allowed, key)) {
        used.set(key, (used.get(key) ?? 0) + 1);
      }
    }
  }
  const stale = Object.keys(allowed).filter(key => !used.has(key));
  return { used, stale };
}

function tsxFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...tsxFiles(path));
    else if (entry.endsWith('.tsx')) out.push(path);
  }
  return out;
}

export function collectSources(root = SOURCE_ROOT) {
  if (!existsSync(root)) return [];
  return tsxFiles(root)
    .filter((p) => !/\.(test|spec)\.tsx$/.test(p))
    .map((path) => ({
      relPath: relative(root, path).split(sep).join('/'),
      source: readFileSync(path, 'utf8'),
    }));
}

// ── Custom components that mint their own accessible name ────────────────
//
// The `attribute` pattern above sees `aria-label="Notifications"`. It cannot see
// `<IconButton label="Notifications" />`, which reaches the user through exactly
// the same `aria-label` (IconButton.tsx renders `aria-label={label}` and
// `title={tooltip ?? label}`). Same string, same screen, one of them invisible to
// the gate — so the check was narrower than the comment above it promised.
//
// The fix is to *discover* the shape rather than hard-code a component list: any
// component whose props declare `p?: string` and whose body forwards `p` into
// `aria-label` / `title` has a prose prop. A list would rot silently; a new
// wrapper added next quarter is picked up with no edit here.
//
// `string` is the load-bearing qualifier, and it is what keeps this from
// becoming a false-positive machine. The first survey found six components
// forwarding a prop into aria-label/title, and only three declare theirs as
// `string` (IconButton.label, Dialog.ariaLabel, Tabs.ariaLabel). The other
// three take a `ReactNode` — a JSX element or a count — where a literal is not
// even a type error, so including them would report shapes that cannot occur.
// A gate that misreports gets allowlisted, and then it protects nothing.

/**
 * Literal copy handed to a discovered prose prop.
 *
 * Same shape rule as the `attribute` pattern (capitalised, at least three
 * characters) so a component's own enum-ish label behaves identically whether it
 * arrives through `aria-label` or through a wrapper.
 *
 * The reported line points at the **attribute**, not at the opening tag. A tag
 * can carry half a dozen props before the one that is wrong, and `Dialog.tsx`
 * puts `label` on the fourth line of its `IconButton`; pointing at the tag would
 * send a reader to the wrong line three rows up.
 *
 * @returns {{copy: string, kind: string, line: number}[]}
 */
/**
 * Re-exported so this gate's own tests keep a single import site, while the
 * knowledge itself is shared with check-a11y-forms.mjs — the same prop that
 * carries untranslated copy is the one that can leave a control unnamed.
 */
export const collectProseProps = (sources) => collectAccessibleNameProps(sources);

export function findProsePropCopy(source, proseProps) {
  if (!proseProps || proseProps.size === 0) return [];
  const code = stripComments(source);
  const found = [];
  for (const [component, props] of proseProps) {
    for (const prop of props) {
      const re = new RegExp(`<${component}\\b[^>]*?\\b${prop}="([^"]*)"`, 'g');
      let m;
      while ((m = re.exec(code)) !== null) {
        const attrOffset = m[0].lastIndexOf(`${prop}="`);
        const at = m.index + (attrOffset >= 0 ? attrOffset : 0);
        found.push({ copy: m[1].trim(), kind: 'prose-prop', line: code.slice(0, at).split('\n').length });
      }
    }
  }
  return found.filter((hit) => /^[A-Z][A-Za-z0-9 ,.'’!?()\-:]{2,}$/.test(hit.copy)).sort((a, b) => a.line - b.line);
}

function main() {
  const files = collectSources();
  // Discovered before checking, because "is this prop prose?" is a question about
  // the whole tree: IconButton's `label` is prose because IconButton forwards it
  // to aria-label, which no single page can tell on its own.
  const proseProps = collectProseProps(files);
  const violations = files.flatMap((f) => checkFile(f.relPath, f.source, ALLOWED, proseProps));
  // Batch 880. Two numbers used to be conflated into one, and neither was the
  // number the sentence claimed. The old line counted *hits* and printed
  // "N intentional technical string(s) allowlisted"; with one entry matching
  // twice and another matching nothing it would still have printed 7. An
  // entry nobody uses is now a failure, so the two numbers can only disagree
  // the other way round, and both are printed.
  const { used, stale } = auditAllowlist(files, ALLOWED);

  if (stale.length > 0) {
    console.error('Allowlist entries that no longer allow anything:');
    for (const key of stale) {
      console.error(
        `- [stale-allowlist-entry] ${key} is in ALLOWED but ${key.split(':')[0]} no longer`
          + ` contains "${key.slice(key.lastIndexOf(':') + 1)}". Delete the entry, or restore`
          + ' the string. A stale entry keeps silently allowing the next occurrence of'
          + ' the same text, justified by a reason written for a string that is gone.',
      );
    }
    console.error(
      '\nThe allowlist is not an amnesty. An entry earns its place by naming a string\n'
        + 'that is really there; keeping it after the string moves on is how a list of\n'
        + 'seven honest exceptions becomes a list of whatever happens to match.',
    );
    process.exitCode = 1;
    return;
  }

  if (violations.length > 0) {
    console.error('Hardcoded user-visible copy:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      '\nUser-visible text must come from src/i18n/locales via t(). If a string is a\n' +
        'technical term or an enum value whose label is its own value, add it to ALLOWED\n' +
        'in scripts/check-hardcoded-copy.mjs with the reason — not a bare suppression.',
    );
    process.exitCode = 1;
    return;
  }

  const discovered = [...proseProps.entries()]
    .map(([component, props]) => `${component}.${[...props].join('/')}`)
    .join(', ');
  console.log(
    `Hardcoded-copy check passed; ${files.length} component source(s) scanned, `
      + `${Object.keys(ALLOWED).length} allowlist entr(y|ies), every one in use across `
      + `${[...used.values()].reduce((a, b) => a + b, 0)} occurrence(s), no other `
      + 'user-visible text is a literal.',
  );
  console.log(
    `Custom components whose accessible name comes from a prop: ${discovered || '(none)'}. ` +
      'Literal copy passed to one of those props is reported just like aria-label="…".',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
