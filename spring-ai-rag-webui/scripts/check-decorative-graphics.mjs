#!/usr/bin/env node
/**
 * Decorative-graphic gate: an inline `<svg>` that names nothing is decoration,
 * and decoration has to say so.
 *
 * An SVG with no `role` and no accessible name is, by definition, telling a
 * screen reader nothing. Left unmarked, assistive technology still walks it —
 * an unnamed graphic announced between the button's label and its content — and
 * the cost is paid by exactly the readers the rest of this document is written
 * for. `aria-hidden="true"` says "this is not content", which is the truth.
 *
 * The rule is deliberately narrow in the other direction too: a graphic that
 * *does* name something — `role="img"` with an `aria-label`, or a `<title>`
 * child — is content, and hiding it would be the bug. That is why the check
 * asks about the absence of a name rather than the presence of `aria-hidden`.
 *
 * Scope is inline `<svg>` only. Every icon in this repository is a component
 * that already sets `aria-hidden` (`ThemeToggle`, `ReembedAllButton`,
 * `SearchResults`, `ErrorBoundary`, `Layout`), and `IconButton` gets its
 * accessible name from the caller. Batch 911 measured one inline `<svg>` in the
 * whole `src/` tree: the clock glyph inside `Search.tsx`'s history toggle, whose
 * `<button>` already carried `aria-label` — so the glyph was pure decoration
 * and was being walked anyway.
 *
 * `focusable="false"` travels with the `aria-hidden` because that is the pair
 * IE/Edge honoured to keep a hidden graphic out of the tab order, and the cost
 * of adding it to a decorative glyph is one attribute.
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { isMainModule } from './lib/is-main-module.mjs';
import { stripTsxComments } from './lib/tsx-source.mjs';

function walk(dir, out = []) {
  for (const entry of readdirSync(dir).sort()) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (entry.endsWith('.tsx') && !entry.includes('.test.')) out.push(full);
  }
  return out;
}

/** The opening tag of an inline `<svg>`, plus its body up to the close. */
function svgElements(source) {
  const out = [];
  for (const m of source.matchAll(/<svg\b/g)) {
    const openEnd = source.indexOf('>', m.index);
    if (openEnd < 0) continue;
    const close = source.indexOf('</svg>', openEnd);
    out.push({
      opening: source.slice(m.index, openEnd + 1),
      hasTitle: close > 0 && /<title[\s>]/.test(source.slice(openEnd, close)),
      line: source.slice(0, m.index).split('\n').length,
    });
  }
  return out;
}

/**
 * Attribute names on one JSX opening tag.
 *
 * Parsed rather than grepped, because a text search cannot tell an attribute
 * from a string that looks like one: `<svg data-note="aria-hidden='true'">`
 * declares `data-note`, not `aria-hidden`, and a gate that reports the graphic
 * as hidden has made the opposite mistake from the one it exists to catch.
 */
export function attributesOf(openingTag) {
  const inner = openingTag.replace(/^<\s*svg\b/i, '').replace(/\/?>$/, '');
  const names = [];
  let i = 0;
  while (i < inner.length) {
    if (/[\s/]/.test(inner[i])) { i += 1; continue; }
    let name = '';
    while (i < inner.length && !/[\s=/]/.test(inner[i])) {
      name += inner[i];
      i += 1;
    }
    names.push(name);
    // Step over the value, respecting whichever quote opened it.
    while (i < inner.length && /\s/.test(inner[i])) i += 1;
    if (inner[i] !== '=') continue;
    i += 1;
    while (i < inner.length && /\s/.test(inner[i])) i += 1;
    const q = inner[i];
    if (q === '"' || q === "'") {
      const end = inner.indexOf(q, i + 1);
      i = end < 0 ? inner.length : end + 1;
    } else {
      while (i < inner.length && !/\s/.test(inner[i])) i += 1;
    }
  }
  return names;
}

/**
 * Inline SVGs that neither name themselves nor declare themselves decorative.
 *
 * @returns {Array<{line: number, snippet: string}>}
 */
export function checkSource(name, source) {
  // Comments are stripped so `aria-hidden="true"` inside a note cannot make an
  // unnamed graphic look declared. String contents are NOT stripped, because
  // this gate's question is literally about an attribute's value — hence
  // `stripTsxComments` rather than `stripTsxNoise`.
  const code = stripTsxComments(source);
  const findings = [];
  for (const svg of svgElements(code)) {
    const names = attributesOf(svg.opening);
    const namesRole = names.includes('role') && /\bimg\b/.test(svg.opening);
    const hasLabel = names.some((a) => a === 'aria-label' || a === 'aria-labelledby');
    // `aria-hidden="false"` is a declaration *against* being hidden, so the value
    // has to be read, not just the attribute's presence.
    const hidden = names.includes('aria-hidden')
      && /\baria-hidden\s*=\s*(?:\{\s*)?["']?true\b/.test(svg.opening);
    if (hasLabel || namesRole || hidden || svg.hasTitle) continue;
    findings.push({
      file: name,
      line: svg.line,
      snippet: svg.opening.replace(/\s+/g, ' ').slice(0, 100),
    });
  }
  return findings;
}

function main() {
  const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
  const srcDir = path.join(root, 'src');
  const files = walk(srcDir);
  const findings = files.flatMap((file) =>
    checkSource(path.relative(srcDir, file), readFileSync(file, 'utf8')));

  const total = files.reduce((n, f) => n + svgElements(readFileSync(f, 'utf8')).length, 0);
  console.log(`tsx files scanned: ${files.length}, inline <svg> elements: ${total}.`);
  if (findings.length === 0) {
    console.log('Every inline graphic either names itself or declares itself decorative.');
    process.exit(0);
  }

  console.error(`\n${findings.length} decorative graphic(s) a screen reader would walk:\n`);
  for (const f of findings) {
    console.error(`  ${f.file}:${f.line}`);
    console.error(`      ${f.snippet}`);
  }
  console.error('\nAdd aria-hidden="true" focusable="false" — unless the graphic really does');
  console.error('carry meaning, in which case give it role="img" and an aria-label instead.');
  process.exit(1);
}

if (isMainModule(import.meta.url)) main();
