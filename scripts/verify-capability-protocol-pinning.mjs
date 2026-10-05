#!/usr/bin/env node
// Does a script assert the integration capability protocol against a written-down version?
//
// Batch 924. The server publishes `/api/v1/rag/integration-capabilities` and
// defines that protocol's identity in exactly one place —
// `IntegrationCapabilityCatalog.CONTRACT_NAME` / `CONTRACT_VERSION`. Six shell
// scripts each asserted against their own copy of the pair, and the copies had
// already drifted in two directions at once:
//
//   * `business-client-contract-e2e.sh` required `protocolVersion == "1.0"` while
//     `business-client-binding-preflight.sh` — the producer of the very report it
//     inspects — refuses to write a successful report unless the value is the
//     contract version. Those cannot both hold, so the assertion was
//     unsatisfiable for *any* report: `null == "1.0"` is false for a failure
//     report, `"1.1" == "1.0"` is false for a success one.
//     `verify-business-client-readiness.sh` had therefore never completed in this
//     work tree. Its first run since (2026-10-06) stopped on precisely that
//     assertion with 16 of 17 steps green.
//   * `verify-document-sync-runs.sh` read the same endpoint and required `"1.0"`.
//     Its last run was 2026-08-20.
//
// A drifted copy is not a stale number. Correcting the two wrong values would
// leave five correct copies free to drift the same way, and the six scripts share
// no execution that would notice. So the literal lives in
// `scripts/lib/business-client-capability.sh` and this gate keeps it the only
// place it appears.
//
// ## What counts
//
// A comparison of a capability protocol version against a string that is *only*
// digits and dots. Two conditions, both required: the left side names a protocol
// version (`.protocol.version`, `.protocolVersion`, `["protocol"]["version"]`),
// and the right side is a bare quoted numeric literal rather than a bound
// variable.
//
// ## What deliberately does not
//
//   - `generic-client-record-mutation-v1`, which shares the `protocolVersion`
//     field name. That value is an envelope *name*, not a version, and it is
//     confined to one script — so the rule separates them on what they are
//     rather than on where they live.
//   - `scripts/lib/business-client-capability.sh` itself, which needs no
//     exemption because its constants are named `…CONTRACT_NAME` /
//     `…CONTRACT_VERSION` and never appear on the left of a comparison.
//   - `scripts/test-support/**`, whose whole job is constructing inputs that
//     disagree. It needs no exemption either: the fixtures pass the mismatching
//     version as a positional parameter, never as a compared literal.
//
// There is no allowlist here, and that is the point rather than a preference —
// an allowlist is a list of things the checker does not look at, and one would
// have been needed for every entry above had the rule been drawn more loosely.
//
// The catalog's third constant, `API_VERSION`, is deliberately *not* in the
// shared library. `OpenApiConfig` writes its own `"1.0.0"` for the OpenAPI
// document's `info.version`, independently of `IntegrationCapabilityCatalog`;
// the two agree today and nothing makes them agree. Binding a shell assertion
// to one of them would assert an equality between two separately owned values,
// which is a claim nobody has measured. A name shared across two fields is not
// a shared meaning.
//
// ## The rule is drawn around a whole-file read
//
// The comparison and the field it reads can sit on different lines once the
// predicate is inside a `jq` program, and the whole reason both drifted copies
// survived is that nobody read the pair across that boundary. Comments are
// stripped before the scan, because this file's own header quotes both broken
// lines verbatim and a scanner that counted prose would fail on itself.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

import { stripShellComments, shellFiles } from './lib/json-assertion-check.mjs';

export const VIOLATION_KIND = 'pinned-capability-protocol-version';

/** The single place the literal is allowed to be written down. */
export const CONTRACT_SOURCE = 'scripts/lib/business-client-capability.sh';

/**
 * @typedef {object} Violation
 * @property {string} gate   the script's path relative to the repository root
 * @property {string} detail what it pins and where
 * @property {number} line   1-based line the comparison is on
 */

/**
 * Left side: a protocol, then *its own* version, with only quoting and dots
 * between. Right side: a quoted string of nothing but digits and dots, compared
 * with `==` or `!=`.
 *
 * The adjacency is what makes this precise, and it was not precise the first
 * time. A looser `protocol … version` window matched `.protocol.apiVersion` —
 * the API version, a different field with a different number to bump — and the
 * first run of this gate failed on `verify-managed-api-principals.sh` for a line
 * that was already correct. Requiring the token to be `version`/`Version`
 * *immediately* after `protocol` admits jq's `.protocol.version` and the Python
 * subscript spellings `["protocol"]["version"]` and `["protocolVersion"]`, and
 * rejects `apiVersion` on a ground that has nothing to do with which file it is
 * in.
 */
const PINNED_PROTOCOL = /protocol["'\[\].]*(?:version|Version)["'\[\].]*\s*(?:==|!=)\s*["'](\d+(?:\.\d+)*)["']/g;

/**
 * The pure half: everything it needs arrives as text, so the self-test can
 * drive it with fixtures instead of a real repository.
 *
 * @param {string} source the script text
 * @param {string} [gate] the path to report violations under
 * @returns {Violation[]}
 */
export function checkPinnedCapabilityProtocol(source, gate = '') {
  const code = stripShellComments(source);
  const out = [];
  for (const match of code.matchAll(PINNED_PROTOCOL)) {
    const line = code.slice(0, match.index).split('\n').length;
    out.push({
      gate,
      line,
      detail:
        `it compares a capability protocol version against the literal "${match[1]}" `
        + `(line ${line}). Bind $INTEGRATION_CAPABILITY_CONTRACT_VERSION from `
        + `${CONTRACT_SOURCE} instead.`,
    });
  }
  return out;
}

/**
 * @param {string} scriptsDir
 * @param {string} [repoRoot] used to report paths relative to the repository
 * @returns {Violation[]}
 */
export function collectPinnedCapabilityProtocols(scriptsDir, repoRoot) {
  const out = [];
  for (const file of shellFiles(scriptsDir)) {
    const found = checkPinnedCapabilityProtocol(readFileSync(file, 'utf8'), file);
    for (const violation of found) {
      out.push({ ...violation, gate: relative(repoRoot ?? scriptsDir, violation.gate) });
    }
  }
  return out;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const violations = collectPinnedCapabilityProtocols(join(root, 'scripts'), root);
  if (violations.length > 0) {
    console.error(`Pinned capability protocol version (${VIOLATION_KIND}):`);
    for (const v of violations) {
      console.error(`  ${v.gate}: ${v.detail}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(
    'No script pins the capability protocol version; every one binds it from '
    + `${CONTRACT_SOURCE}.`,
  );
}

if (process.argv[1] && process.argv[1].endsWith('verify-capability-protocol-pinning.mjs')) {
  main();
}
