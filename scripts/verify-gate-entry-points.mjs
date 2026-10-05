// Do these gates actually run when someone runs them?
//
// Why this file exists
// --------------------
// The gate registry already requires an automated gate to carry a self-test —
// a proof that it can reject. That proof is produced by importing the module
// and calling its functions. Which means the registry's own guarantee is blind
// to the one thing that stops a gate from being a gate at all: if the program
// never runs, every function in it still passes every test.
//
// Measured on this repository, before this gate existed: twenty-two gate
// scripts each carried their own "am I the entry point?" test, in four
// spellings, and eighteen of them compared `import.meta.url` against a form of
// `process.argv[1]` that the ESM loader and the caller spell differently. Run
// through a path that crosses a symlink, those eighteen printed nothing and
// exited 0. A gate that reports success having checked nothing is worse than a
// gate that fails, because nothing downstream can tell the difference.
//
// What is checked here
// --------------------
//   1. No gate spells out its own entry test. The single spelling lives in
//      `lib/is-main-module.mjs`; a gate that rolls its own is reported, which
//      is what keeps the four-idiom drift from coming back.
//   2. The two copies of that helper — the repository's and the webui
//      package's, which cannot import each other — are byte-identical. A mirror
//      that is allowed to drift is a second implementation waiting to happen.
//
// What is deliberately not here
// -----------------------------
// This gate does not run every gate. Rule 1 is a spelling check, and a spelling
// check is a proxy; the proof is behavioural and lives in the self-test, which
// creates a symlink and runs a real gate through it. The honest limit: a gate
// that never had a guard and never calls `main()` passes rule 1 while still
// doing nothing when executed. That is not hypothetical —
// `scripts/verify-json-assertions.mjs` is one — and it is a different question,
// answered by running the thing.
//
// A standing limit on the blast radius, stated because the finding is easy to
// overstate. Node resolves `process.cwd()` to its physical path, so invoking a
// gate by a relative path from a symlinked directory was always safe, and the
// aggregate chain, which invokes every gate relatively, was never affected. The
// exposure was naming a script through a symlinked path.

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { isMainModule } from './lib/is-main-module.mjs';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

export const VIOLATION_KINDS = {
  HAND_ROLLED_ENTRY_GUARD: 'hand-rolled-entry-guard',
  DRIFTED_HELPER_COPY: 'drifted-helper-copy',
};

export const HELPER_COPIES = [
  'scripts/lib/is-main-module.mjs',
  'spring-ai-rag-webui/scripts/lib/is-main-module.mjs',
];

/**
 * Replace the contents of string literals with spaces, keeping length and line
 * structure. Needed because this gate reads source text, and its own source
 * mentions `import.meta.url` inside a string in order to look for it — the
 * first version of this file flagged itself, which is a detector that cannot be
 * trusted to be pointed at a tree containing itself.
 */
export function stripStringLiterals(line) {
  return line
    .replace(/'(?:[^'\\]|\\.)*'/g, (m) => ' '.repeat(m.length))
    .replace(/"(?:[^"\\]|\\.)*"/g, (m) => ' '.repeat(m.length))
    .replace(/`(?:[^`\\]|\\.)*`/g, (m) => ' '.repeat(m.length));
}

/**
 * A line that opens an entry test without going through the helper. Matching
 * on shape rather than on the old spellings means a fourth idiom is caught
 * too: what makes a line suspect is deciding "is this the program" by hand.
 */
export function isHandRolledGuard(line) {
  const code = stripStringLiterals(line);
  return /^if\s*\(/.test(code)
    && code.includes('import.meta.url')
    && !code.includes('isMainModule');
}

export function checkEntryPoints(root, gateScripts) {
  const violations = [];
  const add = (kind, gate, detail) => violations.push({ kind, gate, detail });

  for (const gate of gateScripts) {
    const full = join(root, gate);
    if (!existsSync(full)) continue;
    const text = readFileSync(full, 'utf8');
    const lines = text.split('\n');

    const handRolled = lines
      .map((line, index) => ({ line: line.trim(), number: index + 1 }))
      .filter(({ line }) => isHandRolledGuard(line));
    for (const { line, number } of handRolled) {
      add(
        VIOLATION_KINDS.HAND_ROLLED_ENTRY_GUARD,
        gate,
        `line ${number} decides "is this the entry point" by hand: ${line}. `
          + 'Invoke the symlink-robust test from lib/is-main-module.mjs instead',
      );
    }
  }

  // The two copies have to be the same file. A mirror allowed to drift is a
  // second implementation, and this repository already has a record of what
  // duplication does: it is how four entry-guard idioms appeared.
  const [first, ...rest] = HELPER_COPIES.filter((path) => existsSync(join(root, path)));
  if (first && rest.length) {
    const original = readFileSync(join(root, first), 'utf8');
    for (const path of rest) {
      if (readFileSync(join(root, path), 'utf8') !== original) {
        add(
          VIOLATION_KINDS.DRIFTED_HELPER_COPY,
          path,
          `differs from ${first}; the webui package cannot import the repository's copy, `
            + 'so the only thing keeping them equal is that they are equal',
        );
      }
    }
  }

  return violations;
}

function collectGates(root) {
  // Every `.mjs` sitting directly in a gate directory, in both modules. Not
  // `verify-` specifically: the webui package names its checks `check-*` and
  // its token builder `build-*`, and all of them are registered gates. The
  // `lib` and `test-support` directories are excluded because a helper and a
  // self-test are not programs anybody runs as a gate.
  const dirs = ['scripts', 'spring-ai-rag-webui/scripts'];
  const out = [];
  for (const dir of dirs) {
    const full = join(root, dir);
    if (!existsSync(full)) continue;
    for (const name of readdirSync(full)) {
      if (name.endsWith('.mjs')) out.push(`${dir}/${name}`);
    }
  }
  return out;
}

function main() {
  const root = process.argv[2] ? join(process.cwd(), process.argv[2]) : repoRoot;
  const gates = collectGates(root);
  const violations = checkEntryPoints(root, gates);

  if (violations.length > 0) {
    console.error('Gate entry-point violations:');
    for (const violation of violations) {
      console.error(`- [${violation.kind}] ${violation.gate}: ${violation.detail}`);
    }
    process.exitCode = 1;
    return;
  }

  console.log(
    `Gate entry points passed; ${gates.length} gate script(s) read, none decides its own `
      + 'entry test, and the helper copies are identical.',
  );
}

if (isMainModule(import.meta.url)) main();
