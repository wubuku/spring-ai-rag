#!/usr/bin/env node
// Census of the repository's gates: is every one of them registered, proven to
// be able to reject, actually executed by something, and reached by CI?
//
// The premise is not hypothetical. `verify-project-docs.sh` already has a check
// named "Gates can fail closed" whose comment records three gates that could not
// fail; Batch 809 found a fourth (`check-entity-migration-sync.sh`, deleted that
// batch) and found it slipped through because that check only inspects scripts
// scanning with `rg`/`jq`/`yq`. Between Batch 768 and Batch 809, not one
// `scripts/verify-*.sh` ran in CI, and 208 WebUI gate self-tests ran nowhere.
// A spot check cannot hold that line, so this gate counts instead.
//
// The data it checks lives in scripts/gate-registry.mjs, whose header explains
// each rule and states the two limitations honestly — chiefly that "executed by"
// is read off runner text, so it catches "wired to nothing" but not "wired to a
// lie". Self-test: scripts/test-support/gate-wiring-self-test.mjs.

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import { deadReasonPointers } from './lib/reason-pointer-check.mjs';
import { GATES } from './gate-registry.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

/** Entry points CI executes. `package.json` is a root because ci.yml's webui
 *  job runs `npm run lint` / `npm run test:run`, which resolve to it. */
export const CI_ROOTS = ['.github/workflows/ci.yml', 'spring-ai-rag-webui/package.json'];

/** Scripts that exist to execute other gates. */
export const RUNNERS = [
  'scripts/verify-project-docs.sh',
  'scripts/verify-project-tests.sh',
  'scripts/verify-gated-it.sh',
];

export const VIOLATION_KINDS = {
  UNREGISTERED_GATE: 'unregistered-gate',
  DUPLICATE_REGISTRATION: 'duplicate-registration',
  UNKNOWN_GATE_KIND: 'unknown-gate-kind',
  MISSING_SELF_TEST: 'missing-self-test',
  SELF_TEST_NOT_FOUND: 'self-test-not-found',
  ORPHAN_GATE: 'orphan-gate',
  MISSING_CI_REASON: 'missing-ci-reason',
  STALE_CI_REASON: 'stale-ci-reason',
  DEAD_REASON_POINTER: 'dead-reason-pointer',
  UNDOCUMENTED_GATE: 'undocumented-gate',
  NON_EXECUTABLE_ENTRYPOINT: 'non-executable-entrypoint',
};

/**
 * Documentation is searched for a gate's basename. A gate nobody can find is a
 * gate nobody runs, and Batch 809 measured the cost of that: 13 of the 21
 * gates and entry points — including seven of the nine WebUI checks — appeared
 * in no document at all, so `check:mutation-errors` and `check:query-errors`
 * were undiscoverable to anyone who did not already know they existed.
 */
export function collectDocText(root) {
  const docsDir = join(root, 'docs');
  const files = readdirSync(docsDir)
    .filter((name) => name.endsWith('.md'))
    .map((name) => join(docsDir, name));
  const readme = join(root, 'README.md');
  if (existsSync(readme)) files.push(readme);
  return files.map((path) => readFileSync(path, 'utf8')).join('\n');
}

const GATE_KINDS = new Set(['gate', 'entrypoint', 'manual']);

// A line that invokes a program. The left boundary matters more than it looks:
// without it `\bsh\s` matches the `.sh ` at the end of a filename, which turns
// every allowlist line into an execution. Batch 809 shipped exactly that bug and
// the self-test pins the fix.
const EXEC_WORD = /(?:^|[\s"'=/(])(?:node|bash|sh|npx|npm)\s/;
// `./scripts/foo.sh` — a path invoked directly, with no command word at all.
// A YAML `run:` step may carry the command on the key's own line, so that prefix
// is stripped before the test rather than only matching the pretty-printed
// multi-line form ci.yml happens to use today.
const DIRECT_PATH = /^\.\.?\//;
const YAML_RUN_PREFIX = /^(?:-\s*)?run:\s*/;

function isComment(line) {
  const trimmed = line.trim();
  return trimmed.startsWith('#') || trimmed.startsWith('//') || trimmed.startsWith('*');
}

export function isExecutionLine(line) {
  if (isComment(line)) return false;
  const trimmed = line.trim().replace(YAML_RUN_PREFIX, '');
  return EXEC_WORD.test(trimmed) || DIRECT_PATH.test(trimmed);
}

/**
 * Basenames of the scripts a runner actually invokes.
 *
 * Two shapes exist in this repository and both are handled:
 *   node scripts/verify-test-visibility.mjs      — command word on the line
 *   ./scripts/verify-gated-it.sh                 — direct path invocation
 *   for gate in a.sh b.sh; do ... bash "$gate"    — a list line with no command
 *                                                   word of its own, resolved
 *                                                   through the loop variable
 */
export function collectExecutedScripts(text) {
  const names = new Set();

  for (const line of text.split('\n')) {
    if (!isExecutionLine(line)) continue;
    for (const match of line.matchAll(/[\w.-]+\.(?:sh|mjs)\b/g)) names.add(match[0]);
  }

  for (const match of text.matchAll(/for\s+(\w+)\s+in\s+([^;]+);/g)) {
    const [, variable, list] = match;
    const invoked = new RegExp(
      `(?:^|[\\s"'=/(])(?:node|bash|sh)\\s+"?\\$\\{?${variable}\\}?`,
    ).test(text);
    if (!invoked) continue;
    for (const name of list.split(/\s+/)) {
      // The list carries repo-relative paths (`scripts/foo.sh`), not basenames.
      const base = name.split('/').pop();
      if (base && /^[\w.-]+\.(?:sh|mjs)$/.test(base)) names.add(base);
    }
  }

  return names;
}

/** Every gate script on disk, as repo-relative paths. */
export function collectGateScripts(root) {
  const found = [];
  const repoScripts = readdirSync(join(root, 'scripts'));
  for (const name of repoScripts) {
    if (/^verify-.*\.(sh|mjs)$/.test(name)) found.push(`scripts/${name}`);
  }
  const webuiScripts = join(root, 'spring-ai-rag-webui', 'scripts');
  for (const name of readdirSync(webuiScripts)) {
    if (/^check-.*\.mjs$/.test(name)) found.push(`spring-ai-rag-webui/scripts/${name}`);
  }
  return found.sort();
}

/**
 * Gates CI reaches, following one level of indirection: ci.yml runs
 * `verify-gated-it.sh`, so anything that script runs counts as reached too.
 */
export function resolveCiReachability(gateScripts, runnerTexts) {
  const pathByBasename = new Map();
  for (const path of [...gateScripts, ...RUNNERS]) {
    pathByBasename.set(path.split('/').pop(), path);
  }

  const reached = new Set();
  const queue = [...CI_ROOTS];
  const visited = new Set(queue);

  while (queue.length > 0) {
    const current = queue.shift();
    const text = runnerTexts[current];
    if (text === undefined) continue;
    for (const name of collectExecutedScripts(text)) {
      const path = pathByBasename.get(name);
      if (path && gateScripts.includes(path)) reached.add(path);
      if (path && !visited.has(path)) {
        visited.add(path);
        queue.push(path);
      }
    }
  }

  return reached;
}

/**
 * The pure half. Everything it needs arrives as data so the self-test can drive
 * it with fixtures instead of a real repository.
 */
export function checkWiring({ gateScripts, registry, fileExists, isExecutable, executedBy, ciReached, docText = '' }) {
  const violations = [];
  const add = (kind, gate, detail) => violations.push({ kind, gate, detail });

  const registered = new Map();
  for (const entry of registry) {
    if (registered.has(entry.gate)) {
      add(VIOLATION_KINDS.DUPLICATE_REGISTRATION, entry.gate, 'registered more than once');
      continue;
    }
    registered.set(entry.gate, entry);
  }

  for (const gate of gateScripts) {
    const entry = registered.get(gate);
    if (!entry) {
      add(
        VIOLATION_KINDS.UNREGISTERED_GATE,
        gate,
        'on disk but absent from scripts/gate-registry.mjs; classify it as a gate or a manual script',
      );
      continue;
    }
    if (!GATE_KINDS.has(entry.kind)) {
      add(VIOLATION_KINDS.UNKNOWN_GATE_KIND, gate, `kind "${entry.kind}" is not gate|entrypoint|manual`);
      continue;
    }
    // `manual` scripts are run by a person against a live system; they owe the
    // registry nothing beyond existing. `entrypoint` scripts are run by a person
    // by name — a runner cannot execute them, so the orphan rule would be a
    // category error — but they still owe a self-test and a CI decision.
    if (entry.kind === 'manual') continue;

    // Batch 913. An entry point is invoked by path, by a person, from a
    // terminal: `./scripts/verify-webui-e2e-mock.sh`. A file without the
    // executable bit answers that with `Permission denied` and exit 126 —
    // which is the most confusing possible answer to "run the gate", and it
    // stays invisible precisely because the gate is a *manual* one that CI
    // does not reach. That is how one of the four entry points in this
    // repository shipped as 100644 while its three siblings shipped as
    // 100755: everything this file checks about it was correct.
    //
    // `kind: 'gate'` is deliberately exempt. Those are run as
    // `node scripts/verify-*.mjs`, where the kernel's exec bit is never
    // consulted, and all 29 of them are 100644 on purpose. So is `manual`:
    // those are run through `bash <path>` against a live system. The
    // distinction the rule draws is not "has a shebang" — two sourced
    // libraries in `scripts/lib/` have shebangs and are 100644 correctly —
    // it is "is this the thing a person types".
    if (entry.kind === 'entrypoint' && !isExecutable(gate)) {
      add(
        VIOLATION_KINDS.NON_EXECUTABLE_ENTRYPOINT,
        gate,
        'an entry point is run as ./' + gate + ', which needs the executable bit; '
          + 'it is committed without one, so the documented invocation fails with exit 126',
      );
    }

    // Manual verification scripts are exempt: they are domain procedures rather
    // than part of the automated safety net, and each is documented in whichever
    // section covers its subsystem. A gate or entry point, by contrast, is
    // something a contributor has to be able to find.
    if (!docText.includes(gate.split('/').pop())) {
      add(
        VIOLATION_KINDS.UNDOCUMENTED_GATE,
        gate,
        'no document mentions it, so a contributor cannot discover it exists',
      );
    }

    if (!entry.selfTest && !entry.noSelfTestReason) {
      add(
        VIOLATION_KINDS.MISSING_SELF_TEST,
        gate,
        'no selfTest and no noSelfTestReason; a gate nobody proved can reject is the defect this gate exists for',
      );
    }
    if (entry.selfTest && !fileExists(entry.selfTest)) {
      add(VIOLATION_KINDS.SELF_TEST_NOT_FOUND, gate, `selfTest does not exist: ${entry.selfTest}`);
    }
    if (entry.kind === 'gate' && !executedBy.get(gate)?.length) {
      add(
        VIOLATION_KINDS.ORPHAN_GATE,
        gate,
        'no runner executes it; it can only ever run when a human remembers',
      );
    }
    if (!ciReached.has(gate) && !entry.noCiReason) {
      add(
        VIOLATION_KINDS.MISSING_CI_REASON,
        gate,
        'CI does not reach it, and no noCiReason says whether that is a decision or an oversight',
      );
    }
    if (ciReached.has(gate) && entry.noCiReason) {
      add(
        VIOLATION_KINDS.STALE_CI_REASON,
        gate,
        'CI reaches it now, so its noCiReason is obsolete; delete that line from the registry',
      );
    }
    // Batch 900. A reason that hands a person a path nobody can open is the one
    // failure this file cannot otherwise see: the gate still passes, the reason
    // still prints, and the hole it describes stays exactly as blocked as it
    // was before anybody read it.
    for (const dead of deadReasonPointers(entry.noCiReason, fileExists)) {
      add(
        VIOLATION_KINDS.DEAD_REASON_POINTER,
        gate,
        `its noCiReason sends the reader to ${dead}, which does not exist. `
          + 'Commit the artifact, or write a path that resolves from the repository root',
      );
    }
  }

  for (const entry of registry) {
    if (registered.get(entry.gate) !== entry) continue;
    if (!gateScripts.includes(entry.gate)) {
      add(
        VIOLATION_KINDS.UNREGISTERED_GATE,
        entry.gate,
        'registered but not on disk; delete the entry',
      );
    }
  }

  return violations;
}

function readTexts(root, paths) {
  const texts = {};
  for (const path of paths) {
    const full = join(root, path);
    if (existsSync(full)) texts[path] = readFileSync(full, 'utf8');
  }
  return texts;
}

function main() {
  const root = process.argv[2] ? join(process.cwd(), process.argv[2]) : repoRoot;
  const gateScripts = collectGateScripts(root);
  const runnerTexts = readTexts(root, [...CI_ROOTS, ...RUNNERS]);

  const executedBy = new Map();
  for (const gate of gateScripts) {
    const basename = gate.split('/').pop();
    const runners = [...CI_ROOTS, ...RUNNERS].filter((path) =>
      runnerTexts[path] ? collectExecutedScripts(runnerTexts[path]).has(basename) : false,
    );
    executedBy.set(gate, runners);
  }

  const ciReached = resolveCiReachability(gateScripts, runnerTexts);
  const violations = checkWiring({
    gateScripts,
    registry: GATES,
    // `join` drops the leading slash, so an absolute token would be resolved
    // against the root and reported dead even when it is right there. Reasons
    // are written repo-relative, but the resolver should not punish the other
    // spelling with a false report.
    fileExists: (path) => existsSync(path.startsWith('/') ? path : join(root, path)),
    // The kernel's view, not git's index: a mode that was never staged shows up
    // here and not in `git ls-files -s`, and the failure a person hits is the
    // working tree's. Any execute bit counts, so a mode of 744 is as runnable
    // as 755 and is not this gate's business.
    isExecutable: (path) => {
      try {
        return (statSync(path.startsWith('/') ? path : join(root, path)).mode & 0o111) !== 0;
      } catch {
        return false;
      }
    },
    executedBy,
    ciReached,
    docText: collectDocText(root),
  });

  const automated = GATES.filter((entry) => entry.kind === 'gate');
  const entrypoints = GATES.filter((entry) => entry.kind === 'entrypoint');
  const withSelfTest = [...automated, ...entrypoints].filter((entry) => entry.selfTest).length;
  const owedSelfTest = automated.length + entrypoints.length;
  // Manual scripts are not gaps. They need a running server and real
  // credentials, so "CI does not run this" is their nature rather than a hole —
  // listing them would bury the eleven that are real.
  const manual = new Set(GATES.filter((entry) => entry.kind === 'manual').map((entry) => entry.gate));
  const notInCi = gateScripts.filter((gate) => !ciReached.has(gate) && !manual.has(gate));

  if (violations.length > 0) {
    console.error('Gate wiring violations:');
    for (const violation of violations) {
      console.error(`- [${violation.kind}] ${violation.gate}: ${violation.detail}`);
    }
    process.exitCode = 1;
    return;
  }

  console.log(
    `Gate wiring passed; ${gateScripts.length} gate script(s) registered ` +
      `(${automated.length} automated, ${entrypoints.length} entrypoint, ` +
      `${gateScripts.length - automated.length - entrypoints.length} manual), ` +
      `${withSelfTest}/${owedSelfTest} carry a self-test, ` +
      `${ciReached.size} reached by CI.`,
  );

  if (notInCi.length > 0) {
    console.log(`Standing CI gaps (${notInCi.length}), each with a written reason:`);
    for (const gate of notInCi) {
      const entry = GATES.find((candidate) => candidate.gate === gate);
      const via = executedBy.get(gate) ?? [];
      console.log(`- ${relative(root, join(root, gate))} (via ${via.join(', ') || 'nothing'})`);
      console.log(`    ${entry?.noCiReason ?? 'UNREASONED'}`);
    }
  }
}

if (isMainModule(import.meta.url)) main();
