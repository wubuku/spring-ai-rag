#!/usr/bin/env node
/**
 * Form-accessibility gate.
 *
 * The design-system gate proved that a whole category of defect can sit in a
 * codebase for years while `typecheck`, `lint` and the full test suite stay
 * green — nobody notices because no rule is looking. This gate covers the
 * category that cost Batch 776 its findings:
 *
 *   1. control-no-name          a form control with no accessible name
 *   2. orphan-label             a <label> that labels nothing
 *   3. click-non-interactive    a click handler on an element the keyboard
 *                               cannot reach, with no interactive role
 *   4. weak-allow-reason        an exemption comment too short to be a reason
 *   5. dialog-title-can-be-empty a <Dialog> whose accessible name can be an
 *                               empty string, leaving an unnamed modal
 *
 * Why these and not others: they are the ones that are *unambiguously*
 * wrong, which is what makes a static gate worth having. A placeholder is not
 * a label — it vanishes the moment the field has content — and a click handler
 * on a <div> with no role is invisible to the tab order, so the feature simply
 * does not exist for keyboard and screen-reader users. A modal whose own title
 * element is empty is the same class of defect one layer up: the control is
 * there, and it announces nothing.
 *
 * There is deliberately no debt baseline. Every violation that existed when
 * this gate was written was fixable, so the baseline would have been a list of
 * bugs that a machine had agreed to stop reporting.
 *
 * Narrow, justified exemptions use an inline
 *   /* a11y-allow: <reason> *\/
 * comment on the same or the previous line. A reason is mandatory.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

const SCAN_EXTENSIONS = ['.tsx', '.jsx'];
const SKIP_DIRECTORIES = new Set(['node_modules', 'dist', 'coverage', 'playwright-report', 'test-results']);
const isTestFile = path => /\.(test|spec)\.[cm]?[jt]sx?$/i.test(path);

const CONTROLS = ['input', 'select', 'textarea'];

/** Elements a browser already makes focusable and operable. */
const NATIVE_INTERACTIVE = new Set(['a', 'button', 'input', 'select', 'textarea', 'summary']);

/**
 * ARIA roles that carry interactivity. A role here only counts when the element
 * is *also* focusable and *also* handles a key, otherwise the role is a label
 * on a dead element.
 */
const INTERACTIVE_ROLES = new Set([
  'button',
  'link',
  'checkbox',
  'radio',
  'switch',
  'tab',
  'menuitem',
  'menuitemcheckbox',
  'menuitemradio',
  'option',
  'slider',
  'spinbutton',
  'textbox',
  'combobox',
]);

const ALLOW_COMMENT = /a11y-allow:\s*(.+?)\s*(?:\*\/)?$/;

/**
 * The violation kinds this gate can emit.
 *
 * Exported so the documentation check compares against the real list instead of
 * guessing how the checker happens to spell its literals. The behavioural tests
 * still assert what each kind *means*; this only pins the names.
 */
const [
  CONTROL_NO_NAME,
  ORPHAN_LABEL,
  CLICK_NON_INTERACTIVE,
  WEAK_ALLOW_REASON,
  DIALOG_EMPTY_TITLE,
] = Object.freeze([
  'control-no-name',
  'orphan-label',
  'click-non-interactive',
  'weak-allow-reason',
  'dialog-title-can-be-empty',
]);

export const VIOLATION_KINDS = Object.freeze([
  CONTROL_NO_NAME,
  ORPHAN_LABEL,
  CLICK_NON_INTERACTIVE,
  WEAK_ALLOW_REASON,
  DIALOG_EMPTY_TITLE,
]);

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

/**
 * Mark every span of source that sits inside a string literal.
 *
 * Structural rules must ignore markup that is merely *text*: `const doc =
 * '<div onClick="x"></div>'` is a string, not a component, and reporting it
 * would be a false positive. Attribute values are unaffected because a tag is
 * matched before its contents are read, and the body is sliced from the
 * original source rather than from this map.
 */
function stringSpans(source) {
  const spans = [];
  let index = 0;
  let quote = null;
  let opened = -1;
  while (index < source.length) {
    const char = source[index];
    if (quote !== null) {
      if (char === '\\') {
        index += 2;
        continue;
      }
      if (char === quote) {
        // The span covers the delimiters *and* everything between them:
        // recording only the quote characters would leave the body of the
        // string exposed, which is exactly the text this rule must ignore.
        spans.push([opened, index + 1]);
        quote = null;
        opened = -1;
      }
      index += 1;
      continue;
    }
    if (char === '"' || char === "'" || char === '`') {
      quote = char;
      opened = index;
      index += 1;
      continue;
    }
    index += 1;
  }
  // An unterminated literal still hides everything after its opening quote.
  if (quote !== null) spans.push([opened, source.length]);
  return spans;
}

const inSpans = (spans, offset) => spans.some(([from, to]) => offset >= from && offset <= to);

/**
 * Locate every opening tag of `tag`, together with its full attribute text.
 *
 * The scan has to be string- and brace-aware: an attribute value may contain
 * `>` (as in `onClick={() => f()}`) and a `>` inside a string literal is not the
 * end of the tag.
 *
 * @returns {{start: number, end: number, body: string, line: number}[]}
 */
function openTags(source, tag, spans = []) {
  const pattern = new RegExp(`<${tag}(?=[\\s/>])`, 'g');
  const found = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    const start = match.index;
    if (inSpans(spans, start)) continue;
    let index = start + match[0].length;
    let depth = 0;
    let quote = null;
    for (; index < source.length; index += 1) {
      const char = source[index];
      if (quote !== null) {
        if (char === '\\') {
          index += 1;
        } else if (char === quote) {
          quote = null;
        }
        continue;
      }
      if (char === '"' || char === "'" || char === '`') {
        quote = char;
        continue;
      }
      if (char === '{') {
        depth += 1;
        continue;
      }
      if (char === '}') {
        depth -= 1;
        continue;
      }
      if (char === '>' && depth === 0) break;
    }
    found.push({
      start,
      end: index + 1,
      body: source.slice(start, index + 1),
      line: source.slice(0, start).split('\n').length,
    });
    pattern.lastIndex = index + 1;
  }
  return found;
}

function attr(tagBody, name) {
  const match = tagBody.match(new RegExp(`\\b${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|\\{([^}]*)\\})`));
  if (!match) return null;
  return { present: true, value: (match[1] ?? match[2] ?? match[3] ?? '').trim() };
}

const hasAttr = (tagBody, name) => new RegExp(`\\b${name}(?=[\\s=/>])`).test(tagBody);

/** Normalise `id="x"`, `id={'x'}` and `id={VARIABLE}` to a comparable token. */
function identifier(raw) {
  if (raw === null) return null;
  const bare = raw.replace(/[{}]/g, '').trim().replace(/^['"`]|['"`]$/g, '').trim();
  return bare.length > 0 ? bare : null;
}

/** True when the tag never enters the accessibility tree. */
function hiddenFromAT(tagBody) {
  if (hasAttr(tagBody, 'aria-hidden')) {
    const value = attr(tagBody, 'aria-hidden')?.value;
    return value === 'true' || value === '{true}';
  }
  if (/display:\s*['"]?none/.test(tagBody)) return true;
  if (attr(tagBody, 'type')?.value === 'hidden') return true;
  // React accepts a bare boolean `hidden`, which carries no `=value` at all.
  const hidden = attr(tagBody, 'hidden');
  if (hidden) return hidden.value === '' || hidden.value === 'true' || hidden.value === '{true}';
  return false;
}

/** True when the control is the first control wrapped by an enclosing <label>. */
function wrappedByLabel(source, tagStart) {
  const before = source.slice(0, tagStart);
  const open = before.lastIndexOf('<label');
  if (open === -1) return false;
  // A self-closing <label /> cannot wrap anything.
  if (before.slice(open).match(/^<label[^>]*\/>/)) return false;
  return !before.slice(open).includes('</label>');
}

/**
 * Scan one file into violation records.
 * @returns {{file: string, kind: string, value: string, line: number, allowed: string|null}[]}
 */
export function scanFile(path) {
  return scanSource(relative(projectRoot, path), readFileSync(path, 'utf8'));
}

/**
 * Pure variant used by the focused tests: same rules, but the caller supplies
 * the source text instead of hitting the filesystem.
 * @param {string} relativePath project-relative path, used for reporting and exemptions
 * @param {string} source file contents
 */
export function scanSource(relativePath, source) {
  if (isTestFile(relativePath)) return [];

  // Debt rules read the comment-free source; the allow-reason lookup still sees
  // the raw line, because a justification lives in a comment by definition.
  const rawLines = source.split(/\r?\n/);
  const code = stripComments(source);
  const spans = stringSpans(code);
  const violations = [];

  const lineAt = offset => code.slice(0, offset).split('\n').length;
  const allowFor = line => {
    const match = ALLOW_COMMENT.exec(rawLines[line - 1] ?? '') ?? ALLOW_COMMENT.exec(rawLines[line - 2] ?? '');
    return match ? match[1].trim() : null;
  };
  const report = (kind, value, line, allowed = null) =>
    violations.push({ file: relativePath, kind, value, line, allowed });

  // Every id and every label target in the file, so association can be resolved
  // across the whole component rather than by proximity.
  const ids = new Set();
  const labelTargets = new Set();
  for (const tag of [
    ...CONTROLS,
    'label',
    'div',
    'span',
    'button',
    'a',
    'section',
    'p',
    'ul',
    'li',
    'h1',
    'h2',
    'h3',
    'h4',
    'form',
    'fieldset',
    'table',
    'tr',
  ]) {
    for (const found of openTags(code, tag, spans)) {
      const id = identifier(attr(found.body, 'id')?.value ?? null);
      if (id) ids.add(id);
    }
  }
  for (const found of openTags(code, 'label', spans)) {
    const target = identifier(attr(found.body, 'htmlFor')?.value ?? null);
    if (target) labelTargets.add(target);
  }

  // ── Rule 1: a control with no accessible name ────────────────────────────
  for (const tag of CONTROLS) {
    for (const found of openTags(code, tag, spans)) {
      if (hiddenFromAT(found.body)) continue;
      const line = lineAt(found.start);
      const allowed = allowFor(line);

      const ariaLabel = attr(found.body, 'aria-label')?.value ?? '';
      const labelledBy = attr(found.body, 'aria-labelledby')?.value ?? '';
      const title = attr(found.body, 'title')?.value ?? '';
      const id = identifier(attr(found.body, 'id')?.value ?? null);
      const labelled =
        ariaLabel.length > 0
        || labelledBy.split(/\s+/).filter(Boolean).some(token => ids.has(token))
        || title.length > 0
        || (id !== null && labelTargets.has(id))
        || wrappedByLabel(code, found.start);

      if (!labelled) {
        // A placeholder is a hint, not a name: it disappears as soon as the
        // field holds a value, so the control falls back to announcing nothing.
        report(CONTROL_NO_NAME, tag, line, allowed);
      }
    }
  }

  // ── Rule 2: a <label> that labels nothing ────────────────────────────────
  for (const found of openTags(code, 'label', spans)) {
    const line = lineAt(found.start);
    const allowed = allowFor(line);
    if (identifier(attr(found.body, 'htmlFor')?.value ?? null) !== null) continue;
    if (/\/>$/.test(found.body.trim())) continue;
    const inner = code.slice(found.end, found.end + 4000);
    const close = inner.indexOf('</label>');
    const contents = close === -1 ? inner : inner.slice(0, close);
    const wraps = CONTROLS.some(tag => new RegExp(`<${tag}(?=[\\s/>])`).test(contents));
    if (!wraps) {
      report(ORPHAN_LABEL, 'label', line, allowed);
    }
  }

  // ── Rule 3: a click handler the keyboard cannot reach ───────────────────
  for (const tag of [...CONTROLS, 'div', 'span', 'li', 'tr', 'td', 'section', 'p', 'h1', 'h2', 'h3', 'img', 'svg', 'label']) {
    for (const found of openTags(code, tag, spans)) {
      if (!hasAttr(found.body, 'onClick')) continue;
      if (NATIVE_INTERACTIVE.has(tag)) continue;
      const line = lineAt(found.start);
      const allowed = allowFor(line);
      // aria-hidden takes the element out of the accessibility tree entirely;
      // it is then not a target the keyboard is expected to reach. A
      // self-closing <div onClick={...} /> is explicitly *not* skipped: an
      // empty click-catching overlay is exactly that shape.
      if (attr(found.body, 'aria-hidden')?.value === 'true') continue;

      const role = attr(found.body, 'role')?.value ?? '';
      const focusable = hasAttr(found.body, 'tabIndex') || hasAttr(found.body, 'tabindex');
      const keyboard = hasAttr(found.body, 'onKeyDown') || hasAttr(found.body, 'onKeyUp') || hasAttr(found.body, 'onKeyPress');
      const operable = INTERACTIVE_ROLES.has(role) && focusable && keyboard;

      if (!operable) {
        const missing = [];
        if (!INTERACTIVE_ROLES.has(role)) missing.push('role');
        if (!focusable) missing.push('tabIndex');
        if (!keyboard) missing.push('onKeyDown');
        report(CLICK_NON_INTERACTIVE, `<${tag}> missing ${missing.join('+')}`, line, allowed);
      }
    }
  }

  // ── Rule 5: a dialog whose accessible name can be empty ────────────────
  //
  // `Dialog` names itself with `aria-labelledby` pointing at its own <h2>.
  // When the title expression can evaluate to an empty string, that <h2> is
  // empty and the dialog announces as an unnamed "dialog" — and the header bar
  // on screen is blank too, so the sighted user cannot tell what they opened.
  //
  // The shape caught here is deliberately narrow: a title that coalesces to an
  // empty string literal, or a bare optional-chain read that yields `undefined`.
  // A title built as `` `${prefix} — ${userValue}` `` cannot be empty, which is
  // why VersionHistoryModal's title is not flagged.
  for (const found of openTags(code, 'Dialog', spans)) {
    const line = lineAt(found.start);
    const title = attr(found.body, 'title');
    if (!title || title.value === '') continue;
    const expression = title.value;
    const coalescesToEmpty = /^\s*\(?\s*[\w$.?\[\]]+\s*\)?\s*\?\?\s*(''|"")\s*$/.test(expression);
    const bareOptionalRead = /^\s*[\w$]+\s*\?\.\s*[\w$]+\s*$/.test(expression);
    if (coalescesToEmpty || bareOptionalRead) {
      report(DIALOG_EMPTY_TITLE, `title={${expression}}`, line, allowFor(line));
    }
  }

  // ── Rule 4: an exemption that is not a reason ────────────────────────────
  for (const [index, raw] of rawLines.entries()) {
    const match = ALLOW_COMMENT.exec(raw);
    if (match && match[1].trim().length < 8) {
      violations.push({
        file: relativePath,
        kind: WEAK_ALLOW_REASON,
        value: raw.trim().slice(0, 60),
        line: index + 1,
        allowed: null,
      });
    }
  }

  return violations;
}

export function fingerprint(violation) {
  return `${violation.file}|${violation.kind}|${violation.value}`;
}

function main() {
  const paths = walk(sourceRoot);
  const violations = paths.flatMap(path => scanFile(path));
  const errors = [];

  for (const violation of violations) {
    if (violation.allowed === null) {
      errors.push(`${violation.file}:${violation.line} [${violation.kind}] ${violation.value}`);
    }
  }

  if (errors.length > 0) {
    console.error('Form accessibility violations:');
    for (const error of errors) console.error(`- ${error}`);
    if (violations.some(v => v.allowed !== null)) {
      console.error(
        '\nFix the violations, or record a justified inline exemption with ' +
          '`/* a11y-allow: <reason> */` on the same or previous line.',
      );
    }
    process.exitCode = 1;
    return;
  }

  console.log(
    `Form accessibility policy passed; ${paths.length} component file(s) scanned, ` +
      'every control named, every label bound, every click target keyboard-operable.',
  );
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main();
}
