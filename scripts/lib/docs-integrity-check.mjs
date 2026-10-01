#!/usr/bin/env node
// Integrity checks for the tracked documentation set.
//
// Two defects motivated this file, and both were the same shape — a check
// that looked comprehensive while silently covering far less than it claimed:
//
//   1. A single NUL byte was committed into docs/drafts/HARDENING_LOOP_PLAN.md
//      as part of an inline example (`safe\0/../etc`) documenting NUL-truncation
//      defence. Git immediately reclassified that 11,923-line ledger as a binary
//      blob, which silently disabled diff, blame, merge markers, and ripgrep for
//      the whole file. Nothing failed. The file only looked wrong when someone
//      happened to grep it.
//   2. The bilingual heading-structure check enumerated eight hard-coded pairs.
//      Every other EN/ZH pair in the repository — 27 of 35 at the time — was
//      never examined. Four of the unexamined pairs had in fact drifted: two
//      Chinese documents are missing whole sections, and one Chinese document
//      documents behaviour the English original never gained.
//
// So: discover, do not enumerate. Every bilingual pair found on disk is checked
// by default, and the only way to opt out is a named entry in KNOWN_DRIFT that
// carries a reason, must still be broken, and is capped by DRIFT_CEILING. Debt
// that cannot be registered silently, and debt that is allowed to grow is not
// debt that gets paid down.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// Extensions that legitimately hold binary payloads. Everything else is
// scanned. Deny-listing rather than allow-listing means a newly added text file
// is covered the moment it is committed, instead of waiting for someone to
// remember to extend a list.
const BINARY_EXTENSIONS = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.bmp',
  '.pdf', '.jar', '.zip', '.gz', '.tgz', '.class',
  '.woff', '.woff2', '.ttf', '.otf', '.eot'
]);

// Bilingual pairs whose heading structures genuinely differ today.
//
// Every entry must:
//   - state why the pair still drifts, in terms a reviewer can check;
//   - still drift, so that fixing a document forces its removal from this list
//     rather than leaving a stale exemption behind.
//
// DRIFT_CEILING below pins the count. Removing an entry is the only way to lower
// it, which is what keeps this list a to-do list instead of a bin.
export const KNOWN_DRIFT = [
  {
    pair: ['CHANGELOG.md', 'CHANGELOG-zh-CN.md'],
    reason: 'The Chinese changelog stops at 1.0.0-SNAPSHOT 2026-04-04 (39 headings) ' +
      'while the English one continues through 1.1.0-SNAPSHOT (67 headings). ' +
      'Backfilling release notes is a translation task, not a structural fix.'
  },
  {
    pair: ['docs/rest-api.md', 'docs/rest-api-zh-CN.md'],
    reason: 'The Chinese reference stops before the Cache, Metrics, Models, and ' +
      'Client-Error sections (135 headings against 151), so its heading sequence ' +
      'diverges from the point of the first omission onwards.'
  },
];

// May only decrease. Lowering it is part of fixing a listed pair.
//
// 4 -> 3 in Batch 769: docs/troubleshooting.md was missing only its
// "Duplicate RagProperties Bean" section in Chinese, which offset every heading
// after it. The Chinese section was translated and the pair now matches, so the
// exemption had to go.
//
// 3 -> 2 in the same batch: docs/claude-grok-proxy.md drifted in the opposite
// direction. The Chinese document had five `###` subsections under "Common CLI
// commands" and five under "Troubleshooting" that the English original had
// flattened into bare paragraphs — the same content, different shape. The
// English side was restructured to match; no content was invented or dropped.
export const DRIFT_CEILING = 2;

export function isBinaryPath(file) {
  return BINARY_EXTENSIONS.has(path.extname(file).toLowerCase());
}

/**
 * Heading levels in document order, ignoring anything inside a fenced block so
 * that a shell snippet containing `# comment` is not read as a heading.
 */
export function headingSignature(content) {
  const signature = [];
  let inFence = false;

  for (const line of content.split(/\r?\n/)) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) {
      continue;
    }
    const match = /^(#{1,6})\s+/.exec(line);
    if (match) {
      signature.push(match[1].length);
    }
  }

  return signature;
}

/** Every `<name>.md` / `<name>-zh-CN.md` pair present in `files`. */
export function discoverPairs(files) {
  const chinese = new Set(files.filter((file) => file.endsWith('-zh-CN.md')));
  const pairs = [];

  for (const file of files) {
    if (file.endsWith('-zh-CN.md')) {
      continue;
    }
    const candidate = file.replace(/\.md$/, '-zh-CN.md');
    if (chinese.has(candidate)) {
      pairs.push([file, candidate]);
    }
  }

  return pairs.sort(([a], [b]) => a.localeCompare(b));
}

export function listTrackedFiles(root) {
  return execFileSync('git', ['ls-files', '-co', '--exclude-standard'], {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024
  })
    .split('\n')
    .filter(Boolean);
}

/**
 * A NUL byte in a tracked text file is never intentional. It costs the file its
 * diff, its blame, and its searchability for as long as it is committed, and
 * it does so silently — which is why this reports the line as well as the path.
 */
export function findNulByteFiles(root, files) {
  const findings = [];

  for (const file of files) {
    if (isBinaryPath(file)) {
      continue;
    }
    const absolute = path.join(root, file);
    let buffer;
    try {
      buffer = fs.readFileSync(absolute);
    } catch {
      continue; // Symlink or file removed between listing and reading.
    }

    let offset = buffer.indexOf(0);
    if (offset < 0) {
      continue;
    }

    const offsets = [];
    while (offset >= 0 && offsets.length < 5) {
      offsets.push(offset);
      offset = buffer.indexOf(0, offset + 1);
    }

    findings.push({
      file,
      count: buffer.reduce((total, byte) => total + (byte === 0 ? 1 : 0), 0),
      lines: offsets.map((position) =>
        buffer.subarray(0, position).toString('utf8').split('\n').length
      )
    });
  }

  return findings;
}

/**
 * @returns {{ok: boolean, errors: string[], stats: object}}
 */
export function checkBilingualPairs({
  root,
  files = listTrackedFiles(root),
  knownDrift = KNOWN_DRIFT,
  driftCeiling = DRIFT_CEILING
}) {
  const errors = [];
  const markdown = files.filter((file) => file.endsWith('.md'));
  const pairs = discoverPairs(markdown);
  const driftByEnglish = new Map();

  for (const entry of knownDrift) {
    if (!Array.isArray(entry.pair) || entry.pair.length !== 2) {
      errors.push(`KNOWN_DRIFT entry is malformed: ${JSON.stringify(entry.pair)}`);
      continue;
    }
    const [english, chinese] = entry.pair;
    if (driftByEnglish.has(english)) {
      errors.push(`KNOWN_DRIFT registers ${english} more than once.`);
      continue;
    }
    if (typeof entry.reason !== 'string' || entry.reason.trim() === '') {
      errors.push(`${english} is registered as drifted without a reason.`);
      continue;
    }
    driftByEnglish.set(english, entry);
  }

  let checked = 0;
  let registered = 0;

  for (const [english, chinese] of pairs) {
    const entry = driftByEnglish.get(english);

    if (entry) {
      // A fixed document must leave the list, so an exemption cannot outlive
      // the reason that justified it.
      if (entry.pair[1] !== chinese) {
        errors.push(
          `${english} is registered as drifted against ${entry.pair[1]}, ` +
          `but the discovered pair is ${chinese}.`
        );
      }
      const englishSignature = headingSignature(fs.readFileSync(path.join(root, english), 'utf8'));
      const chineseSignature = headingSignature(fs.readFileSync(path.join(root, chinese), 'utf8'));
      if (JSON.stringify(englishSignature) === JSON.stringify(chineseSignature)) {
        errors.push(
          `${english} is registered as drifted, but its heading structure now matches ` +
          `${chinese}. Fix it, remove it from KNOWN_DRIFT, and lower DRIFT_CEILING.`
        );
      }
      registered += 1;
      continue;
    }

    const englishSignature = headingSignature(fs.readFileSync(path.join(root, english), 'utf8'));
    const chineseSignature = headingSignature(fs.readFileSync(path.join(root, chinese), 'utf8'));
    if (JSON.stringify(englishSignature) !== JSON.stringify(chineseSignature)) {
      errors.push(
        `Heading structure mismatch: ${english} <> ${chinese}\n` +
        `  EN: ${englishSignature.join(',')}\n` +
        `  ZH: ${chineseSignature.join(',')}\n` +
        `  Fix the document, or register the pair in KNOWN_DRIFT with a reason.`
      );
    }
    checked += 1;
  }

  for (const [english] of driftByEnglish) {
    if (!pairs.some(([candidate]) => candidate === english)) {
      errors.push(
        `KNOWN_DRIFT registers ${english}, but no such bilingual pair exists on disk. ` +
        `Stale exemptions are how a gate stops meaning anything.`
      );
    }
  }

  // Exact equality, not an upper bound. An upper bound leaves a loophole: fix a
  // document, delete its entry, and simply forget to lower the ceiling — the gate
  // stays green while the pinned number drifts further from reality. Pinning the
  // count exactly means the number is always the truth, and losing an entry
  // obliges you to record that you lost it.
  if (driftByEnglish.size !== driftCeiling) {
    errors.push(
      `KNOWN_DRIFT holds ${driftByEnglish.size} entries but DRIFT_CEILING is ${driftCeiling}. ` +
      `The ceiling is pinned to the exact count: fixing a pair obliges you to lower it.`
    );
  }

  return {
    ok: errors.length === 0,
    errors,
    stats: {
      pairs: pairs.length,
      enforced: checked,
      registeredDrift: driftByEnglish.size,
      driftCeiling
    }
  };
}

export function checkTrackedTextCleanliness({ root, files = listTrackedFiles(root) }) {
  const findings = findNulByteFiles(root, files);
  const errors = findings.map((finding) =>
    `NUL byte in tracked text file ${finding.file} ` +
    `(count=${finding.count}, first line=${finding.lines[0]}). ` +
    `Git treats the whole file as binary, which silently disables diff, blame, ` +
    `and text search. Write the escape as U+0000 instead.`
  );

  return {
    ok: errors.length === 0,
    errors,
    stats: {
      scanned: files.filter((file) => !isBinaryPath(file)).length,
      skipped: files.filter((file) => isBinaryPath(file)).length,
      findings: findings.length
    }
  };
}

function main() {
  const scope = process.argv[2] ?? 'all';
  const known = ['all', 'text', 'bilingual'];
  if (!known.includes(scope)) {
    console.error(`Unknown scope ${JSON.stringify(scope)}; expected one of ${known.join(', ')}.`);
    process.exit(2);
  }

  const root = process.cwd();
  const files = listTrackedFiles(root);
  const errors = [];
  const stats = {};

  if (scope === 'all' || scope === 'text') {
    const text = checkTrackedTextCleanliness({ root, files });
    errors.push(...text.errors);
    stats.text = text.stats;
  }

  if (scope === 'all' || scope === 'bilingual') {
    const bilingual = checkBilingualPairs({ root, files });
    errors.push(...bilingual.errors);
    stats.bilingual = bilingual.stats;
  }

  for (const error of errors) {
    console.error(error);
  }

  if (errors.length > 0) {
    process.exit(1);
  }

  const parts = Object.entries(stats).map(
    ([name, value]) =>
      `${name}=${
        name === 'text'
          ? `scanned:${value.scanned},binary_skipped:${value.skipped}`
          : `pairs:${value.pairs},enforced:${value.enforced},` +
            `registered_drift:${value.registeredDrift}/${value.driftCeiling}`
      }`
  );

  console.log(`DOCS_INTEGRITY_OK scope=${scope} ${parts.join(' ')}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}

export { BINARY_EXTENSIONS };
