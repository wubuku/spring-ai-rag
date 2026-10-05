#!/usr/bin/env node
// Self-test for scripts/verify-port-probe-authority.mjs.
//
// Batch 926. The positive case is the **pre-fix text** of one of the seven
// scripts, because the defect it describes is not visible by reading: the probe
// looks correct, it just answers a different question than the server does, and
// only a measurement shows the two disagreeing.
//
// The negative cases matter as much, because the tempting version of this rule —
// "no `node` in verification scripts" — would flag seven scripts that use node
// for something else entirely. The rule looks for a socket being bound.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  checkBindBasedPortProbe,
  collectBindBasedPortProbes,
  PORT_PROBE_SOURCE,
} from '../verify-port-probe-authority.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let failures = 0;

function expectFlagged(label, source) {
  const found = checkBindBasedPortProbe(source);
  if (found === null) {
    console.error(`not ok - ${label}: expected a violation, got none`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

function expectAccepted(label, source) {
  const found = checkBindBasedPortProbe(source);
  if (found !== null) {
    console.error(`not ok - ${label}: unexpected violation: ${found.detail}`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

// ── the pre-fix script, verbatim in the part that matters ────────────────────

expectFlagged('the pre-fix release probe, verbatim', `
find_available_port() {
  node - "$1" <<'NODE'
const net = require('node:net');
const preferred = Number(process.argv[2]);

function probe(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(null));
    server.listen({ host: '127.0.0.1', port, exclusive: true }, () => {
      server.close(() => resolve(port));
    });
  });
}
NODE
}
`);

expectFlagged('a bind probe on any port shape', `
  const server = net.createServer();
  server.listen(0, () => { console.log(server.address().port); });
`);

// ── shapes that must not be reported ─────────────────────────────────────────

expectAccepted('the corrected script, which sources the library', `
source scripts/lib/port-probe.sh
find_available_port() {
  local candidate="$1"
  while lsof -nP -iTCP:"$candidate" -sTCP:LISTEN >/dev/null 2>&1; do
    candidate=$((candidate + 1))
  done
  printf '%s' "$candidate"
}
`);

// node is used for a great deal in this repository that has nothing to do with
// sockets. A rule that flagged the word would take all of these with it.
expectAccepted('node used to parse JSON from an API response', `
  local models
  models="$(node -e "process.stdout.write(JSON.stringify(JSON.parse(process.argv[1])))" "$body")"
`);

expectAccepted('node used to write an evidence file', `
  node - "$1" <<'NODE'
const fs = require('node:fs');
fs.writeFileSync(process.argv[2], JSON.stringify({ result: 'PASS' }, null, 2));
NODE
`);

expectAccepted("this gate's own header quotes the bind probe", `
  #     $ node -e "net.createServer().listen({host:'127.0.0.1',port:4173})"
  find_available_port() { lsof -nP -iTCP:"$1" -sTCP:LISTEN; }
`);

expectAccepted('a script that starts a server without asking first', `
  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview --host 127.0.0.1 --port "$PLAYWRIGHT_PORT" --strictPort
  ) &
`);

// ── the real repository ──────────────────────────────────────────────────────

const violations = collectBindBasedPortProbes(join(root, 'scripts'), root);
if (violations.length > 0) {
  console.error('not ok - the real scripts directory is clean:');
  for (const v of violations) console.error(`    ${v.gate}: ${v.detail}`);
  failures += 1;
} else {
  console.log('ok   - the real scripts directory reports nothing');
}

// The message names a file and a function. Both have to be there, or the rule
// sends a reader somewhere that cannot help them.
const library = readFileSync(join(root, PORT_PROBE_SOURCE), 'utf8');
if (/^find_available_port\(\) \{/m.test(library) && library.includes('lsof -nP -iTCP:')) {
  console.log(`ok   - ${PORT_PROBE_SOURCE} defines find_available_port with lsof`);
} else {
  console.error(`not ok - ${PORT_PROBE_SOURCE} does not define it as claimed`);
  failures += 1;
}

if (failures > 0) {
  console.error(`\n${failures} case(s) failed.`);
  process.exit(1);
}
console.log('\nAll port-probe-authority self-test cases passed.');
