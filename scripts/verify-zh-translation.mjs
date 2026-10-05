#!/usr/bin/env node
/**
 * Detects untranslated English prose inside Chinese (`-zh-CN.md`) documents.
 *
 * The bilingual gate compares heading *levels* between the English and Chinese
 * side of a pair. It is structurally blind to the body: a Chinese document can
 * carry a hundred English paragraphs and still pass. Batch 783 found exactly
 * that — 53 prose lines, 60 table rows, 11 headings and 34 bold labels had been
 * copied verbatim out of the English original — and fixed it by hand, leaving
 * nothing behind to stop it happening again.
 *
 * This is that missing check. Run:
 *   node scripts/verify-zh-translation.mjs            # every Chinese document
 *   node scripts/verify-zh-translation.mjs <file>...  # only the named files
 *
 * What counts as untranslated prose:
 *   - outside fenced code blocks;
 *   - no CJK character anywhere on the line;
 *   - at least `MIN_WORDS` English word tokens, each at least `MIN_WORD_LEN`
 *     letters, so that `GET`, `application/json` or `COLLECTION_PURGE_FORBIDDEN`
 *     do not trip it.
 *
 * Lines that are purely structural (markdown links, table rules, anchors,
 * headings that are only an API path) are skipped: an identifier is allowed to
 * stay English, prose is not.
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));

// Calibrated against this repository, not guessed:
//   MIN_WORDS=1  -> 4 false positives on the current 35 Chinese documents
//                  (a version table, a `# spring-ai-rag` title, two `### GET ...`
//                  headings): every one of them a legitimate English identifier.
//   MIN_WORDS=2  -> 0 false positives, and still 41 hits on the pre-Batch-783
//                  `docs/rest-api-zh-CN.md`, which really did carry 53 lines of
//                  copied English prose.
// A threshold that cries wolf gets ignored, so 2 is the line: it is the lowest
// value with no false positive anywhere in the tree.
const MIN_WORDS = 2;
const MIN_WORD_LEN = 3;

const CJK = /[一-鿿]/;

/**
 * Longest run of consecutive ordinary English words on a line.
 *
 * This is the discriminator that matters. Untranslated prose arrives as a
 * phrase — "with current document index, total docs, phase" — while a Chinese
 * document is *supposed* to be full of English identifiers: class names, config
 * keys, directory names, error codes, product names. Counting every alphabetic
 * token flagged nine lines that were all identifier lists.
 *
 * A word only counts when it is entirely lowercase (`citationId`, `API`,
 * `Maven` and `Spring` do not), and it only extends a run when the words are
 * separated by ordinary spacing or sentence punctuation — never by a slash, a
 * pipe or a CJK enumeration comma, which is how identifier lists are written.
 *
 * @param {string} text
 * @returns {number} longest run length
 */
export function longestProseRun(text) {
  const tokens = text.split(/(\s+|[/、|,;()（）「」【】\-–—])/);
  let run = 0;
  let best = 0;
  for (const raw of tokens) {
    // Sentence punctuation rides along with the word (`statistics.`, `docs,`),
    // so trim it before deciding whether this token is an ordinary word.
    const token = raw.replace(/^[.,:;!?'"“”]+/, '').replace(/[.,:;!?'"“”]+$/, '');
    if (/^[a-z]+$/.test(token) && token.length >= MIN_WORD_LEN) {
      run += 1;
      if (run > best) best = run;
    } else if (raw.length === 0 || /^[\s,.。;:'"!?]*$/.test(raw)) {
      // whitespace and sentence punctuation keep a sentence together
    } else {
      run = 0;
    }
  }
  return best;
}

/** Strips fences, comments, inline code and URLs so only prose is judged. */
export function proseOf(line) {
  return line
    .replace(/`[^`]*`/g, ' ') // inline code, paths, config keys, identifiers
    .replace(/!?\[[^\]]*\]\([^)]*\)/g, ' ') // links and images
    .replace(/!?\[[^\]]*\]\[[^\]]*\]/g, ' ') // reference-style links, badges
    .replace(/https?:\/\/\S+/g, ' ')
    .replace(/<[^>]+>/g, ' ') // raw HTML / anchors
    .replace(/^\s{0,3}#{1,6}\s+/, ' ') // heading marker
    .replace(/^\s*[-*+]\s+/, ' ') // bullet marker
    .replace(/^\s*>\s?/, ' ') // quote marker
    .replace(/[*_]/g, ' ');
}

/**
 * @param {string} text whole document
 * @returns {{line: number, text: string}[]}
 */
export function findUntranslated(text) {
  const hits = [];
  const lines = text.split('\n');
  let inFence = false;
  // An inline code span may straddle a line break, so its state carries over;
  // otherwise a wrapped identifier path is judged as prose.
  let inSpan = false;
  lines.forEach((raw, index) => {
    if (/^\s*```/.test(raw)) {
      inFence = !inFence;
      return;
    }
    if (inFence) return;
    if (CJK.test(raw)) return;
    const line = raw.trim();
    if (!line || /^[-:|=\s]+$/.test(line)) return;

    const code = line.replace(/`/g, m => m);
    let prose = '';
    let span = inSpan;
    for (const ch of code) {
      if (ch === '`') {
        span = !span;
        prose += ' ';
        continue;
      }
      prose += span ? ' ' : ch;
    }
    inSpan = span;

    if (longestProseRun(proseOf(prose)) >= MIN_WORDS) {
      hits.push({ line: index + 1, text: raw });
    }
  });
  return hits;
}

function main() {
  const args = process.argv.slice(2);
  const files = args.length
    ? args
    : chineseDocuments(projectRoot).filter(f => existsSync(f));

  if (files.length === 0) {
    console.error('No Chinese documents to check.');
    process.exitCode = 1;
    return;
  }

  let total = 0;
  for (const file of files) {
    if (!existsSync(file)) {
      console.error(`No such file: ${file}`);
      process.exitCode = 1;
      return;
    }
    const hits = findUntranslated(readFileSync(file, 'utf8'));
    if (hits.length === 0) continue;
    total += hits.length;
    console.error(`${relative(projectRoot, file) || file}:`);
    for (const h of hits) {
      console.error(`  ${h.line}: ${h.text.trim()}`);
    }
  }

  if (total > 0) {
    console.error(
      `\n${total} line(s) of untranslated English in Chinese document(s).\n` +
        'A `-zh-CN.md` document must not ship English prose. Identifiers, API\n' +
        'paths, config keys and error codes may stay English; sentences may not.',
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    `Chinese translation check passed; ${files.length} document(s), no untranslated prose.`,
  );
}

export function chineseDocuments(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || entry.name === 'node_modules' || entry.name === 'target') {
      continue;
    }
    const path = join(dir, entry.name);
    if (entry.isDirectory()) chineseDocuments(path, acc);
    else if (entry.name.endsWith('-zh-CN.md')) acc.push(path);
  }
  return acc;
}

if (isMainModule(import.meta.url)) {
  main();
}
