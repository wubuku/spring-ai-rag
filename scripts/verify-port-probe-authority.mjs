#!/usr/bin/env node
// Does a script decide port availability by asking a server the question it is about?
//
// Batch 926. Seven scripts here decided a port was free by *binding* a throwaway
// `node:net` server to `127.0.0.1` and reading the error. That is not the
// question `vite preview` asks, and on this machine the two answers disagreed:
//
//     $ lsof -nP -iTCP:4173 -sTCP:LISTEN
//     python3.1  37159  4u  IPv6  ...  TCP *:4173 (LISTEN)
//     $ node -e "net.createServer().listen({host:'127.0.0.1',port:4173})"
//     bind 成功 → 4173 其实是空的！
//
// The listener is an IPv6 wildcard socket; a Node bind to the IPv4 loopback
// succeeds anyway. So the probe returned 4173, `verify-release.sh` handed that to
// `vite preview --port 4173 --strictPort`, and the step died with "Port 4173 is
// already in use" — reporting a conflict on the very port it had just certified
// free, in a script that has never completed in this work tree. Six sibling
// scripts carried the same probe and the same latent hole.
//
// ## What counts
//
// A script that decides port availability by binding a socket, and does not
// source `scripts/lib/port-probe.sh`. The library asks with
// `lsof -nP -iTCP:<port> -sTCP:LISTEN`, which is the authoritative question and
// is the shape seven other scripts in this repository already used.
//
// ## What deliberately does not
//
//   - Scripts that start a server and do not first ask whether the port is free.
//     Starting a server is not a port probe, and `verify-gate-entry-points.mjs`
//     already covers the other half of that.
//   - `node -e` used for anything else. The rule looks for a socket being bound
//     and listened on, not for the word `node`.
//
// The library also replaced a behaviour worth naming: the old probe's fallback
// was `probe(0)`, an ephemeral port the kernel picks — unpredictable to a reader
// and unreproducible by a retry. Counting up from the preferred port is what the
// seven correct scripts already did.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

import { stripShellComments, shellFiles } from './lib/json-assertion-check.mjs';

export const VIOLATION_KIND = 'bind-based-port-probe';

/** The library that holds the authoritative check. */
export const PORT_PROBE_SOURCE = 'scripts/lib/port-probe.sh';

/** A socket being created, bound and listened on: the shape of a port probe. */
const BIND_PROBE = /createServer[\s\S]{0,400}?\.listen\(/;

/**
 * @typedef {object} Violation
 * @property {string} gate   the script's path relative to the repository root
 * @property {string} detail what it probes and why the answer can be wrong
 */

/**
 * The pure half: everything it needs arrives as text, so the self-test can
 * drive it with fixtures instead of a real repository.
 *
 * @param {string} source the script text
 * @returns {Violation|null}
 */
export function checkBindBasedPortProbe(source) {
  const code = stripShellComments(source);
  if (!BIND_PROBE.test(code)) return null;
  if (code.includes(PORT_PROBE_SOURCE)) return null;
  return {
    gate: '',
    detail:
      'it decides port availability by binding a throwaway socket, which answers a '
      + 'different question than the server it is probing for: a Node bind to '
      + '`127.0.0.1` succeeds against an IPv6 wildcard listener that `vite preview` '
      + 'then refuses to bind. Source ' + PORT_PROBE_SOURCE + ' and call '
      + 'find_available_port instead.',
  };
}

/**
 * @param {string} scriptsDir
 * @param {string} [repoRoot]
 * @returns {Violation[]}
 */
export function collectBindBasedPortProbes(scriptsDir, repoRoot) {
  const out = [];
  for (const file of shellFiles(scriptsDir)) {
    const found = checkBindBasedPortProbe(readFileSync(file, 'utf8'));
    if (found) out.push({ ...found, gate: relative(repoRoot ?? scriptsDir, file) });
  }
  return out;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const violations = collectBindBasedPortProbes(join(root, 'scripts'), root);
  if (violations.length > 0) {
    console.error(`Bind-based port probe (${VIOLATION_KIND}):`);
    for (const v of violations) {
      console.error(`  ${v.gate}: ${v.detail}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(
    'Every port probe asks with lsof, via ' + PORT_PROBE_SOURCE
    + '; none of them binds a socket to find out.',
  );
}

if (process.argv[1] && process.argv[1].endsWith('verify-port-probe-authority.mjs')) {
  main();
}
