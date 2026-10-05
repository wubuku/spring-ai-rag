// The one way a gate in this repository decides it is the program being run.
//
// Why this file exists
// --------------------
// A gate that exits 0 without having run is the worst failure available here,
// because it is indistinguishable from a pass. Twenty-two gate scripts each
// carried their own answer to "am I the entry point?", and they had drifted
// into four idioms:
//
//   import.meta.url === `file://${process.argv[1]}`                     7 files
//   import.meta.url === `file://${resolve(process.argv[1])}`           11 files
//   import.meta.url.endsWith(process.argv[1].split('/').pop())          4 files
//
// Measured, not argued: invoked through a path that crosses a symlink, the
// first eighteen print nothing and exit 0. The ESM loader resolves symlinks
// when it computes `import.meta.url`, and leaves `process.argv[1]` exactly as
// the caller typed it, so the two sides disagree and `main()` is never called.
// `resolve()` does not help — it makes a path absolute, it does not follow
// links, which is the distinction the second idiom got wrong.
//
// How wide this reaches, stated honestly. Node resolves `process.cwd()` to its
// physical path, so invoking a gate by a *relative* path from a symlinked
// directory is safe, and the aggregate chain — which invokes every gate
// relatively — is not affected today. The exposure is naming a script through
// a symlinked path: a wrapper, a dotfile-managed link into the checkout, an
// editor task, a CI step pointed at a linked workspace. It is a real way to run
// a program, and the result is a gate that reports success having checked
// nothing.
//
// Why a shared helper rather than four correct idioms
// --------------------------------------------------
// Four files already had a correct answer, and the duplication is what produced
// the other eighteen. A rule that accepts several spellings is a rule with
// several places to be wrong, so this is the single spelling and the census
// that enforces it reports any gate that rolls its own.
//
// The self-test exercises the real mechanism — it creates a symlink and runs a
// real gate through it — so the helper is checked by what it does rather than
// by how it is spelled.

import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * True when the module identified by `metaUrl` is the one node was asked to
 * run. Both sides are reduced to real paths first, so a symlink anywhere in
 * the path the caller typed still matches.
 */
export function isMainModule(metaUrl) {
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    return realpathSync(entry) === fileURLToPath(metaUrl);
  } catch {
    // An entry that cannot be resolved is not this module. Reporting "not the
    // entry" keeps a broken invocation from running main() twice; the caller
    // gets a silent no-op, which is the failure mode above — so the census
    // that requires this helper is what makes silence acceptable here.
    return false;
  }
}
