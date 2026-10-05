// Does a script leave the surefire reports in a state another gate can read?
//
// Batch 915. `scripts/verify-test-visibility.mjs` reconciles the test source
// tree against `spring-ai-rag-core/target/surefire-reports` in both directions,
// which means every gate that reads those reports depends on what the last
// thing to run in `target/` left behind. Twelve scripts under `scripts/` run a
// Maven `clean`. Five of them run an unscoped `mvn test` afterwards and so put
// the reports back. **The other seven do not**, and nothing in any of them, and
// nothing in the testing guide, said so — a reader who ran one and then the gate
// chain got `No surefire reports at …`, which reads like their own mistake
// rather than like the script having taken the evidence away.
//
// Two shapes are deliberately *not* violations, and both have correct instances
// in this repository today:
//
//   - a script that restores the reports. Five do. That is the honest fix and
//     the gate cannot tell anyone to prefer it, so it is not the gate's business.
//   - a script that says, in its header, that it leaves the reports gone. That
//     is a declaration, and it belongs to the script rather than to a registry
//     of known offenders — the reason travels with the command instead of
//     living in a file the reader has to know to open.
//
// The rule is drawn around **Maven `clean`**, not around the word "clean": a
// comment that lists `mvn clean compile test-compile` is not an invocation, and
// counting it as one would have made the self-test's own fixture a violation.
// Goal tokens are matched with `;` `)` and end-of-line as terminators too,
// because shell writes `mvn test;` and function wrappers write `{ mvn test; }`,
// and a rule that only recognises whitespace-terminated goals silently misses
// exactly the shape that makes a script self-healing.
//
// Known miss: a clean reached through a variable (`goals="clean compile"`) or a
// wrapper the reader cannot see in one line. The direction is only-miss.

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Strips a shell comment, honouring quotes.
 *
 * A `#` inside a quoted string is data — the scripts under test do put `#` in
 * SQL fixtures and colours — and treating it as a comment would delete the
 * invocation that follows it on the same line.
 */
export function stripShellComment(line) {
  const trimmed = line.trim();
  if (trimmed.startsWith('#')) return '';
  let inSingle = false;
  let inDouble = false;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (ch === "'" && !inDouble) inSingle = !inSingle;
    else if (ch === '"' && !inSingle) inDouble = !inDouble;
    else if (ch === '#' && !inSingle && !inDouble && (i === 0 || /\s/.test(line[i - 1]))) {
      return line.slice(0, i);
    }
  }
  return line;
}

const MVN = /\bmvn\b/;
const GOAL_END = /(\s|;|\)|&|\||$)/;
const CLEAN_GOAL = new RegExp(`(^|\\s)clean${GOAL_END.source}`);
const TEST_GOAL = new RegExp(`(^|\\s)test${GOAL_END.source}`);
// `-Dtest=` / `-Dit.test=` pin the run to named classes, so the report
// directory that comes out is not the full suite the reconciler needs.
const SCOPED_RUN = /-D(?:it\.)?test=/;

/** A mention of the surefire *report data*. */
const DATA_MENTION = /surefire[-\s]?reports?\b/i;
/** The shared reader, which is a script rather than a report. */
const READER_PATH = /scripts\/lib\/surefire-report\.sh/;
/** Words for removal, in the two languages this repository's comments use. */
const DELETION_WORD =
  /\b(delete|deletes|deleted|removes?|removed|clears?|cleared|wipes?|wiped|gone)\b|删除|删掉|清空|清掉/;

/** A line that runs `mvn clean`. */
function isCleanInvocation(code) {
  return MVN.test(code) && CLEAN_GOAL.test(code);
}

/** A line that runs `mvn test` without pinning it to named classes. */
function isFullTestInvocation(code) {
  return MVN.test(code) && TEST_GOAL.test(code) && !SCOPED_RUN.test(code);
}

/**
 * The leading comment block: every line before the first line that is neither
 * blank nor a comment.
 *
 * @returns {string}
 */
export function shellHeader(lines) {
  let end = 0;
  while (end < lines.length) {
    const stripped = stripShellComment(lines[end]);
    if (stripped.trim() !== '' && !lines[end].trim().startsWith('#')) break;
    end += 1;
  }
  return lines.slice(0, end).join('\n');
}

/**
 * Does the header say, specifically, that this script takes the reports away?
 *
 * "Mentions surefire" is not enough, and the reason is concrete: three of the
 * scripts in question carry a note about `scripts/lib/surefire-report.sh`,
 * which is them talking about sharing a report *reader*. One of them could
 * have moved that note into its header and been declared innocent without
 * changing a thing. So the rule asks for a mention of the report *data* — a
 * line that is not the reader path — plus a word for deletion, anywhere in the
 * header. Both are needed, and the pairing is what makes it a claim rather
 * than a coincidence of vocabulary.
 *
 * @param {string} header
 * @returns {boolean}
 */
export function declaresReportDestruction(header) {
  const lines = header.split('\n');
  const mentionsReportData = lines.some(
    line => DATA_MENTION.test(line) && !READER_PATH.test(line),
  );
  const mentionsDeletion = lines.some(line => DELETION_WORD.test(line));
  return mentionsReportData && mentionsDeletion;
}

/**
 * @typedef {object} ReportDestruction
 * @property {string} script        the script's basename
 * @property {boolean} restores     it runs an unscoped `mvn test` after the clean
 * @property {boolean} declared     its header says the reports go away
 * @property {number} cleanCount    how many Maven `clean` invocations it makes
 */

/**
 * Classifies one script.
 *
 * @param {string} name basename, for the finding
 * @param {string} source the script text
 * @returns {ReportDestruction|null} null when the script never runs `mvn clean`
 */
export function classifyReportDestruction(name, source) {
  const lines = source.split('\n');
  const code = lines.map(stripShellComment);

  const cleanIndexes = [];
  const fullTestIndexes = [];
  code.forEach((line, index) => {
    if (isCleanInvocation(line)) cleanIndexes.push(index);
    if (isFullTestInvocation(line)) fullTestIndexes.push(index);
  });
  if (cleanIndexes.length === 0) return null;

  const lastClean = Math.max(...cleanIndexes);
  // `mvn clean test` is one command that both destroys and restores, so a test
  // on the same line as the last clean counts.
  const restores = fullTestIndexes.some(i => i >= lastClean);
  const declared = declaresReportDestruction(shellHeader(lines));

  return { script: name, restores, declared, cleanCount: cleanIndexes.length };
}

/** @returns {ReportDestruction[]} every script that runs `mvn clean`. */
export function collectReportDestruction(scriptsDir) {
  const out = [];
  for (const name of readdirSync(scriptsDir).filter(n => n.endsWith('.sh')).sort()) {
    const found = classifyReportDestruction(name, readFileSync(join(scriptsDir, name), 'utf8'));
    if (found) out.push(found);
  }
  return out;
}

/**
 * The scripts that take the reports away and never say they do.
 *
 * @param {ReportDestruction[]} classified
 * @returns {ReportDestruction[]}
 */
export function unannouncedReportDestructions(classified) {
  return classified.filter(entry => !entry.restores && !entry.declared);
}
