// One way to strip Java comments, for the four gates that read Java source.
//
// Why this file exists
// --------------------
// Four gates each had their own `stripComments`, in three different behaviours.
// Two of them were a naive `/\/\*[\s\S]*?\*\//` followed by `/\/\/[^\n]*/`,
// with no notion of a string literal, and they read a different file from the
// other two. Measured on this repository, with no code changed:
//
//   351 string literals in the Java tree contain `//` or `/*`
//   331 of them — 94% — are gone from the naive stripper's output
//
// Almost all of them are URLs in test fixtures. The `//` case costs the rest of
// one line. The `/*` case is the dangerous one, because the non-greedy match
// runs to the next `*/` anywhere in the file: nine files have a `/*` inside a
// string literal, eight of them production code, and in
// `SecurityPathTraversalProbeTest` the naive stripper turns 369 lines into 251.
// A gate that cannot see a third of a file is not reading that file.
//
// The contract
// ------------
// Comments become spaces. Length is preserved, and so is every newline, because
// `verify-error-code-catalog` maps a finding back to a line by counting lines in
// the stripped text — that gate had to be fixed once already for reporting
// findings up to 54 lines off, and an implementation that collapsed newlines
// would hand the same bug back.
//
// The scanner is a character walk, not a pattern, and it tracks quote state so
// that an apostrophe inside a `//` note cannot be read as a string delimiter —
// which is exactly how eighteen `@Test` methods once came back with no body at
// all (Batch 888).

/**
 * Replace Java comments with spaces, keeping every other character in place.
 * String and character literals are left untouched, including the `//` and
 * `/*` inside them.
 */
export function stripJavaComments(source) {
  let out = '';
  let i = 0;
  let quote = null;

  while (i < source.length) {
    const c = source[i];

    if (quote !== null) {
      // A backslash escapes the next character, so `\"` must not close the
      // string. Without this, one escaped quote makes the rest of the file
      // read as string content and every comment after it survives.
      if (c === '\\') {
        out += source.slice(i, i + 2);
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      out += c;
      i += 1;
      continue;
    }

    if (c === '"' || c === "'") {
      quote = c;
      out += c;
      i += 1;
      continue;
    }

    if (c === '/' && source[i + 1] === '/') {
      while (i < source.length && source[i] !== '\n') {
        out += ' ';
        i += 1;
      }
      continue;
    }

    if (c === '/' && source[i + 1] === '*') {
      while (i < source.length && !(source[i] === '*' && source[i + 1] === '/')) {
        // Newlines are kept so line numbers survive.
        out += source[i] === '\n' ? '\n' : ' ';
        i += 1;
      }
      out += '  ';
      i += 2;
      continue;
    }

    out += c;
    i += 1;
  }

  return out;
}

/**
 * The shape this file replaces, kept so a reader can see what was wrong and so
 * the self-test can prove the difference rather than assert it. Not exported
 * for production use — a gate that reaches for this has reintroduced the bug.
 */
export const NAIVE_SHAPE = 'source.replace(/\\/\\*[\\s\\S]*?\\*\\//g, " ").replace(/\\/\\/[^\\n]*/g, " ")';
