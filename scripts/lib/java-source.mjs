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
 * Replace comments with spaces in a file that is not Java: `.yml`, `.properties`,
 * `.sh`, `.env` and JavaScript/TypeScript.
 *
 * Why a second walk when `stripJavaComments` exists
 * --------------------------------------------------
 * A gate reading a **mixed** corpus hits two things Java does not have, and both
 * fail in the direction that makes a gate cry wolf rather than pass quietly:
 *
 *   1. `#` is a line comment in shell, YAML and properties. Left in place, a
 *      sentence in an `application.yml` comment becomes a consumer of whatever
 *      variable it names.
 *   2. `//` is **not** always a comment marker. `.env` and YAML carry unquoted
 *      URLs — `spring.datasource.url=jdbc:postgresql://localhost:5432/db` is in
 *      this repository — and `stripJavaComments` treats the `//` as a comment and
 *      deletes the rest of the line, which deletes a *real* consumer and makes the
 *      gate report a violation that does not exist. So here `//` opens a comment
 *      only after whitespace or one of `([{,;`; in Java and JavaScript a real line
 *      comment never touches an identifier, and the price of this rule is that a
 *      comment marker glued to one is read as code.
 *
 * It also tracks backticks, so a JS template literal cannot swallow the rest of
 * the file. The contract is the same as `stripJavaComments`: comments become
 * spaces, length and every newline survive, string literals keep their `//`,
 * `/*` and `#`.
 */
export function stripConfigComments(source) {
  let out = '';
  let i = 0;
  let quote = null;

  while (i < source.length) {
    const c = source[i];

    if (quote !== null) {
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

    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      out += c;
      i += 1;
      continue;
    }

    if (c === '/' && source[i + 1] === '/') {
      const prev = i > 0 ? source[i - 1] : '\n';
      if (!/[\s([{,;]/u.test(prev)) {
        out += c + source[i + 1];
        i += 2;
        continue;
      }
      while (i < source.length && source[i] !== '\n') {
        out += ' ';
        i += 1;
      }
      continue;
    }

    if (c === '/' && source[i + 1] === '*') {
      while (i < source.length && !(source[i] === '*' && source[i + 1] === '/')) {
        out += source[i] === '\n' ? '\n' : ' ';
        i += 1;
      }
      out += '  ';
      i += 2;
      continue;
    }

    if (c === '#') {
      // In code this is the shebang or nothing; in `.yml` / `.sh` / `.properties`
      // it is a line comment. Either way the rest of the line is not code.
      while (i < source.length && source[i] !== '\n') {
        out += ' ';
        i += 1;
      }
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
