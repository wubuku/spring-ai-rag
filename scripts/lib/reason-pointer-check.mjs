// Finding the dead pointers inside a standing-gap reason.
//
// Why this exists
// ---------------
// `verify-gate-wiring.mjs` checks every machine-readable path the registry
// carries — `selfTest` must exist, the gate must exist — and did not check the
// paths inside `noCiReason`. That asymmetry is the whole defect. A `noCiReason`
// is the one field in this repository whose reader is a person, and its job is
// to name the artifact that would clear the blockage. Batch 900 found that all
// sixteen of them named `/tmp/b806-ci-gates.patch`, a file that no longer
// exists, so the standing gap had no way out and nothing said so: the gate
// printed the reason on every run and the reason was a dead end.
//
// A standing gap is also the only hole here with exactly one possible remover.
// A dead pointer in an ordinary comment costs a reader one look. In the reason
// attached to a hole only a human can fill, it costs the hole its only exit,
// quietly, for as long as nobody follows the link.
//
// What counts as a path, and what deliberately does not
// -----------------------------------------------------
// A token must contain a slash. `verify-gate-wiring.mjs` has none, so it is not
// treated as a path: a bare script name is found by `ls scripts/`, and treating
// it as a path would mean guessing a directory, which is the ambiguity this
// rule would then have to resolve. This was measured, not guessed — a census of
// every path-shaped token in the registry and its checker found six that do not
// resolve, and four of the six were regex constants and a deliberately deleted
// script from Batch 809. A looser rule would have reported all six.
//
// A token must also contain a dot, or end in a slash. Without that, ordinary
// prose matches: `text/plain` and `2026/10/05` are both path-shaped and neither
// is a path. The cost of this narrowing is that an extension-less directory
// like `docs/pending` is not checked. That is the limit, stated rather than
// hidden — and it errs toward missing a check, never toward a wrong verdict.
//
// One further limit, also measured. Resolution is against the repository root,
// which is the convention a reason string is written in. The developer reference
// tables abbreviate `scripts/` away and would produce sixty false positives if
// they were scanned; they are not scanned, because they are documents and this
// is a registry field. A future reason that abbreviates a directory will be
// reported, and the fix is to write the path out — the message says so.

/**
 * Path-shaped tokens in a reason string, in order, without duplicates.
 * Returns [] for a reason that names no path, which is a normal thing for a
 * reason to be: a cost decision has no artifact to hand over.
 */
export function extractReasonPaths(reason) {
  if (typeof reason !== 'string' || reason.length === 0) return [];
  const out = [];
  // A slash is required; a leading `/`, `./` or `../` is part of the token.
  // The leading-slash branch matters: without it an absolute path is captured
  // from its second character, resolved against the repository root, and
  // reported dead while sitting right there on disk.
  const candidate = /(?:\.{1,2}\/|\/)?(?:[\w.-]+\/)+[\w.-]*/g;
  for (const match of reason.matchAll(candidate)) {
    const token = match[0];
    if (!token.includes('/')) continue;
    // A dot anywhere, or a directory — see the note above.
    if (!token.includes('.') && !token.endsWith('/')) continue;
    if (!out.includes(token)) out.push(token);
  }
  return out;
}

/**
 * Tokens in `reason` that do not resolve. `exists` receives a repository-root
 * relative path and answers whether it is there, so this stays testable without
 * a filesystem.
 */
export function deadReasonPointers(reason, exists) {
  return extractReasonPaths(reason).filter((token) => !exists(token));
}
