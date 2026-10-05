#!/usr/bin/env node
/**
 * Reduced-motion gate: an animation that never stops has to stop when the
 * reader has asked the operating system to stop it.
 *
 * The rule is one sentence. An `infinite` animation is continuous motion — a
 * sweeping background, a blinking cursor, a spinning wheel — and continuous
 * motion is what people with vestibular disorders turn off animations *for*.
 * So: any rule that declares an `infinite` animation must have its selector
 * neutralized (`animation: none`) inside a `@media (prefers-reduced-motion:
 * reduce)` block.
 *
 * Why `infinite` and not every animation, because that distinction is the
 * whole gate. A one-shot animation ends on its own and is short by
 * construction — the 140ms dialog fade in this repository is not what a
 * reduced-motion reader is asking you to remove, and WCAG's own guidance
 * tolerates opacity transitions for that reason. A gate that flagged those
 * would be reporting a correct shape, and this repository does not build gates
 * that need an allowlist to go green (see `verify-json-assertions.mjs`).
 * `infinite` is where the line sits.
 *
 * Five stylesheets were doing continuous motion with no guard when Batch 910
 * measured them, and only two — `Tooltip` and `Dialog` — carried one:
 *
 *   Skeleton.module.css        shimmer 1.5s infinite   every page that loads
 *   ReembedAllButton           shimmer 1.5s infinite
 *   Chat.module.css            blink 1s infinite       the streaming cursor
 *   Files.module.css           spin 0.6s infinite ×2   upload and preview
 *
 * The house pattern for the guard already existed in two files, so this gate
 * enforces a shape the codebase had already chosen rather than inventing one:
 *
 *     .spinner { animation: spin 0.6s linear infinite; }
 *
 *     @media (prefers-reduced-motion: reduce) {
 *       .spinner { animation: none; }
 *     }
 *
 * ## The mistake this file's own first draft made
 *
 * The first version asked "is there an `animation:` declaration outside a
 * reduced-motion block?" — which is true of *every* correctly guarded
 * stylesheet, because the house pattern declares the animation by default and
 * overrides it. It reported all six files as violations, five of which had
 * just been fixed correctly. A rule that flags the shape it exists to enforce
 * is worse than no rule: it teaches everyone to ignore it.
 *
 * So the rule is about selectors, not about positions: an infinite animation
 * is a finding unless *that selector* is neutralized somewhere under a
 * reduced-motion query.
 *
 * Comments are stripped before anything else, and Batch 910 hit this the
 * uncomfortable way: the guards it added each carry a comment that spells out
 * `prefers-reduced-motion` in prose, so a text-counting version counted its
 * own explanation as the guard and would have passed a file with none.
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { isMainModule } from './lib/is-main-module.mjs';

const INFINITE = /\binfinite\b/;
const REDUCED = /@media[^{]*prefers-reduced-motion\s*:\s*reduce/;
const ANIMATION_DECL = /(?:^|[;{\s])(animation|animation-name)\s*:\s*([^;}]*)/g;

/** Blank out CSS comments while keeping every newline, so line numbers hold. */
export function stripCssComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * Every brace-delimited block with the selector that introduced it, where that
 * selector started in the source, and the chain of enclosing at-rules. CSS
 * modules in this repository are flat — no nested rules — so a brace walker is
 * the whole parser, and pretending to understand the full grammar would be a
 * way to be wrong in new places.
 */
function blocks(src) {
  const out = [];
  const stack = [];
  let preludeStart = 0;
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === '{') {
      const start = i;
      stack.push({
        prelude: src.slice(preludeStart, i).trim(),
        bodyStart: i + 1,
        startLine: src.slice(0, start).split('\n').length,
      });
      preludeStart = i + 1;
      i++;
    } else if (c === '}') {
      const block = stack.pop();
      if (block) {
        out.push({
          prelude: block.prelude,
          body: src.slice(block.bodyStart, i),
          bodyStart: block.bodyStart,
          startLine: block.startLine,
          parents: stack.map((f) => f.prelude),
        });
      }
      preludeStart = i + 1;
      i++;
    } else {
      if (c === ';' && stack.length === 0) preludeStart = i + 1;
      i++;
    }
  }
  return out;
}

/** Split a selector list into normalized single selectors. */
function selectorList(prelude) {
  return prelude
    .split(',')
    .map((s) => s.replace(/\s+/g, ' ').trim())
    .filter(Boolean);
}

/** Every animation declared directly in this block's body. */
function animationsIn(body) {
  const found = [];
  ANIMATION_DECL.lastIndex = 0;
  let m;
  while ((m = ANIMATION_DECL.exec(body)) !== null) {
    found.push({
      value: m[2].replace(/\s+/g, ' ').trim(),
      line: body.slice(0, m.index).split('\n').length,
    });
  }
  return found;
}

/**
 * Report infinite animations whose selector is never neutralized under a
 * reduced-motion query.
 *
 * @returns {Array<{selector: string, value: string, line: number}>}
 */
export function checkStylesheet(name, css) {
  const src = stripCssComments(css);
  const all = blocks(src);
  const reduced = (block) => block.parents.some((p) => REDUCED.test(p));

  const neutralized = new Set();
  for (const block of all) {
    if (!reduced(block)) continue;
    const animations = animationsIn(block.body);
    // Only a block that turns the animation *off* counts as the guard; a
    // reduced-motion block that narrows a duration is still leaving it running.
    if (!animations.some((a) => /^\s*none\s*$/.test(a.value))) continue;
    for (const selector of selectorList(block.prelude)) neutralized.add(selector);
  }

  const findings = [];
  for (const block of all) {
    if (reduced(block)) continue;
    if (block.prelude.startsWith('@')) continue;
    const animations = animationsIn(block.body);
    for (const animation of animations) {
      if (!INFINITE.test(animation.value)) continue;
      for (const selector of selectorList(block.prelude)) {
        if (neutralized.has(selector)) continue;
        findings.push({
          selector,
          value: animation.value,
          file: name,
          // The declaration's own line, not the line the selector sits on.
          // Batch 889 already paid for reporting a finding 54 lines away from
          // the code it was about, and a multi-line rule makes "where the
          // selector is" a genuinely unhelpful answer.
          line: animation.line + block.startLine - 1,
        });
      }
    }
  }
  return findings;
}

/** Every .css file under `dir`, recursively, sorted for stable output. */
export function cssFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir).sort()) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...cssFiles(full));
    else if (entry.endsWith('.css')) out.push(full);
  }
  return out;
}

function main() {
  const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
  const srcDir = path.join(root, 'src');
  const files = cssFiles(srcDir);

  const findings = files.flatMap((file) => {
    const relative = path.relative(srcDir, file);
    return checkStylesheet(relative, readFileSync(file, 'utf8'));
  });

  console.log(`CSS files scanned: ${files.length}.`);
  if (findings.length === 0) {
    console.log('Every animation that runs forever is neutralized under prefers-reduced-motion.');
    process.exit(0);
  }

  console.error(`\n${findings.length} animation(s) that never stop and cannot be turned off:\n`);
  for (const f of findings) {
    console.error(`  ${f.file}:${f.line}  ${f.selector}  ${f.value}`);
  }
  console.error('\nAdd the guard the rest of this repository already uses:');
  console.error("  @media (prefers-reduced-motion: reduce) {\n    <selector> { animation: none; }\n  }");
  console.error('A one-shot animation is deliberately not reported — this gate is about motion');
  console.error('that never ends on its own.');
  process.exit(1);
}

if (isMainModule(import.meta.url)) main();
