// One way to strip comments and string literals from TSX, for the gates that
// read it.
//
// Why this file exists
// --------------------
// Batch 910 added `check-reduced-motion` and Batch 911 added two more gates
// that read source text. All three went looking for something that did not
// exist: a stripper that knows a `//` inside a quoted string is not a comment.
// The first version of each gate read raw text, and the self-tests caught it in
// minutes — `const s = '<h3>'` was reported as a heading, and an SVG carrying
// `aria-hidden='true'` inside a `data-note` string was reported as hidden.
//
// Batch 905 is the precedent, and it is a cautionary one rather than a
// reassuring one: four Java gates each carried their own `stripComments`, in
// three different behaviours, two of them naive, and measured on this repository
// they destroyed 316 string literals. The Java fix was `scripts/lib/java-source.mjs`.
// Writing a third dialect of the same mistake here would make four languages
// worse instead of one.
//
// The contract, same as the Java one
// ---------------------------------
// Comments and string contents become spaces. Length is preserved and so is
// every newline, because gates map findings back to a line by counting lines in
// the stripped text, and a stripper that collapsed newlines would report every
// finding off by the height of the file.
//
// Three constructs are tracked, because all three appear in this repository:
// single quotes, double quotes, and template literals — the last one because a
// template literal can hold an interpolation `${...}` that is real code, so the
// walk re-enters normal scanning inside the braces rather than treating the
// whole literal as inert.
//
// The limitation, stated rather than implied
// ------------------------------------------
// JSX **text** nodes are not tracked. `<p>https://example.com</p>` contains a
// `//` that is text, and this stripper will read it as a comment. Measured on
// `src/`: zero occurrences, so nothing is being missed today. A future page that
// renders a URL as text would need this extended, and the honest way to notice
// is to grep for it rather than to assume — which is why the number is written
// down here instead of only in a commit message.

/**
 * Strip comments, leaving string literals intact.
 *
 * Use this when the caller needs to read an attribute's *value*. `check-decorative-graphics`
 * asks whether `aria-hidden="true"` is present, and blanking string contents
 * erases exactly the token it is looking for — the two gates in this directory
 * need opposite treatments, so both are spelled out rather than one of them
 * quietly losing.
 */
export function stripTsxComments(source) {
  return walk(source, false);
}

/**
 * Replace comments *and* string-literal contents with spaces.
 *
 * Use this when the caller matches markup that should never appear inside a
 * string — a heading tag, for instance.
 */
export function stripTsxNoise(source) {
  return walk(source, true);
}

/**
 * @param {string} source
 * @param {boolean} blankStrings
 * @returns {string}
 */
function walk(source, blankStrings) {
  const out = [];
  let i = 0;
  let quote = null;

  while (i < source.length) {
    const c = source[i];

    if (quote !== null) {
      // Inside a string: a backslash escapes the next character, so `\"` must
      // not close it. Without this, one escaped quote turns the rest of the
      // file into string content and every comment after it survives.
      if (c === '\\') {
        out.push(source.slice(i, i + 2));
        i += 2;
        continue;
      }
      if (c === quote) {
        quote = null;
        out.push(c);
        i += 1;
        continue;
      }
      // A template literal may embed real code between ${ and }.
      if (quote === '`' && c === '$' && source[i + 1] === '{') {
        out.push('${');
        i += 2;
        let depth = 1;
        let inner = '';
        while (i < source.length && depth > 0) {
          if (source[i] === '{') depth++;
          else if (source[i] === '}') {
            depth--;
            if (depth === 0) break;
          }
          inner += source[i];
          i += 1;
        }
        out.push(walk(inner, blankStrings));
        if (source[i] === '}') {
          out.push('}');
          i += 1;
        }
        continue;
      }
      // A string stays verbatim when the caller needs its value, and becomes
      // spaces when the caller must not match markup inside it. Newlines are
      // kept either way so a line number still resolves.
      out.push(blankStrings && c !== '\n' ? ' ' : c);
      i += 1;
      continue;
    }

    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      out.push(c);
      i += 1;
      continue;
    }

    // `//` and `/*` are unconditionally comments. A regex literal can contain an
    // unescaped `/`, so it does have to be consumed whole — but that happens on
    // the bare-`/` branch below, and by the time an unconsumed `//` is reached
    // there is nothing left for it to be anything but a comment. The first
    // version of this walk asked "could this be a regex?" before treating `//`
    // as a comment, and the cost was that `const re = /x/; // note` lost the
    // real note.
    if (c === '/') {
      if (source[i + 1] === '/') {
        while (i < source.length && source[i] !== '\n') {
          out.push(' ');
          i += 1;
        }
        continue;
      }
      if (source[i + 1] === '*') {
        while (i < source.length) {
          if (source[i] === '\n') {
            out.push('\n');
            i += 1;
            continue;
          }
          if (source[i] === '*' && source[i + 1] === '/') {
            out.push('  ');
            i += 2;
            break;
          }
          out.push(' ');
          i += 1;
        }
        continue;
      }

      // A bare `/` is either a division or the start of a regex. The heuristic
      // is the usual one — a regex can only follow something that cannot end an
      // expression — and being wrong in either direction costs one line.
      const before = source.slice(0, i).trimEnd();
      const prev = before[before.length - 1];
      const canStartRegex = before === ''
        || prev === '(' || prev === ',' || prev === '=' || prev === '['
        || prev === ':' || prev === '!' || prev === '&' || prev === '|'
        || prev === '{' || prev === ';' || prev === '?' || prev === 'n';
      if (canStartRegex) {
        out.push('/');
        i += 1;
        let inClass = false;
        while (i < source.length) {
          const r = source[i];
          if (r === '\\') {
            out.push(source.slice(i, i + 2));
            i += 2;
            continue;
          }
          if (r === '[') inClass = true;
          else if (r === ']') inClass = false;
          else if (r === '/' && !inClass) {
            out.push('/');
            i += 1;
            break;
          } else if (r === '\n') {
            break;
          }
          out.push(r);
          i += 1;
        }
        continue;
      }
    }

    out.push(c);
    i += 1;
  }

  return out.join('');
}
